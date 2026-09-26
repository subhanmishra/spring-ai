package com.example.subhanmishra.dto;

import java.util.List;

/** The {@code done} event that closes a streamed answer: its citations, and what the turn cost. */
public record ChatDoneDto(List<CitationDto> citations, UsageDto usage) {
}
