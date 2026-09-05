package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentMetadataRepository extends ListCrudRepository<DocumentMetadata, UUID> {

    List<DocumentMetadata> findByStatus(DocumentStatus status);

    List<DocumentMetadata> findAllByOrderByCreatedAtDesc();
}
