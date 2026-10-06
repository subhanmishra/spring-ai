package com.example.subhanmishra.event;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's verdict on one answer, as the chat path hands it to evaluation.
 *
 * <p>Separate from {@link ChatTurnCompleted} because it arrives later - seconds or minutes after the
 * answer, if at all - and from a different request. The consumer stores it by {@code turnId} without
 * needing the turn to have been evaluated yet, so the two can arrive in either order.
 *
 * <p>ragr-app keeps no record of its turns, so it cannot check that {@code turnId} names one; an
 * unknown id is simply feedback that joins nothing.
 *
 * @param turnId      the {@code turnId} the answer was returned with
 * @param rating      thumbs up or down
 * @param reason      optional free text
 * @param submittedAt when ragr-app received it
 */
public record ChatFeedbackSubmitted(UUID turnId, Rating rating, @Nullable String reason, Instant submittedAt) {

    public enum Rating {
        UP,
        DOWN
    }
}
