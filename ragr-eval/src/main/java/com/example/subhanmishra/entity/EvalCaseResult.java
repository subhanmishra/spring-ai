package com.example.subhanmishra.entity;

import com.example.subhanmishra.service.EvalScoringService.ReferenceScores;
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
 * <p>The answer is stored on purpose: a score says a case regressed, only the answer says how - and
 * re-running gives a different answer.
 *
 * <p>{@code relevancyPass} and {@code groundednessPass} are boxed: null is "not judged", not "failed".
 * Aggregates must skip nulls, or an unjudged run reads as failing everything.
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
                             @Nullable Double citedContextPrecision,
                             @Nullable Double citedPrecisionAtK,
                             @Nullable String citedRelevance,
                             @Nullable Double recallAtK,
                             @Nullable Double ndcgAtK,
                             @Nullable String referenceRelevance,
                             @Nullable UUID turnId,
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
     * @param judgedContextPrecision from the per-chunk judge, or null on an unjudged run. Its verdict
     *                               vector is stored too - set against {@code pagesRetrieved} it shows
     *                               whether the expected pages are too narrow, and it cannot be rebuilt
     *                               later
     * @param citedContextPrecision  a chunk counted as used when the answer cites its page; null when
     *                               nothing was retrieved. Where it and the judge disagree, the citations
     *                               are the model's own account, and the judge is likelier wrong
     */
    public static EvalCaseResult from(UUID runId,
                                      String caseId,
                                      String query,
                                      String answer,
                                      EvalScores scores,
                                      int firstRelevantRank,
                                      @Nullable ContextPrecisionScores contextPrecision,
                                      @Nullable ContextPrecisionScores judgedContextPrecision,
                                      @Nullable ContextPrecisionScores citedContextPrecision,
                                      @Nullable ReferenceScores reference,
                                      @Nullable UUID turnId,
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
                                  citedContextPrecision != null ? citedContextPrecision.averagePrecision() : null,
                                  citedContextPrecision != null ? citedContextPrecision.precisionAtK() : null,
                                  citedContextPrecision != null ? citedContextPrecision.relevanceAsString() : null,
                                  reference != null ? reference.pageRecall() : null,
                                  reference != null ? reference.ranking().ndcgAtK() : null,
                                  reference != null ? reference.relevance() : null,
                                  turnId,
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
