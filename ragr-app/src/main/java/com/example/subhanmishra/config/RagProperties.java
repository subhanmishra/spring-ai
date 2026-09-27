package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval settings: how many chunks the chat path asks the vector store for, and how similar each
 * must be. The chunking and indexing settings that decide what is stored belong to ragr-ingest.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(int topK,
                            double similarityThreshold) {
}
