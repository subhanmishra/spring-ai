package com.example.subhanmishra.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPrefixEmbeddingModelTest {

    /** Records every text it is asked to embed, and answers each with a one-element vector. */
    private static final class RecordingModel implements EmbeddingModel {

        final List<String> received = new ArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            received.addAll(request.getInstructions());
            return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                                                  .mapToObj(i -> new Embedding(new float[]{i}, i))
                                                  .toList());
        }

        @Override
        public float[] embed(Document document) {
            throw new AssertionError("the wrapper must not bypass its own prefix by delegating embed(Document)");
        }

        @Override
        public int dimensions() {
            return 768;
        }
    }

    private final RecordingModel delegate = new RecordingModel();
    private final TaskPrefixEmbeddingModel model = new TaskPrefixEmbeddingModel(delegate, "search_query: ");

    @Test
    @DisplayName("a single query, the path PgVectorStore searches with, is prefixed")
    void prefixesQuery() {
        model.embed("what is a starter");

        assertThat(delegate.received).containsExactly("search_query: what is a starter");
    }

    @Test
    @DisplayName("every document of a batched add, the path PgVectorStore writes with, is prefixed")
    void prefixesBatchedDocuments() {
        List<Document> documents = List.of(new Document("[a.pdf, p. 1]\nfirst"), new Document("[a.pdf, p. 2]\nsecond"));

        List<float[]> vectors = model.embed(documents, EmbeddingOptions.builder().build(), List::of);

        assertThat(delegate.received).containsExactly("search_query: [a.pdf, p. 1]\nfirst",
                                                      "search_query: [a.pdf, p. 2]\nsecond");
        assertThat(vectors).hasSize(2);
    }

    @Test
    @DisplayName("a single document is prefixed, and its stored text is left as it was")
    void prefixesDocumentWithoutChangingIt() {
        Document document = new Document("[a.pdf, p. 1]\nbody");

        model.embed(document);

        assertThat(delegate.received).containsExactly("search_query: [a.pdf, p. 1]\nbody");
        assertThat(document.getText()).isEqualTo("[a.pdf, p. 1]\nbody");
    }

    @Test
    @DisplayName("dimensions come from the delegate rather than from embedding a test string")
    void delegatesDimensions() {
        assertThat(model.dimensions()).isEqualTo(768);
        assertThat(delegate.received).isEmpty();
    }
}
