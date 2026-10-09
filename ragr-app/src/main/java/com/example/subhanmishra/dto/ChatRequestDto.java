package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A chat turn: the prompt to answer, and nothing else. The conversation it continues travels in the
 * {@code X-Conversation-Id} header - see {@code ConversationIdInterceptor}.
 *
 * <p>In the body, not the URL, so questions - often the most sensitive thing in a RAG system - stay out
 * of access logs, browser history and proxy logs, and are not capped by URL length.
 */
public record ChatRequestDto(

        /*
         * 4000 characters, about 1,000 tokens. The model's 8192-token window must also hold the system
         * prompt, the passages and the answer; a much longer question would crowd out the passages.
         */
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        @Schema(description = "The question or instruction to send to the model",
                example = "what is a spring boot starter")
        String prompt) {
}
