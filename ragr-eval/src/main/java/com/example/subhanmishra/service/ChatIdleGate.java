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
 * started while a user waits puts that user behind it. Kafka took judging off the chat <em>thread</em>;
 * this is what takes it off the chat <em>model</em>. Measured 6 Oct 2026 before the gate existed: a chat
 * turn takes 16-75 s alone, and the two judges then running on every turn took 5-22 s each.
 *
 * <p>The signal is ragr-app's {@code rag.chat.generations.active} gauge, read from its actuator. This is
 * the second place one application calls another - the first being the golden suite driving chat - and
 * it is read-only. An unreachable ragr-app counts as idle: with no chat running there is nobody to
 * protect, and an eval process that stopped judging whenever ragr-app was down would build a backlog for
 * no reason.
 *
 * <p>What it cannot do is pre-empt. A user who arrives while a judge call is already running waits for
 * that one call - which is why the judges are many short calls rather than a few long ones.
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
        // A probe that hangs would hold the judge just as surely as a busy chat; a second is plenty for a
        // local actuator read.
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
