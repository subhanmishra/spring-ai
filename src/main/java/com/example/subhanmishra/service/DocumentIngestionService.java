package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final DocumentHistoryService historyService;

    public DocumentIngestionService(VectorStore vectorStore, RagProperties ragProperties, DocumentHistoryService historyService) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.historyService = historyService;
    }

    @Transactional
    public int ingest(DocumentMetadata metadata, List<Document> parsedDocs) {
        log.info("Starting ingestion process for document [id={}, name={}]", metadata.getId(), metadata.getFilename());

        // Record the PROCESSING milestone. This happens in a new, separate transaction
        // and will not be rolled back if the main ingestion transaction fails.
        historyService.recordHistory(metadata.getId(), DocumentStatus.PROCESSING, "Starting to chunk and embed document.");

        // 1. Text chunking using TokenTextSplitter
        TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder()
                .withChunkSize(ragProperties.chunkSize())
                .withMinChunkSizeChars(ragProperties.minChunkSizeChars())
                .withMinChunkLengthToEmbed(ragProperties.minChunkLengthToEmbed())
                .withMaxNumChunks(ragProperties.maxNumChunks())
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = tokenTextSplitter.apply(parsedDocs);
        if (chunks.isEmpty()) {
            // Throwing an exception will cause this transaction to roll back.
            // The caller will catch this and record the FAILED status in the history.
            throw new IllegalStateException("Document parsing resulted in zero chunks. The document may be empty or unscannable.");
        }

        // 2. Enrich metadata on each chunk
        List<Document> enrichedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> enrichedMetadata = new HashMap<>(chunk.getMetadata());
            enrichedMetadata.put("documentId", metadata.getId().toString());
            enrichedMetadata.put("fileName", metadata.getFilename());
            enrichedMetadata.put("contentType", metadata.getContentType());
            enrichedMetadata.put("chunkIndex", i);
            Object pageNumber = chunk.getMetadata().get("page_number");
            if (pageNumber == null) {
                pageNumber = chunk.getMetadata().get("pageNumber");
            }
            if (pageNumber != null) {
                enrichedMetadata.put("pageNumber", pageNumber);
            }
            Document enrichedDoc = new Document(chunk.getText(), enrichedMetadata);
            enrichedChunks.add(enrichedDoc);
        }

        // 3. Write chunks and embeddings to the vector store. This is the main transactional work.
        log.info("Writing {} vector chunks to PgVectorStore for document: {}", enrichedChunks.size(), metadata.getFilename());
        vectorStore.add(enrichedChunks);

        log.info("Successfully processed document for vectorization [id={}, name={}, chunks={}]", metadata.getId(), metadata.getFilename(), enrichedChunks.size());

        return enrichedChunks.size();
    }
}