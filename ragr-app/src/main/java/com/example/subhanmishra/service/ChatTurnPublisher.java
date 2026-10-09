package com.example.subhanmishra.service;

import com.example.subhanmishra.citation.CitationResolver.Resolution;
import com.example.subhanmishra.config.EventsProperties;
import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.config.SpringAiConfig;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.ChatTurnCompleted.GenerationUsage;
import com.example.subhanmishra.event.ChatTurnCompleted.RetrievedChunk;
import com.example.subhanmishra.event.ChatTurnCompleted.Timings;
import com.example.subhanmishra.event.TurnOrigin;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Hands each completed chat turn to Kafka for evaluation to pick up, without ever holding up the answer.
 *
 * <ul>
 *   <li><b>Sent on a virtual thread, not the caller's.</b> {@code KafkaTemplate.send} can block while it
 *       waits for the broker - 60 s by default, which with Kafka down would stall every answer. It is
 *       also capped at 500 ms ({@code max.block.ms} in application-dev.yaml).</li>
 *   <li><b>At most once.</b> A turn that cannot be sent is counted and dropped, never retried or stored.
 *       Evaluation is observability; an outbox table would add a database write to every chat turn to
 *       protect data that does not need it. A rising
 *       {@code rag_chat_turn_events_total{outcome="dropped"}} means Kafka is unreachable, not that chat
 *       is failing.</li>
 *   <li><b>Traced.</b> The caller's trace context is carried onto the sending thread, so the send and its
 *       processing in ragr-eval join the chat turn's trace.</li>
 * </ul>
 */
@Service
public class ChatTurnPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnPublisher.class);

    private final KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate;
    private final EventsProperties.ChatTurns properties;
    private final RagProperties ragProperties;
    private final Executor executor;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();
    private final Counter published;
    private final Counter dropped;

    @Autowired
    public ChatTurnPublisher(KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate,
                             EventsProperties properties,
                             RagProperties ragProperties,
                             MeterRegistry registry) {
        this(kafkaTemplate, properties, ragProperties, registry,
             Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-turn-publish-", 0).factory()));
    }

    /** Takes the executor so a test can run the send on its own thread and assert on the outcome. */
    ChatTurnPublisher(KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate,
                      EventsProperties properties,
                      RagProperties ragProperties,
                      MeterRegistry registry,
                      Executor executor) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties.chatTurns();
        this.ragProperties = ragProperties;
        this.executor = executor;
        this.published = Counter.builder("rag.chat.turn.events")
                                .description("Completed chat turns handed to Kafka for evaluation")
                                .tag("outcome", "published")
                                .register(registry);
        this.dropped = Counter.builder("rag.chat.turn.events")
                              .description("Completed chat turns handed to Kafka for evaluation")
                              .tag("outcome", "dropped")
                              .register(registry);
    }

    /**
     * Publishes one turn. Returns at once and never throws: nothing here is worth turning a good answer
     * into an error.
     */
    public void publish(Turn turn) {
        if (!properties.enabled()) {
            return;
        }
        try {
            Resolution resolution = turn.resolution();
            ChatTurnCompleted event = new ChatTurnCompleted(turn.turnId(),
                                                            turn.origin(),
                                                            Instant.now(),
                                                            turn.conversationId(),
                                                            turn.query(),
                                                            resolution.answer(),
                                                            resolution.repaired(),
                                                            resolution.abstained(),
                                                            resolution.unresolved(),
                                                            turn.retrieved().stream().map(RetrievedChunk::of).toList(),
                                                            modelOf(turn.response()),
                                                            ragProperties.topK(),
                                                            ragProperties.similarityThreshold(),
                                                            ChatTurnCompleted.SCHEMA_VERSION,
                                                            turn.excluded().stream().map(RetrievedChunk::of).toList(),
                                                            ragProperties.poolSize(),
                                                            turn.timings(),
                                                            usageOf(turn.response()),
                                                            SpringAiConfig.PROMPT_VERSION);
            ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
            executor.execute(snapshot.wrap(() -> send(event)));
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Could not publish a chat turn for evaluation; the answer itself was unaffected", e);
        }
    }

    private static @Nullable String modelOf(@Nullable ChatResponse response) {
        return response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
    }

    private static @Nullable GenerationUsage usageOf(@Nullable ChatResponse response) {
        if (response == null) {
            return null;
        }
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        String finishReason = response.getResult() != null && response.getResult().getMetadata() != null
                ? response.getResult().getMetadata().getFinishReason()
                : null;
        return new GenerationUsage(usage != null ? usage.getPromptTokens() : null,
                                   usage != null ? usage.getCompletionTokens() : null,
                                   finishReason);
    }

    /**
     * One finished turn, as {@code ChatService} hands it over.
     *
     * @param turnId     also returned to the caller, who quotes it back to give feedback
     * @param resolution the answer after {@code CitationResolver} - the text the evaluation scores
     * @param retrieved  the chunks in the prompt
     * @param excluded   the rest of the candidate pool, in rank order after them
     * @param response   the final model response, for its model, usage and finish reason; null when
     *                   a stream ended without one
     */
    public record Turn(UUID turnId,
                       TurnOrigin origin,
                       String conversationId,
                       String query,
                       Resolution resolution,
                       List<Document> retrieved,
                       List<Document> excluded,
                       @Nullable ChatResponse response,
                       Timings timings) {
    }

    private void send(ChatTurnCompleted event) {
        try {
            kafkaTemplate.send(properties.topic(), event.conversationId(), event)
                         .whenComplete((result, failure) -> {
                             if (failure == null) {
                                 published.increment();
                             } else {
                                 dropped.increment();
                                 log.debug("Dropped chat turn {}: the broker did not acknowledge it",
                                           event.turnId(), failure);
                             }
                         });
        } catch (RuntimeException e) {
            // max.block.ms expired waiting for metadata or buffer space - the broker is unreachable.
            dropped.increment();
            log.debug("Dropped chat turn {}: could not hand it to the producer", event.turnId(), e);
        }
    }
}
