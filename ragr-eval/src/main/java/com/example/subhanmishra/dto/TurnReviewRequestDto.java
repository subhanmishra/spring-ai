package com.example.subhanmishra.dto;

import com.example.subhanmishra.service.TurnReviewService.Verdict;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A reviewer's verdict on one evaluated turn. */
public record TurnReviewRequestDto(@NotNull(message = "verdict must be CORRECT, PARTIAL or WRONG") Verdict verdict,
                                   @Size(max = 2000, message = "notes must be at most 2000 characters")
                                   @Nullable String notes) {
}
