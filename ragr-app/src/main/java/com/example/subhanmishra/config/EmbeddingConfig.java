package com.example.subhanmishra.config;

import com.example.subhanmishra.embedding.TaskPrefixEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingConfig {

    /**
     * Ollama's model, wrapped so every question - from chat and from the diagnostics endpoint - is embedded
     * with {@code app.embedding.task-prefix}. Primary, so {@code PgVectorStore} uses it. Ollama's own bean
     * is still created, since its auto-configuration backs off only for another
     * {@link OllamaEmbeddingModel}.
     */
    @Bean
    @Primary
    EmbeddingModel taskPrefixEmbeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel,
                                            @Value("${app.embedding.task-prefix}") String taskPrefix) {
        return new TaskPrefixEmbeddingModel(ollamaEmbeddingModel, taskPrefix);
    }
}
