package com.example.subhanmishra.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The evaluation service's error handling, for its one API - human review. Everything it needs is common
 * to every HTTP-facing application here, so it all lives in {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class EvalExceptionHandler extends ApiExceptionHandler {
}
