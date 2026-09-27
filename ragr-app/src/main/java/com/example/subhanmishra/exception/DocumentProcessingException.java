package com.example.subhanmishra.exception;

import java.util.UUID;

public class DocumentProcessingException extends RuntimeException {

    /**
     * The document whose processing failed, when a record for it exists.
     *
     * <p>Null for failures thrown before the metadata row is saved, or from below the layer that owns
     * it - parsing and ingestion both throw this type without knowing the id. Only
     * {@code DocumentMetadataService.uploadAndProcess} throws it after having written the FAILED row,
     * and it is that id the bulk-upload path reports back so a caller can follow the failure to
     * {@code GET /api/v1/documents/{id}/history}.
     */
    private final UUID documentId;

    public DocumentProcessingException(String message) {
        this(message, null, null);
    }

    public DocumentProcessingException() {
        this("Error in processing documents !!", null, null);
    }

    public DocumentProcessingException(String message, Throwable ex) {
        this(message, ex, null);
    }

    public DocumentProcessingException(String message, Throwable ex, UUID documentId) {
        super(message, ex);
        this.documentId = documentId;
    }

    /** The failed document's id, or null when the failure happened before one was recorded. */
    public UUID getDocumentId() {
        return documentId;
    }
}
