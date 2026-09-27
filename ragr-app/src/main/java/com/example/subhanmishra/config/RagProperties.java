package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(int chunkSize,
                            int minChunkSizeChars,
                            int minChunkLengthToEmbed,
                            int maxNumChunks,
                            int maxEmbedTokens,
                            int topK,
                            double similarityThreshold,
                            int batchSize,
                            int ingestionConcurrency,
                            int ingestionMaxAttempts,
                            Duration ingestionRetryBackoff,
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