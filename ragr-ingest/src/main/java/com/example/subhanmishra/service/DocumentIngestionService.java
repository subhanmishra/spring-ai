package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.citation.CitationParser;
import com.example.subhanmishra.config.IngestionProperties;
import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.repository.VectorStoreRepository;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** Fragments of the Go dial error Ollama returns when its model runner is not listening. */
    private static final List<String> RUNNER_UNAVAILABLE_MARKERS =
            List.of("dial tcp", "connection refused", "actively refused", "connectex");

    private final VectorStore vectorStore;
    private final DocumentHistoryService historyService;
    private final VectorStoreRepository vectorStoreRepository;
    private final IngestionProperties ingestionProperties;

    /** Stamped on every chunk as {@link ChunkMetadata#PIPELINE_VERSION}; see {@link IngestionProperties#pipelineVersion}. */
    private final String pipelineVersion;

    /** Each batch commits on its own, in a new transaction, even if a caller ever holds one. */
    private final TransactionTemplate transactionTemplate;

    /**
     * Limits how many batches are written at once, across all uploads. Virtual threads are cheap but
     * database connections are not, so this - not the thread count - protects the connection pool.
     */
    private final Semaphore ingestionPermits;

    private final ThreadFactory ingestionThreadFactory = Thread.ofVirtual().name("doc-ingest-", 0).factory();
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public DocumentIngestionService(VectorStore vectorStore,
                                    DocumentHistoryService historyService,
                                    VectorStoreRepository vectorStoreRepository,
                                    IngestionProperties ingestionProperties,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${spring.ai.ollama.embedding.model}") String embeddingModel,
                                    @Value("${app.embedding.task-prefix}") String embeddingTaskPrefix) {
        this.vectorStore = vectorStore;
        this.historyService = historyService;
        this.vectorStoreRepository = vectorStoreRepository;
        this.ingestionProperties = ingestionProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ingestionPermits = new Semaphore(Math.max(1, ingestionProperties.concurrency()));
        this.pipelineVersion = ingestionProperties.pipelineVersion(embeddingModel, embeddingTaskPrefix);
    }

    /**
     * Deliberately not {@code @Transactional}. Batches are written on several threads, and a transaction
     * belongs to one thread, so an outer one would only hold an idle connection while the real writes
     * committed outside it. All-or-nothing is kept by {@link #deleteWrittenChunks} instead.
     */
    public int ingest(DocumentMetadata metadata, Map<String, Object> parseResult) {
        log.info("Starting ingestion process for document [id={}, name={}]", metadata.getId(), metadata.getFilename());

        // In its own transaction, so a failed batch does not roll it back.
        historyService.recordHistory(metadata.getId(), DocumentStatus.PROCESSING, "Starting to chunk and embed document");

        Stream<Document> enrichedStream = getEnrichedStream(metadata, (Stream<Document>) parseResult.get("documentStream"), pipelineVersion);

        List<List<Document>> batches = partition(enrichedStream, ingestionProperties.batchSize()).toList();
        int totalChunks = writeBatches(metadata, batches);

        if (totalChunks == 0) {
            throw new IllegalStateException("Document parsing resulted in zero chunks. The document may be empty or unscannable.");
        }

        log.info("Successfully processed document for vectorization [id={}, name={}, chunks={}]", metadata.getId(), metadata.getFilename(), totalChunks);
        return totalChunks;
    }

    /**
     * One virtual thread per batch. ({@code StructuredTaskScope} would fit better, but is still a preview
     * API on Java 26.)
     */
    private int writeBatches(DocumentMetadata metadata, List<List<Document>> batches) {
        if (batches.isEmpty()) {
            return 0;
        }

        // Carries the trace context onto the batch threads, so their logs link to the right trace.
        ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
        AtomicBoolean aborted = new AtomicBoolean(false);

        try {
            // The first batch runs alone, to warm Ollama up. Ollama loads the embedding model on first
            // use, and requests that arrive while it is still loading fail with "connection refused" (as
            // an HTTP 400 that Spring AI will not retry). Only after one batch succeeds do the rest start.
            int totalChunks = writeBatch(metadata, batches.getFirst(), aborted);

            List<Callable<Integer>> tasks = batches.subList(1, batches.size())
                                                   .stream()
                                                   .map(batch -> snapshot.wrap((Callable<Integer>) () -> writeBatch(metadata, batch, aborted)))
                                                   .toList();

            // The semaphore in writeBatchOnce limits the load; closing the executor waits for every batch.
            if (!tasks.isEmpty()) {
                try (ExecutorService executor = Executors.newThreadPerTaskExecutor(ingestionThreadFactory)) {
                    for (Future<Integer> future : executor.invokeAll(tasks)) {
                        totalChunks += future.get();
                    }
                }
            }
            return totalChunks;

        } catch (ExecutionException e) {
            deleteWrittenChunks(metadata);
            throw new DocumentProcessingException("Failed to write chunks to the vector store for document: " + metadata.getFilename(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
            deleteWrittenChunks(metadata);
            throw new DocumentProcessingException("Interrupted while writing chunks for document: " + metadata.getFilename(), e);
        } catch (RuntimeException e) {
            // The first batch runs inline, so its failure arrives here rather than as an ExecutionException.
            deleteWrittenChunks(metadata);
            throw new DocumentProcessingException("Failed to write chunks to the vector store for document: " + metadata.getFilename(), e);
        }
    }

    /**
     * Retries a batch only when Ollama's model runner could not be reached. Spring AI's retry never sees
     * this, because Ollama reports it as an HTTP 400. Writing the same batch again is safe: the vector
     * store upserts by chunk id.
     */
    private int writeBatch(DocumentMetadata metadata, List<Document> batch, AtomicBoolean aborted) throws InterruptedException {
        long backoffMillis = ingestionProperties.retryBackoff().toMillis();

        for (int attempt = 1; ; attempt++) {
            try {
                return writeBatchOnce(metadata, batch, aborted);
            } catch (RuntimeException e) {
                if (attempt >= ingestionProperties.maxAttempts() || !isModelRunnerUnavailable(e)) {
                    aborted.set(true);
                    throw e;
                }
                log.warn("Embedding model runner was unreachable on attempt {}/{} for document: {}. Retrying in {}ms",
                         attempt, ingestionProperties.maxAttempts(), metadata.getFilename(), backoffMillis);
                Thread.sleep(backoffMillis);
                backoffMillis *= 2;
            }
        }
    }

    private int writeBatchOnce(DocumentMetadata metadata, List<Document> batch, AtomicBoolean aborted) throws InterruptedException {
        ingestionPermits.acquire();
        try {
            if (aborted.get()) {
                // Another batch failed and the document will be rolled back; skip the work.
                return 0;
            }
            log.info("Writing batch of {} vector chunks to PgVectorStore for document: {}", batch.size(), metadata.getFilename());
            transactionTemplate.executeWithoutResult(_ -> vectorStore.add(batch));
            return batch.size();
        } finally {
            // Released before any backoff sleep, so a waiting batch is not blocked by one that is retrying.
            ingestionPermits.release();
        }
    }

    /**
     * The error message is the only sign of an unreachable runner. Matching it narrowly keeps real 400s,
     * such as a malformed request, failing at once instead of being retried.
     */
    private static boolean isModelRunnerUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (RUNNER_UNAVAILABLE_MARKERS.stream().anyMatch(normalized::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Undoes a partly written document. Batches commit separately, so this is what makes a failed upload
     * leave no chunks behind.
     */
    private void deleteWrittenChunks(DocumentMetadata metadata) {
        try {
            transactionTemplate.executeWithoutResult(_ -> vectorStoreRepository.deleteByDocumentId(metadata.getId().toString()));
            log.warn("Removed partially written chunks for document [id={}, name={}]", metadata.getId(), metadata.getFilename());
        } catch (RuntimeException cleanupFailure) {
            // Never mask the original ingestion failure with a cleanup failure.
            log.error("Compensating delete failed for document [id={}]; orphan chunks may remain in the vector store", metadata.getId(), cleanupFailure);
        }
    }

    private static @NonNull Stream<Document> getEnrichedStream(DocumentMetadata metadata, Stream<Document> documentStream,
                                                               String pipelineVersion) {
        if (documentStream == null) {
            throw new IllegalStateException("Parsing result did not contain a document stream.");
        }

        // chunkIndex assumes this stream is sequential and in document order.
        AtomicInteger chunkIndex = new AtomicInteger(0);
        return documentStream.map(chunk -> {
            Map<String, Object> newMetadata = new HashMap<>(chunk.getMetadata());
            newMetadata.put(ChunkMetadata.DOCUMENT_ID, metadata.getId().toString());
            newMetadata.put(ChunkMetadata.FILE_NAME, metadata.getFilename());
            newMetadata.put(ChunkMetadata.CONTENT_TYPE, metadata.getContentType());
            newMetadata.put(ChunkMetadata.CHUNK_INDEX, chunkIndex.getAndIncrement());
            newMetadata.put(ChunkMetadata.PIPELINE_VERSION, pipelineVersion);

            // Normalize page number metadata
            Object pageNumber = chunk.getMetadata().get("page_number");
            if (pageNumber == null) {
                pageNumber = chunk.getMetadata().get(ChunkMetadata.PAGE_NUMBER);
            }
            if (pageNumber != null) {
                newMetadata.put(ChunkMetadata.PAGE_NUMBER, pageNumber);
            }
            return new Document(citationHeader(metadata.getFilename(), pageNumber, sectionLine(newMetadata)) + chunk.getText(),
                                newMetadata);
        });
    }

    /**
     * What the {@code Section:} line names: the product and version from the front matter, then the
     * heading path - {@code "Spring Boot 4.1.1 > Task Execution and Scheduling > Customizing the
     * Executor"}. Null when the chunk has no section, so PDF and Tika chunks get no line. A page without a
     * version, such as a release note, names the product alone; a page without front matter, the path
     * alone.
     */
    static @Nullable String sectionLine(Map<String, Object> chunkMetadata) {
        String section = ChunkMetadata.asString(chunkMetadata.get(ChunkMetadata.SECTION));
        if (section == null || section.isBlank()) {
            return null;
        }
        String product = Stream.of(chunkMetadata.get(ChunkMetadata.PROJECT), chunkMetadata.get(ChunkMetadata.PROJECT_VERSION))
                               .map(ChunkMetadata::asString)
                               .filter(value -> value != null && !value.isBlank())
                               .collect(Collectors.joining(" "));
        return product.isEmpty() ? section : product + " > " + section;
    }

    /**
     * Builds the header at the start of every chunk's text: the citation line - {@code [manual.pdf, p. 590]}
     * for a paged source, {@code [file.md]} otherwise - and a {@code Section:} line when the chunk has a
     * section.
     *
     * <p><b>Why it is part of the text, not only metadata.</b> Spring AI builds the prompt from each
     * chunk's text and drops its metadata, so a page number kept only in metadata could never reach the
     * model. Being text, the header is also embedded and stored with the chunk. The costs, accepted: every
     * vector carries the same filename, which narrows the gap between scores a little, and the stored text
     * is not the document's own - anything reading chunks back must remove the header with
     * {@code CitationParser.stripHeader}.
     *
     * <p><b>Why the section line.</b> A chunk can start with an example whose heading fell in the chunk
     * before, and then nothing in it says what the example configures. Naming the section fixed answers
     * that took the actuator's {@code management.server.port} for the application's own port, where a
     * prompt rule had not. The product and version also tell apart pages that several projects share
     * word for word.
     */
    private static String citationHeader(String fileName, Object pageNumber, @Nullable String section) {
        // Formats without pages get no page: a wrong citation is worse than a missing one.
        String citation = pageNumber != null
                ? "[%s, p. %s]".formatted(fileName, pageNumber)
                : "[%s]".formatted(fileName);
        // "\n", not System.lineSeparator(): this is stored and embedded, so it must not depend on the OS.
        if (section == null) {
            return citation + "\n\n";
        }
        return citation + "\n" + CitationParser.SECTION_LINE_PREFIX + section + "\n\n";
    }

    // Splits the chunks into batches. This collects the whole stream first.
    private <T> Stream<List<T>> partition(Stream<T> source, int size) {
        final AtomicInteger counter = new AtomicInteger(0);
        return source.collect(Collectors.groupingBy(_ -> counter.getAndIncrement() / size)).values().stream();
    }
}
