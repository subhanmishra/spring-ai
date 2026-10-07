package com.example.subhanmishra.embedding;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/**
 * Prepends the embedding model's task prefix to every text sent to it. nomic-embed-text was trained with
 * {@code "search_document: "} on passages and {@code "search_query: "} on queries, and Ollama adds
 * neither: its Modelfile template is {@code {{ .Prompt }}}, so text reaches the model exactly as sent.
 * Without the prefixes the model embeds both sides as untyped text, below what it was trained to do.
 *
 * <p>Each application embeds in one direction only - ragr-ingest writes chunks, ragr-app searches with
 * queries - so each wraps its model with one fixed prefix rather than telling the two kinds of call
 * apart. Every path {@code PgVectorStore} takes, the batched add and the single-query search alike, ends
 * in {@link #call}, which is the one place the prefix is applied.
 *
 * <p>Only the request changes. The stored chunk text, its citation header included, is untouched, so
 * nothing that reads chunks back has a prefix to strip.
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
     * The delegate's, which knows its model's size. The default would find out by embedding a test
     * string - through {@link #call}, prefix and all.
     */
    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
