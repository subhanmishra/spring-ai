package com.example.subhanmishra.entity;

/**
 * Lifecycle of a golden evaluation run.
 *
 * <p>{@link #RUNNING} is saved before any case runs, not at the end: a run takes minutes, and one that
 * died part-way would otherwise leave no trace.
 */
public enum EvalRunStatus {

    /** Cases are executing. A row left in this state is a run that was killed, not one still going. */
    RUNNING,

    /** Every case executed. Says nothing about whether they passed - that is {@code passedCount}. */
    COMPLETED,

    /** The run itself broke, e.g. the corpus was missing or the model was unreachable. */
    FAILED
}
