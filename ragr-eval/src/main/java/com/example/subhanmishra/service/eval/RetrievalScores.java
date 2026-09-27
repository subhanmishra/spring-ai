package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * What the retrieval step produced for one query, scored without reference to any expected answer.
 *
 * @param retrievedCount   chunks the advisor actually retrieved. Fewer than the configured top-k means
 *                         the similarity threshold excluded the rest, and zero means the answer was
 *                         ungrounded no matter how confident it sounds.
 * @param topScore         similarity of the best hit, or 0 when nothing was retrieved
 * @param lowestScore      similarity of the worst hit that still cleared the threshold
 * @param scoreSpread      {@code topScore - lowestScore}. Worth watching as the corpus grows: every
 *                         chunk carries an identical filename prefix in its embedded text, which
 *                         compresses the spread between vectors, and a band that narrows towards zero
 *                         means top-k ranking is becoming arbitrary.
 * @param pagesRetrieved   distinct pages present in the retrieved set, in rank order
 * @param chunksWithHeader how many retrieved chunks carry a citation header. Anything below
 *                         {@code retrievedCount} means the corpus is mixed - some chunks predate the
 *                         header and cannot be cited - and citation metrics are being scored against a
 *                         context that was never fully citable.
 */
public record RetrievalScores(int retrievedCount,
                              double topScore,
                              double lowestScore,
                              double scoreSpread,
                              List<Integer> pagesRetrieved,
                              int chunksWithHeader) {

    public static final RetrievalScores EMPTY = new RetrievalScores(0, 0, 0, 0, List.of(), 0);

    public boolean isZeroHit() {
        return retrievedCount == 0;
    }
}
