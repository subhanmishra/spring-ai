package com.example.subhanmishra.event;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's verdict on one answer, as the chat path hands it to evaluation.
 *
 * <p>A separate event from {@link ChatTurnCompleted} because it comes later, from another request, if at
 * all. It is stored by {@code turnId}, so the two can arrive in either order. ragr-app keeps no turns,
 * so it cannot check the id; an unknown one simply matches nothing.
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
