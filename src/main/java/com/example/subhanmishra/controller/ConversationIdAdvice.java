package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.ChatRequestDto;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;
import java.util.UUID;

/**
 * Settles which conversation a chat turn belongs to before {@link ChatController} sees the request,
 * and reports it back in the {@code X-Conversation-Id} response header.
 *
 * <p>When the caller supplied no id, one is generated here and written into the request DTO, so the
 * controller and {@code ChatService} only ever receive a real id. {@code ChatService} does not generate
 * ids on purpose: every id a caller can end up with is one this advice also returned in the header.
 *
 * <p>The header is set on the request side, as the body is read, rather than in a
 * {@code ResponseBodyAdvice}. {@code /generateStream} returns a {@code Flux}, which Spring MVC hands to
 * its streaming return-value handler without passing through {@code ResponseBodyAdvice}, and whose
 * headers are committed before the first element is written - too late to add one. Before the
 * controller runs, nothing has been committed on either endpoint.
 */
@ControllerAdvice(assignableTypes = ChatController.class)
class ConversationIdAdvice extends RequestBodyAdviceAdapter {

    static final String HEADER = "X-Conversation-Id";

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return methodParameter.getParameterType() == ChatRequestDto.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        ChatRequestDto request = (ChatRequestDto) body;
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();

        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            attributes.getResponse().setHeader(HEADER, conversationId);
        }
        return new ChatRequestDto(request.prompt(), conversationId);
    }
}
