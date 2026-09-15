package com.example.subhanmishra.service;

import com.example.subhanmishra.entity.DocumentMetadataHistory;
import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.repository.DocumentMetadataHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.UUID;

@Service
public class DocumentHistoryService {

    private final DocumentMetadataHistoryRepository historyRepository;

    public DocumentHistoryService(DocumentMetadataHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHistory(UUID documentId, DocumentStatus status, String details) {
        DocumentMetadataHistory historyRecord = DocumentMetadataHistory.builder()
                .documentMetadataId(documentId)
                .status(status)
                .details(details)
                .createdAt(Instant.now())
                .build();
        historyRepository.save(historyRecord);
    }
}