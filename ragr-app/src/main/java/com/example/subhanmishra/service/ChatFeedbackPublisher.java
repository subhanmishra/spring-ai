package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EventsProperties;
import com.example.subhanmishra.dto.FeedbackRequestDto;
import com.example.subhanmishra.event.ChatFeedbackSubmitted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Hands a user's rating of an answer to ragr-eval, which stores it beside the turn it rates.
 *
 * <p>ragr-app keeps no record of its turns, so it accepts any turn id: an id that names no turn is
 * feedback that joins nothing on the evaluation side, which costs a row and misleads no metric.
 *
 * <p>Fire-and-forget for the same reason {@link ChatTurnPublisher} is: the caller has nothing to do
 * about a broker outage, and the send is counted either way as {@code rag.chat.feedback.events}.
 * {@code KafkaTemplate.send} is already asynchronous; its blocking part is bounded by the producer's
 * {@code max.block.ms}.
 */
@Service
public class ChatFeedbackPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatFeedbackPublisher.class);

    private final KafkaTemplate<String, ChatFeedbackSubmitted> kafkaTemplate;
    private final String topic;
    private final Counter published;
    private final Counter dropped;

    public ChatFeedbackPublisher(KafkaTemplate<String, ChatFeedbackSubmitted> kafkaTemplate,
                                 EventsProperties properties,
                                 MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.feedback().topic();
        this.published = Counter.builder("rag.chat.feedback.events")
                                .description("Answer ratings handed to Kafka for evaluation")
                                .tag("outcome", "published")
                                .register(registry);
        this.dropped = Counter.builder("rag.chat.feedback.events")
                              .description("Answer ratings handed to Kafka for evaluation")
                              .tag("outcome", "dropped")
                              .register(registry);
    }

    public void submit(UUID turnId, FeedbackRequestDto request) {
        ChatFeedbackSubmitted event = new ChatFeedbackSubmitted(turnId, request.rating(), request.reason(),
                                                                Instant.now());
        try {
            kafkaTemplate.send(topic, turnId.toString(), event)
                         .whenComplete((result, failure) -> {
                             if (failure == null) {
                                 published.increment();
                             } else {
                                 dropped.increment();
                                 log.debug("Dropped feedback for turn {}: the broker did not acknowledge it",
                                           turnId, failure);
                             }
                         });
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Could not publish feedback for turn {}", turnId, e);
        }
    }
}
