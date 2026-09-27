package com.example.subhanmishra.service;

import com.example.subhanmishra.citation.CitationResolver.Resolution;
import com.example.subhanmishra.config.EventsProperties;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.ChatTurnCompleted.RetrievedChunk;
import com.example.subhanmishra.service.provenance.PipelineProvenance;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><strong>The send happens on a virtual thread, not the caller's.</strong> {@code KafkaTemplate.send}
 * looks asynchronous but is not entirely: it blocks the calling thread while it waits for the broker's
 * metadata and whenever its buffer is full, for up to {@code max.block.ms}. That defaults to 60 seconds,
 * so with the broker down every chat response would stall for a minute. It is set to 500 ms in
 * {@code application-dev.yaml} as well, which bounds how long each of these threads can live.
 *
 * <p><strong>Delivery is at most once.</strong> A turn that cannot be sent is counted and dropped, never
 * retried from here or stored for later. Evaluation is sampled observability, and a transactional outbox
 * would put a database write on every chat turn to protect data that does not need it. A rising
 * {@code rag_chat_turn_events_total{outcome="dropped"}} means the broker is unreachable, not that chat
 * is failing.
 *
 * <p>Trace context is captured on the caller's thread and restored on the sending one, so the send - and,
 * through the observation-enabled template, the consumer's processing of it - joins the chat turn's trace.
 */
@Service
public class ChatTurnPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnPublisher.class);

    private final KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate;
    private final EventsProperties.ChatTurns properties;
    private final Executor executor;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();
    private final Counter published;
    private final Counter dropped;

    @Autowired
    public ChatTurnPublisher(KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate,
                             EventsProperties properties,
                             MeterRegistry registry) {
        this(kafkaTemplate, properties, registry,
             Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-turn-publish-", 0).factory()));
    }

    /** Takes the executor so a test can run the send on its own thread and assert on the outcome. */
    ChatTurnPublisher(KafkaTemplate<String, ChatTurnCompleted> kafkaTemplate,
                      EventsProperties properties,
                      MeterRegistry registry,
                      Executor executor) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties.chatTurns();
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
     * Publishes one turn. Returns immediately and never throws: the answer has already been generated,
     * and nothing that goes wrong here is worth turning a successful response into an error.
     *
     * @param resolution the answer after {@code CitationResolver} - the text the evaluation scores
     */
    public void publish(String conversationId, String query, Resolution resolution,
                        List<Document> retrieved, @Nullable String chatModel) {
        if (!properties.enabled()) {
            return;
        }
        try {
            ChatTurnCompleted event = new ChatTurnCompleted(UUID.randomUUID(),
                                                            Instant.now(),
                                                            conversationId,
                                                            query,
                                                            resolution.answer(),
                                                            resolution.repaired(),
                                                            resolution.abstained(),
                                                            resolution.unresolved(),
                                                            retrieved.stream().map(RetrievedChunk::of).toList(),
                                                            chatModel,
                                                            PipelineProvenance.CURRENT_VERSION);
            ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
            executor.execute(snapshot.wrap(() -> send(event)));
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Could not publish a chat turn for evaluation; the answer itself was unaffected", e);
        }
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
