package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the paragraph coalescing that makes app.rag.chunk-size meaningful. Splitting by paragraph alone
 * left every short paragraph as its own chunk, so a 645-page manual produced 7289 chunks with a median of
 * 27 tokens against a configured budget of 400.
 */
class DocumentParserServiceTest {

    /** Small budget so the tests can express "over budget" with short, readable strings. */
    private static final int CHUNK_SIZE_TOKENS = 20;

    private DocumentParserService parserService;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties(CHUNK_SIZE_TOKENS, 150, 5, 10000, 5, 0.6,
                                                        200, 4, 3, Duration.ofSeconds(2));
        parserService = new DocumentParserService(new TokenTextSplitter(), ForkJoinPool.commonPool(), ragProperties);
    }

    @Test
    @DisplayName("consecutive short paragraphs are merged into a single group")
    void mergesShortParagraphs() {
        Document page = new Document("alpha one\n\nbeta two\n\ngamma three", Map.of());

        List<Document> groups = parserService.coalesceParagraphs(page);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().getText()).isEqualTo("alpha one\n\nbeta two\n\ngamma three");
    }

    @Test
    @DisplayName("a group is closed before it would exceed the token budget")
    void closesGroupBeforeExceedingBudget() {
        // Each paragraph is ~12 tokens, so two fit in the 20-token budget only one at a time.
        String paragraph = "the quick brown fox jumps over the lazy dog again and again";
        Document page = new Document(paragraph + "\n\n" + paragraph + "\n\n" + paragraph, Map.of());

        List<Document> groups = parserService.coalesceParagraphs(page);

        assertThat(groups).hasSize(3);
        assertThat(groups).allSatisfy(group -> assertThat(group.getText()).isEqualTo(paragraph));
    }

    @Test
    @DisplayName("a paragraph larger than the whole budget is emitted on its own")
    void oversizedParagraphIsNotMerged() {
        String oversized = "word ".repeat(CHUNK_SIZE_TOKENS * 3).trim();
        Document page = new Document("tiny intro\n\n" + oversized + "\n\ntiny outro", Map.of());

        List<Document> groups = parserService.coalesceParagraphs(page);

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).getText()).isEqualTo("tiny intro");
        assertThat(groups.get(1).getText()).isEqualTo(oversized);
        assertThat(groups.get(2).getText()).isEqualTo("tiny outro");
    }

    @Test
    @DisplayName("source metadata is carried onto every group")
    void preservesSourceMetadata() {
        String paragraph = "the quick brown fox jumps over the lazy dog again and again";
        Document page = new Document(paragraph + "\n\n" + paragraph, Map.of("page_number", 42));

        List<Document> groups = parserService.coalesceParagraphs(page);

        assertThat(groups).hasSize(2);
        assertThat(groups).allSatisfy(group -> assertThat(group.getMetadata()).containsEntry("page_number", 42));
    }

    @Test
    @DisplayName("blank paragraphs are skipped rather than becoming empty chunks")
    void skipsBlankParagraphs() {
        Document page = new Document("alpha\n\n   \n\nbeta\n\n\t\n\ngamma", Map.of());

        List<Document> groups = parserService.coalesceParagraphs(page);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().getText()).isEqualTo("alpha\n\nbeta\n\ngamma");
    }

    @Test
    @DisplayName("a document with no usable text produces no groups")
    void emptyDocumentProducesNoGroups() {
        assertThat(parserService.coalesceParagraphs(new Document("   \n\n   ", Map.of()))).isEmpty();
    }
}
