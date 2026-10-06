package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retrieval settings: how many chunks the chat path asks the vector store for, and how similar each
 * must be. The chunking and indexing settings that decide what is stored belong to ragr-ingest.
 *
 * @param topK                the most chunks the prompt receives
 * @param similarityThreshold the least score a chunk needs to reach the prompt
 * @param poolSize            how many candidates the one vector query fetches. Only the leading top-k
 *                            above the threshold reach the prompt; the rest travel to evaluation, which
 *                            measures recall against them. Must be at least top-k
 * @param poolFloor           the least score a candidate needs to be in the pool at all. 0 keeps the pool
 *                            full, so a relevant chunk scoring just under the threshold is still seen
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(int topK,
                            double similarityThreshold,
                            @DefaultValue("10") int poolSize,
                            @DefaultValue("0.0") double poolFloor) {

    public RagProperties {
        if (poolSize < topK) {
            throw new IllegalStateException("app.rag.pool-size (" + poolSize + ") must be at least app.rag.top-k ("
                                            + topK + ")");
        }
    }
}
