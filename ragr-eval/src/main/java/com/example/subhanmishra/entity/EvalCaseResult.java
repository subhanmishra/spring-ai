package com.example.subhanmishra.entity;

import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What one golden case produced on one run.
 *
 * <p>The answer text is stored deliberately. A score tells you a case regressed; only the answer tells
 * you how, and re-running the case to find out gives a different answer, because generation is not
 * deterministic even at a low temperature.
 *
 * <p>{@code relevancyPass} and {@code groundednessPass} are boxed because null means "not judged",
 * which is not the same as FALSE meaning "judged and failed". Any aggregate has to exclude nulls
 * rather than coalesce them, or a run with judging switched off reads as a run that failed everything.
 */
@Table(name = "eval_case_result")
public record EvalCaseResult(@Id @Nullable UUID id,
                             UUID runId,
                             String caseId,
                             String query,
                             @Nullable String answer,
                             int retrievedCount,
                             @Nullable Double topScore,
                             @Nullable Double scoreSpread,
                             @Nullable String pagesRetrieved,
                             int firstRelevantRank,
                             @Nullable Double contextPrecision,
                             @Nullable Double precisionAtK,
                             @Nullable Double judgedContextPrecision,
                             @Nullable Double judgedPrecisionAtK,
                             @Nullable String judgedRelevance,
                             int citationsEmitted,
                             int citationsValid,
                             int citationsFabricated,
                             @Nullable Double phraseCoverage,
                             boolean refused,
                             @Nullable Boolean relevancyPass,
                             @Nullable Boolean groundednessPass,
                             boolean passed,
                             @Nullable String failureReasons,
                             @Nullable Long latencyMillis,
                             Instant createdAt) {

    /**
     * @param contextPrecision       scored against the case's expected pages, or null when it declares
     *                               none
     * @param judgedContextPrecision scored by the per-chunk LLM judge, or null on an unjudged run.
     *                               Its verdict vector is stored alongside it: comparing that vector
     *                               against {@code pagesRetrieved} is what shows whether the dataset's
     *                               expected-page list is too narrow, and it is the one thing here that
     *                               cannot be reconstructed later from the dataset and the other
     *                               columns.
     */
    public static EvalCaseResult from(UUID runId,
                                      String caseId,
                                      String query,
                                      String answer,
                                      EvalScores scores,
                                      int firstRelevantRank,
                                      @Nullable ContextPrecisionScores contextPrecision,
                                      @Nullable ContextPrecisionScores judgedContextPrecision,
                                      long latencyMillis) {
        List<String> reasons = scores.failureReasons();
        return new EvalCaseResult(null,
                                  runId,
                                  caseId,
                                  query,
                                  answer,
                                  scores.retrieval().retrievedCount(),
                                  scores.retrieval().topScore(),
                                  scores.retrieval().scoreSpread(),
                                  formatPages(scores.retrieval().pagesRetrieved()),
                                  firstRelevantRank,
                                  contextPrecision != null ? contextPrecision.averagePrecision() : null,
                                  contextPrecision != null ? contextPrecision.precisionAtK() : null,
                                  judgedContextPrecision != null
                                          ? judgedContextPrecision.averagePrecision() : null,
                                  judgedContextPrecision != null
                                          ? judgedContextPrecision.precisionAtK() : null,
                                  judgedContextPrecision != null
                                          ? judgedContextPrecision.relevanceAsString() : null,
                                  scores.citations().emitted(),
                                  scores.citations().valid(),
                                  scores.citations().fabricated(),
                                  scores.answer().phraseCoverage(),
                                  scores.answer().refused(),
                                  scores.relevancy(),
                                  scores.groundedness(),
                                  reasons.isEmpty(),
                                  reasons.isEmpty() ? null : String.join("; ", reasons),
                                  latencyMillis,
                                  Instant.now());
    }

    private static @Nullable String formatPages(List<Integer> pages) {
        return pages.isEmpty() ? null : pages.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
