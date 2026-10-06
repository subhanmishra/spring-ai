package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the judges concluded about one turn, filled stage by stage.
 *
 * <p>Mutable because the stages run one at a time with chat able to interrupt between any two of them,
 * and each stage's result is worth keeping even if a later one never runs. Every field starts null and
 * stays null for a stage that was skipped, timed out or returned something unreadable - "not measured",
 * which no aggregate may treat as a failure.
 */
public final class TurnVerdicts {

    private @Nullable TaskType taskType;
    private final List<@Nullable Integer> grades = new ArrayList<>();
    private @Nullable RetrievalRanking ranking;
    private @Nullable Boolean relevancyPass;
    private @Nullable Boolean groundednessPass;
    private @Nullable Integer claimsTotal;
    private @Nullable Integer claimsSupported;
    private @Nullable Integer citationsChecked;
    private @Nullable Integer citationsSupported;
    private @Nullable Boolean completenessPass;
    private boolean anyStageUnmeasured;

    public @Nullable TaskType taskType() {
        return taskType;
    }

    public void taskType(@Nullable TaskType taskType) {
        this.taskType = taskType;
        noteMeasured(taskType);
    }

    /** One grade per pool chunk in rank order; an entry is null where that chunk's grade failed. */
    public List<@Nullable Integer> grades() {
        return Collections.unmodifiableList(grades);
    }

    public void grades(List<@Nullable Integer> grades, int inContext) {
        this.grades.clear();
        this.grades.addAll(grades);
        if (grades.stream().anyMatch(grade -> grade == null)) {
            // A hole in the pool's grades leaves no defensible ranking: recall and NDCG both depend on
            // every grade. The grades that did arrive are still stored per chunk.
            this.ranking = null;
            anyStageUnmeasured = true;
        } else {
            this.ranking = RetrievalRanking.of(grades.stream().map(Integer.class::cast).toList(), inContext);
        }
    }

    public @Nullable RetrievalRanking ranking() {
        return ranking;
    }

    public @Nullable Boolean relevancyPass() {
        return relevancyPass;
    }

    public void relevancyPass(@Nullable Boolean pass) {
        this.relevancyPass = pass;
        noteMeasured(pass);
    }

    public @Nullable Boolean groundednessPass() {
        return groundednessPass;
    }

    public void groundednessPass(@Nullable Boolean pass) {
        this.groundednessPass = pass;
        noteMeasured(pass);
    }

    public @Nullable Integer claimsTotal() {
        return claimsTotal;
    }

    public @Nullable Integer claimsSupported() {
        return claimsSupported;
    }

    /** @param verdicts one per claim; null when extraction or verification failed */
    public void claims(@Nullable List<Boolean> verdicts) {
        if (verdicts == null) {
            anyStageUnmeasured = true;
            return;
        }
        this.claimsTotal = verdicts.size();
        this.claimsSupported = (int) verdicts.stream().filter(Boolean::booleanValue).count();
    }

    /** Supported claims over all claims; 1.0 for an answer that makes none, which asserts nothing false. */
    public @Nullable Double faithfulness() {
        if (claimsTotal == null || claimsSupported == null) {
            return null;
        }
        return claimsTotal == 0 ? 1.0 : (double) claimsSupported / claimsTotal;
    }

    public @Nullable Integer citationsChecked() {
        return citationsChecked;
    }

    public @Nullable Integer citationsSupported() {
        return citationsSupported;
    }

    /** @param verdicts one per checked citation; an entry is null where that check failed */
    public void citations(List<@Nullable Boolean> verdicts) {
        if (verdicts.stream().anyMatch(verdict -> verdict == null)) {
            anyStageUnmeasured = true;
            return;
        }
        this.citationsChecked = verdicts.size();
        this.citationsSupported = (int) verdicts.stream().filter(Boolean.TRUE::equals).count();
    }

    public @Nullable Boolean completenessPass() {
        return completenessPass;
    }

    public void completenessPass(@Nullable Boolean pass) {
        this.completenessPass = pass;
        noteMeasured(pass);
    }

    /**
     * Whether a relevant chunk reached the prompt; null when the pool could not be graded, and null for a
     * turn that got no context from a pool holding nothing relevant - retrieval was not wrong to return
     * nothing for a question the corpus does not cover.
     */
    public @Nullable Boolean retrievalOk() {
        if (ranking == null || (ranking.precisionAtK() == null && ranking.relevantInPool() == 0)) {
            return null;
        }
        return ranking.retrievalOk();
    }

    /**
     * The end-to-end verdict: relevant, faithful to at least {@code faithfulnessThreshold}, complete, and
     * citing nothing fabricated. Null when any of those went unmeasured - an answer is not "wrong" because
     * one of its judges timed out.
     */
    public @Nullable Boolean answerOk(double faithfulnessThreshold, int citationsFabricated) {
        Double faithfulness = faithfulness();
        if (relevancyPass == null || faithfulness == null || completenessPass == null) {
            return null;
        }
        return relevancyPass && faithfulness >= faithfulnessThreshold && completenessPass
                && citationsFabricated == 0;
    }

    /** Whether any stage that ran failed to produce a measurement. */
    public boolean anyStageUnmeasured() {
        return anyStageUnmeasured;
    }

    /** For a stage that could not run at all - a timeout, or chat shutting the worker down. */
    public void markUnmeasured() {
        anyStageUnmeasured = true;
    }

    private void noteMeasured(@Nullable Object verdict) {
        if (verdict == null) {
            anyStageUnmeasured = true;
        }
    }
}
