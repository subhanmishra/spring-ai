package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The audit trail for one document, oldest entry first.
 *
 * <p>{@code documentExists} is reported because history deliberately outlives the document it
 * describes: there is no foreign key from {@code document_metadata_history} to
 * {@code document_metadata}, and {@code deleteDocument} removes only the latter. Without the flag, a
 * trail ending in {@code INDEXED} for a document that has since been deleted is indistinguishable
 * from one for a document still present and healthy.
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
