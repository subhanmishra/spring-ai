package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The audit trail for one document, oldest entry first.
 *
 * <p>History outlives a deleted document, so {@code documentExists} says whether it is still there.
 * Without it, a trail ending in {@code INDEXED} would look the same for a deleted document and a healthy
 * one.
 */
public record DocumentHistoryDto(

        UUID documentId,

        @Schema(description = "False when the document has since been deleted. Its history is kept "
                + "regardless - the table is an immutable audit log, not a detail of the document.")
        boolean documentExists,

        int entryCount,

        @Schema(description = "Status transitions, oldest first")
        List<DocumentHistoryEntryDto> history) {
}
