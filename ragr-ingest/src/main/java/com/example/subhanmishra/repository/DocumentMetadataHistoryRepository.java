package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.DocumentMetadataHistory;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentMetadataHistoryRepository extends ListCrudRepository<DocumentMetadataHistory, UUID> {

    /**
     * The audit trail for one document, oldest entry first.
     *
     * <p>May return rows for a deleted document. That is the point of the table - there is deliberately
     * no foreign key - not corruption.
     */
    List<DocumentMetadataHistory> findByDocumentMetadataIdOrderByCreatedAtAsc(UUID documentMetadataId);
}