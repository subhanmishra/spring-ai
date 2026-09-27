package com.example.subhanmishra.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the chat-turn topic, which {@code KafkaAdmin} creates at startup if it is missing.
 *
 * <p>One partition, deliberately. The consumer judges turns one at a time anyway - Ollama serialises on
 * a single runner slot, so a second partition and a second consumer would only queue two judgements
 * behind the same model - and a single partition keeps every turn in order without relying on keys.
 * One replica because there is one broker.
 *
 * <p>A broker that is down at startup does not stop the application: {@code KafkaAdmin} logs the
 * failure and carries on, and the topic is created on the next start that can reach it. Until then
 * sends fail and are counted as dropped, which is the same outcome as any other broker outage.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic chatTurnsTopic(EventsProperties properties) {
        EventsProperties.ChatTurns chatTurns = properties.chatTurns();
        return TopicBuilder.name(chatTurns.topic())
                           .partitions(1)
                           .replicas(1)
                           .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(chatTurns.retention().toMillis()))
                           .build();
    }
}
