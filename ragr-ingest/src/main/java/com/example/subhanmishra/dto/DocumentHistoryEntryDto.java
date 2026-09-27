package com.example.subhanmishra.dto;

import com.example.subhanmishra.entity.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One status transition recorded while a document was being processed.
 */
public record DocumentHistoryEntryDto(

        UUID id,

        @Schema(description = "The status the document moved to", example = "INDEXED")
        DocumentStatus status,

        @Schema(description = "What happened. For a FAILED entry this is the error message.",
                example = "Document successfully indexed.")
        String details,

        @Schema(description = "When the transition was recorded")
        Instant createdAt) {
}
