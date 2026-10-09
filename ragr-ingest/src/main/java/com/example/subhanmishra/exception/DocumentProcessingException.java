package com.example.subhanmishra.exception;

import java.util.UUID;

public class DocumentProcessingException extends RuntimeException {

    /**
     * The document whose processing failed, when a record for it exists.
     *
     * <p>Set only by {@code DocumentMetadataService.uploadAndProcess}, after it has written the FAILED
     * row; a bulk upload reports it so the caller can follow it to {@code /{id}/history}. Null when
     * thrown from parsing or ingestion, which do not know the id.
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
