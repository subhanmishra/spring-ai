package com.example.subhanmishra.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The chat service's error handling. It needs nothing beyond the shared {@link ApiExceptionHandler}.
 */
@RestControllerAdvice
public class ChatExceptionHandler extends ApiExceptionHandler {
}
