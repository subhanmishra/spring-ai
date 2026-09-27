package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A chat turn: the prompt to answer, and optionally the conversation to continue.
 *
 * <p>The prompt travels in a request body rather than a query parameter so it stays out of access
 * logs, browser history and proxy logs - for a RAG assistant the questions people ask about their own
 * documents are usually the most sensitive thing in the system - and so it is not bounded by URL
 * length limits, which cap well below what a long prompt can reach.
 */
public record ChatRequestDto(

        /*
         * The cap is characters, and it is deliberately far below "as much as the caller likes".
         * The chat model runs with num-ctx 8192 tokens, and QuestionAnswerAdvisor spends most of that
         * window on the retrieved passages plus the system prompt. 4000 characters is roughly 1,000
         * tokens, which leaves room for the RAG context and the generated answer; a much larger
         * prompt would crowd out the very passages the answer is supposed to be grounded in.
         */
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        @Schema(description = "The question or instruction to send to the model",
                example = "what is a spring boot starter")
        String prompt,

        @Schema(description = "Conversation to continue. Omit to start a new one - the id that was "
                + "used is returned in the X-Conversation-Id response header either way.")
        String conversationId) {
}
