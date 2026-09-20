package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.dto.RetrievalRequestDto;
import com.example.subhanmishra.dto.RetrievalResponseDto;
import com.example.subhanmishra.dto.RetrievedChunkDto;
import com.example.subhanmishra.service.parse.ChunkMetadata;
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
 * <p>This exists to separate a retrieval failure from a generation failure. When a grounded answer is
 * wrong, the question is whether the right chunk was never retrieved or was retrieved and ignored,
 * and nothing else in the application can answer it.
 *
 * <p>The search parameters default from {@link RagProperties} - the same record
 * {@code SpringAiConfig.chatClient} reads when it builds the {@code QuestionAnswerAdvisor}'s
 * {@code SearchRequest}. That shared source is what makes this faithful rather than merely similar;
 * hardcoding either value here would let the diagnostic drift away from the thing it reports on.
 *
 * <p>What it does <em>not</em> reproduce is the effect of chat memory. {@code QuestionAnswerAdvisor}
 * searches with the user's message as written, so a single-turn query matches exactly, but a
 * follow-up turn that only makes sense in context ("and what about the other one?") retrieves just as
 * poorly here as it does there - which is itself worth being able to see.
 */
@Service
public class RetrievalDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalDiagnosticsService.class);

    /**
     * A citation header occupies the whole of the chunk's first line and is bracketed, e.g.
     * {@code [manual.pdf, p. 590]}. Matching the shape rather than splitting on the first blank line
     * matters: chunks ingested before the header existed start straight into their content, and
     * splitting those unconditionally would present a real first line as a citation and drop it from
     * the body.
     */
    private static final Pattern CITATION_LINE = Pattern.compile("^\\[[^\\]\\n]*]$");

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public RetrievalDiagnosticsService(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public RetrievalResponseDto search(RetrievalRequestDto request) {

        // Both defaults MUST keep coming from RagProperties - the same record SpringAiConfig.chatClient
        // reads when it builds the QuestionAnswerAdvisor's SearchRequest. That shared source is the
        // whole basis of this endpoint being faithful: it is here to tell a retrieval failure from a
        // generation failure, which it can only do while it searches exactly as the chat path searches.
        // Hardcoding either value would leave the endpoint working and quietly reporting on different
        // retrieval than the application performs - a diagnostic that lies is worse than none. The
        // response echoes the values actually in force so a caller can tell a default from an override.
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
            // Parse before interpolating: this rejects junk with a 400 rather than a filter-parse
            // failure, and keeps an arbitrary caller-supplied string out of the filter expression.
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
                                     asString(metadata.get("documentId")),
                                     asString(metadata.get("fileName")),
                                     asInteger(metadata.get("pageNumber")),
                                     asInteger(metadata.get("chunkIndex")),
                                     asString(metadata.get(ChunkMetadata.BLOCK_TYPE)),
                                     asInteger(metadata.get(ChunkMetadata.TABLE_INDEX)),
                                     asString(metadata.get(ChunkMetadata.TABLE_ROWS)),
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

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Metadata makes a round trip through a JSONB column, so a value written as an {@code int} can come
     * back as any {@link Number} subtype - or, for a page number a reader supplied as text, as a String.
     */
    private static Integer asInteger(Object value) {
        return switch (value) {
            case Number number -> number.intValue();
            case String string -> {
                try {
                    yield Integer.valueOf(string.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }
}
