package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
