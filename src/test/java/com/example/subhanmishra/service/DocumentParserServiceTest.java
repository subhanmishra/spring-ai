package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.config.SpringAiConfig;
import com.example.subhanmishra.service.parse.ChunkMetadata;
import com.example.subhanmishra.service.parse.ContentBlock;
import com.example.subhanmishra.service.parse.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the paragraph coalescing that makes app.rag.chunk-size meaningful - splitting by paragraph alone
 * left every short paragraph as its own chunk, so a 645-page manual produced 7289 chunks with a median of
 * 27 tokens against a configured budget of 400 - and the block boundaries that keep a table out of the
 * prose around it.
 */
class DocumentParserServiceTest {

    /** Small budget so the tests can express "over budget" with short, readable strings. */
    private static final int CHUNK_SIZE_TOKENS = 20;

    /** Well above the budget, so only tests that mean to probe the ceiling ever reach it. */
    private static final int CEILING_TOKENS = 2048;

    private DocumentParserService parserService;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties(CHUNK_SIZE_TOKENS, 150, 5, 10000, CEILING_TOKENS, 5, 0.6,
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

    @Test
    @DisplayName("a group's token count is measured on the joined text, so it never exceeds the budget")
    void groupsNeverExceedTheBudget() {
        // Many short paragraphs: summing their individual counts ignores the tokens the blank-line joins
        // add, which used to push every group a few percent over budget.
        List<ContentBlock> blocks = List.of(new ContentBlock.Prose(
                java.util.stream.IntStream.rangeClosed(1, 80)
                                          .mapToObj(i -> "Line number " + i + " of the section.")
                                          .collect(java.util.stream.Collectors.joining("\n\n"))));

        List<Document> chunks = parserService.coalesceBlocks(blocks, Map.of());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(TokenCounter.count(chunk.getText()))
                        .as("chunk must fit the %d token budget", CHUNK_SIZE_TOKENS)
                        .isLessThanOrEqualTo(CHUNK_SIZE_TOKENS));
    }

    @Test
    @DisplayName("no paragraph is lost when prose is coalesced and split")
    void noContentIsLostThroughTheWholePipeline() {
        // Built with the app's own splitter configuration, because the content loss came from the
        // interaction between the budget and the splitter's discard-below-floor behaviour.
        RagProperties appLike = new RagProperties(CHUNK_SIZE_TOKENS, 150, 100, 10000, CEILING_TOKENS,
                                                  5, 0.6, 200, 4, 3, Duration.ofSeconds(2));
        DocumentParserService service = new DocumentParserService(
                new SpringAiConfig().tokenTextSplitter(appLike), ForkJoinPool.commonPool(), appLike);

        List<String> paragraphs = java.util.stream.IntStream.rangeClosed(1, 40)
                                                            .mapToObj(i -> "Distinctive marker " + i + " appears exactly once here.")
                                                            .toList();
        List<ContentBlock> blocks = List.of(new ContentBlock.Prose(String.join("\n\n", paragraphs)));

        String combined = String.join(" ", service.coalesceBlocks(blocks, Map.of())
                                                  .stream()
                                                  .flatMap(chunk -> service.splitIfOverBudget(chunk))
                                                  .map(Document::getText)
                                                  .toList());

        assertThat(paragraphs).allSatisfy(paragraph ->
                assertThat(combined).as("paragraph must survive chunking: %s", paragraph)
                                    .contains(paragraph));
    }

    @Test
    @DisplayName("a short trailing piece is merged into the chunk before it, never dropped")
    void shortTailIsMergedNotDropped() {
        RagProperties appLike = new RagProperties(CHUNK_SIZE_TOKENS, 150, 100, 10000, CEILING_TOKENS,
                                                  5, 0.6, 200, 4, 3, Duration.ofSeconds(2));
        DocumentParserService service = new DocumentParserService(
                new SpringAiConfig().tokenTextSplitter(appLike), ForkJoinPool.commonPool(), appLike);

        // One paragraph well over the budget, so the splitter has to cut it and will leave a remainder.
        String oversized = "The quick brown fox jumps over the lazy dog. ".repeat(12) + "Tiny tail.";
        List<Document> pieces = service.splitIfOverBudget(
                new Document(oversized, Map.of(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.PROSE))).toList();

        assertThat(pieces).allSatisfy(piece ->
                assertThat(piece.getText().length()).isGreaterThanOrEqualTo(appLike.minChunkLengthToEmbed()));
        assertThat(String.join(" ", pieces.stream().map(Document::getText).toList())).contains("Tiny tail.");
    }

