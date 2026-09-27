package com.example.subhanmishra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vector_store")
public class VectorStoreEntity {

    @Id
    private UUID id; // Assuming 'id' is the primary key of the vector_store table

    // Spring Data JDBC requires an @Id, even if we don't use it for our custom delete query.
    // We don't need to map 'embedding' or 'metadata' directly for the delete operation
    // as we'll use a custom query targeting metadata->>'documentId'.

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}