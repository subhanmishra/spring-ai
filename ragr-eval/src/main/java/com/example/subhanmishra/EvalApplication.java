package com.example.subhanmishra;

import com.example.subhanmishra.config.EvalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Evaluation, as its own application, so scoring and judging never share a JVM, a thread pool or a
 * failure with chat.
 *
 * <p>Chat turns arrive from Kafka as {@code ChatTurnCompleted} events; {@code OnlineEvalService} scores
 * and stores them, and {@code TurnJudgeWorker} judges them while chat is idle. A golden run
 * ({@code GoldenEvalService}, started over HTTP) asks the running ragr-app its curated questions, and its
 * turns come back the same way. The web server is for the actuator, golden runs and human review.
 */
@SpringBootApplication
@EnableConfigurationProperties(EvalProperties.class)
@EnableScheduling
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalApplication.class, args);
    }
}
