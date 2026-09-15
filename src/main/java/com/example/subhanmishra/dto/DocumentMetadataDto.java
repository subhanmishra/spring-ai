package com.example.subhanmishra.dto;

import com.example.subhanmishra.entity.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentMetadataDto(UUID id, String filename, String contentType, Long fileSize, Integer totalPages,
                                  Integer totalChunks, DocumentStatus status, String errorMessage,
                                  Instant createdAt, Instant updatedAt) {
}