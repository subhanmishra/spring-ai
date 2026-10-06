package com.example.subhanmishra.entity;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationParser;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * One candidate in a turn's pool, as {@code eval_turn_chunk} stores it.
 *
 * @param rank      1-based pool rank, in score order
 * @param inContext whether the model was shown it - always the leading ranks
 * @param cited     whether the answer cites its page
 * @param text      the chunk text as stored, citation header included, so the judges and the citation
 *                  checks see exactly what the model saw
 */
public record EvalTurnChunk(int rank,
                            @Nullable String chunkId,
                            @Nullable String documentId,
                            @Nullable String fileName,
                            @Nullable Integer page,
                            @Nullable String section,
                            @Nullable String pipelineVersion,
                            @Nullable Double score,
                            boolean inContext,
                            boolean cited,
                            @Nullable Integer judgeGrade,
                            String text) {

    public static EvalTurnChunk of(int rank, Document document, boolean inContext, boolean cited) {
        Map<String, Object> metadata = document.getMetadata();
        Citation header = CitationParser.parseHeader(document.getText());
        String fileName = header != null ? header.fileName() : ChunkMetadata.asString(metadata.get(ChunkMetadata.FILE_NAME));
        Integer page = header != null && header.pageNumber() != null
                ? header.pageNumber()
                : ChunkMetadata.asInteger(metadata.get(ChunkMetadata.PAGE_NUMBER));
        return new EvalTurnChunk(rank,
                                 document.getId(),
                                 ChunkMetadata.asString(metadata.get(ChunkMetadata.DOCUMENT_ID)),
                                 fileName,
                                 page,
                                 ChunkMetadata.asString(metadata.get(ChunkMetadata.SECTION)),
                                 ChunkMetadata.asString(metadata.get(ChunkMetadata.PIPELINE_VERSION)),
                                 document.getScore(),
                                 inContext,
                                 cited,
                                 null,
                                 document.getText() != null ? document.getText() : "");
    }

    /** Back to the shape the judges take. */
    public Document toDocument() {
        Map<String, Object> metadata = new HashMap<>();
        if (fileName != null) {
            metadata.put(ChunkMetadata.FILE_NAME, fileName);
        }
        if (page != null) {
            metadata.put(ChunkMetadata.PAGE_NUMBER, page);
        }
        Document.Builder builder = Document.builder().text(text).metadata(metadata).score(score);
        if (chunkId != null) {
            builder.id(chunkId);
        }
        return builder.build();
    }
}
