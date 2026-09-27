package com.example.subhanmishra.service.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The formula, checked against the worked examples quoted in {@link ContextPrecisionScores}'s javadoc.
 *
 * <p>Worth testing despite being arithmetic, because the two numbers differ only in their denominator
 * and the whole point of keeping both is that they answer different questions. Swapping them silently
 * would leave every score plausible and every conclusion drawn from them wrong.
 */
class ContextPrecisionScoresTest {

    private static List<Boolean> ranks(int... flags) {
        return java.util.Arrays.stream(flags).mapToObj(flag -> flag == 1).toList();
    }

    @Nested
    @DisplayName("average precision - ranking quality")
    class AveragePrecision {

        @Test
        @DisplayName("normalises by relevant chunks found, not by k")
        void normalisesByRelevantFound() {
            // The property that surprises people: one relevant chunk at rank 1 and five relevant chunks
            // at every rank both score a perfect 1.000, because neither is mis-ordered.
            assertThat(ContextPrecisionScores.of(ranks(1, 0, 0, 0, 0)).averagePrecision()).isEqualTo(1.0);
            assertThat(ContextPrecisionScores.of(ranks(1, 1, 1, 1, 1)).averagePrecision()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("separates two sets that hit rate and MRR both score identically")
        void separatesWhatMrrCannot() {
            // Hit rate 1.000 and MRR 1.000 for both - the first relevant chunk is at rank 1 either way.
            assertThat(ContextPrecisionScores.of(ranks(1, 1, 0, 0, 0)).averagePrecision())
                    .isEqualTo(1.0);
            // (1/1 + 2/5) / 2
            assertThat(ContextPrecisionScores.of(ranks(1, 0, 0, 0, 1)).averagePrecision())
                    .isCloseTo(0.7, within(1e-9));
        }

        @Test
        @DisplayName("punishes a relevant chunk buried at the bottom")
        void punishesLateRelevance() {
            assertThat(ContextPrecisionScores.of(ranks(0, 0, 0, 0, 1)).averagePrecision())
                    .isCloseTo(0.2, within(1e-9));
        }

        @Test
        @DisplayName("nothing relevant retrieved is a scored zero, not a missing measurement")
        void nothingRelevant() {
            ContextPrecisionScores scores = ContextPrecisionScores.of(ranks(0, 0, 0));
            assertThat(scores.averagePrecision()).isZero();
            assertThat(scores.precisionAtK()).isZero();
            assertThat(scores.relevantCount()).isZero();
            assertThat(scores.retrievedCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("precision@k - the noise fraction")
    class PrecisionAtK {

        @Test
        @DisplayName("is relevant over k, unweighted by rank")
        void unweightedByRank() {
            // Same two chunks, opposite ends of the ranking: precision@k cannot tell them apart, which
            // is exactly why it is not reported on its own.
            assertThat(ContextPrecisionScores.of(ranks(1, 1, 0, 0, 0)).precisionAtK())
                    .isCloseTo(0.4, within(1e-9));
            assertThat(ContextPrecisionScores.of(ranks(0, 0, 0, 1, 1)).precisionAtK())
                    .isCloseTo(0.4, within(1e-9));
        }

        @Test
        @DisplayName("a well-ordered context that is mostly padding scores high then low")
        void ordersWellButRetrievesJunk() {
            ContextPrecisionScores scores = ContextPrecisionScores.of(ranks(1, 0, 0, 0, 0));
            assertThat(scores.averagePrecision()).isEqualTo(1.0);
            assertThat(scores.precisionAtK()).isCloseTo(0.2, within(1e-9));
        }
    }

    @Test
    @DisplayName("an empty retrieved set scores zero on both and records no chunks")
    void empty() {
        ContextPrecisionScores scores = ContextPrecisionScores.of(List.of());
        assertThat(scores.averagePrecision()).isZero();
        assertThat(scores.precisionAtK()).isZero();
        assertThat(scores.retrievedCount()).isZero();
        assertThat(scores.relevanceAsString()).isEmpty();
    }

    @Test
    @DisplayName("the verdict vector round-trips in rank order")
    void relevanceVector() {
        assertThat(ContextPrecisionScores.of(ranks(1, 0, 1, 1, 0)).relevanceAsString())
                .isEqualTo("1,0,1,1,0");
    }

    @Nested
    @DisplayName("judge verdict parsing")
    class VerdictParsing {

        @Test
        @DisplayName("reads the leading word, whatever punctuation follows")
        void leadingWord() {
            assertThat(ContextPrecisionEvaluator.parse("YES")).isTrue();
            assertThat(ContextPrecisionEvaluator.parse("  yes.\n")).isTrue();
            assertThat(ContextPrecisionEvaluator.parse("No, the passage is unrelated.")).isFalse();
        }

        @Test
        @DisplayName("\"not\" does not read as \"no\"")
        void notIsNotNo() {
            // The reason the first word is matched as a whole word: a judge that ignores the one-word
            // instruction and opens with "Not enough information" would otherwise be recorded as a
            // confident NO rather than as the failed measurement it is.
            assertThat(ContextPrecisionEvaluator.parse("Not enough information to say")).isNull();
        }

        @Test
        @DisplayName("anything unparseable is null - a failed measurement, never a NO")
        void unparseable() {
            assertThat(ContextPrecisionEvaluator.parse(null)).isNull();
            assertThat(ContextPrecisionEvaluator.parse("")).isNull();
            assertThat(ContextPrecisionEvaluator.parse("42")).isNull();
            assertThat(ContextPrecisionEvaluator.parse("maybe")).isNull();
        }
    }
}
