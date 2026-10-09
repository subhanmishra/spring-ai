package com.example.subhanmishra.service;

import com.example.subhanmishra.dto.DocumentHistoryDto;
import com.example.subhanmishra.dto.DocumentHistoryEntryDto;
import com.example.subhanmishra.entity.DocumentMetadataHistory;
import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.repository.DocumentMetadataHistoryRepository;
import com.example.subhanmishra.repository.DocumentMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentHistoryService {

    private final DocumentMetadataHistoryRepository historyRepository;
    private final DocumentMetadataRepository documentMetadataRepository;

    public DocumentHistoryService(DocumentMetadataHistoryRepository historyRepository,
                                  DocumentMetadataRepository documentMetadataRepository) {
        this.historyRepository = historyRepository;
        this.documentMetadataRepository = documentMetadataRepository;
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

    /**
     * Reads back the audit trail for one document, oldest entry first.
     *
     * <p>The document need not still exist: history has no foreign key to it and outlives a delete, which
     * is why the table exists. The response says whether the document is still there.
     *
     * @throws ResourceNotFoundException when there is no history for the id at all
     */
    public DocumentHistoryDto getHistory(UUID documentId) {
        List<DocumentMetadataHistory> entries =
                historyRepository.findByDocumentMetadataIdOrderByCreatedAtAsc(documentId);

        if (entries.isEmpty()) {
            throw new ResourceNotFoundException("No history found for document with ID " + documentId + ".");
        }

        return new DocumentHistoryDto(documentId,
                                      documentMetadataRepository.existsById(documentId),
                                      entries.size(),
                                      entries.stream().map(DocumentHistoryService::toDto).toList());
    }

    private static DocumentHistoryEntryDto toDto(DocumentMetadataHistory entry) {
        return new DocumentHistoryEntryDto(entry.getId(),
                                           entry.getStatus(),
                                           entry.getDetails(),
                                           entry.getCreatedAt());
    }
}
