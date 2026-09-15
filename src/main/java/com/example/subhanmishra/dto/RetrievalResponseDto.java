package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The result of a retrieval probe.
 *
 * <p>The search parameters are echoed back as the values that were actually <em>in force</em>, not as
 * they arrived on the request: a caller who supplied no overrides needs to see what the configured
 * defaults are, since those are the ones the chat path uses.
 */
public record RetrievalResponseDto(

        String query,

        @Schema(description = "The topK actually used - the request's override, or app.rag.top-k")
        int topK,

        @Schema(description = "The threshold actually used - the request's override, or app.rag.similarity-threshold")
        double similarityThreshold,

        @Schema(description = "The document the search was restricted to, or null if it searched the whole corpus")
        String documentId,

        @Schema(description = "Chunks returned. Fewer than topK means the threshold excluded the rest.")
        int hitCount,

        @Schema(description = "Wall-clock time for the embedding call plus the pgvector query")
        long tookMillis,

        List<RetrievedChunkDto> hits) {
}
