package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * How well the retrieved set was ordered, scored from a per-chunk relevance verdict.
 *
 * <p>This is RAGAS's {@code ContextPrecision}, and the two numbers here are deliberately both kept
 * because the metric everyone reaches for does not measure what its name suggests.
 *
 * <p><strong>{@link #averagePrecision()} is RAGAS's definition</strong> - mean of {@code Precision@k}
 * taken at each rank that holds a relevant chunk, divided by the number of relevant chunks retrieved:
 *
 * <pre>{@code
 * averagePrecision = sum(Precision@k * v_k) / relevantCount
 * Precision@k      = (relevant chunks in ranks 1..k) / k
 * }</pre>
 *
 * Note what that denominator does: it normalises by the relevant chunks that were <em>found</em>, not
 * by top-k. {@code [1,0,0,0,0]} and {@code [1,1,1,1,1]} both score 1.000. It is a measure of
 * <em>ranking quality among the relevant chunks retrieved</em> - were they at the top - and says
 * nothing about how much of the context was noise. Where it earns its place next to MRR is the case
 * MRR cannot see: {@code [1,1,0,0,0]} scores 1.000 and {@code [1,0,0,0,1]} scores 0.700, while hit
 * rate and MRR are 1.000 for both.
 *
 * <p><strong>{@link #precisionAtK()} is the noise fraction</strong> - plain {@code relevantCount / k},
 * unweighted by rank. This is the question "how much of what I paid to retrieve was junk", which is
 * the one the average above is routinely but wrongly assumed to answer. It costs one division off the
 * same vector, so there is no reason to compute one without the other.
 *
 * <p>Read them together. A high average with a low precision@k is a well-ordered context that is
 * mostly padding - the signal to lower top-k or raise the similarity threshold. Both low means
 * retrieval is ranking badly, which points at embedding or chunking rather than at the advisor.
 *
 * @param averagePrecision rank-weighted, normalised by relevant chunks found. 0.0 when none were.
 * @param precisionAtK     fraction of the retrieved set judged relevant
 * @param relevantCount    chunks judged relevant
 * @param retrievedCount   chunks the advisor returned, the {@code k} in {@code Precision@k}
 * @param relevance        the per-rank verdicts themselves, in rank order. Kept rather than discarded
 *                         because the two relevance sources disagree in a way that is worth reading:
 *                         a chunk the LLM judged useful that {@code expectedPages} omits is evidence
 *                         the dataset's page list is too narrow, not that retrieval erred.
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

        // Nothing relevant retrieved is a genuine 0.0 rather than a missing measurement: the case was
        // scored, and it scored badly. "Not scored at all" is represented by a null ContextPrecisionScores
        // at the call site, which is why it is not folded in here.
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
