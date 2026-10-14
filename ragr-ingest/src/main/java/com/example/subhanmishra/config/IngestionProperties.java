package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * How documents are parsed, chunked and written to the vector store. Retrieval settings live in
 * ragr-app ({@code app.rag.*}).
 *
 * <p>Changing {@code chunkSize}, {@code minChunkSizeChars}, {@code minChunkLengthToEmbed},
 * {@code maxEmbedTokens}, {@code tableDetection} or {@code markdown} changes what is stored, so the
 * corpus must be re-ingested. Batching, concurrency and retry only change the speed.
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
                                  TableDetection tableDetection,
                                  @DefaultValue Markdown markdown) {

    /**
     * Bump this whenever code changes what a chunk contains - a new stripper, a different split, another
     * header line. No setting changes in that case, so without the bump the pipeline version would not.
     */
    public static final int PARSER_REVISION = 5;

    /**
     * How Markdown files are read.
     *
     * @param dropTabLabels example tabs removed when their group also has a {@code Java} tab - the Kotlin
     *                      and XML versions of the same example. Compared ignoring case; empty keeps all.
     */
    public record Markdown(@DefaultValue({"Kotlin", "Xml"}) List<String> dropTabLabels) {
    }

    /**
     * A short hash of everything that decides a chunk's content and vector: the chunk settings,
     * {@link #PARSER_REVISION}, the embedding model and its task prefix. The prefix is never stored, but it
     * moves every vector just as a new model would. Batching, concurrency and retry are left out.
     */
    public String pipelineVersion(String embeddingModel, String embeddingTaskPrefix) {
        String inputs = String.join("|", String.valueOf(PARSER_REVISION), String.valueOf(chunkSize),
                                    String.valueOf(minChunkSizeChars), String.valueOf(minChunkLengthToEmbed),
                                    String.valueOf(maxEmbedTokens), String.valueOf(tableDetection),
                                    String.valueOf(markdown.dropTabLabels()), embeddingModel, embeddingTaskPrefix);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(inputs.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * How, if at all, tables are found in PDFs ({@code auto} in application.yaml). Anything but {@code OFF}
     * replaces the PDF reader entirely, so it changes how prose is read too.
     */
    public enum TableDetection {
        /** Read PDFs with {@code PagePdfDocumentReader}, one prose block per page. */
        OFF,
        /** Choose per page: ruled pages take columns from the rules, unruled ones from text alignment. */
        AUTO,
        /** Force columns from drawn rules - useful for isolating a misdetection. */
        LATTICE,
        /** Force columns from text alignment. */
        STREAM
    }
}
