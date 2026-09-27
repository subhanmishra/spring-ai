package com.example.subhanmishra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Table(name = "document_metadata")
public class DocumentMetadata {

    @Id
    private UUID id;
    private String filename;
    private String contentType;
    private Long fileSize;
    private Integer totalPages;
    private Integer totalChunks;
    private DocumentStatus status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public DocumentMetadata() {
    }

    private DocumentMetadata(Builder builder) {
        this.id = builder.id;
        this.filename = builder.filename;
        this.contentType = builder.contentType;
        this.fileSize = builder.fileSize;
        this.totalPages = builder.totalPages;
        this.totalChunks = builder.totalChunks;
        this.status = builder.status;
        this.errorMessage = builder.errorMessage;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .id(this.id)
                .filename(this.filename)
                .contentType(this.contentType)
                .fileSize(this.fileSize)
                .totalPages(this.totalPages)
                .totalChunks(this.totalChunks)
                .status(this.status)
                .errorMessage(this.errorMessage)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt);
    }

    public static class Builder {
        private UUID id;
        private String filename;
        private String contentType;
        private Long fileSize;
        private Integer totalPages;
        private Integer totalChunks;
        private DocumentStatus status;
        private String errorMessage;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder fileSize(Long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public Builder totalPages(Integer totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        public Builder totalChunks(Integer totalChunks) {
            this.totalChunks = totalChunks;
            return this;
        }

        public Builder status(DocumentStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public DocumentMetadata build() {
            return new DocumentMetadata(this);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public Integer getTotalPages() {
        return totalPages;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DocumentMetadata that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(filename, that.filename) && Objects.equals(contentType, that.contentType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, filename, contentType);
    }

    @Override
    public String toString() {
        return "DocumentMetadata{" +
                "id=" + id +
                ", filename='" + filename + '\'' +
                ", contentType='" + contentType + '\'' +
                ", fileSize=" + fileSize +
                ", totalPages=" + totalPages +
                ", totalChunks=" + totalChunks +
                ", status=" + status +
                ", errorMessage='" + errorMessage + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}