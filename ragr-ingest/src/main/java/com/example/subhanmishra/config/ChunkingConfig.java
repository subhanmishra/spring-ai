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
                // Deliberately 1, not app.ingestion.min-chunk-length-to-embed: the splitter applies its
                // floor by DELETING short pieces, which are often real content left over from its own
                // cuts. DocumentParserService applies the floor instead, by merging.
                .withMinChunkLengthToEmbed(1)
                .withMaxNumChunks(ingestionProperties.maxNumChunks())
                .withKeepSeparator(true)
                .build();
    }
}
