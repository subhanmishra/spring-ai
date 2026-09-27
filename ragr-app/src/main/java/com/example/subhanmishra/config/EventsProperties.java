package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where completed chat turns are published for evaluation.
 *
 * @param chatTurns the chat-turn topic
 */
@ConfigurationProperties(prefix = "app.events")
public record EventsProperties(ChatTurns chatTurns) {

    /**
     * @param enabled   off, no event is built or sent; the chat path is otherwise unchanged
     * @param topic     created at startup by {@code KafkaConfig}, since the broker does not auto-create
     * @param retention how long the broker keeps turns. Longer than evaluation needs to keep up, because
     *                  the retained log is also what lets past traffic be replayed through a new judge
     */
    public record ChatTurns(boolean enabled, String topic, Duration retention) {
    }
}
