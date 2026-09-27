package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One turn of a stored conversation.
 *
 * <p>{@code role} is Spring AI's {@code MessageType} lower-cased - {@code user}, {@code assistant},
 * {@code system} or {@code tool} - rather than the enum constant, so the wire format does not change
 * shape if the enum gains members.
 */
public record ChatMessageDto(

        @Schema(description = "Who produced this message", example = "user",
                allowableValues = {"user", "assistant", "system", "tool"})
        String role,

        @Schema(description = "The message text")
        String text) {
}
