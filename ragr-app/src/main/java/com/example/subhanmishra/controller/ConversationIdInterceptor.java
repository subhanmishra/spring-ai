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
 * <p>With no id sent, one is generated here, so only this class creates ids and every id a caller ends up
 * with came back in this header. The controller gets it as the {@link #ATTRIBUTE} request attribute.
 *
 * <p>The response header is set here, before the controller runs, because afterwards is too late for the
 * stream: its headers are sent with the first event, and it never passes through a
 * {@code ResponseBodyAdvice}.
 *
 * <p>Registered for the two generate endpoints only, in {@code WebConfig}.
 */
public class ConversationIdInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Conversation-Id";

    // A literal, because the controller names it in an annotation.
    static final String ATTRIBUTE = "com.example.subhanmishra.controller.ConversationIdInterceptor.conversationId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The stream's completion passes through here again after the response was sent; the first id
        // stands.
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
