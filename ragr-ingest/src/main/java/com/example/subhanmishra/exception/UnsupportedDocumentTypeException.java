package com.example.subhanmishra.exception;

/**
 * An upload whose file type the parser does not handle, refused before anything is persisted.
 *
 * <p>Extends {@link DocumentProcessingException} on purpose. {@code IngestExceptionHandler} maps this
 * subclass to 415 and the parent to 422 (Spring picks the closest match), and a bulk upload catches the
 * parent, so one unsupported file becomes a FAILED entry instead of stopping the batch.
 *
 * <p>Unlike every other failure it has <em>no</em> id and no history: the check runs before anything is
 * written, which is the point.
 */
public class UnsupportedDocumentTypeException extends DocumentProcessingException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
