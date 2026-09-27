package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.DocumentResponseDto;
import com.example.subhanmishra.entity.DocumentStatus;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

/**
 * Chooses the status of a bulk upload from its per-file results, so {@link DocumentController} can
 * declare its success status and return the list.
 *
 * <p>201 when every file indexed, 207 Multi-Status when some failed, 422 when none did. The body is
 * returned unchanged either way: every file sent has an entry, failed ones included.
 *
 * <p>Applies to any {@code List<DocumentResponseDto>} returned by {@code DocumentController}, which
 * today is only the bulk upload. A single upload returns one {@code DocumentResponseDto} and is not
 * matched.
 */
@ControllerAdvice(assignableTypes = DocumentController.class)
class BatchUploadStatusAdvice implements ResponseBodyAdvice<Object> {

    private static final ResolvableType RESULTS =
            ResolvableType.forClassWithGenerics(List.class, DocumentResponseDto.class);

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return RESULTS.isAssignableFrom(ResolvableType.forMethodParameter(returnType));
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof List<?> results && !results.isEmpty()) {
            long failed = results.stream()
                    .filter(r -> r instanceof DocumentResponseDto dto && dto.status() == DocumentStatus.FAILED)
                    .count();
            if (failed == results.size()) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_CONTENT);
            } else if (failed > 0) {
                response.setStatusCode(HttpStatus.MULTI_STATUS);
            }
        }
        return body;
    }
}
