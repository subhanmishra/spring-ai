package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retrieval settings. What is stored, and how, belongs to ragr-ingest.
 *
 * @param topK                the most chunks the prompt receives
 * @param similarityThreshold the lowest score a chunk needs to reach the prompt
 * @param poolSize            how many chunks the one vector query fetches; those beyond the prompt go to
 *                            evaluation. At least top-k
 * @param poolFloor           the lowest score a chunk needs to be in the pool. 0 keeps the pool full, so a
 *                            relevant chunk just under the threshold is still seen
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
