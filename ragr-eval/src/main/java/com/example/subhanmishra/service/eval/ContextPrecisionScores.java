package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * How well the retrieved set was ordered, scored from a per-chunk relevance verdict.
 *
 * <p>RAGAS's context precision, plus a second number, because the RAGAS one does not measure what its
 * name suggests.
 *
 * <p><b>{@link #averagePrecision()} is RAGAS's definition</b> - {@code Precision@k} at each rank holding a
 * relevant chunk, averaged over the relevant chunks retrieved:
 *
 * <pre>{@code
 * averagePrecision = sum(Precision@k * v_k) / relevantCount
 * Precision@k      = (relevant chunks in ranks 1..k) / k
 * }</pre>
 *
 * It divides by the relevant chunks <em>found</em>, not by k, so {@code [1,0,0,0,0]} and
 * {@code [1,1,1,1,1]} both score 1.000. It measures whether the relevant chunks were at the top, not how
 * much of the context was noise. Its value beside MRR: {@code [1,1,0,0,0]} scores 1.000 and
 * {@code [1,0,0,0,1]} 0.700, where MRR gives both 1.000.
 *
 * <p><b>{@link #precisionAtK()} is the noise share</b> - plain {@code relevantCount / k}: how much of what
 * was retrieved was junk, the question people wrongly expect the first number to answer.
 *
 * <p>Read them together. A high average with a low precision@k is a well-ordered but padded context:
 * lower top-k or raise the threshold. Both low means retrieval ranks badly: look at embedding or
 * chunking.
 *
 * @param averagePrecision rank-weighted, normalised by relevant chunks found. 0.0 when none were.
 * @param precisionAtK     fraction of the retrieved set judged relevant
 * @param relevantCount    chunks judged relevant
 * @param retrievedCount   chunks the advisor returned, the {@code k} in {@code Precision@k}
 * @param relevance        the per-rank verdicts, kept because where the judge and the expected pages
 *                         disagree is worth reading: a useful chunk the pages omit means the list is too
 *                         narrow, not that retrieval erred
 */
public record ContextPrecisionScores(double averagePrecision,
                                     double precisionAtK,
                                     int relevantCount,
                                     int retrievedCount,
                                     List<Boolean> relevance) {

    public ContextPrecisionScores {
        relevance = List.copyOf(relevance);
    }

    /** Scores one ranked list of verdicts, ordered best hit first. */
    public static ContextPrecisionScores of(List<Boolean> relevance) {
        int retrieved = relevance.size();
        if (retrieved == 0) {
            return new ContextPrecisionScores(0.0, 0.0, 0, 0, List.of());
        }

        int relevantSoFar = 0;
        double weightedTotal = 0;
        for (int index = 0; index < retrieved; index++) {
            if (Boolean.TRUE.equals(relevance.get(index))) {
                relevantSoFar++;
                // Precision@k at this rank, k being 1-based.
                weightedTotal += (double) relevantSoFar / (index + 1);
            }
        }

        // Nothing relevant is a real 0.0: scored, and badly. "Not scored" is a null at the call site.
        double average = relevantSoFar == 0 ? 0.0 : weightedTotal / relevantSoFar;

        return new ContextPrecisionScores(average,
                                          (double) relevantSoFar / retrieved,
                                          relevantSoFar,
                                          retrieved,
                                          relevance);
    }

    /** The verdict vector as {@code "1,0,1"}, for the per-case row that makes the two sources comparable. */
    public String relevanceAsString() {
        StringBuilder text = new StringBuilder(relevance.size() * 2);
        for (Boolean relevant : relevance) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(Boolean.TRUE.equals(relevant) ? '1' : '0');
        }
        return text.toString();
    }
}
