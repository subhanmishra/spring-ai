package com.example.subhanmishra.event;

/**
 * Who asked the question a chat turn answers.
 *
 * <p>Carried on every {@link ChatTurnCompleted} so the online evaluation can leave golden-suite turns
 * out of the live metrics. Those turns go through exactly the same chat path as real traffic - that is
 * the point of the suite - but a run replays a fixed set of hand-picked questions in a burst, and
 * counting them would move live citation and retrieval rates by however many cases the dataset holds.
 */
public enum TurnOrigin {

    /** A real user, or anything else that did not say otherwise. */
    LIVE,

    /** The golden evaluation suite, which marks its requests with the {@code X-Eval-Origin} header. */
    GOLDEN;

    /** The request header a caller sets to mark its turn; absent means {@link #LIVE}. */
    public static final String HEADER = "X-Eval-Origin";
}
