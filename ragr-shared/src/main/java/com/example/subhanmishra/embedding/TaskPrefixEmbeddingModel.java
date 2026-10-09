package com.example.subhanmishra.embedding;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/**
 * Adds the embedding model's task prefix to every text sent to it. nomic-embed-text was trained with
 * {@code "search_document: "} on passages and {@code "search_query: "} on questions, and Ollama adds
 * neither - its template passes text through unchanged.
 *
 * <p>Each application embeds only one kind of text - ragr-ingest passages, ragr-app questions - so each
 * wraps its model with one fixed prefix. Every path {@code PgVectorStore} takes ends in {@link #call},
 * the one place the prefix is added.
 *
 * <p>Only the request changes; the stored text is untouched, so nothing reading chunks back has a
 * prefix to remove.
 */
public final class TaskPrefixEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final String prefix;

    public TaskPrefixEmbeddingModel(EmbeddingModel delegate, String prefix) {
        this.delegate = delegate;
        this.prefix = prefix;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> prefixed = request.getInstructions().stream().map(text -> prefix + text).toList();
        return delegate.call(new EmbeddingRequest(prefixed, request.getOptions()));
    }

    @Override
    public float[] embed(Document document) {
        String text = getEmbeddingContent(document);
        if (text == null) {
            throw new IllegalStateException("Document " + document.getId() + " has no text to embed");
        }
        return embed(text); // through call(), so this is prefixed too
    }

    @Override
    public @Nullable String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    /**
     * Asks the wrapped model, which knows its size. The default would embed a test string to find out.
     */
    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
