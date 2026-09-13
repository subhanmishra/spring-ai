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
                            Duration ingestionRetryBackoff) {
}