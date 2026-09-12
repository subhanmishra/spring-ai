package com.example.subhanmishra.service;

import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private final VectorStore vectorStore;
    private final DocumentHistoryService historyService;

    public DocumentIngestionService(VectorStore vectorStore, DocumentHistoryService historyService) {
        this.vectorStore = vectorStore;
        this.historyService = historyService;
    }

    @Transactional
    public int ingest(DocumentMetadata metadata, Map<String, Object> parseResult) {
        log.info("Starting ingestion process for document [id={}, name={}]", metadata.getId(), metadata.getFilename());

        // Record the PROCESSING milestone. This happens in a new, separate transaction
        // and will not be rolled back if the main ingestion transaction fails.
        historyService.recordHistory(metadata.getId(), DocumentStatus.PROCESSING, "Starting to chunk and embed document.");

        Stream<Document> enrichedStream = getEnrichedStream(metadata, (Stream<Document>) parseResult.get("documentStream"));

        // 2. Batch the stream and write to the vector store
        int batchSize = 50; // A configurable batch size is recommended
        final AtomicInteger totalChunks = new AtomicInteger(0);

        partition(enrichedStream, batchSize).forEach(batch -> {
            log.info("Writing batch of {} vector chunks to PgVectorStore for document: {}", batch.size(), metadata.getFilename());
            vectorStore.add(batch);
            totalChunks.addAndGet(batch.size());
        });

        if (totalChunks.get() == 0) {
            throw new IllegalStateException("Document parsing resulted in zero chunks. The document may be empty or unscannable.");
        }

        log.info("Successfully processed document for vectorization [id={}, name={}, chunks={}]", metadata.getId(), metadata.getFilename(), totalChunks.get());
        return totalChunks.get();
    }

    private static @NonNull Stream<Document> getEnrichedStream(DocumentMetadata metadata, Stream<Document> documentStream) {
        if (documentStream == null) {
            throw new IllegalStateException("Parsing result did not contain a document stream.");
        }

        // 1. Enrich metadata on each chunk lazily as part of the stream
        AtomicInteger chunkIndex = new AtomicInteger(0);
        return documentStream.map(chunk -> {
            Map<String, Object> newMetadata = new HashMap<>(chunk.getMetadata());
            newMetadata.put("documentId", metadata.getId().toString());
            newMetadata.put("fileName", metadata.getFilename());
            newMetadata.put("contentType", metadata.getContentType());
            newMetadata.put("chunkIndex", chunkIndex.getAndIncrement());

            // Normalize page number metadata
            Object pageNumber = chunk.getMetadata().get("page_number");
            if (pageNumber == null) {
                pageNumber = chunk.getMetadata().get("pageNumber");
            }
            if (pageNumber != null) {
                newMetadata.put("pageNumber", pageNumber);
            }
            return new Document(chunk.getText(), newMetadata);
        });
    }

    // Helper method to partition a stream into batches
    private <T> Stream<List<T>> partition(Stream<T> source, int size) {
        final AtomicInteger counter = new AtomicInteger(0);
        return source.collect(Collectors.groupingBy(_ -> counter.getAndIncrement() / size)).values().stream();
    }
}