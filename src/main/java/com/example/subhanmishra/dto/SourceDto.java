package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One retrieved chunk the answer was given as context, in the order the vector store ranked it.
 *
 * <p>Every chunk retrieved is listed, not only the ones the answer cites: together they are what the
 * answer was grounded on. {@code cited} marks the ones it actually relied on.
 */
public record SourceDto(

        @Schema(description = "1-based position in the retrieval ranking; citations refer to sources by it",
                example = "1")
        int ref,

        @Schema(description = "Id of the document this chunk came from")
        String documentId,

        @Schema(example = "spring-boot-reference.pdf")
        String fileName,

        @Schema(description = "1-based page, absent for sources Tika reads whole (DOCX, XLSX, PPTX, HTML, TXT, MD, CSV)",
                example = "42")
        Integer page,

        @Schema(description = "\"prose\" or \"table\"", example = "prose")
        String blockType,

        @Schema(description = "Similarity to the question, higher is closer", example = "0.8194")
        Double score,

        @Schema(description = "The chunk's full text as the model saw it, without its [filename, p. N] source line")
        String excerpt,

        @Schema(description = "Whether the answer cites this source")
        boolean cited) {
}
