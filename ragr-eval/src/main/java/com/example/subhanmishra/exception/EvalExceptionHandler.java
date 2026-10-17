package com.example.subhanmishra.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The evaluation service's error handling: human review and golden runs, on top of the shared
 * {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class EvalExceptionHandler extends ApiExceptionHandler {

    @ExceptionHandler(GoldenRunInProgressException.class)
    public ProblemDetail handleRunInProgress(GoldenRunInProgressException ex) {
        logger.info("Golden run refused: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Golden Run In Progress", ex.getMessage());
    }
}
