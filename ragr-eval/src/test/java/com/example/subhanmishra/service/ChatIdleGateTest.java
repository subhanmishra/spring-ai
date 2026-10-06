package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate against a stand-in for ragr-app's actuator: it holds while the gauge reads busy, lets go once
 * it reads idle, and never holds when ragr-app cannot be reached.
 */
class ChatIdleGateTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves the gauge as busy for the first {@code busyReads} reads, then idle. */
    private URI actuator(int busyReads) throws IOException {
        AtomicInteger reads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/metrics/rag.chat.generations.active", exchange -> {
            double value = reads.getAndIncrement() < busyReads ? 1.0 : 0.0;
            byte[] body = ("{\"name\":\"rag.chat.generations.active\",\"measurements\":"
                           + "[{\"statistic\":\"VALUE\",\"value\":" + value + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private ChatIdleGate gate(URI url, Duration maxWait) {
        EvalProperties base = EvalPropertiesFixture.properties(1.0);
        EvalProperties.Judge judge = base.judge();
        EvalProperties.Judge gated = new EvalProperties.Judge(judge.workerEnabled(), judge.pollInterval(),
                judge.maxBacklogAge(), judge.callTimeoutSeconds(), judge.shutdownWaitSeconds(),
                judge.faithfulnessThreshold(), judge.maxClaims(), judge.claimsNumPredict(),
                new EvalProperties.IdleGate(true, url, Duration.ofMillis(20), maxWait));
        EvalProperties properties = new EvalProperties(true, base.topic(), base.feedbackTopic(), base.judgeModel(),
                base.judgeTemperature(), base.judgeNumPredict(), base.judgeNumCtx(), base.online(), gated,
                base.retention(), base.golden());
        return new ChatIdleGate(properties, RestClient.builder(), registry);
    }

    @Test
    @DisplayName("holds while a generation is running and lets go once chat is idle")
    void holdsWhileBusy() throws Exception {
        ChatIdleGate gate = gate(actuator(3), Duration.ofSeconds(10));

        gate.awaitIdle();

        assertThat(registry.get("rag.eval.online.judge.idle.waits").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.eval.online.judge.idle.timeouts").counter().count()).isZero();
    }

    @Test
    @DisplayName("does not wait at all when chat is idle")
    void idlePassesStraightThrough() throws Exception {
        gate(actuator(0), Duration.ofSeconds(10)).awaitIdle();

        assertThat(registry.get("rag.eval.online.judge.idle.waits").counter().count()).isZero();
    }

    @Test
    @DisplayName("gives up after max-wait rather than stalling the queue on a stuck gauge")
    void maxWait() throws Exception {
        ChatIdleGate gate = gate(actuator(Integer.MAX_VALUE), Duration.ofMillis(200));

        long startedAt = System.nanoTime();
        gate.awaitIdle();

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        assertThat(registry.get("rag.eval.online.judge.idle.timeouts").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unreachable ragr-app counts as idle")
    void unreachableIsIdle() throws Exception {
        ChatIdleGate gate = gate(URI.create("http://127.0.0.1:9"), Duration.ofSeconds(10));

        assertThat(gate.activeGenerations()).isZero();
        gate.awaitIdle();
    }
}
