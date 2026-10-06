package com.example.subhanmishra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt must receive exactly what the stock advisor's top-k, threshold-filtered search would have
 * returned, however deep the pool behind it is.
 */
class PooledQuestionAnswerAdvisorTest {

    @Test
    @DisplayName("top-k caps the prompt when every candidate clears the threshold")
    void topKCaps() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.9, 0.85, 0.8, 0.75, 0.7, 0.65), 5, 0.6))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("the threshold cuts the prompt short of top-k")
    void thresholdCuts() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.9, 0.7, 0.59, 0.5), 5, 0.6)).isEqualTo(2);
    }

    @Test
    @DisplayName("a score exactly at the threshold is kept, as the vector store's own filter keeps it")
    void thresholdInclusive() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.6, 0.59), 5, 0.6)).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing reaches the prompt when the best candidate is below the threshold")
    void noneClear() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.55, 0.5), 5, 0.6)).isZero();
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(List.of(), 5, 0.6)).isZero();
    }

    private static List<Document> pool(double... scores) {
        return Arrays.stream(scores)
                     .mapToObj(score -> Document.builder().text("chunk " + score).score(score).build())
                     .toList();
    }
}
