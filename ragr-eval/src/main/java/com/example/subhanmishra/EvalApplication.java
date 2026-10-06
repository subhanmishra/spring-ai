package com.example.subhanmishra;

import com.example.subhanmishra.config.EvalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Evaluation, run as its own application so that scoring and LLM judging never share a JVM, a thread
 * pool or a failure with the chat path.
 *
 * <p>Live turns arrive from Kafka as {@code ChatTurnCompleted} events, published by {@code ragr-app}
 * once each answer is complete; {@code OnlineEvalService} scores and stores them, and
 * {@code TurnJudgeWorker} judges them while chat is idle. The golden suite, run by the
 * tagged {@code EvalSuiteIT}, drives the running {@code ragr-app} over HTTP and reads its own turns back
 * from the same topic. The web server exists for the actuator endpoints Prometheus scrapes and for one
 * API, human review of evaluated turns.
 */
@SpringBootApplication
@EnableConfigurationProperties(EvalProperties.class)
@EnableScheduling
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalApplication.class, args);
    }
}
