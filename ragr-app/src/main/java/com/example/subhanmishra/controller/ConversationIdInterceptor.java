package com.example.subhanmishra.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Settles which conversation a chat turn belongs to before {@link ChatController} sees the request.
 * The caller names it in the {@code X-Conversation-Id} request header; the id that was used comes back
 * in the response header of the same name.
 *
 * <p>When the caller sent no id, one is generated here, so the controller and {@code ChatService} only
 * ever receive a real id. {@code ChatService} does not generate ids on purpose: every id a caller can end
 * up with is one this interceptor also returned in the header. The id reaches the controller as the
 * {@link #ATTRIBUTE} request attribute.
 *
 * <p>The id is a header rather than a body field so the request body holds only what the model is
 * asked, and it is set on the response here, before the handler runs, rather than after it.
 * {@code /generateStream} returns a {@code Flux}, which Spring MVC hands to its streaming return-value
 * handler without passing through {@code ResponseBodyAdvice}, and whose headers are committed before the
 * first element is written - too late to add one. Before the controller runs, nothing has been committed
 * on either endpoint.
 *
 * <p>Registered for the two generate endpoints only, in {@code WebConfig}.
 */
public class ConversationIdInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Conversation-Id";

    // A literal, because the controller names it in an annotation.
    static final String ATTRIBUTE = "com.example.subhanmishra.controller.ConversationIdInterceptor.conversationId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The streaming endpoint's completion comes back through here as an async dispatch, after the
        // response is committed; the id settled on the first pass stands.
        if (request.getAttribute(ATTRIBUTE) != null) {
            return true;
        }
        String supplied = request.getHeader(HEADER);
        String conversationId = supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;

        request.setAttribute(ATTRIBUTE, conversationId);
        response.setHeader(HEADER, conversationId);
        return true;
    }
}
