package com.example.subhanmishra.entity;

/**
 * Lifecycle of a golden evaluation run.
 *
 * <p>{@link #RUNNING} is persisted before any case executes, rather than the row being written once at
 * the end. A suite takes minutes - a single grounded answer on this host is 53-70 seconds - so a run
 * that dies partway through would otherwise leave nothing at all behind, and an empty table is
 * indistinguishable from a suite nobody ran.
 */
public enum EvalRunStatus {

    /** Cases are executing. A row left in this state is a run that was killed, not one still going. */
    RUNNING,

    /** Every case executed. Says nothing about whether they passed - that is {@code passedCount}. */
    COMPLETED,

    /** The run itself broke, e.g. the corpus was missing or the model was unreachable. */
    FAILED
}
