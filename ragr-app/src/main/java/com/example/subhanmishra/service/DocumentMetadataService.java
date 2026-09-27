package com.example.subhanmishra.service;

import com.example.subhanmishra.dto.DocumentMetadataDto;
import com.example.subhanmishra.dto.DocumentResponseDto;
import com.example.subhanmishra.entity.DocumentMetadata;
import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.exception.DocumentProcessingException;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.exception.UnsupportedDocumentTypeException;
import com.example.subhanmishra.repository.DocumentMetadataRepository;
import com.example.subhanmishra.repository.VectorStoreRepository;
import com.example.subhanmishra.service.parse.SupportedDocumentTypes;
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
    private final PipelineProvenanceService provenanceService;

    public DocumentMetadataService(DocumentMetadataRepository documentMetadataRepo,
                                   DocumentParserService parserService,
                                   DocumentIngestionService ingestionService,
                                   ModelMapper modelMapper,
                                   VectorStoreRepository vectorStoreRepository,
                                   DocumentHistoryService historyService,
                                   PipelineProvenanceService provenanceService) {
        this.documentMetadataRepo = documentMetadataRepo;
        this.parserService = parserService;
        this.ingestionService = ingestionService;
        this.modelMapper = modelMapper;
        this.vectorStoreRepository = vectorStoreRepository;
        this.historyService = historyService;
        this.provenanceService = provenanceService;
    }

    public DocumentResponseDto uploadAndProcess(MultipartFile file) {

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octat-stream";

        // 0. Refuse types the parser cannot handle BEFORE anything is written. Doing this first is the
        // whole point: a file that was never a candidate should not leave a metadata row and a history
        // trail behind, and the caller should hear that the type was refused rather than that
        // processing broke somewhere deep in Tika.
        if (!SupportedDocumentTypes.isSupported(fileName, contentType)) {
            log.warn("Rejected unsupported upload: {} (contentType={})", fileName, contentType);
            throw new UnsupportedDocumentTypeException(
                    "Unsupported file type for '%s'. Supported types are: %s."
                            .formatted(fileName, SupportedDocumentTypes.describeAllowed()));
        }

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

            // 4. On success, update the main record to the terminal INDEXED status, stamping the
            // pipeline that produced these chunks. Only on this path: a FAILED document produced no
            // chunks, so provenance for it would describe nothing.
            DocumentMetadata finalMetadata = documentMetadata.toBuilder()
                    .status(DocumentStatus.INDEXED)
                    .totalChunks(chunksCreated)
                    .totalPages(totalPages)
                    .pipelineVersion(provenanceService.currentVersion())
                    .pipelineSettings(provenanceService.currentSettings())
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
            // The id is carried on the exception so the bulk path can report which file failed and
            // give the caller a handle to its history; a single upload just surfaces the message.
            throw new DocumentProcessingException("Failed to process document: " + errorMessage, e,
                                                  documentMetadata.getId());
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

    /**
     * Uploads each file in turn, reporting one result per input in the order supplied.
     *
     * <p>A failed file gets a {@code FAILED} entry rather than being dropped from the response. It used
     * to be omitted entirely, so a caller who sent ten files and got seven back could not tell which
     * three were missing or why - the only record was a server-side log line they could not see. The
     * entry carries the document's id, which is the handle to {@code /{id}/history} where the full
     * trail and error detail already live.
     *
     * <p>The loop is deliberately serial. Ollama pins embedding runners to a single slot regardless of
     * concurrency, so uploading in parallel would not embed any faster; it would only contend for the
     * Hikari pool that {@code app.rag.ingestion-concurrency} already sizes against that single slot.
     */
    public List<DocumentResponseDto> uploadMultipleDocuments(List<MultipartFile> files) {
        List<DocumentResponseDto> responseDtos = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                responseDtos.add(this.uploadAndProcess(file));
            } catch (DocumentProcessingException e) {
                log.warn("Failed to process file {} in batch: {}", file.getOriginalFilename(), e.getMessage());
                responseDtos.add(DocumentResponseDto.builder()
                                                    .id(e.getDocumentId())
                                                    .fileName(file.getOriginalFilename())
                                                    .fileSize(file.getSize())
                                                    .chunksCreated(0)
                                                    .status(DocumentStatus.FAILED)
                                                    .message(e.getMessage())
                                                    .build());
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