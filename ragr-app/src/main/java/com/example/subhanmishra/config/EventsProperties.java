package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where completed chat turns, and users' feedback on them, are published for evaluation.
 *
 * @param chatTurns the chat-turn topic
 * @param feedback  the feedback topic
 */
@ConfigurationProperties(prefix = "app.events")
public record EventsProperties(ChatTurns chatTurns, Feedback feedback) {

    /**
     * @param enabled   when off, no event is built or sent; chat is otherwise unchanged
     * @param topic     created at startup by {@code KafkaConfig}; the broker does not create topics itself
     * @param retention how long Kafka keeps turns - longer than evaluation needs, so past turns can be
     *                  replayed through a new judge
     */
    public record ChatTurns(boolean enabled, String topic, Duration retention) {
    }

    /**
     * @param topic     created at startup by {@code KafkaConfig}
     * @param retention how long Kafka keeps ratings; ragr-eval stores them on arrival, so this only has to
     *                  outlast an evaluation outage
     */
    public record Feedback(String topic, Duration retention) {
    }
}
