package com.example.subhanmishra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt must receive exactly what the stock advisor's top-k, threshold-filtered search would have
 * returned, however deep the pool behind it is.
 */
class PooledQuestionAnswerAdvisorTest {

    @Test
    @DisplayName("top-k caps the prompt when every candidate clears the threshold")
    void topKCaps() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.9, 0.85, 0.8, 0.75, 0.7, 0.65), 5, 0.6))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("the threshold cuts the prompt short of top-k")
    void thresholdCuts() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.9, 0.7, 0.59, 0.5), 5, 0.6)).isEqualTo(2);
    }

    @Test
    @DisplayName("a score exactly at the threshold is kept, as the vector store's own filter keeps it")
    void thresholdInclusive() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.6, 0.59), 5, 0.6)).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing reaches the prompt when the best candidate is below the threshold")
    void noneClear() {
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(pool(0.55, 0.5), 5, 0.6)).isZero();
        assertThat(PooledQuestionAnswerAdvisor.inContextCount(List.of(), 5, 0.6)).isZero();
    }

    @Test
    @DisplayName("every passage, the last included, is closed by the end marker, with LF on every platform")
    void passagesAreClosed() {
        List<Document> chunks = List.of(new Document("[manual.pdf, p. 375]\n\nset server.port"),
                                        new Document("[manual.pdf, p. 301]\nSection: Management\n\nexample"));

        assertThat(PooledQuestionAnswerAdvisor.passages(chunks)).isEqualTo("""
                [manual.pdf, p. 375]

                set server.port

                (end of passage)

                [manual.pdf, p. 301]
                Section: Management

                example

                (end of passage)""");
    }

    @Test
    @DisplayName("without keyword matches the prompt is the threshold-filtered prefix, the rest excluded")
    void selectWithoutKeyword() {
        List<Document> pool = pool(0.9, 0.8, 0.7, 0.6, 0.5);

        var selection = PooledQuestionAnswerAdvisor.select(pool, List.of(), 5, 0.65);

        assertThat(selection.inContext()).containsExactlyElementsOf(pool.subList(0, 3));
        assertThat(selection.excluded()).containsExactlyElementsOf(pool.subList(3, 5));
    }

    @Test
    @DisplayName("keyword matches replace the weakest vector chunks, below the threshold or not")
    void keywordReplacesWeakest() {
        List<Document> pool = pool(0.9, 0.85, 0.8, 0.75, 0.7, 0.6);
        List<Document> keyword = pool(0.55, 0.5);

        var selection = PooledQuestionAnswerAdvisor.select(pool, keyword, 5, 0.65);

        assertThat(selection.inContext()).containsExactly(pool.get(0), pool.get(1), pool.get(2),
                                                          keyword.get(0), keyword.get(1));
        assertThat(selection.excluded()).containsExactly(pool.get(3), pool.get(4), pool.get(5));
    }

    @Test
    @DisplayName("a keyword match already in the vector prompt keeps its place and takes no extra one")
    void keywordAlreadyInPrompt() {
        List<Document> pool = pool(0.9, 0.85, 0.8, 0.75, 0.7);
        List<Document> keyword = List.of(pool.get(1));

        var selection = PooledQuestionAnswerAdvisor.select(pool, keyword, 5, 0.65);

        assertThat(selection.inContext()).containsExactlyElementsOf(pool);
        assertThat(selection.excluded()).isEmpty();
    }

    @Test
    @DisplayName("a keyword match from lower in the pool moves into the prompt and leaves the excluded list")
    void keywordFromExcluded() {
        List<Document> pool = pool(0.9, 0.85, 0.8, 0.75, 0.7, 0.6);

        var selection = PooledQuestionAnswerAdvisor.select(pool, List.of(pool.get(5)), 5, 0.65);

        assertThat(selection.inContext()).containsExactly(pool.get(0), pool.get(1), pool.get(2), pool.get(3),
                                                          pool.get(5));
        assertThat(selection.excluded()).containsExactly(pool.get(4));
    }

    @Test
    @DisplayName("with few vector chunks above the threshold, keyword matches are added, not swapped in")
    void keywordAddedToShortPrompt() {
        List<Document> pool = pool(0.7, 0.5);
        List<Document> keyword = pool(0.45);

        var selection = PooledQuestionAnswerAdvisor.select(pool, keyword, 5, 0.65);

        assertThat(selection.inContext()).containsExactly(pool.get(0), keyword.get(0));
        assertThat(selection.excluded()).containsExactly(pool.get(1));
    }

    @Test
    @DisplayName("page-less passages get the page-less citation rules; one paged passage switches to the paged ones")
    void citationRulesFollowThePassages() {
        var rules = new PooledQuestionAnswerAdvisor.CitationRules("paged", "pageless");
        Document markdown = new Document("[01-spring-boot__087-reference-actuator-endpoints.md]\n\ntext");
        Document pdf = new Document("[manual.pdf, p. 375]\n\ntext");

        assertThat(rules.forPassages(List.of(markdown))).isEqualTo("pageless");
        assertThat(rules.forPassages(List.of())).isEqualTo("pageless");
        assertThat(rules.forPassages(List.of(markdown, pdf))).isEqualTo("paged");
    }

    private static List<Document> pool(double... scores) {
        return Arrays.stream(scores)
                     .mapToObj(score -> Document.builder().text("chunk " + score).score(score).build())
                     .toList();
    }
}
