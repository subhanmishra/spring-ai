package com.example.subhanmishra;

import com.example.subhanmishra.config.IngestionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Document ingestion: upload, parse, chunk, embed and write to the vector store that the chat service
 * reads. It owns the {@code public} schema's migrations, including {@code vector_store}.
 *
 * <p>Start it after ragr-app, which brings the compose stack up; this application has no Docker Compose
 * support of its own and never starts or stops containers.
 */
@SpringBootApplication
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
