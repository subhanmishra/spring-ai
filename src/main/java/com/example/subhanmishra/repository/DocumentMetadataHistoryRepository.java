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
     * <p>There is deliberately no foreign key from {@code document_metadata_history} back to
     * {@code document_metadata}, so this can return rows for a document that has since been deleted.
     * That is the point of the table - the migration calls it an immutable audit log - and not a case
     * to treat as corruption.
     */
    List<DocumentMetadataHistory> findByDocumentMetadataIdOrderByCreatedAtAsc(UUID documentMetadataId);
}