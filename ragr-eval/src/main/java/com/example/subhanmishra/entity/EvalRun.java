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
 * model, top-k and threshold that produced it cannot be compared to any other score.
 *
 * <p>What is deliberately not recorded is the corpus. A pipeline change is handled by dropping every
 * chunk and re-ingesting, and nothing marks that boundary here: two runs either side of a re-ingest
 * look comparable and are scoring different chunks. Read the trend with the date of the last re-ingest
 * in mind.
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

    /**
     * A run about to start. The pipeline's own settings are not known yet - they belong to ragr-app,
     * which the run drives over HTTP - and are filled in by {@link #withPipeline} from the first turn.
     */
    public static EvalRun starting(String suite,
                                   int caseCount,
                                   @Nullable String judgeModel,
                                   boolean judged) {
        return new EvalRun(null, suite, EvalRunStatus.RUNNING, Instant.now(), null, caseCount, 0,
                           null, judgeModel, judged, null, null,
                           null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The chat model and retrieval settings the run's answers were produced with, as the first turn
     * reported them.
     */
    public EvalRun withPipeline(@Nullable String chatModel, int topK, double similarityThreshold) {
        return new EvalRun(id, suite, status, startedAt, finishedAt, caseCount, passedCount,
                           chatModel, judgeModel, judged, topK, similarityThreshold,
                           hitRate, meanReciprocalRank, contextPrecision, precisionAtK,
                           judgedContextPrecision, judgedPrecisionAtK, citationValidity,
                           citationFabrication, relevancyRate, groundednessRate, durationMillis, errorMessage);
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
                           hitRate, meanReciprocalRank, contextPrecision, precisionAtK,
                           judgedContextPrecision, judgedPrecisionAtK, citationValidity,
                           citationFabrication, relevancyRate, groundednessRate,
                           finished.toEpochMilli() - startedAt.toEpochMilli(), null);
    }

    public EvalRun failed(String message) {
        Instant finished = Instant.now();
        return new EvalRun(id, suite, EvalRunStatus.FAILED, startedAt, finished, caseCount, passedCount,
                           chatModel, judgeModel, judged, topK, similarityThreshold,
                           hitRate, meanReciprocalRank, contextPrecision, precisionAtK,
                           judgedContextPrecision, judgedPrecisionAtK,
                           citationValidity, citationFabrication,
                           relevancyRate, groundednessRate,
                           finished.toEpochMilli() - startedAt.toEpochMilli(), message);
    }
}
