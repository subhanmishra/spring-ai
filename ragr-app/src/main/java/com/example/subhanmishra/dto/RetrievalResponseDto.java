package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The result of a retrieval probe.
 *
 * <p>The search settings are the ones actually <em>used</em>, so a caller who sent none sees chat's
 * configured defaults.
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
