package com.example.subhanmishra.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class DocAiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(DocAiExceptionHandler.class);


    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        logger.warn("Resource not found : {}", ex.getMessage());
        //return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.builder().success(false).message(ex.getMessage()).data(null).timestamp(LocalDateTime.now()).build());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problemDetail.setTitle("Resource Not Found");
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;


    }


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
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problemDetail.setTitle("Unsupported File Type");
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    @ExceptionHandler(DocumentProcessingException.class)
    public ProblemDetail handleProcessingError(DocumentProcessingException ex) {
        logger.error("Document processing error: {}", ex.getMessage(), ex);
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problemDetail.setTitle("Document Processing Error");
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxSize(MaxUploadSizeExceededException ex) {
        logger.warn("File size limit exceeded: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.CONTENT_TOO_LARGE);
        problemDetail.setTitle("File Too Large");
        problemDetail.setDetail("The uploaded file exceeds the maximum allowed size of 25MB.");
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {

        // key value pair--> validation error
        Map<String, String> errors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        logger.warn("Validation failed for request: {}", errors);

        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Validation Failed");
        problemDetail.setDetail("One or more fields in the request are invalid.");
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now(), "errors", errors));

        return problemDetail;

    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Illegal argument");
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        logger.debug("No static resource found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problemDetail.setTitle("Not Found");
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    /**
     * A path variable or query parameter that could not be converted to the type the handler declares -
     * most often a malformed UUID in a path such as {@code /api/v1/documents/not-a-uuid}.
     *
     * <p>Another one outside the {@link ErrorResponse} family:
     * {@code MethodArgumentTypeMismatchException} descends from {@code BeansException}, so without this
     * it falls through to the catch-all and a plainly malformed id reads as a server failure.
     *
     * <p>Handled at this narrow type rather than at {@code TypeMismatchException} deliberately.
     * {@code ConversionNotSupportedException} is also a {@code TypeMismatchException} but means the
     * server has no converter configured, which genuinely is a 500 - catching the parent would report
     * that server-side misconfiguration as the caller's fault.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Invalid Parameter");
        problemDetail.setDetail("The value supplied for '%s' is not valid for its expected type."
                .formatted(ex.getName()));
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    /**
     * A request body that could not be parsed - malformed JSON, or a value of the wrong shape for the
     * field it lands in.
     *
     * <p>This needs its own handler rather than riding on the {@link ErrorResponse} delegation below,
     * because {@code HttpMessageNotReadableException} extends {@code NestedRuntimeException} and is one
     * of the few Spring MVC exceptions with no {@code ErrorResponse} anywhere in its hierarchy - so it
     * would otherwise fall through and be reported as a 500 for what is plainly a bad request.
     *
     * <p>The parse error itself is not returned: it quotes the offending byte offset and the Jackson
     * internals around it, which tells a caller more about the server than about their own mistake.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        logger.warn("Unreadable request body: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Malformed Request Body");
        problemDetail.setDetail("The request body could not be read. It must be valid JSON matching the "
                + "documented schema.");
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }


    /**
     * Last resort for anything not handled above.
     *
     * <p>Spring's own MVC exceptions are passed through rather than flattened. Most of them implement
     * {@link ErrorResponse} and already carry the correct status and detail - 405 for a wrong method,
     * 415 for an unsupported {@code Content-Type}, 406, missing path variable, and so on - but they are
     * also {@code Exception}s, so this handler claims them and, before this check existed, reported
     * every one as a 500 titled "Unexpected error". The visible symptom was {@code GET /ai/generate}
     * answering {@code 500 "Request method 'GET' is not supported"} after that endpoint moved to POST,
     * which tells a caller almost the opposite of what happened.
     *
     * <p>"Most", not all: the {@code HttpMessageNot(Readable|Writable)Exception} pair descends from
     * {@code NestedRuntimeException} and implements no {@code ErrorResponse}, so the unreadable-body
     * case is handled explicitly above. Check the hierarchy before assuming a given Spring exception
     * is covered here.
     *
     * <p>Anything genuinely unexpected gets a fixed detail. {@code ex.getMessage()} on a real failure
     * is a JDBC, Ollama or internal message that a caller can neither act on nor should see; it is
     * already logged above with its stack trace, which is where it belongs.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            logger.warn("Request rejected: {} - {}", errorResponse.getStatusCode(), ex.getMessage());
            ProblemDetail problemDetail = errorResponse.getBody();
            problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
            return problemDetail;
        }

        logger.error("Unexpected error occurred: ", ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problemDetail.setTitle("Unexpected error");
        problemDetail.setDetail("An unexpected error occurred. Please try again, or contact support "
                + "with the timestamp below if it persists.");
        problemDetail.setProperties(Map.of("Timestamp", LocalDateTime.now()));
        return problemDetail;
    }
}
