package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.dto.RetrievalRequestDto;
import com.example.subhanmishra.dto.RetrievalResponseDto;
import com.example.subhanmishra.dto.RetrievedChunkDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs the same similarity search the chat path runs, and reports what came back.
 *
 * <p>It tells a retrieval failure from a generation failure: when an answer is wrong, was the right
 * chunk never found, or found and ignored? Nothing else in the application can say.
 *
 * <p>The search settings default from {@link RagProperties}, the same record chat's advisor is built
 * from. That is what makes this faithful to chat rather than merely similar.
 *
 * <p>Like chat, it searches with the question as written, without the conversation, so a follow-up
 * question retrieves here as poorly as it does there.
 */
@Service
public class RetrievalDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalDiagnosticsService.class);

    /**
     * The citation line, {@code [manual.pdf, p. 590]}: a copy of {@code CitationParser.CITATION_LINE},
     * which must stay identical. The shape is matched so a chunk without a header keeps its first line.
     */
    private static final Pattern CITATION_LINE = Pattern.compile("^\\[[^\\]\\n]*]$");

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public RetrievalDiagnosticsService(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public RetrievalResponseDto search(RetrievalRequestDto request) {

        // Both defaults MUST keep coming from RagProperties, as chat's advisor does. This endpoint is only
        // useful while it searches exactly as chat searches; a hardcoded value would quietly report on a
        // different search - a diagnostic that lies is worse than none. The response echoes the values
        // used, so a caller can tell a default from an override.
        int topK = request.topK() != null ? request.topK() : ragProperties.topK();
        double threshold = request.similarityThreshold() != null
                ? request.similarityThreshold()
                : ragProperties.similarityThreshold();

        SearchRequest.Builder searchRequest = SearchRequest.builder()
                                                           .query(request.query())
                                                           .topK(topK)
                                                           .similarityThreshold(threshold);

        String documentId = request.documentId();
        if (documentId != null && !documentId.isBlank()) {
            // Parsed as a UUID first: junk is a 400, and no caller string reaches the filter expression.
            documentId = UUID.fromString(documentId.trim()).toString();
            searchRequest.filterExpression("documentId == '" + documentId + "'");
        } else {
            documentId = null;
        }

        long startedAt = System.nanoTime();
        List<Document> results = vectorStore.similaritySearch(searchRequest.build());
        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;

        List<Document> hits = results != null ? results : List.of();
        log.info("Retrieval probe returned {} hit(s) in {}ms [topK={}, threshold={}, documentId={}]",
                 hits.size(), tookMillis, topK, threshold, documentId);

        return new RetrievalResponseDto(request.query(),
                                        topK,
                                        threshold,
                                        documentId,
                                        hits.size(),
                                        tookMillis,
                                        hits.stream().map(RetrievalDiagnosticsService::toDto).toList());
    }

    private static RetrievedChunkDto toDto(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String[] split = splitCitation(document.getText());

        return new RetrievedChunkDto(document.getId(),
                                     document.getScore(),
                                     ChunkMetadata.asString(metadata.get(ChunkMetadata.DOCUMENT_ID)),
                                     ChunkMetadata.asString(metadata.get(ChunkMetadata.FILE_NAME)),
                                     ChunkMetadata.asInteger(metadata.get(ChunkMetadata.PAGE_NUMBER)),
                                     ChunkMetadata.asInteger(metadata.get(ChunkMetadata.CHUNK_INDEX)),
                                     ChunkMetadata.asString(metadata.get(ChunkMetadata.BLOCK_TYPE)),
                                     ChunkMetadata.asInteger(metadata.get(ChunkMetadata.TABLE_INDEX)),
                                     ChunkMetadata.asString(metadata.get(ChunkMetadata.TABLE_ROWS)),
                                     split[0],
                                     split[1],
                                     split[0] != null);
    }

    /**
     * Separates a chunk's citation header from its content, as {@code [citation, body]}. The citation
     * is null when the chunk carries none, and the body is then the whole text.
     */
    private static String[] splitCitation(String text) {
        if (text == null) {
            return new String[]{null, null};
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return new String[]{null, text};
        }
        String firstLine = text.substring(0, firstBreak).stripTrailing();
        if (!CITATION_LINE.matcher(firstLine).matches()) {
            return new String[]{null, text};
        }
        return new String[]{firstLine, text.substring(firstBreak).stripLeading()};
    }
}
