package com.example.subhanmishra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vector_store")
public class VectorStoreEntity {

    @Id
    private UUID id; // Assuming 'id' is the primary key of the vector_store table

    // Mapped only for the delete-by-document query, which reads metadata->>'documentId'. Spring Data
    // JDBC needs an @Id; the other columns are not mapped.

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}