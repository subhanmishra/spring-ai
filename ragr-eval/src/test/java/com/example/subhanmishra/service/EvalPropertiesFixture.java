package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;

import java.net.URI;
import java.time.Duration;

/** Settings for unit tests: the values {@code application.yaml} ships, with the knobs a test turns exposed. */
final class EvalPropertiesFixture {

    private EvalPropertiesFixture() {
    }

    static EvalProperties properties(double judgeSampleRate) {
        return properties(judgeSampleRate, 120, 20, false);
    }

    static EvalProperties properties(double judgeSampleRate, int callTimeoutSeconds, int shutdownWaitSeconds,
                                     boolean idleGate) {
        EvalProperties.Online online = new EvalProperties.Online(true, judgeSampleRate, 0.0, Duration.ofMinutes(5), 0.6);
        EvalProperties.IdleGate gate = new EvalProperties.IdleGate(idleGate, URI.create("http://localhost:9"),
                                                                   Duration.ofMillis(20), Duration.ofSeconds(5));
        EvalProperties.Judge judge = new EvalProperties.Judge(true, Duration.ofMillis(20), Duration.ofHours(6),
                                                              callTimeoutSeconds, shutdownWaitSeconds, 0.8, 8, 400,
                                                              gate);
        return new EvalProperties(true, "rag.chat.turn.completed", "rag.chat.feedback", "judge", 0.0, 8, 8192,
                                  online, judge, new EvalProperties.Retention(14), null);
    }
}
