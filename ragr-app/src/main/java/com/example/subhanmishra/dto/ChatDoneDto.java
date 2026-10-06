package com.example.subhanmishra.dto;

import java.util.List;
import java.util.UUID;

/**
 * The {@code done} event that closes a streamed answer: its citations, and what the turn cost.
 *
 * @param turnId quoted back to {@code POST /ai/turns/{turnId}/feedback} to rate this answer
 */
public record ChatDoneDto(UUID turnId, List<CitationDto> citations, UsageDto usage) {
}
