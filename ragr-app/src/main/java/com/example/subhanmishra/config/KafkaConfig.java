package com.example.subhanmishra.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the two topics - turns and ratings - which {@code KafkaAdmin} creates at startup if missing.
 *
 * <p><b>One partition, on purpose.</b> Evaluation judges one turn at a time on the one Ollama runner, so
 * a second partition would only queue two judgements behind the same model; one keeps every turn in
 * order. One replica, because there is one broker.
 *
 * <p>Kafka being down at startup does not stop the application: the topics are created on the next start
 * that reaches it, and until then sends are counted as dropped, like any other outage.
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

    /** Users' ratings of answers. One partition for the same reasons; the volume is a fraction of turns. */
    @Bean
    public NewTopic feedbackTopic(EventsProperties properties) {
        EventsProperties.Feedback feedback = properties.feedback();
        return TopicBuilder.name(feedback.topic())
                           .partitions(1)
                           .replicas(1)
                           .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(feedback.retention().toMillis()))
                           .build();
    }
}
