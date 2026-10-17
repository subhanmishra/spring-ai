package com.example.subhanmishra.entity;

import com.example.subhanmishra.service.eval.GoldenRunResult;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One execution of the curated evaluation suite, with its rule-based aggregates and regression verdict.
 *
 * <p>A null {@code id} lets Postgres generate one. The judges' verdicts on the run's turns are aggregated
 * from {@code eval_turn} by {@code run_id}, since they arrive after the run completes.
 *
 * <p><b>The settings columns are the point of the table.</b> A score without the chat model, top-k and
 * threshold behind it cannot be compared with any other.
 */
@Table(name = "eval_run")
public record EvalRun(@Id @Nullable UUID id,
                      String suite,
                      EvalRunStatus status,
                      Instant startedAt,
                      @Nullable Instant finishedAt,
                      int caseCount,
                      int passedCount,
                      @Nullable String chatModel,
                      @Nullable Integer topK,
                      @Nullable Double similarityThreshold,
                      @Nullable Double hitRate,
                      @Nullable Double meanReciprocalRank,
                      @Nullable Double contextPrecision,
                      @Nullable Double precisionAtK,
                      @Nullable Double citedContextPrecision,
                      @Nullable Double citedPrecisionAtK,
                      @Nullable Double recallAtK,
                      @Nullable Double ndcgAtK,
                      @Nullable Double citationValidity,
                      @Nullable Double citationFabrication,
                      int citationsEmitted,
                      int inventedPages,
                      @Nullable Double phraseCoverage,
                      @Nullable Long durationMillis,
                      @Nullable String errorMessage,
                      @Nullable Boolean gatePassed,
                      @Nullable String gateFailures) {

    /** A run about to start. Chat's settings are filled in from its turns when it completes. */
    public static EvalRun starting(String suite, int caseCount) {
        return new EvalRun(null, suite, EvalRunStatus.RUNNING, Instant.now(), null, caseCount, 0,
                           null, null, null, null, null, null, null, null, null, null, null, null, null,
                           0, 0, null, null, null, null, null);
    }

    /**
     * @param chatModel           the chat model and retrieval settings the run's turns reported
     * @param gateFailures        each regression threshold the run missed; empty when it passed
     */
    public EvalRun completed(GoldenRunResult result,
                             @Nullable String chatModel,
                             @Nullable Integer topK,
                             @Nullable Double similarityThreshold,
                             List<String> gateFailures) {
        Instant finished = Instant.now();
        return new EvalRun(id, suite, EvalRunStatus.COMPLETED, startedAt, finished, caseCount,
                           result.passedCount(), chatModel, topK, similarityThreshold,
                           result.hitRate(), result.meanReciprocalRank(),
                           result.contextPrecision(), result.precisionAtK(),
                           result.citedContextPrecision(), result.citedPrecisionAtK(),
                           result.recallAtK(), result.ndcgAtK(),
                           result.citationValidity(), result.citationFabrication(),
                           result.citationsEmitted(), result.inventedPageCount(), result.phraseCoverage(),
                           finished.toEpochMilli() - startedAt.toEpochMilli(), null,
                           gateFailures.isEmpty(), gateFailures.isEmpty() ? null : String.join("; ", gateFailures));
    }

    public EvalRun failed(String message) {
        Instant finished = Instant.now();
        return new EvalRun(id, suite, EvalRunStatus.FAILED, startedAt, finished, caseCount, passedCount,
                           chatModel, topK, similarityThreshold, hitRate, meanReciprocalRank,
                           contextPrecision, precisionAtK, citedContextPrecision, citedPrecisionAtK,
                           recallAtK, ndcgAtK, citationValidity, citationFabrication, citationsEmitted,
                           inventedPages, phraseCoverage, finished.toEpochMilli() - startedAt.toEpochMilli(),
                           message, gatePassed, gateFailures);
    }
}
