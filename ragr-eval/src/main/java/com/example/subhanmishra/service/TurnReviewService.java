package com.example.subhanmishra.service;

import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.repository.EvalTurnRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * A person's verdict on a stored turn - the human half of evaluation, and the yardstick for the judges:
 * the dashboard compares each reviewed turn's verdict with what the judges concluded about it.
 */
@Service
public class TurnReviewService {

    /** A reviewer's verdict on the answer as a whole. */
    public enum Verdict {
        CORRECT,
        PARTIAL,
        WRONG
    }

    private final EvalTurnRepository turns;

    public TurnReviewService(EvalTurnRepository turns) {
        this.turns = turns;
    }

    /** Records the verdict, replacing any earlier one; 404 when no such turn is stored. */
    public void review(UUID turnId, Verdict verdict, @Nullable String notes) {
        if (!turns.saveReview(turnId, verdict.name(), notes)) {
            throw new ResourceNotFoundException("No evaluated turn with ID " + turnId
                                                + " - it may never have been stored, or was purged by retention.");
        }
    }
}
