package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A stored conversation, oldest message first.
 *
 * <p>{@code messageCount} can be smaller than the number of turns the conversation actually had:
 * {@code MessageWindowChatMemory} trims to {@code app.ai.max-chat-messages} when it <em>writes</em>,
 * so anything older has already been dropped from Redis and cannot be recovered. That limit is
 * reported as {@code maxRetainedMessages} so a caller can tell a short conversation apart from a
 * truncated one.
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
