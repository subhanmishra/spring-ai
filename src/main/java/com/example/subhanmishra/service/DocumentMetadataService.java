package com.example.subhanmishra.service;

import com.example.subhanmishra.dto.DocumentMetadataDto;
import com.example.subhanmishra.dto.DocumentResponseDto;
import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.repository.DocumentMetadataRepository;
import com.example.subhanmishra.repository.VectorStoreRepository;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

@Service
public class DocumentMetadataService {

    private static final Logger log = LoggerFactory.getLogger(DocumentMetadataService.class);

    private final DocumentMetadataRepository documentMetadataRepo;
    private final DocumentParserService parserService;
    private final DocumentIngestionService ingestionService;
    private final ModelMapper modelMapper;
    private final VectorStoreRepository vectorStoreRepository;
    private final DocumentHistoryService historyService;

    public DocumentMetadataService(DocumentMetadataRepository documentMetadataRepo,
                                   DocumentParserService parserService,
                                   DocumentIngestionService ingestionService,
                                   ModelMapper modelMapper,
                                   VectorStoreRepository vectorStoreRepository,
                                   DocumentHistoryService historyService) {
        this.documentMetadataRepo = documentMetadataRepo;
        this.parserService = parserService;
        this.ingestionService = ingestionService;
        this.modelMapper = modelMapper;
        this.vectorStoreRepository = vectorStoreRepository;
        this.historyService = historyService;
    }

    public DocumentResponseDto uploadAndProcess(MultipartFile file) {

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octat-stream";

        // 1. Create the initial metadata record. The status here is just an initial marker.
        DocumentMetadata documentMetadata = DocumentMetadata.builder()
                .filename(fileName)
                .contentType(contentType)
                .status(DocumentStatus.UPLOADING) // Initial non-terminal status
                .fileSize(file.getSize())
                .createdAt(Instant.now())
                .build();
        documentMetadata = documentMetadataRepo.save(documentMetadata);

        // 2. Record the UPLOADING status in the history table.
        historyService.recordHistory(documentMetadata.getId(), DocumentStatus.UPLOADING, "Document upload initiated.");

        int chunksCreated = 0;
        try {
            // 3. Parse and ingest the document.
            Map<String, Object> parseResult = parserService.parse(file);
            chunksCreated = ingestionService.ingest(documentMetadata, parseResult);

            int totalPages = (int) parseResult.getOrDefault("totalPages", 0);

            // 4. On success, update the main record to the terminal INDEXED status.
            DocumentMetadata finalMetadata = documentMetadata.toBuilder()
                    .status(DocumentStatus.INDEXED)
                    .totalChunks(chunksCreated)
                    .totalPages(totalPages)
                    .updatedAt(Instant.now())
                    .build();
            documentMetadataRepo.save(finalMetadata);

            // 5. Record the INDEXED status in the history table.
            historyService.recordHistory(documentMetadata.getId(), DocumentStatus.INDEXED, "Document successfully indexed.");

            documentMetadata = finalMetadata;

        } catch (Exception e) {
            log.error("Failed to process and ingest document [id={}]", documentMetadata.getId(), e);

            String errorMessage = Optional.ofNullable(e.getMessage()).orElse("An unknown error occurred during processing.");

            // 6. On failure, update the main record to the terminal FAILED status.
            DocumentMetadata failedMetadata = documentMetadata.toBuilder()
                    .status(DocumentStatus.FAILED)
                    .errorMessage(errorMessage)
                    .updatedAt(Instant.now())
                    .build();
            documentMetadataRepo.save(failedMetadata);

            // 7. Record the FAILED status in the history table.
            historyService.recordHistory(documentMetadata.getId(), DocumentStatus.FAILED, errorMessage);

            // Re-throw the exception to signal failure to the API caller.
            throw new DocumentProcessingException("Failed to process document: " + errorMessage, e);
        }

        // 8. Return the response DTO for successful processing.
        return DocumentResponseDto.builder()
                .id(documentMetadata.getId())
                .fileName(documentMetadata.getFilename())
                .fileSize(documentMetadata.getFileSize())
                .chunksCreated(chunksCreated)
                .status(documentMetadata.getStatus())
                .message("Document successfully processed and indexed.")
                .build();
    }

    public List<DocumentResponseDto> uploadMultipleDocuments(List<MultipartFile> files) {
        List<DocumentResponseDto> responseDtos = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                DocumentResponseDto result = this.uploadAndProcess(file);
                responseDtos.add(result);
            } catch (DocumentProcessingException e) {
                log.warn("Skipping file: {} due to processing error: {}", file.getOriginalFilename(), e.getMessage());
            }
        }
        return responseDtos;
    }

    public List<DocumentMetadataDto> getAllDocuments() {
        List<DocumentMetadata> allDocuments = documentMetadataRepo.findAllByOrderByCreatedAtDesc();
        return allDocuments.stream()
                .map(documentMetadata -> modelMapper.map(documentMetadata, DocumentMetadataDto.class))
                .toList();
    }

    public DocumentMetadataDto getDocumentById(UUID id) {
        DocumentMetadata documentMetadata = documentMetadataRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Document with given id not found !!"));
        return modelMapper.map(documentMetadata, DocumentMetadataDto.class);
    }

    @Transactional
    public void deleteDocument(UUID id) {
        DocumentMetadata documentMetadata = documentMetadataRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Document with ID " + id + " not found."));

        documentMetadataRepo.delete(documentMetadata);
        vectorStoreRepository.deleteByDocumentId(id.toString());
        log.info("Deleted document {} and its associated vector chunks.", id);
    }
}