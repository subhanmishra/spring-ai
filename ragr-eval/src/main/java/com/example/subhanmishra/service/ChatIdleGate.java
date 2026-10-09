package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Holds each judge call until no chat generation is running.
 *
 * <p>The judges and chat share one Ollama runner that serves one request at a time, so a judge call
 * makes a waiting user wait longer. Kafka took judging off chat's <em>thread</em>; this takes it off
 * chat's <em>model</em>.
 *
 * <p>The signal is ragr-app's {@code rag.chat.generations.active} gauge, read from its actuator - one of
 * only two places an application calls another, and read-only. An unreachable ragr-app counts as idle:
 * with no chat running there is nobody to protect.
 *
 * <p>It cannot pre-empt. A user arriving mid-call waits for that one call - which is why the judges are
 * many short calls, not a few long ones.
 */
@Service
public class ChatIdleGate {

    private static final Logger log = LoggerFactory.getLogger(ChatIdleGate.class);
    private static final String GAUGE_PATH = "/actuator/metrics/rag.chat.generations.active";

    private final EvalProperties.IdleGate properties;
    private final RestClient actuator;
    private final Counter waits;
    private final Counter timeouts;

    public ChatIdleGate(EvalProperties properties, RestClient.Builder restClients, MeterRegistry registry) {
        this.properties = properties.judge().idleGate();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        // A hanging probe would hold the judge as surely as busy chat; a second is plenty locally.
        requestFactory.setReadTimeout(Duration.ofSeconds(1));
        this.actuator = restClients.baseUrl(this.properties.chatActuatorUrl().toString())
                                   .requestFactory(requestFactory)
                                   .build();
        this.waits = Counter.builder("rag.eval.online.judge.idle.waits")
                            .description("Judge calls held because a chat generation was running")
                            .register(registry);
        this.timeouts = Counter.builder("rag.eval.online.judge.idle.timeouts")
                               .description("Judge calls released after waiting max-wait for chat to go idle")
                               .register(registry);
    }

    /**
     * Returns once chat is idle, after {@code max-wait}, or at once when the gate is disabled.
     *
     * @throws InterruptedException when the worker is being stopped
     */
    public void awaitIdle() throws InterruptedException {
        if (!properties.enabled()) {
            return;
        }
        long deadline = System.nanoTime() + properties.maxWait().toNanos();
        boolean waited = false;
        while (activeGenerations() > 0) {
            if (!waited) {
                waits.increment();
                waited = true;
            }
            if (System.nanoTime() > deadline) {
                timeouts.increment();
                log.warn("Chat still busy after {}; judging anyway rather than stalling the queue",
                         properties.maxWait());
                return;
            }
            Thread.sleep(properties.pollInterval());
        }
    }

    /** The gauge's value, or 0 when it cannot be read. */
    @SuppressWarnings("unchecked")
    double activeGenerations() {
        try {
            Map<String, Object> body = actuator.get().uri(GAUGE_PATH).retrieve().body(Map.class);
            if (body != null && body.get("measurements") instanceof List<?> measurements && !measurements.isEmpty()
                    && measurements.getFirst() instanceof Map<?, ?> first && first.get("value") instanceof Number value) {
                return value.doubleValue();
            }
        } catch (RuntimeException e) {
            log.debug("Could not read ragr-app's active generations; treating chat as idle", e);
        }
        return 0;
    }
}
