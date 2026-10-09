package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A stored conversation, oldest message first.
 *
 * <p>Memory keeps only the last {@code app.ai.max-chat-messages}, trimmed on every write, so older
 * messages are gone. {@code maxRetainedMessages} lets a caller tell a short conversation from a trimmed
 * one.
 */
public record ConversationDto(

        String conversationId,

        @Schema(description = "Messages currently retained, oldest first")
        int messageCount,

        @Schema(description = "The app.ai.max-chat-messages window. A messageCount at this value means "
                + "older turns have already been discarded and are not recoverable.", example = "10")
        int maxRetainedMessages,

        List<ChatMessageDto> messages) {
}
