package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a chat turn cost. Token counts are as the model server reported them, and absent if it did not. */
public record UsageDto(

        @Schema(example = "gemma4:e2b")
        String model,

        @Schema(description = "Tokens in the prompt, including the retrieved context and chat history")
        Integer promptTokens,

        Integer completionTokens,

        @Schema(description = "Wall-clock time for retrieval plus generation")
        long latencyMillis) {
}
