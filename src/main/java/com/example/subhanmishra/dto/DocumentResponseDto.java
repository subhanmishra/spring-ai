package com.example.subhanmishra.dto;

import com.example.subhanmishra.entity.DocumentStatus;

import java.util.UUID;

public record DocumentResponseDto(UUID id, String fileName, Long fileSize, DocumentStatus status, Integer chunksCreated,
                                  String message) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String fileName;
        private Long fileSize;
        private DocumentStatus status;
        private Integer chunksCreated;
        private String message;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder fileSize(Long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public Builder status(DocumentStatus status) {
            this.status = status;
            return this;
        }

        public Builder chunksCreated(Integer chunksCreated) {
            this.chunksCreated = chunksCreated;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public DocumentResponseDto build() {
            return new DocumentResponseDto(id, fileName, fileSize, status, chunksCreated, message);
        }
    }
}