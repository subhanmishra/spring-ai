package com.example.subhanmishra.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The upload failures only this application can produce, on top of the handling every HTTP-facing
 * application shares in {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class IngestExceptionHandler extends ApiExceptionHandler {

    /**
     * An upload whose type the parser does not handle.
     *
     * <p>415, while its parent {@link DocumentProcessingException} keeps 422 - Spring picks the closest
     * match. The difference matters: 422 means the content could not be processed, 415 that the type was
     * never accepted and nothing was written.
     */
    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ProblemDetail handleUnsupportedType(UnsupportedDocumentTypeException ex) {
        logger.warn("Unsupported document type: {}", ex.getMessage());
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported File Type", ex.getMessage());
    }


    @ExceptionHandler(DocumentProcessingException.class)
    public ProblemDetail handleProcessingError(DocumentProcessingException ex) {
        logger.error("Document processing error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Document Processing Error", ex.getMessage());
    }


    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxSize(MaxUploadSizeExceededException ex) {
        logger.warn("File size limit exceeded: {}", ex.getMessage());
        return problem(HttpStatus.CONTENT_TOO_LARGE, "File Too Large",
                "The uploaded file exceeds the maximum allowed size of 25MB.");
    }
}
