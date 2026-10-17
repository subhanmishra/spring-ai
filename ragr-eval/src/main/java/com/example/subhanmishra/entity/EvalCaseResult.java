package com.example.subhanmishra.entity;

import com.example.subhanmishra.service.EvalScoringService.ReferenceScores;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What one golden case produced on one run, scored against the case's expectations.
 *
 * <p>The answer is stored on purpose: a score says a case regressed, only the answer says how - and
 * re-running gives a different answer.
 *
 * <p>The judges' verdicts are not here: they are on the case's turn ({@code turnId}) in {@code eval_turn},
 * filled in by the judge worker like any live turn's.
 */
@Table(name = "eval_case_result")
public record EvalCaseResult(@Id @Nullable UUID id,
                             UUID runId,
                             String caseId,
                             String query,
                             @Nullable String answer,
                             @Nullable String conversationId,
                             Status status,
                             int retrievedCount,
                             @Nullable Double topScore,
                             @Nullable Double scoreSpread,
                             @Nullable String pagesRetrieved,
                             int firstRelevantRank,
                             @Nullable Double contextPrecision,
                             @Nullable Double precisionAtK,
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
                             int inventedPages,
                             @Nullable Double phraseCoverage,
                             boolean refused,
                             boolean passed,
                             @Nullable String failureReasons,
                             @Nullable Long latencyMillis,
                             Instant createdAt) {

    /** Where a case is between the run asking its question and the listener scoring its turn. */
    public enum Status {
        /** Asked, or about to be; the listener has not seen its turn yet. */
        AWAITING,
        /** Its turn arrived and was scored. */
        SCORED,
        /** The request failed, or its turn never arrived. */
        FAILED
    }

    /** The row a run writes before asking the case's question, so the listener can find it. */
    public static EvalCaseResult awaiting(UUID runId, GoldenCase goldenCase, String conversationId) {
        return new EvalCaseResult(null, runId, goldenCase.id(), goldenCase.query(), null, conversationId,
                                  Status.AWAITING, 0, null, null, null, 0, null, null, null, null, null, null,
                                  null, null, null, 0, 0, 0, 0, null, false, false, null, null, Instant.now());
    }

    /**
     * This case, scored from its turn.
     *
     * @param contextPrecision      scored against the case's expected files or pages, or null when it
     *                              declares none
     * @param citedContextPrecision a chunk counted as used when the answer cites it; null when nothing
     *                              was retrieved
     * @param latencyMillis         chat's own time for the turn, as live turns measure it
     */
    public EvalCaseResult scored(String answer,
                                 EvalScores scores,
                                 int firstRelevantRank,
                                 @Nullable ContextPrecisionScores contextPrecision,
                                 @Nullable ContextPrecisionScores citedContextPrecision,
                                 @Nullable ReferenceScores reference,
                                 UUID turnId,
                                 @Nullable Long latencyMillis) {
        List<String> reasons = scores.failureReasons();
        int invented = (int) scores.citations().fabricatedCitations().stream()
                                   .filter(citation -> !citation.hasMalformedPage())
                                   .count();
        return new EvalCaseResult(id, runId, caseId, query, answer, conversationId, Status.SCORED,
                                  scores.retrieval().retrievedCount(),
                                  scores.retrieval().topScore(),
                                  scores.retrieval().scoreSpread(),
                                  formatPages(scores.retrieval().pagesRetrieved()),
                                  firstRelevantRank,
                                  contextPrecision != null ? contextPrecision.averagePrecision() : null,
                                  contextPrecision != null ? contextPrecision.precisionAtK() : null,
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
                                  invented,
                                  scores.answer().phraseCoverage(),
                                  scores.answer().refused(),
                                  reasons.isEmpty(),
                                  reasons.isEmpty() ? null : String.join("; ", reasons),
                                  latencyMillis,
                                  createdAt);
    }

    private static @Nullable String formatPages(List<Integer> pages) {
        return pages.isEmpty() ? null : pages.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
