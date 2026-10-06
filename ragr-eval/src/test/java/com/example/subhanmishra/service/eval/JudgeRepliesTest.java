package com.example.subhanmishra.service.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How each judge's reply is read. The rule throughout is strict: anything that is not the expected
 * answer is a failed measurement (null), never coerced into the nearer verdict.
 */
class JudgeRepliesTest {

    @Nested
    @DisplayName("YES/NO and digit verdicts")
    class Verdicts {

        @Test
        @DisplayName("reads the leading word, and 'not' is not 'no'")
        void yesNo() {
            assertThat(JudgeText.yesNo("YES")).isTrue();
            assertThat(JudgeText.yesNo(" no.")).isFalse();
            assertThat(JudgeText.yesNo("not sure")).isNull();
            assertThat(JudgeText.yesNo(null)).isNull();
        }

        @Test
        @DisplayName("reads a grade only inside its range")
        void digit() {
            assertThat(JudgeText.digit("2", 0, 2)).isEqualTo(2);
            assertThat(JudgeText.digit("Grade: 0", 0, 2)).isZero();
            assertThat(JudgeText.digit("3", 0, 2)).isNull();
            assertThat(JudgeText.digit("two", 0, 2)).isNull();
        }

        @Test
        @DisplayName("a chunk graded as the whole answer or part of it is stored as relevant")
        void chunkGradeFoldsWholeAndPartialAnswers() {
            assertThat(ChunkGradeEvaluator.gradeOf("Grade: 3")).isEqualTo(RetrievalRanking.RELEVANT);
            assertThat(ChunkGradeEvaluator.gradeOf("Grade: 2")).isEqualTo(RetrievalRanking.RELEVANT);
            assertThat(ChunkGradeEvaluator.gradeOf("Grade: 1")).isEqualTo(1);
            assertThat(ChunkGradeEvaluator.gradeOf("Grade: 4")).isNull();
            assertThat(ChunkGradeEvaluator.gradeOf("relevant")).isNull();
        }
    }

    @Nested
    @DisplayName("claim verification")
    class Claims {

        @Test
        @DisplayName("one verdict per numbered claim, in order, whatever the reply's formatting")
        void parsesNumberedVerdicts() {
            assertThat(ClaimFaithfulnessEvaluator.parseVerdicts("1 SUPPORTED\n2. unsupported\n3: Supported", 3))
                    .containsExactly(true, false, true);
        }

        @Test
        @DisplayName("a claim left without a verdict abandons the measurement")
        void missingVerdict() {
            assertThat(ClaimFaithfulnessEvaluator.parseVerdicts("1 SUPPORTED\n3 SUPPORTED", 3)).isNull();
        }

        @Test
        @DisplayName("list lines lose their bullets and numbers")
        void lines() {
            assertThat(JudgeText.lines("- First claim.\n2) Second claim.\n\n* Third."))
                    .containsExactly("First claim.", "Second claim.", "Third.");
        }
    }

    @Nested
    @DisplayName("task classification")
    class Tasks {

        @Test
        @DisplayName("maps the one-word reply to its type, and anything else to null")
        void fromReply() {
            assertThat(TaskType.fromReply("howto")).isEqualTo(TaskType.HOW_TO);
            assertThat(TaskType.fromReply("Config.")).isEqualTo(TaskType.CONFIG_LOOKUP);
            assertThat(TaskType.fromReply("recipe")).isNull();
        }

        @Test
        @DisplayName("tags round-trip")
        void tags() {
            assertThat(TaskType.CONFIG_LOOKUP.tag()).isEqualTo("config-lookup");
            assertThat(TaskType.fromTag("config-lookup")).isEqualTo(TaskType.CONFIG_LOOKUP);
        }
    }
}
