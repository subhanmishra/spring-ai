package com.example.subhanmishra.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The evaluation service's error handling, for its one API, human review. It needs nothing beyond the
 * shared {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class EvalExceptionHandler extends ApiExceptionHandler {
}
