package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.citation.CitationParser;
import com.example.subhanmishra.citation.CitationResolver;
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
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Each batch commits on its own connection, so batch writes cannot join the caller's transaction.
     * REQUIRES_NEW states that explicitly and stays correct even if a caller ever holds one.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Bounds how many batches may hold a pooled connection at once. Virtual threads are cheap but Hikari
     * connections are not, so the permit count - not the thread count - is what protects the pool. The
     * semaphore lives on this singleton, so the bound is global across concurrent uploads.
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
     * Deliberately not {@code @Transactional}: batches are written concurrently, and a JDBC transaction is
     * bound to one thread and one connection. An enclosing transaction here would pin an idle connection
     * for the whole run while the real writes committed outside it. The all-or-nothing guarantee is kept
     * by the compensating delete in {@link #deleteWrittenChunks(DocumentMetadata)} instead.
     */
    public int ingest(DocumentMetadata metadata, Map<String, Object> parseResult) {
        log.info("Starting ingestion process for document [id={}, name={}]", metadata.getId(), metadata.getFilename());

        // Record the PROCESSING milestone. This happens in a new, separate transaction
        // and will not be rolled back if a batch write fails.
        historyService.recordHistory(metadata.getId(), DocumentStatus.PROCESSING, "Starting to chunk and embed document");

        Stream<Document> enrichedStream = getEnrichedStream(metadata, (Stream<Document>) parseResult.get("documentStream"), pipelineVersion);

        // 2. Batch the stream, then write the batches concurrently to the vector store
        List<List<Document>> batches = partition(enrichedStream, ingestionProperties.batchSize()).toList();
        int totalChunks = writeBatches(metadata, batches);

        if (totalChunks == 0) {
            throw new IllegalStateException("Document parsing resulted in zero chunks. The document may be empty or unscannable.");
        }

        log.info("Successfully processed document for vectorization [id={}, name={}, chunks={}]", metadata.getId(), metadata.getFilename(), totalChunks);
        return totalChunks;
    }

    /**
     * Runs one virtual thread per batch. {@code StructuredTaskScope} would express this more directly but
     * is still a preview API on this JDK, and preview class files would pin the build to exactly Java 26
     * and require --enable-preview on the launcher too.
     */
    private int writeBatches(DocumentMetadata metadata, List<List<Document>> batches) {
        if (batches.isEmpty()) {
            return 0;
        }

        // Captured on the caller's thread so the batch threads inherit the trace context. Without this
        // their log lines reach Loki with an empty traceId and the logs/traces correlation breaks.
        ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
        AtomicBoolean aborted = new AtomicBoolean(false);

        try {
            // The first batch is written on the calling thread, which both does real work and warms up
            // Ollama. Ollama loads the embedding model lazily, and requests that arrive while its model
            // runner is still starting are proxied to a port nothing is listening on yet; that surfaces
            // as a connection-refused message wrapped in an HTTP 400, which Spring AI treats as
            // non-transient and will not retry. Fanning out only after one request has completed means
            // the runner is loaded and listening before any concurrency arrives.
            int totalChunks = writeBatch(metadata, batches.getFirst(), aborted);

            List<Callable<Integer>> tasks = batches.subList(1, batches.size())
                                                   .stream()
                                                   .map(batch -> snapshot.wrap((Callable<Integer>) () -> writeBatch(metadata, batch, aborted)))
                                                   .toList();

            // Virtual threads, so one per batch is fine; the semaphore inside writeBatch is what bounds
            // the load on the connection pool. close() on the try-with-resources awaits termination.
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
     * Retries a batch only when the failure is Ollama's model runner being unreachable. Spring AI's own
     * retry cannot cover this: Ollama reports the unreachable runner as HTTP 400, which maps to
     * {@code NonTransientAiException} and is deliberately never retried. Re-adding the same batch is safe
     * because the vector store upserts on chunk id, so a partially applied attempt is overwritten.
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
                // A sibling batch already failed and the whole document is going to be rolled back,
                // so there is nothing to gain by embedding and writing this one.
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
     * Ollama surfaces an unreachable model runner as a Go dial error embedded in an HTTP 400 body, so the
     * message text is the only signal available. Matching narrowly keeps genuine 400s (malformed request,
     * oversized input) failing fast instead of burning the retry budget on a permanent error.
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
     * Compensating action for a partially written document. Batches commit independently, so this is what
     * restores the guarantee that a failed ingestion leaves no chunks behind.
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

        // 1. Enrich metadata on each chunk lazily as part of the stream
        AtomicInteger chunkIndex = new AtomicInteger(0);
        // The section a chunk belongs to is carried forward from the chunks before it, which relies on
        // the stream being sequential and in document order - the same assumption chunkIndex makes.
        AtomicReference<@Nullable String> currentSection = new AtomicReference<>();
        return documentStream.map(chunk -> {
            Map<String, Object> newMetadata = new HashMap<>(chunk.getMetadata());
            newMetadata.put(ChunkMetadata.DOCUMENT_ID, metadata.getId().toString());
            newMetadata.put(ChunkMetadata.FILE_NAME, metadata.getFilename());
            newMetadata.put(ChunkMetadata.CONTENT_TYPE, metadata.getContentType());
            newMetadata.put(ChunkMetadata.CHUNK_INDEX, chunkIndex.getAndIncrement());
            newMetadata.put(ChunkMetadata.PIPELINE_VERSION, pipelineVersion);
            List<String> headings = CitationResolver.sectionHeadings(chunk.getText());
            // The section the chunk's first line belongs to, read before this chunk's own headings move it
            // on - named in the header only when the chunk does not open with that heading itself.
            String openedIn = CitationResolver.opensWithSectionHeading(chunk.getText()) ? null : currentSection.get();
            String section = headings.isEmpty() ? currentSection.get() : headings.getFirst();
            if (!headings.isEmpty()) {
                currentSection.set(headings.getLast());
            }
            if (section != null) {
                newMetadata.put(ChunkMetadata.SECTION, section);
            }

            // Normalize page number metadata
            Object pageNumber = chunk.getMetadata().get("page_number");
            if (pageNumber == null) {
                pageNumber = chunk.getMetadata().get(ChunkMetadata.PAGE_NUMBER);
            }
            if (pageNumber != null) {
                newMetadata.put(ChunkMetadata.PAGE_NUMBER, pageNumber);
            }
            return new Document(citationHeader(metadata.getFilename(), pageNumber, openedIn) + chunk.getText(), newMetadata);
        });
    }

    /**
     * Builds the citation line prepended to every chunk's text, e.g. {@code [manual.pdf, p. 590]}.
     *
     * <p>This is deliberately part of the chunk <em>text</em>, not just its metadata, because
     * {@code QuestionAnswerAdvisor} builds the RAG context with {@code Document::getText} and discards
     * metadata entirely - so a page number that lives only in metadata can never reach the model, and the
     * system prompt's request to cite page numbers is unsatisfiable. Three call sites read {@code getText()}
     * and all three are affected on purpose:
     * <ul>
     *   <li>{@code OllamaEmbeddingModel.embed(Document)} - the header is embedded along with the content;</li>
     *   <li>{@code PgVectorStore} - the stored {@code content} column includes the header;</li>
     *   <li>{@code QuestionAnswerAdvisor} - the header reaches the prompt, which is the point.</li>
     * </ul>
     *
     * <p>Known costs, accepted when this was chosen over formatting the citation at prompt-assembly time:
     * the filename is identical on every chunk, so a constant prefix on every vector compresses the spread
     * between them (the measured top-5 band was 0.8194-0.7927 before this); the page number varies, adding a
     * numeric signal unrelated to meaning; and the stored text is no longer verbatim what the document said,
     * so anything reading chunks back must strip this line. Chunks written before this existed carry no
     * header and cannot be cited - re-ingest a document to make its citations work.
     *
     * <p>A chunk that starts part-way through a section gets a second line naming it, {@code Section:
     * Customizing the Management Server Port}, without its number so the model is not offered a section
     * number where it should cite a page. Chunk boundaries fall wherever the budget runs out, so a chunk
     * can hold an example with nothing saying what it configures: on 6 Oct 2026 the page 301 chunk was
     * "Properties / management.server.port=8081 / Yaml ...", its heading left in the chunk before. Asked
     * how to fix "port 8080 already in use", gemma4:e2b copied that example as the application's port in
     * 7 of 10 answers, and a system-prompt rule telling it to check which component an example configures
     * changed nothing (4 of 5, at both placements tried) - it cannot check what the passage never says.
     * With the section named beside the passage it did so in 0 of 10, while "how do I move the actuator
     * to another port" still got management.server.port in 5 of 5. The line is embedded too, so the
     * chunk's vector carries its subject as well.
     */
    private static String citationHeader(String fileName, Object pageNumber, @Nullable String section) {
        // Tika sources (DOCX/XLSX/PPTX/HTML) have no page attribution, so the page half is omitted rather
        // than written as a guess - a wrong citation is worse than an absent one.
        String citation = pageNumber != null
                ? "[%s, p. %s]".formatted(fileName, pageNumber)
                : "[%s]".formatted(fileName);
        // "\n\n", not System.lineSeparator(): this string is persisted in the vector store's content column
        // and embedded, so letting it follow the host OS would make the stored corpus differ between a
        // Windows dev machine (CRLF) and a Linux deployment for the same source document.
        if (section == null) {
            return citation + "\n\n";
        }
        String title = section.replaceFirst("^[\\d.]+\\s*", "");
        return citation + "\n" + CitationParser.SECTION_LINE_PREFIX + title + "\n\n";
    }

    // Helper method to partition a stream into batches
    private <T> Stream<List<T>> partition(Stream<T> source, int size) {
        final AtomicInteger counter = new AtomicInteger(0);
        return source.collect(Collectors.groupingBy(_ -> counter.getAndIncrement() / size)).values().stream();
    }
}
