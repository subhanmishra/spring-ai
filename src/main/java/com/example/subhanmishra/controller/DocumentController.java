package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.DocumentHistoryDto;
import com.example.subhanmishra.dto.DocumentMetadataDto;
import com.example.subhanmishra.dto.DocumentResponseDto;
import com.example.subhanmishra.service.DocumentHistoryService;
import com.example.subhanmishra.service.DocumentMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController()
@RequestMapping("/api/v1/documents")
@Tag(
        name = "Document Management",
        description = "Endpoints for uploading, listing and managing documents and their vectors embeddings."
)
public class DocumentController {

    private final DocumentMetadataService documentService;
    private final DocumentHistoryService historyService;

    public DocumentController(DocumentMetadataService documentService, DocumentHistoryService historyService) {
        this.documentService = documentService;
        this.historyService = historyService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload and index a document(PDF, DOCX, TEXT, MD, CSV)",
            description = "This api is used to upload and index documents files."

    )
    public ResponseEntity<DocumentResponseDto> uploadDocument(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.documentService.uploadAndProcess(file));
    }


    //    api to upload multiple documents
    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload and index multiple documents simultaneously",
            description = "This api is used to upload and index multiple documents."
    )
    public ResponseEntity<List<DocumentResponseDto>> uploadMultipole(@RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.uploadMultipleDocuments(files));
    }

    //    list all uploaded documents
    @GetMapping
    @Operation(summary = "List all uploaded documents and their indexing status")
    public ResponseEntity<List<DocumentMetadataDto>> getAllDocuments() {
        return ResponseEntity.ok(documentService.getAllDocuments());
    }


    @GetMapping("/{id}")
    @Operation(summary = "Get metadata of a specific document by ID")
    public ResponseEntity<DocumentMetadataDto> getDocumentById(@PathVariable UUID id) {
        return ResponseEntity.ok(documentService.getDocumentById(id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get the processing history of a document",
            description = "Returns every status transition recorded for the document, oldest first, with "
                    + "the details logged at each step - including the error message on a FAILED entry. "
                    + "History outlives the document it describes: deleting a document removes its "
                    + "metadata and vector chunks but keeps the trail, so this still answers for a deleted "
                    + "document and reports documentExists: false. 404 only when no history exists at all.")
    public ResponseEntity<DocumentHistoryDto> getDocumentHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(historyService.getHistory(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document and purge its vector embeddings from vector store")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {

        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

}
