package com.example.subhanmishra.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * The error handling every HTTP-facing application here shares: each one extends this with its own
 * {@code @RestControllerAdvice} and adds the handlers only it needs.
 *
 * <p>Deliberately not annotated. Every application scans {@code com.example.subhanmishra}, so an
 * annotated advice here would register in all of them and run beside each subclass instead of being
 * replaced by it. Subclasses inherit these {@code @ExceptionHandler} methods.
 */
public abstract class ApiExceptionHandler {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        logger.warn("Resource not found : {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {

        // key value pair--> validation error
        Map<String, String> errors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        logger.warn("Validation failed for request: {}", errors);

        ProblemDetail problemDetail = problem(HttpStatus.BAD_REQUEST, "Validation Failed",
                "One or more fields in the request are invalid.");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Illegal argument", ex.getMessage());
    }


    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        logger.debug("No static resource found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }


    /**
     * A path variable or query parameter that could not be converted to the type the handler declares -
     * most often a malformed UUID in a path such as {@code /api/v1/documents/not-a-uuid}.
     *
     * <p>It is not an {@link ErrorResponse}, so without this handler a malformed id would be a 500. It is
     * caught at this narrow type on purpose: its parent, {@code TypeMismatchException}, also covers a
     * missing converter, which really is a server fault.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid Parameter",
                "The value supplied for '%s' is not valid for its expected type.".formatted(ex.getName()));
    }


    /**
     * A request body that could not be parsed - malformed JSON, or a value of the wrong shape for the
     * field it lands in.
     *
     * <p>One of the few Spring MVC exceptions that is not an {@link ErrorResponse}, so it needs its own
     * handler or it would be a 500. The parser's own message is not returned: it describes Jackson's
     * internals more than the caller's mistake.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        logger.warn("Unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed Request Body",
                "The request body could not be read. It must be valid JSON matching the documented schema.");
    }


    /**
     * Last resort for anything not handled above.
     *
     * <ul>
     *   <li><b>Spring MVC's own exceptions pass through</b> with their own status - 405 for a wrong
     *       method, 415 for a wrong {@code Content-Type}, and so on - because most are an
     *       {@link ErrorResponse}. Without this check every one became a 500. Not all of them are, so
     *       check before assuming one is covered: the unreadable-body case above is the exception.</li>
     *   <li><b>Anything else is a 500 with a fixed message.</b> The real message (JDBC, Ollama, internal)
     *       is no use to a caller and should not reach one; it is logged with its stack trace.</li>
     * </ul>
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

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "An unexpected error occurred. Please try again, or contact support "
                        + "with the timestamp below if it persists.");
    }


    protected static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setProperty("Timestamp", LocalDateTime.now());
        return problemDetail;
    }
}
