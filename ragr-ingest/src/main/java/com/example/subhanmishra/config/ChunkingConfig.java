package com.example.subhanmishra.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkingConfig {

    @Bean
    public TokenTextSplitter tokenTextSplitter(IngestionProperties ingestionProperties) {
        return TokenTextSplitter.builder()
                .withChunkSize(ingestionProperties.chunkSize())
                .withMinChunkSizeChars(ingestionProperties.minChunkSizeChars())
                // Deliberately 1, not app.ingestion.min-chunk-length-to-embed. TokenTextSplitter enforces
                // that floor by DISCARDING a short piece, and the short pieces are ones it manufactures by
                // cutting an over-budget chunk - which silently deleted real content. The floor is applied
                // in DocumentParserService instead, by merging a short piece into the one before it.
                .withMinChunkLengthToEmbed(1)
                .withMaxNumChunks(ingestionProperties.maxNumChunks())
                .withKeepSeparator(true)
                .build();
    }
}
