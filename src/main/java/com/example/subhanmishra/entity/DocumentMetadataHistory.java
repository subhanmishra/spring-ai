package com.example.subhanmishra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Table("document_metadata_history")
public class DocumentMetadataHistory {

    @Id
    private UUID id;
    private UUID documentMetadataId;
    private DocumentStatus status;
    private String details;
    private LocalDateTime createdAt;

    // Private constructor for the builder
    private DocumentMetadataHistory(Builder builder) {
        this.id = builder.id; // The ID will be null when built from the service
        this.documentMetadataId = builder.documentMetadataId;
        this.status = builder.status;
        this.details = builder.details;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().id(this.id).details(this.details).documentMetadataId(this.documentMetadataId).status(this.status).createdAt(this.createdAt);
    }

    public static class Builder {
        private UUID id;
        private UUID documentMetadataId;
        private DocumentStatus status;
        private String details;
        private LocalDateTime createdAt;


        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder documentMetadataId(UUID documentMetadataId) {
            this.documentMetadataId = documentMetadataId;
            return this;
        }

        public Builder status(DocumentStatus status) {
            this.status = status;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }


        public DocumentMetadataHistory build() {
            return new DocumentMetadataHistory(this);
        }
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getDocumentMetadataId() {
        return documentMetadataId;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DocumentMetadataHistory that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(documentMetadataId, that.documentMetadataId) && status == that.status && Objects.equals(details, that.details) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, documentMetadataId, status, details, createdAt);
    }
}