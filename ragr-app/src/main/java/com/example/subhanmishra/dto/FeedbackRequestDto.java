package com.example.subhanmishra.dto;

import com.example.subhanmishra.event.ChatFeedbackSubmitted.Rating;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A user's verdict on one answer. */
public record FeedbackRequestDto(
        @NotNull(message = "rating must be UP or DOWN")
        @Schema(description = "Thumbs up or down", example = "DOWN")
        Rating rating,

        @Size(max = 1000, message = "reason must be at most 1000 characters")
        @Schema(description = "Optional: what was wrong or right", example = "Cited the wrong page")
        @Nullable String reason) {
}
