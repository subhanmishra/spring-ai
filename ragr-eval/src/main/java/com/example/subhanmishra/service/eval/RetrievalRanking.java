package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Rank metrics for one turn's retrieval, computed from a graded candidate pool.
 *
 * <p>The pool is every chunk the one vector query returned, in score order; the first
 * {@code inContext} of them were in the prompt. Each carries a grade - 0 not relevant, 1 partly, 2
 * relevant - from the judge on live traffic, or from the dataset's expected pages on a golden case
 * (2 on an expected page, else 0). The same arithmetic serves both, which is what makes the two
 * comparable at all.
 *
 * <p><strong>A chunk counts as relevant at grade 2 only.</strong> Every pool chunk is topically close to
 * the question by construction - it was retrieved for it - and a three-level scale gives that topical
 * closeness somewhere to go other than "relevant". Grade 1 still earns gain in NDCG, so it is not thrown
 * away; it just cannot make a turn's retrieval "right".
 *
 * <p><strong>Recall is pooled, and an upper bound.</strong> Its denominator is the relevant chunks in the
 * pool, not in the corpus: a relevant chunk that ranked below the pool is invisible to it. So a recall of
 * 1.0 means retrieval kept everything relevant it found, not everything relevant that exists. It is
 * {@code null} when the pool holds nothing relevant - "nothing to recall" is not a recall of zero, and
 * {@link #relevantInPool} = 0 is the signal for that case (an uncovered question, or a pool too shallow).
 *
 * @param precisionAtK      relevant chunks in the prompt / chunks in the prompt; null when the prompt had none
 * @param recallAtK         relevant chunks in the prompt / relevant chunks in the pool; null when the pool
 *                          has none
 * @param reciprocalRank    1 / rank of the first relevant chunk in the prompt, 0 when none is; null when
 *                          the prompt had no chunks
 * @param ndcgAtK           discounted cumulative gain of the prompt's chunks, with gain 2^grade - 1, over
 *                          the best possible ordering of the whole pool; null when the pool has no gain
 * @param relevantInContext relevant chunks the model was shown
 * @param relevantInPool    relevant chunks retrieval found at all
 * @param relevantCutOff    relevant chunks found but left out by top-k or the threshold - the direct
 *                          signal that one of them is set too tight
 */
public record RetrievalRanking(@Nullable Double precisionAtK,
                               @Nullable Double recallAtK,
                               @Nullable Double reciprocalRank,
                               @Nullable Double ndcgAtK,
                               int relevantInContext,
                               int relevantInPool,
                               int relevantCutOff) {

    /** The grade at which a chunk counts as relevant. */
    public static final int RELEVANT = 2;

    /**
     * @param grades    one grade per pool chunk, in rank order
     * @param inContext how many leading chunks were in the prompt
     */
    public static RetrievalRanking of(List<Integer> grades, int inContext) {
        if (inContext < 0 || inContext > grades.size()) {
            throw new IllegalArgumentException("inContext " + inContext + " outside a pool of " + grades.size());
        }
        int relevantInContext = 0;
        int firstRelevantRank = 0;
        for (int i = 0; i < inContext; i++) {
            if (grades.get(i) >= RELEVANT) {
                relevantInContext++;
                if (firstRelevantRank == 0) {
                    firstRelevantRank = i + 1;
                }
            }
        }
        int relevantInPool = (int) grades.stream().filter(grade -> grade >= RELEVANT).count();

        Double precision = inContext > 0 ? (double) relevantInContext / inContext : null;
        Double recall = relevantInPool > 0 ? (double) relevantInContext / relevantInPool : null;
        Double reciprocalRank = inContext > 0 ? (firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0) : null;

        double ideal = dcg(grades.stream().sorted(Comparator.reverseOrder()).toList(), inContext);
        Double ndcg = ideal > 0 ? dcg(grades, inContext) / ideal : null;

        return new RetrievalRanking(precision, recall, reciprocalRank, ndcg, relevantInContext, relevantInPool,
                                    relevantInPool - relevantInContext);
    }

    /** Whether a relevant chunk reached the prompt - retrieval's half of the debugging matrix. */
    public boolean retrievalOk() {
        return relevantInContext > 0;
    }

    /** DCG over the first {@code k} grades: sum of (2^g - 1) / log2(rank + 1). */
    private static double dcg(List<Integer> grades, int k) {
        double total = 0;
        for (int i = 0; i < Math.min(k, grades.size()); i++) {
            total += (Math.pow(2, grades.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return total;
    }
}
