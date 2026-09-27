package com.example.subhanmishra;

import com.example.subhanmishra.config.EvalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Evaluation, run as its own application so that scoring and LLM judging never share a JVM, a thread
 * pool or a failure with the chat path.
 *
 * <p>Live turns arrive from Kafka as {@code ChatTurnCompleted} events, published by {@code ragr-app}
 * once each answer is complete; {@code OnlineEvalService} scores them. The golden suite, run by the
 * tagged {@code EvalSuiteIT}, drives the running {@code ragr-app} over HTTP and reads its own turns back
 * from the same topic. Nothing here serves an API: the web server exists for the actuator endpoints
 * Prometheus scrapes.
 */
@SpringBootApplication
@EnableConfigurationProperties(EvalProperties.class)
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalApplication.class, args);
    }
}