    @Test
    @DisplayName("a table closes the open prose group instead of being merged into it")
    void tableIsNeverMergedIntoProse() {
        List<ContentBlock> blocks = List.of(new ContentBlock.Prose("before the table"),
                                            new ContentBlock.Table(List.of("Code"), List.of(List.of("E01")), null),
                                            new ContentBlock.Prose("after the table"));

        List<Document> chunks = parserService.coalesceBlocks(blocks, Map.of());

        // All three would comfortably fit one 20-token group, so only the block boundary keeps them apart.
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getText()).isEqualTo("before the table");
        assertThat(chunks.get(1).getText()).isEqualTo("| Code |\n| --- |\n| E01 |");
        assertThat(chunks.get(2).getText()).isEqualTo("after the table");
    }

    @Test
    @DisplayName("every chunk says whether it is prose or a table")
    void chunksCarryTheirBlockType() {
        List<ContentBlock> blocks = List.of(new ContentBlock.Prose("some prose"),
                                            new ContentBlock.Table(List.of("Code"), List.of(List.of("E01")), null));

        List<Document> chunks = parserService.coalesceBlocks(blocks, Map.of("page_number", 7));

        assertThat(chunks.get(0).getMetadata()).containsEntry(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.PROSE);
        assertThat(chunks.get(1).getMetadata()).containsEntry(ChunkMetadata.BLOCK_TYPE, ChunkMetadata.TABLE)
                                               .containsEntry(ChunkMetadata.TABLE_INDEX, 0)
                                               .containsEntry(ChunkMetadata.TABLE_ROWS, "1-1")
                                               .containsEntry("page_number", 7);
    }

    @Test
    @DisplayName("a table in an HTML upload survives Tika and reaches the chunk stream as Markdown")
    void htmlTableIsRecoveredEndToEnd() {
        String html = """
                <html><head><title>Service manual</title></head><body>
                <p>Alarm codes are listed below.</p>
                <table>
                  <tr><th>Code</th><th>Meaning</th></tr>
                  <tr><td>E01</td><td>Overheat</td></tr>
                  <tr><td>E02</td><td>Low pressure</td></tr>
                </table>
                </body></html>
                """;

        Map<String, Object> result = parserService.parse(
                new MockMultipartFile("file", "manual.html", "text/html", html.getBytes(StandardCharsets.UTF_8)));

        @SuppressWarnings("unchecked")
        List<Document> chunks = ((Stream<Document>) result.get("documentStream")).toList();

        List<Document> tableChunks = chunks.stream()
                                           .filter(chunk -> ChunkMetadata.TABLE.equals(chunk.getMetadata().get(ChunkMetadata.BLOCK_TYPE)))
                                           .toList();

        // The 20-token test budget is narrow enough to split even this table, which is what makes the
        // repeated header worth asserting here rather than only in TableChunkerTest.
        assertThat(tableChunks).isNotEmpty();
        assertThat(tableChunks).allSatisfy(chunk -> assertThat(chunk.getText())
                .startsWith("| Code | Meaning |\n| --- | --- |\n"));
        assertThat(tableChunks).satisfiesOnlyOnce(chunk -> assertThat(chunk.getText()).contains("| E01 | Overheat |"));
        assertThat(tableChunks).satisfiesOnlyOnce(chunk -> assertThat(chunk.getText()).contains("| E02 | Low pressure |"));

        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getText()).contains("Alarm codes are listed below."));
        assertThat(result).containsEntry("totalPages", 1);
    }

    @Test
    @DisplayName("the document title is not ingested as content")
    void htmlTitleIsNotIngested() {
        String html = "<html><head><title>Service manual</title></head><body><p>Body text.</p></body></html>";

        Map<String, Object> result = parserService.parse(
                new MockMultipartFile("file", "manual.html", "text/html", html.getBytes(StandardCharsets.UTF_8)));

        @SuppressWarnings("unchecked")
        List<Document> chunks = ((Stream<Document>) result.get("documentStream")).toList();

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("Service manual"));
    }
}
