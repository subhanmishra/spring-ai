package com.example.subhanmishra.exception;

/**
 * An upload whose file type the parser does not handle, refused before anything is persisted.
 *
 * <p>Extends {@link DocumentProcessingException} deliberately, and the inheritance does work in two
 * directions. {@code DocAiExceptionHandler} maps this subclass to 415 while the parent keeps its 422,
 * and Spring's resolver picks the closest match in the hierarchy so the two do not conflict. Meanwhile
 * {@code DocumentMetadataService.uploadMultipleDocuments} catches the parent, so one unsupported file
 * in a batch becomes a FAILED entry and the batch carries on rather than aborting.
 *
 * <p>Unlike every other failure, a document rejected this way has <em>no</em> id and no history: the
 * check runs before the metadata row is written, which is the point of it.
 */
public class UnsupportedDocumentTypeException extends DocumentProcessingException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
