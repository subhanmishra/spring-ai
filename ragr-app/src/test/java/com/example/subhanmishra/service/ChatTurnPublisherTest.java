package com.example.subhanmishra.service;

import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationResolver.Resolution;
import com.example.subhanmishra.config.EventsProperties;
import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.TurnOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnPublisherTest {

    private static final String TOPIC = "rag.chat.turn.completed";

    private final Document chunk = Document.builder()
                                           .id("chunk-1")
                                           .text("[manual.pdf, p. 12]\nSet server.port to change the port.")
                                           .metadata(Map.of("fileName", "manual.pdf", "pageNumber", 12))
                                           .score(0.77)
                                           .build();
    private final Resolution resolution = new Resolution("Set server.port (manual.pdf, p. 12).", 1, 1,
                                                         List.of(new Citation("manual.pdf", null, "5.3")),
                                                         List.of());

    private KafkaTemplate<String, ChatTurnCompleted> template;
    private MeterRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        registry = new SimpleMeterRegistry();
    }

    private ChatTurnPublisher publisher(boolean enabled) {
        EventsProperties properties = new EventsProperties(
                new EventsProperties.ChatTurns(enabled, TOPIC, Duration.ofDays(3)));
        // Runs the send on the calling thread so the outcome can be asserted straight away.
        RagProperties rag = new RagProperties(400, 150, 100, 10000, 2048, 5, 0.6, 35, 4, 3, Duration.ofSeconds(2),
                                              RagProperties.TableDetection.AUTO);
        return new ChatTurnPublisher(template, properties, rag, registry, Runnable::run);
    }

    private double count(String outcome) {
        return registry.get("rag.chat.turn.events").tag("outcome", outcome).counter().count();
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("sends the turn keyed by conversation and counts it once the broker acknowledges")
        @SuppressWarnings("unchecked")
        void sendsAndCounts() {
            when(template.send(eq(TOPIC), anyString(), any(ChatTurnCompleted.class)))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

            publisher(true).publish(TurnOrigin.LIVE, "conv-1", "How do I change the port?", resolution, List.of(chunk), "gemma4:e2b");

            ArgumentCaptor<ChatTurnCompleted> sent = ArgumentCaptor.forClass(ChatTurnCompleted.class);
            verify(template).send(eq(TOPIC), eq("conv-1"), sent.capture());
            ChatTurnCompleted event = sent.getValue();
            assertThat(event.conversationId()).isEqualTo("conv-1");
            assertThat(event.origin()).isEqualTo(TurnOrigin.LIVE);
            assertThat(event.topK()).isEqualTo(5);
            assertThat(event.similarityThreshold()).isEqualTo(0.6);
            assertThat(event.answer()).isEqualTo(resolution.answer());
            assertThat(event.citationsRepaired()).isEqualTo(1);
            assertThat(event.citationsAbstained()).isEqualTo(1);
            assertThat(event.retrieved()).singleElement()
                                         .satisfies(c -> assertThat(c.text()).startsWith("[manual.pdf, p. 12]"));
            assertThat(count("published")).isEqualTo(1);
            assertThat(count("dropped")).isZero();
        }

        @Test
        @DisplayName("sends nothing when disabled")
        void disabled() {
            publisher(false).publish(TurnOrigin.LIVE, "conv-1", "q", resolution, List.of(chunk), null);

            verify(template, never()).send(anyString(), anyString(), any(ChatTurnCompleted.class));
        }
    }

    @Nested
    @DisplayName("with the broker unreachable")
    class BrokerDown {

        @Test
        @DisplayName("drops the turn without throwing when send() gives up waiting for metadata")
        void sendThrows() {
            when(template.send(anyString(), anyString(), any(ChatTurnCompleted.class)))
                    .thenThrow(new TimeoutException("Topic not present in metadata after 500 ms."));

            assertThatNoException().isThrownBy(
                    () -> publisher(true).publish(TurnOrigin.LIVE, "conv-1", "q", resolution, List.of(chunk), null));
            assertThat(count("dropped")).isEqualTo(1);
            assertThat(count("published")).isZero();
        }

        @Test
        @DisplayName("drops the turn when the broker never acknowledges it")
        void futureFails() {
            when(template.send(anyString(), anyString(), any(ChatTurnCompleted.class)))
                    .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s)")));

            publisher(true).publish(TurnOrigin.LIVE, "conv-1", "q", resolution, List.of(chunk), null);

            assertThat(count("dropped")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the event on the wire")
    class Wire {

        @Test
        @DisplayName("survives the configured JSON serializer with no type headers, chunks included")
        void roundTrips() {
            when(template.send(anyString(), anyString(), any(ChatTurnCompleted.class)))
                    .thenReturn(new CompletableFuture<>());
            publisher(true).publish(TurnOrigin.LIVE, "conv-1", "How do I change the port?", resolution, List.of(chunk), "gemma4:e2b");
            ArgumentCaptor<ChatTurnCompleted> sent = ArgumentCaptor.forClass(ChatTurnCompleted.class);
            verify(template).send(anyString(), anyString(), sent.capture());
            ChatTurnCompleted original = sent.getValue();

            byte[] json;
            try (JacksonJsonSerializer<ChatTurnCompleted> serializer = new JacksonJsonSerializer<>()) {
                serializer.configure(Map.of("spring.json.add.type.headers", false), false);
                json = serializer.serialize(TOPIC, original);
            }
            ChatTurnCompleted read;
            try (JacksonJsonDeserializer<ChatTurnCompleted> deserializer =
                         new JacksonJsonDeserializer<>(ChatTurnCompleted.class, false)) {
                read = deserializer.deserialize(TOPIC, json);
            }

            assertThat(read).isEqualTo(original);
            Document restored = read.retrievedDocuments().getFirst();
            assertThat(restored.getText()).isEqualTo(chunk.getText());
            assertThat(restored.getScore()).isEqualTo(chunk.getScore());
            assertThat(restored.getMetadata()).containsEntry("pageNumber", 12);
        }
    }
}
