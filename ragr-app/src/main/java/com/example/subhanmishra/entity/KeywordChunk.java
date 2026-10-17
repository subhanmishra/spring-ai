package com.example.subhanmishra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * A chunk found by keyword search. Read-only: ragr-ingest owns {@code vector_store}.
 *
 * @param metadata the JSONB metadata as text
 * @param distance cosine distance to the question, computed by the query
 */
@Table("vector_store")
public record KeywordChunk(@Id UUID id, String content, String metadata, double distance) {
}
