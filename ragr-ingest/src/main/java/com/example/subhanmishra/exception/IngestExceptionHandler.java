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
     * <p>Registered on the subclass while {@link DocumentProcessingException} keeps its 422: Spring
     * resolves to the closest match in the hierarchy, so the two coexist without ambiguity. The
     * distinction is worth keeping - 422 means the content could not be processed, whereas this means
     * the type was never accepted, and nothing was written before saying so.
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
