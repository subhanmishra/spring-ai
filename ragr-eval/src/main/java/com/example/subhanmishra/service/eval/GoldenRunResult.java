package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The rule-based aggregate of one golden run. The judges' verdicts are not part of it: they arrive on the
 * run's turns after it completes.
 *
 * @param contextPrecision      rank-weighted context precision over the cases that list expected files or
 *                              pages, with {@code precisionAtK} (relevant / k) beside it; null when no case
 *                              lists any. A <em>floor</em>: the lists hold the sources with the answer, not
 *                              every useful one. Compare runs, not levels.
 * @param citedContextPrecision the same, with a chunk counted as used when the answer cites it. It
 *                              under-counts: a passage used but not cited scores as unused.
 * @param citationValidity      1.0 when nothing was cited at all, which is why {@code citationsEmitted}
 *                              sits beside it.
 * @param inventedPageCount     citations naming a page number the context never offered; against
 *                              page-less sources, any page at all.
 */
public record GoldenRunResult(String suite,
                              int caseCount,
                              int passedCount,
                              double hitRate,
                              double meanReciprocalRank,
                              @Nullable Double contextPrecision,
                              @Nullable Double precisionAtK,
                              @Nullable Double citedContextPrecision,
                              @Nullable Double citedPrecisionAtK,
                              @Nullable Double recallAtK,
                              @Nullable Double ndcgAtK,
                              double citationValidity,
                              double citationFabrication,
                              int citationsEmitted,
                              int inventedPageCount,
                              @Nullable Double phraseCoverage,
                              long durationMillis,
                              List<String> failures) {

    public double passRate() {
        return caseCount == 0 ? 0.0 : (double) passedCount / caseCount;
    }
}
