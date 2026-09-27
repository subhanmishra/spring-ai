package com.example.subhanmishra.service.provenance;

import com.example.subhanmishra.config.RagProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings that determined what a document's stored chunks look like, captured at ingestion time.
 *
 * <p><strong>Only settings that change what gets stored belong here.</strong> {@code batchSize},
 * {@code ingestionConcurrency}, {@code ingestionMaxAttempts} and {@code ingestionRetryBackoff} govern
 * throughput and do not alter a single stored byte; {@code topK} and {@code similarityThreshold} act
 * at retrieval time, long after the chunks exist. Recording any of them would manufacture false
 * staleness - and {@code batch-size} in particular is expected to be retuned whenever chunk sizing
 * changes, so including it would mark the whole corpus stale on exactly the occasions when it is not.
 *
 * <p>This is a record rather than a {@code Map} so that adding a setting is a compile-time event and
 * the comparison below cannot silently miss a field.
 */
public record PipelineSettings(int chunkSize,
                               int minChunkSizeChars,
                               int minChunkLengthToEmbed,
                               int maxNumChunks,
                               int maxEmbedTokens,
                               String tableDetection,
                               String embeddingModel,
                               int dimensions) {

    public static PipelineSettings from(RagProperties rag, String embeddingModel, int dimensions) {
        return new PipelineSettings(rag.chunkSize(),
                                    rag.minChunkSizeChars(),
                                    rag.minChunkLengthToEmbed(),
                                    rag.maxNumChunks(),
                                    rag.maxEmbedTokens(),
                                    rag.tableDetection().name(),
                                    embeddingModel,
                                    dimensions);
    }

    /**
     * Names the settings that differ between this snapshot and another, for reporting <em>why</em> a
     * document is stale. An empty list means the two are equivalent.
     */
    public List<String> differencesFrom(PipelineSettings other) {
        List<String> differences = new ArrayList<>();
        if (chunkSize != other.chunkSize) {
            differences.add("chunkSize");
        }
        if (minChunkSizeChars != other.minChunkSizeChars) {
            differences.add("minChunkSizeChars");
        }
        if (minChunkLengthToEmbed != other.minChunkLengthToEmbed) {
            differences.add("minChunkLengthToEmbed");
        }
        if (maxNumChunks != other.maxNumChunks) {
            differences.add("maxNumChunks");
        }
        if (maxEmbedTokens != other.maxEmbedTokens) {
            differences.add("maxEmbedTokens");
        }
        if (!java.util.Objects.equals(tableDetection, other.tableDetection)) {
            differences.add("tableDetection");
        }
        if (!java.util.Objects.equals(embeddingModel, other.embeddingModel)) {
            differences.add("embeddingModel");
        }
        if (dimensions != other.dimensions) {
            differences.add("dimensions");
        }
        return differences;
    }
}
