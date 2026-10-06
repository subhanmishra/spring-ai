package com.example.subhanmishra.service;

import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationResolver.Resolution;
import com.example.subhanmishra.config.EventsProperties;
import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.ChatTurnCompleted.Timings;
import com.example.subhanmishra.event.TurnOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final Document belowThreshold = Document.builder()
                                                    .id("chunk-2")
                                                    .text("[manual.pdf, p. 40]\nPorts are integers.")
                                                    .metadata(Map.of("fileName", "manual.pdf", "pageNumber", 40))
                                                    .score(0.41)
                                                    .build();

    /** A finished turn with one chunk in the prompt and one left in the pool. */
    private ChatTurnPublisher.Turn turn(String query) {
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(resolution.answer()),
                                                    ChatGenerationMetadata.builder().finishReason("stop").build())))
                .metadata(ChatResponseMetadata.builder()
                                              .model("gemma4:e2b")
                                              .usage(new DefaultUsage(1200, 85))
                                              .build())
                .build();
        return new ChatTurnPublisher.Turn(UUID.fromString("00000000-0000-0000-0000-000000000042"),
                                          TurnOrigin.LIVE, "conv-1", query, resolution,
                                          List.of(chunk), List.of(belowThreshold), response,
                                          new Timings(3L, null, 21_000L, false));
    }

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
                new EventsProperties.ChatTurns(enabled, TOPIC, Duration.ofDays(3)),
                new EventsProperties.Feedback("rag.chat.feedback", Duration.ofDays(3)));
        // Runs the send on the calling thread so the outcome can be asserted straight away.
        RagProperties rag = new RagProperties(5, 0.6, 10, 0.0);
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

            publisher(true).publish(turn("How do I change the port?"));

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
            assertThat(event.turnId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000042"));
            assertThat(event.schemaVersion()).isEqualTo(ChatTurnCompleted.SCHEMA_VERSION);
            assertThat(event.excluded()).singleElement()
                                        .satisfies(c -> assertThat(c.text()).startsWith("[manual.pdf, p. 40]"));
            assertThat(event.pool()).extracting(ChatTurnCompleted.RetrievedChunk::id)
                                    .containsExactly("chunk-1", "chunk-2");
            assertThat(event.poolSize()).isEqualTo(10);
            assertThat(event.chatModel()).isEqualTo("gemma4:e2b");
            assertThat(event.usage()).isEqualTo(new ChatTurnCompleted.GenerationUsage(1200, 85, "stop"));
            assertThat(event.timings()).isEqualTo(new Timings(3L, null, 21_000L, false));
            assertThat(event.promptVersion()).hasSize(12);
            assertThat(count("published")).isEqualTo(1);
            assertThat(count("dropped")).isZero();
        }

        @Test
        @DisplayName("sends nothing when disabled")
        void disabled() {
            publisher(false).publish(turn("q"));

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
                    () -> publisher(true).publish(turn("q")));
            assertThat(count("dropped")).isEqualTo(1);
            assertThat(count("published")).isZero();
        }

        @Test
        @DisplayName("drops the turn when the broker never acknowledges it")
        void futureFails() {
            when(template.send(anyString(), anyString(), any(ChatTurnCompleted.class)))
                    .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s)")));

            publisher(true).publish(turn("q"));

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
            publisher(true).publish(turn("How do I change the port?"));
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

        @Test
        @DisplayName("still reads a version-1 event, which has none of the version-2 fields")
        void readsVersionOne() {
            String v1 = """
                    {"turnId":"00000000-0000-0000-0000-000000000001","origin":"LIVE",
                     "occurredAt":"2026-10-01T10:00:00Z","conversationId":"conv-1","query":"q","answer":"a",
                     "citationsRepaired":0,"citationsAbstained":0,"unresolved":[],"retrieved":[],
                     "chatModel":"gemma4:e2b","topK":5,"similarityThreshold":0.6}
                    """;
            ChatTurnCompleted read;
            try (JacksonJsonDeserializer<ChatTurnCompleted> deserializer =
                         new JacksonJsonDeserializer<>(ChatTurnCompleted.class, false)) {
                read = deserializer.deserialize(TOPIC, v1.getBytes(StandardCharsets.UTF_8));
            }

            assertThat(read.schemaVersion()).isNull();
            assertThat(read.excluded()).isEmpty();
            assertThat(read.timings()).isNull();
            assertThat(read.topK()).isEqualTo(5);
        }
    }
}
