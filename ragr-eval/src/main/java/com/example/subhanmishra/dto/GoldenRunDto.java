package com.example.subhanmishra.dto;

import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.entity.EvalRunStatus;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeProgress;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A golden run as the API reports it: its rule-based results and verdict, and how far the judges have got
 * through its turns.
 *
 * @param scored     cases whose turn has been scored; the run completes when none is still awaiting
 * @param gatePassed the regression verdict; null until the run completes
 * @param judging    the judge queue's progress through the run's turns, which continues after completion
 * @param failures   each failed case, with why
 */
public record GoldenRunDto(UUID runId,
                           String suite,
                           EvalRunStatus status,
                           Instant startedAt,
                           @Nullable Instant finishedAt,
                           int caseCount,
                           int scored,
                           int passedCount,
                           @Nullable Boolean gatePassed,
                           @Nullable String gateFailures,
                           @Nullable String chatModel,
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
                           Judging judging,
                           List<String> failures) {

    /**
     * @param judged  turns the judges finished, partly judged ones included
     * @param skipped turns that waited past {@code max-backlog-age} and will not be judged
     */
    public record Judging(int turns, int judged, int skipped) {
    }

    public static GoldenRunDto of(EvalRun run, List<EvalCaseResult> cases, JudgeProgress progress) {
        int scored = (int) cases.stream().filter(c -> c.status() == EvalCaseResult.Status.SCORED).count();
        // The run row is written when it completes; until then, count the cases scored so far.
        int passed = run.status() == EvalRunStatus.RUNNING
                ? (int) cases.stream().filter(EvalCaseResult::passed).count()
                : run.passedCount();
        List<String> failures = cases.stream()
                                     .filter(c -> c.status() != EvalCaseResult.Status.AWAITING && !c.passed())
                                     .map(c -> c.caseId() + ": " + c.failureReasons())
                                     .toList();
        return new GoldenRunDto(run.id(), run.suite(), run.status(), run.startedAt(), run.finishedAt(),
                                run.caseCount(), scored, passed, run.gatePassed(), run.gateFailures(),
                                run.chatModel(), run.hitRate(), run.meanReciprocalRank(), run.contextPrecision(),
                                run.precisionAtK(), run.citedContextPrecision(), run.citedPrecisionAtK(),
                                run.recallAtK(), run.ndcgAtK(), run.citationValidity(), run.citationFabrication(),
                                run.citationsEmitted(), run.inventedPages(), run.phraseCoverage(),
                                run.durationMillis(), run.errorMessage(),
                                new Judging(progress.turns(), progress.judged(), progress.skipped()), failures);
    }
}
