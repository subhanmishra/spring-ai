package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.DocumentMetadataHistory;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentMetadataHistoryRepository extends ListCrudRepository<DocumentMetadataHistory, UUID> {
}