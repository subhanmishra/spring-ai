package com.example.subhanmishra.service.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/** The rank arithmetic, worked by hand for each case. */
class RetrievalRankingTest {

    @Test
    @DisplayName("relevant first, one more cut off by the threshold: precision, pooled recall, MRR and NDCG")
    void cutOffRelevantChunk() {
        // Pool of five, three in the prompt; relevant at ranks 1 and 4 - rank 4 was left out.
        RetrievalRanking ranking = RetrievalRanking.of(List.of(2, 0, 1, 2, 0), 3);

        assertThat(ranking.precisionAtK()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(ranking.recallAtK()).isEqualTo(0.5);
        assertThat(ranking.reciprocalRank()).isEqualTo(1.0);
        assertThat(ranking.relevantCutOff()).isEqualTo(1);
        // DCG@3 = 3/1 + 0 + 1/log2(4) = 3.5; ideal is grades 2,2,1 = 3 + 3/log2(3) + 1/2
        double ideal = 3 + 3 / (Math.log(3) / Math.log(2)) + 0.5;
        assertThat(ranking.ndcgAtK()).isCloseTo(3.5 / ideal, within(1e-9));
        assertThat(ranking.retrievalOk()).isTrue();
    }

    @Test
    @DisplayName("a relevant chunk below rank 1 is what MRR sees")
    void reciprocalRankOfSecond() {
        assertThat(RetrievalRanking.of(List.of(0, 2, 0), 3).reciprocalRank()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("partly relevant chunks earn NDCG gain but do not make retrieval right")
    void partlyRelevantOnly() {
        RetrievalRanking ranking = RetrievalRanking.of(List.of(1, 1, 0), 2);

        assertThat(ranking.precisionAtK()).isZero();
        assertThat(ranking.reciprocalRank()).isZero();
        assertThat(ranking.recallAtK()).as("nothing relevant to recall").isNull();
        assertThat(ranking.relevantInPool()).isZero();
        assertThat(ranking.ndcgAtK()).isEqualTo(1.0);
        assertThat(ranking.retrievalOk()).isFalse();
    }

    @Test
    @DisplayName("a pool with no gain at all has no NDCG, and an empty prompt no precision or MRR")
    void undefinedCases() {
        RetrievalRanking none = RetrievalRanking.of(List.of(0, 0), 2);
        assertThat(none.ndcgAtK()).isNull();
        assertThat(none.recallAtK()).isNull();

        RetrievalRanking empty = RetrievalRanking.of(List.of(2, 0), 0);
        assertThat(empty.precisionAtK()).isNull();
        assertThat(empty.reciprocalRank()).isNull();
        assertThat(empty.recallAtK()).isZero();
        assertThat(empty.relevantCutOff()).isEqualTo(1);
    }

    @Test
    @DisplayName("more in context than in the pool is a caller error")
    void inContextBeyondPool() {
        assertThatIllegalArgumentException().isThrownBy(() -> RetrievalRanking.of(List.of(2), 2));
    }
}
