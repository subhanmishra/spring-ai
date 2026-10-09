package com.example.subhanmishra.event;

/**
 * Who asked the question a chat turn answers.
 *
 * <p>Golden-suite turns take exactly the same chat path as real ones - that is the point of the suite -
 * but a run is a burst of hand-picked questions that would skew the live rates. This lets live metrics
 * leave them out.
 */
public enum TurnOrigin {

    /** A real user, or anything else that did not say otherwise. */
    LIVE,

    /** The golden evaluation suite, which marks its requests with the {@code X-Eval-Origin} header. */
    GOLDEN;

    /** The request header a caller sets to mark its turn; absent means {@link #LIVE}. */
    public static final String HEADER = "X-Eval-Origin";
}
