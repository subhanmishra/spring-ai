package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A retrieval probe: the query to embed, plus optional overrides for the search parameters the chat
 * path would otherwise take from {@code app.rag}.
 *
 * <p>The overrides are nullable rather than defaulted here, because "not supplied" has to be
 * distinguishable from "supplied as the same value the configuration happens to hold" - the response
 * echoes back which parameters were actually in force.
 */
public record RetrievalRequestDto(

        @NotBlank(message = "query must not be blank")
        @Schema(description = "The text to embed and search with", example = "what is a spring boot starter")
        String query,

        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 100, message = "topK must be at most 100")
        @Schema(description = "Number of chunks to return. Defaults to app.rag.top-k.", example = "5")
        Integer topK,

        @DecimalMin(value = "0.0", message = "similarityThreshold must be between 0.0 and 1.0")
        @DecimalMax(value = "1.0", message = "similarityThreshold must be between 0.0 and 1.0")
        @Schema(description = "Minimum similarity a chunk must reach to be returned. Defaults to "
                + "app.rag.similarity-threshold. Set 0.0 to see what the threshold is excluding.",
                example = "0.6")
        Double similarityThreshold,

        @Schema(description = "Restrict the search to one document. Must be a UUID.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String documentId) {
}
