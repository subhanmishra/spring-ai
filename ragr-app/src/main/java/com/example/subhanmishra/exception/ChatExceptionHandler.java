package com.example.subhanmishra.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The chat service's error handling. Everything it needs is common to every HTTP-facing application
 * here, so it all lives in {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class ChatExceptionHandler extends ApiExceptionHandler {
}
