package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(int chunkSize,
                            int minChunkSizeChars,
                            int minChunkLengthToEmbed,
                            int maxNumChunks,
                            int topK,
                            double similarityThreshold) {
}