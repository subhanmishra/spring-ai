package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * What the retrieval step produced for one query, scored without reference to any expected answer.
 *
 * @param retrievedCount   chunks in the prompt. Fewer than top-k means the threshold cut the rest; zero
 *                         means the answer was ungrounded, however confident it sounds
 * @param topScore         similarity of the best chunk, or 0 when nothing was retrieved
 * @param lowestScore      similarity of the weakest chunk that cleared the threshold
 * @param scoreSpread      {@code topScore - lowestScore}. Watch it as the corpus grows: every chunk embeds
 *                         the same filename, which narrows the gap, and near zero the ranking is arbitrary
 * @param pagesRetrieved   distinct pages in the prompt, in rank order
 * @param chunksWithHeader chunks with a citation header. Fewer than {@code retrievedCount} means some were
 *                         stored before headers existed and cannot be cited
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
