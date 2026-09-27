package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.service.provenance.PipelineProvenance;
import com.example.subhanmishra.service.provenance.PipelineSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Supplies the provenance to stamp on a document being ingested now, and judges whether an already
 * ingested one is out of date.
 *
 * <p>The embedding model and vector dimensions are bound from configuration rather than read off the
 * {@code OllamaEmbeddingModel} bean, which exposes no public accessor for its default options in
 * {@code spring-ai-ollama-2.0.1}.
 */
@Service
public class PipelineProvenanceService {

    private final PipelineSettings currentSettings;

    public PipelineProvenanceService(RagProperties ragProperties,
                                     @Value("${spring.ai.ollama.embedding.model}") String embeddingModel,
                                     @Value("${spring.ai.vectorstore.pgvector.dimensions}") int dimensions) {
        this.currentSettings = PipelineSettings.from(ragProperties, embeddingModel, dimensions);
    }

    /** The settings in force right now - what a document ingested today would be stamped with. */
    public PipelineSettings currentSettings() {
        return currentSettings;
    }

    public int currentVersion() {
        return PipelineProvenance.CURRENT_VERSION;
    }

    /**
     * Why the given document's chunks are out of date, or {@code null} when they are current.
     */
    public String stalenessReason(Integer recordedVersion, PipelineSettings recordedSettings) {
        return new PipelineProvenance(recordedVersion, recordedSettings).stalenessAgainstCurrent(currentSettings);
    }
}
