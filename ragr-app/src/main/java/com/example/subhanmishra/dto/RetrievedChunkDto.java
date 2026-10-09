package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One chunk as the vector store returned it, with its score and the metadata the pipeline wrote onto
 * it at ingestion time.
 *
 * <p>{@code citation} and {@code text} are the two halves of the stored content: the
 * {@code [filename, p. N]} line the model cites from, and the content itself.
 */
public record RetrievedChunkDto(

        @Schema(description = "The vector store's id for this chunk")
        String chunkId,

        @Schema(description = "Similarity to the query, higher is closer", example = "0.8194")
        Double score,

        @Schema(description = "Id of the document this chunk came from")
        String documentId,

        String fileName,

        @Schema(description = "1-based page, absent for sources Tika reads whole (DOCX, XLSX, PPTX, HTML)")
        Integer pageNumber,

        @Schema(description = "Position of this chunk in its document, in ingestion order")
        Integer chunkIndex,

        @Schema(description = "\"prose\" or \"table\"", example = "prose")
        String blockType,

        @Schema(description = "Position of the table within its page, absent for prose")
        Integer tableIndex,

        @Schema(description = "The data rows a table chunk covers, e.g. \"13-24\"", example = "13-24")
        String tableRows,

        @Schema(description = "The source line prefixed to the stored text, or null if this chunk has none",
                example = "[manual.pdf, p. 590]")
        String citation,

        @Schema(description = "The chunk's content with the source line removed")
        String text,

        @Schema(description = "False for chunks ingested before the citation header existed. Those "
                + "cannot be cited by the model, and nothing else distinguishes them - re-ingest "
                + "the document to fix it.")
        boolean hasCitationHeader) {
}
