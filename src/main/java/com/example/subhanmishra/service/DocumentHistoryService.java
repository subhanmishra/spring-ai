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
     * <p>This deliberately does not require the document to still exist. There is no foreign key from
     * {@code document_metadata_history} back to {@code document_metadata}, and {@code deleteDocument}
     * removes only the metadata row, so a deleted document keeps its trail - which is the reason the
     * table exists. The response reports whether the document is still present so a caller can tell
     * the two situations apart rather than having to guess from the last status.
     *
     * @throws ResourceNotFoundException when no history exists for the id at all, which is the only
     *                                   case in which there is genuinely nothing to show
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
