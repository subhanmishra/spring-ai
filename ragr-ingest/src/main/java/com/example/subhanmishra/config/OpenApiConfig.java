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
        return new OpenAPI().info(new Info().title("DocAI — document ingestion")
                            .description("Upload, parse and index documents: multi-format parsing, chunking, and nomic-embed-text embeddings written to PostgreSQL pgvector. Questions are answered by the chat service on port 8080.")
                            .version("1.0.0")
                            .contact(new Contact().name("Subhankar Mishra")
                                                  .email("subhan.mishra@gmail.com")
                                                  .url("https://subhanmishra.com")));
    }
}
