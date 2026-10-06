package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * How documents are parsed, chunked and written to the vector store. The retrieval settings the chat
 * path searches with ({@code app.rag.top-k}, {@code app.rag.similarity-threshold}) live in ragr-app.
 *
 * <p>The chunk-shaping settings here change what is stored: after changing {@code chunkSize},
 * {@code minChunkSizeChars}, {@code minChunkLengthToEmbed}, {@code maxEmbedTokens} or
 * {@code tableDetection}, the whole corpus has to be re-ingested. The batching, concurrency and retry
 * settings only change how fast the same chunks are written.
 */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(int chunkSize,
                                  int minChunkSizeChars,
                                  int minChunkLengthToEmbed,
                                  int maxNumChunks,
                                  int maxEmbedTokens,
                                  int batchSize,
                                  int concurrency,
                                  int maxAttempts,
                                  Duration retryBackoff,
                                  TableDetection tableDetection) {

    /**
     * Bump whenever parsing or ingestion code changes what a chunk contains - a new stripper, a different
     * block split, another line in the citation header - since no setting below would change to say so.
     * The pipeline version hashes it with them.
     */
    public static final int PARSER_REVISION = 3;

    /**
     * A short hash of everything that decides a chunk's content and embedding: the chunk-shaping settings,
     * {@link #PARSER_REVISION} and the embedding model. Batching, concurrency and retry are left out, as
     * they change only how fast the same chunks are written.
     */
    public String pipelineVersion(String embeddingModel) {
        String inputs = String.join("|", String.valueOf(PARSER_REVISION), String.valueOf(chunkSize),
                                    String.valueOf(minChunkSizeChars), String.valueOf(minChunkLengthToEmbed),
                                    String.valueOf(maxEmbedTokens), String.valueOf(tableDetection),
                                    embeddingModel);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(inputs.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * How, if at all, tables are recovered from PDFs. Off by default: it replaces the reader used for
     * every PDF, so it changes prose extraction as well as adding tables, and that is worth enabling
     * deliberately rather than inheriting.
     */
    public enum TableDetection {
        /** Read PDFs with {@code PagePdfDocumentReader}, one prose block per page, as before. */
        OFF,
        /** Choose per page: ruled pages take columns from the rules, unruled ones from text alignment. */
        AUTO,
        /** Force columns from drawn rules - useful for isolating a misdetection. */
        LATTICE,
        /** Force columns from text alignment. */
        STREAM
    }
}
