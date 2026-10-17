package com.example.subhanmishra.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which questions trigger a keyword search, and how its matches are ranked. The questions are the golden
 * suite's own.
 */
class KeywordSearchServiceTest {

    @Test
    @DisplayName("a fully qualified class name is one term, and its simple name another")
    void fullyQualifiedClass() {
        assertThat(KeywordSearchService.identifiers("""
                After upgrading to Spring Boot 4, my import of org.springframework.boot.actuate.health.HealthIndicator
                no longer compiles. Where does HealthIndicator live now?"""))
                .containsExactly("org.springframework.boot.actuate.health.HealthIndicator", "HealthIndicator");
    }

    @Test
    @DisplayName("a property key keeps its hyphens; a sentence's full stop is not part of it")
    void propertyKey() {
        assertThat(KeywordSearchService.identifiers(
                "After upgrading to Spring Boot 4.1, my logging.file.max-history setting has no effect."))
                .containsExactly("logging.file.max-history");
        assertThat(KeywordSearchService.identifiers("Is it management.server.port?"))
                .containsExactly("management.server.port");
    }

    @Test
    @DisplayName("annotations and camelCase words are names")
    void annotationsAndCamelCase() {
        assertThat(KeywordSearchService.identifiers(
                "How do I register my own AsyncTaskExecutor so that Spring MVC and @EnableAsync both use it?"))
                .containsExactly("AsyncTaskExecutor", "@EnableAsync");
        assertThat(KeywordSearchService.identifiers("Why is jdbcTemplate null?")).containsExactly("jdbcTemplate");
    }

    @Test
    @DisplayName("plain questions, versions, abbreviations and capitalised words name nothing")
    void plainQuestions() {
        assertThat(KeywordSearchService.identifiers(
                "What were the total quarterly revenue figures for Acme Corporation in 2019?")).isEmpty();
        assertThat(KeywordSearchService.identifiers("What changed in Spring Boot 4.1, e.g. for Kafka or MVC?"))
                .isEmpty();
        assertThat(KeywordSearchService.identifiers("When should I use a stack instead of a queue?")).isEmpty();
        assertThat(KeywordSearchService.identifiers(null)).isEmpty();
    }

    @Test
    @DisplayName("a pasted stack trace is capped at eight names")
    void capped() {
        String trace = "a.b.Class1 a.b.Class2 a.b.Class3 a.b.Class4 a.b.Class5 a.b.Class6 a.b.Class7 a.b.Class8 a.b.Class9";
        assertThat(KeywordSearchService.identifiers(trace)).hasSize(8);
    }

    @Test
    @DisplayName("the chunk with the rarest name ranks first, then the higher score, up to the slots")
    void ranking() {
        Document common = Document.builder().id("common").text("x").score(0.9).build();
        Document rareLow = Document.builder().id("rare-low").text("x").score(0.5).build();
        Document rareHigh = Document.builder().id("rare-high").text("x").score(0.6).build();

        List<Document> ranked = KeywordSearchService.rank(List.of(common, rareLow, rareHigh),
                                                          Map.of("common", 15, "rare-low", 1, "rare-high", 1), 2);

        assertThat(ranked).containsExactly(rareHigh, rareLow);
    }
}
