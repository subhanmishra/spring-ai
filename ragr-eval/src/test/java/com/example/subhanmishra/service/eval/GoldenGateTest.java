package com.example.subhanmishra.service.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The regression verdict: each threshold, on either side of its boundary. */
class GoldenGateTest {

    private static GoldenRunResult result(double hitRate, double fabrication, int inventedPages) {
        return new GoldenRunResult("suite", 24, 24, hitRate, 0.9, null, null, null, null, null, null,
                                   1.0 - fabrication, fabrication, 20, inventedPages, null, 1_000, List.of());
    }

    @Test
    @DisplayName("a run at every boundary passes")
    void boundariesPass() {
        assertThat(GoldenGate.failures(result(0.6, 0.10, 0))).isEmpty();
    }

    @Test
    @DisplayName("one invented page fails the run")
    void inventedPage() {
        assertThat(GoldenGate.failures(result(1.0, 0.0, 1))).singleElement().asString().contains("invented pages");
    }

    @Test
    @DisplayName("fabrication above a tenth fails the run")
    void fabrication() {
        assertThat(GoldenGate.failures(result(1.0, 0.11, 0))).singleElement().asString().contains("fabrication");
    }

    @Test
    @DisplayName("a hit rate below 0.6 fails the run, and every miss is named")
    void hitRateAndAll() {
        assertThat(GoldenGate.failures(result(0.59, 0.0, 0))).singleElement().asString().contains("hit rate");
        assertThat(GoldenGate.failures(result(0.5, 0.2, 2))).hasSize(3);
    }
}
