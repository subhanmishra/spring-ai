package com.example.subhanmishra.entity;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One execution of the curated evaluation suite.
 *
 * <p>A record rather than the builder-and-setters shape the document entities use: these rows are
 * written once and read back for reporting, never mutated in place, so immutability costs nothing and
 * the canonical constructor is what Spring Data JDBC binds to. A null {@code id} marks a new row and
 * lets Postgres supply {@code gen_random_uuid()}.
 *
 * <p>The configuration columns are the point of the table. A score without the chat model, judge
 * model, top-k, threshold and pipeline version that produced it cannot be compared to any other score,
 * and comparing across a {@code PipelineProvenance.CURRENT_VERSION} bump silently compares two
 * different corpora.
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
                      @Nullable String judgeModel,
                      boolean judged,
                      @Nullable Integer topK,
                      @Nullable Double similarityThreshold,
                      @Nullable Integer pipelineVersion,
                      @Nullable Double hitRate,
                      @Nullable Double meanReciprocalRank,
                      @Nullable Double contextPrecision,
                      @Nullable Double precisionAtK,
                      @Nullable Double judgedContextPrecision,
                      @Nullable Double judgedPrecisionAtK,
                      @Nullable Double citationValidity,
                      @Nullable Double citationFabrication,
                      @Nullable Double relevancyRate,
                      @Nullable Double groundednessRate,
                      @Nullable Long durationMillis,
                      @Nullable String errorMessage) {

    /** A run about to start, with its configuration recorded and its results not yet known. */
    public static EvalRun starting(String suite,
                                   int caseCount,
                                   String chatModel,
                                   @Nullable String judgeModel,
                                   boolean judged,
                                   int topK,
                                   double similarityThreshold,
                                   @Nullable Integer pipelineVersion) {
        return new EvalRun(null, suite, EvalRunStatus.RUNNING, Instant.now(), null, caseCount, 0,
                           chatModel, judgeModel, judged, topK, similarityThreshold, pipelineVersion,
                           null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * @param contextPrecision       reference-based, from the dataset's expected pages. Null when no
     *                               case in the run scored recall.
     * @param judgedContextPrecision reference-free, from the per-chunk LLM judge. Null on an unjudged
     *                               run, which is the default - and null rather than zero, for the same
     *                               reason {@code relevancyRate} is.
     */
    public EvalRun completed(int passedCount,
                             double hitRate,
                             double meanReciprocalRank,
                             @Nullable Double contextPrecision,
                             @Nullable Double precisionAtK,
                             @Nullable Double judgedContextPrecision,
                             @Nullable Double judgedPrecisionAtK,
                             double citationValidity,
                             double citationFabrication,
                             @Nullable Double relevancyRate,
                             @Nullable Double groundednessRate) {
        Instant finished = Instant.now();
        return new EvalRun(id, suite, EvalRunStatus.COMPLETED, startedAt, finished, caseCount,
                           passedCount, chatModel, judgeModel, judged, topK, similarityThreshold,
                           pipelineVersion, hitRate, meanReciprocalRank, contextPrecision, precisionAtK,
                           judgedContextPrecision, judgedPrecisionAtK, citationValidity,
                           citationFabrication, relevancyRate, groundednessRate,
                           finished.toEpochMilli() - startedAt.toEpochMilli(), null);
    }

    public EvalRun failed(String message) {
        Instant finished = Instant.now();
        return new EvalRun(id, suite, EvalRunStatus.FAILED, startedAt, finished, caseCount, passedCount,
                           chatModel, judgeModel, judged, topK, similarityThreshold, pipelineVersion,
                           hitRate, meanReciprocalRank, contextPrecision, precisionAtK,
                           judgedContextPrecision, judgedPrecisionAtK,
                           citationValidity, citationFabrication,
                           relevancyRate, groundednessRate,
                           finished.toEpochMilli() - startedAt.toEpochMilli(), message);
    }
}
