package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.RetrievalRequestDto;
import com.example.subhanmishra.dto.RetrievalResponseDto;
import com.example.subhanmishra.service.RetrievalDiagnosticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic and analytics endpoints, for operators rather than users.
 *
 * <p>Gated on the {@code dev} profile: outside it the bean is not registered and the paths do not
 * exist, which is a weaker guarantee than authentication but a stronger one than an unprotected route.
 * There is no Spring Security on the classpath; when there is, this gate should be replaced by a real
 * one rather than supplemented.
 *
 * <p>The whole of this controller reads. Nothing here writes to the vector store or to the metadata
 * tables, which is what makes it safe to point at a live corpus.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Profile("dev")
@Tag(name = "Admin Diagnostics",
     description = "Operator-facing checks against the live corpus. Registered only under the dev profile.")
public class AdminDiagnosticsController {

    private final RetrievalDiagnosticsService retrievalDiagnosticsService;

    public AdminDiagnosticsController(RetrievalDiagnosticsService retrievalDiagnosticsService) {
        this.retrievalDiagnosticsService = retrievalDiagnosticsService;
    }

    @PostMapping("/retrieval/search")
    @Operation(summary = "Show what the vector store returns for a query",
               description = "Runs the same similarity search the chat path runs and reports the chunks "
                       + "with their scores and metadata, without generating an answer. Use it to tell a "
                       + "retrieval failure from a generation failure: whether the passage the answer "
                       + "needed was never retrieved, or was retrieved and ignored. topK and "
                       + "similarityThreshold default to the configured app.rag values the chat path "
                       + "uses; override them to see what the threshold is excluding. The request is a "
                       + "POST so the query stays out of access logs and URL length limits.")
    public RetrievalResponseDto searchRetrieval(@Valid @RequestBody RetrievalRequestDto request) {
        return retrievalDiagnosticsService.search(request);
    }
}
