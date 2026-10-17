package com.example.subhanmishra.service.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * The regression verdict on a golden run, from its rule-based scores only.
 *
 * <p>Loose on purpose: a regression guard, not a quality bar. It catches a pipeline that has broken
 * outright - retrieval finding nothing, or the model inventing citations - not normal run-to-run variance.
 */
public final class GoldenGate {

    /** Against page-less sources any page cited is invented, so none is tolerated. */
    public static final int MAX_INVENTED_PAGES = 0;

    /** Share of emitted citations naming a source the turn never retrieved; tolerates one stray in ~20. */
    public static final double MAX_CITATION_FABRICATION = 0.10;

    /** Share of recall cases where an expected source was retrieved; well below the observed level. */
    public static final double MIN_HIT_RATE = 0.6;

    private GoldenGate() {
    }

    /** Each threshold the run missed, as a sentence; empty when it passed. */
    public static List<String> failures(GoldenRunResult result) {
        List<String> failures = new ArrayList<>();
        if (result.inventedPageCount() > MAX_INVENTED_PAGES) {
            failures.add("invented pages %d > %d".formatted(result.inventedPageCount(), MAX_INVENTED_PAGES));
        }
        if (result.citationFabrication() > MAX_CITATION_FABRICATION) {
            failures.add("citation fabrication %.3f > %.2f".formatted(result.citationFabrication(),
                                                                      MAX_CITATION_FABRICATION));
        }
        if (result.hitRate() < MIN_HIT_RATE) {
            failures.add("hit rate %.3f < %.2f".formatted(result.hitRate(), MIN_HIT_RATE));
        }
        return failures;
    }
}
