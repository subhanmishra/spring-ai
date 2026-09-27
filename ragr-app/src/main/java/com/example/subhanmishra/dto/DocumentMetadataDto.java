package com.example.subhanmishra.dto;

import com.example.subhanmishra.entity.DocumentStatus;
import com.example.subhanmishra.service.provenance.PipelineSettings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record DocumentMetadataDto(UUID id, String filename, String contentType, Long fileSize, Integer totalPages,
                                  Integer totalChunks, DocumentStatus status, String errorMessage,
                                  Instant createdAt, Instant updatedAt,

                                  @Schema(description = "Ingestion pipeline version that produced this "
                                          + "document's chunks. Null for documents ingested before "
                                          + "provenance was recorded.")
                                  Integer pipelineVersion,

                                  @Schema(description = "The output-affecting settings in force when this "
                                          + "document was ingested")
                                  PipelineSettings pipelineSettings,

                                  @Schema(description = "True when this document's stored chunks differ from "
                                          + "what the same source file would produce now. Stale chunks cannot "
                                          + "be corrected in place - the document has to be ingested again.")
                                  boolean stale,

                                  @Schema(description = "Why the document is stale, naming the version gap or "
                                          + "the differing settings. Null when it is current.",
                                          example = "ingested with different settings: chunkSize")
                                  String staleReason) {
}
