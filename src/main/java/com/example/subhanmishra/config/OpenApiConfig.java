package com.example.subhanmishra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("DocAI — AI Document Intelligence & RAG backend")
                            .description("REST API for DocAI: Multi-format document ingestion, vector embeddings with PostgreSQL pgvector and nomic-embed-text, and hybrid conversational Q&A with Gemma 4.")
                            .version("1.0.0")
                            .contact(new Contact().name("Subhankar Mishra")
                                                  .email("subhan.mishra@gmail.com")
                                                  .url("https://subhanmishra.com")));
    }
}