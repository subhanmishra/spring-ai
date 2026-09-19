package com.example.subhanmishra.service.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures here are written in the shape {@code DocumentIngestionService.citationHeader} actually
 * produces - the citation line, then a literal {@code "\n\n"}, then the content. The separator is
 * deliberately not {@code System.lineSeparator()} in production code, because the header is persisted
 * and embedded, and following the host OS would make the same document produce a different corpus on
 * Windows than on Linux. A test that used the platform separator would pass on one and fail on the
 * other, so these use the literal too.
 */
class CitationParserTest {

    @Nested
    @DisplayName("chunk headers")
    class Headers {

        @Test
        void parsesFilenameAndPage() {
            Citation citation = CitationParser.parseHeader("[spring-boot-reference.pdf, p. 277]\n\nbody text");

            assertThat(citation).isNotNull();
            assertThat(citation.fileName()).isEqualTo("spring-boot-reference.pdf");
            assertThat(citation.pageNumber()).isEqualTo(277);
        }

        @Test
        @DisplayName("a pageless header is valid, not malformed")
        void parsesFilenameWithoutPage() {
            // Tika sources - DOCX, XLSX, PPTX, HTML - carry no page attribution, so citationHeader
            // omits the page half rather than guessing. This must not read as a parse failure.
            Citation citation = CitationParser.parseHeader("[quarterly-report.docx]\n\nbody text");

            assertThat(citation).isNotNull();
            assertThat(citation.fileName()).isEqualTo("quarterly-report.docx");
            assertThat(citation.pageNumber()).isNull();
        }

        @Test
        @DisplayName("a chunk ingested before headers existed has none")
        void returnsNullForPreHeaderChunk() {
            assertThat(CitationParser.parseHeader("Just some content.\n\nMore content.")).isNull();
        }

        @Test
        @DisplayName("a first line that merely starts with a bracket is not a header")
        void requiresTheWholeFirstLineToBeBracketed() {
            // The regex matches the shape of the entire line for a reason: splitting unconditionally on
            // the first blank line would promote a real first line to a citation and drop it from the
            // body, which is exactly the silent content loss this pipeline has suffered before.
            assertThat(CitationParser.parseHeader("[Note] this is body text\n\nmore")).isNull();
        }

        @Test
        void stripsTheHeaderFromTheBody() {
            String stripped = CitationParser.stripHeader("[manual.pdf, p. 5]\n\nthe real content");
            assertThat(stripped).isEqualTo("the real content");
        }

        @Test
        void leavesAPreHeaderChunkIntact() {
            assertThat(CitationParser.stripHeader("no header here\n\nbody")).isEqualTo("no header here\n\nbody");
        }
    }

    @Nested
    @DisplayName("inline citations in an answer")
    class InlineCitations {

        @Test
        void parsesTheConventionThePromptAsksFor() {
            List<Citation> citations =
                    CitationParser.parseAnswerCandidates("As described in (spring-boot-reference.pdf, p. 277).");

            assertThat(citations).hasSize(1);
            assertThat(citations.getFirst().fileName()).isEqualTo("spring-boot-reference.pdf");
            assertThat(citations.getFirst().pageNumber()).isEqualTo(277);
        }

        @Test
        @DisplayName("accepts the variations a model actually produces")
        void acceptsLooserFormats() {
            // The prompt asks for one convention; a model complies approximately. Rejecting these would
            // count a perfectly good citation as absent and understate citation coverage.
            String answer = """
                    First (manual.pdf, p. 1).
                    Second [manual.pdf, p. 2].
                    Third (manual.pdf p. 3).
                    Fourth (manual.pdf, page 4).
                    Fifth (manual.pdf, pp. 5).
                    """;

            assertThat(CitationParser.parseAnswerCandidates(answer))
                    .extracting(Citation::pageNumber)
                    .containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("several citations inside one pair of brackets are all found")
        void parsesMultipleCitationsInOneSpan() {
            // Regression test, taken verbatim from the first real answer this evaluator ever scored.
            // An earlier pattern anchored the closing bracket immediately after the page number, so it
            // matched NEITHER of these: the first is followed by a semicolon, and the second has no
            // opening bracket of its own. Four citations were silently counted as two.
            String answer = "They can be exposed using Jersey, Spring MVC, or Spring WebFlux "
                            + "(spring-boot-reference.pdf, p. 283; spring-boot-reference.pdf, p. 299).";

            assertThat(CitationParser.parseAnswerCandidates(answer))
                    .extracting(Citation::pageNumber)
                    .containsExactly(283, 299);
        }

        @Test
        @DisplayName("a decimal number in parentheses is not a filename")
        void ignoresDecimalNumbers() {
            // Requiring at least two extension characters is not enough on its own - "3.14" satisfies
            // that. The extension has to start with a letter.
            assertThat(CitationParser.parseAnswerCandidates("Upgrade to the new release (version 3.14)."))
                    .isEmpty();
        }

        @Test
        @DisplayName("abbreviations with dots are not filenames")
        void ignoresAbbreviations() {
            assertThat(CitationParser.parseAnswerCandidates("Use a starter (e.g. the web one).")).isEmpty();
        }

        @Test
        @DisplayName("the same source cited repeatedly counts once")
        void deduplicates() {
            String answer = "See (manual.pdf, p. 7). Also (manual.pdf, p. 7). And again (manual.pdf, p. 7).";
            assertThat(CitationParser.parseAnswerCandidates(answer)).hasSize(1);
        }

        @Test
        @DisplayName("a page range yields only its first page")
        void takesFirstPageOfARange() {
            // Deliberate: chunks are one page each, so a range is the model summarising. Expanding it
            // would manufacture fabrications for pages the model never actually claimed.
            List<Citation> citations = CitationParser.parseAnswerCandidates("See (manual.pdf, pp. 12-14).");

            assertThat(citations).hasSize(1);
            assertThat(citations.getFirst().pageNumber()).isEqualTo(12);
        }

        @Test
        @DisplayName("ordinary prose in parentheses is not a citation")
        void ignoresNonFilenameParentheticals() {
            String answer = "Set the property (see the section on configuration) and restart (it is quick).";
            assertThat(CitationParser.parseAnswerCandidates(answer)).isEmpty();
        }

        @Test
        @DisplayName("a bare filename in prose is still only a candidate")
        void returnsBareFilenamesAsCandidates() {
            // These are the false positives that make the filtering step in EvalScoringService
            // necessary: answers about this corpus mention config files constantly.
            assertThat(CitationParser.parseAnswerCandidates("Put it in (application.properties)."))
                    .extracting(Citation::fileName)
                    .containsExactly("application.properties");
        }

        @Test
        void handlesNullAndBlank() {
            assertThat(CitationParser.parseAnswerCandidates(null)).isEmpty();
            assertThat(CitationParser.parseAnswerCandidates("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("citations available from retrieved context")
    class Available {

        @Test
        void collectsDistinctHeaders() {
            List<Document> retrieved = List.of(
                    new Document("[manual.pdf, p. 1]\n\nfirst"),
                    new Document("[manual.pdf, p. 2]\n\nsecond"),
                    new Document("[manual.pdf, p. 1]\n\nduplicate page, different chunk"));

            assertThat(CitationParser.availableCitations(retrieved))
                    .extracting(Citation::pageNumber)
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("chunks without headers contribute nothing")
        void skipsPreHeaderChunks() {
            List<Document> retrieved = List.of(
                    new Document("[manual.pdf, p. 1]\n\nwith header"),
                    new Document("no header at all"));

            assertThat(CitationParser.availableCitations(retrieved)).hasSize(1);
            assertThat(CitationParser.availableFileNames(retrieved)).containsExactly("manual.pdf");
        }

        @Test
        void isEmptyForAnUngroundedTurn() {
            assertThat(CitationParser.availableCitations(List.of())).isEmpty();
            assertThat(CitationParser.availableCitations(null)).isEmpty();
        }
    }

    @Nested
    class Matching {

        @Test
        void filenameComparisonIgnoresCase() {
            assertThat(new Citation("Manual.PDF", 5).matches(new Citation("manual.pdf", 5))).isTrue();
        }

        @Test
        void pageMustMatchExactly() {
            assertThat(new Citation("manual.pdf", 5).matches(new Citation("manual.pdf", 6))).isFalse();
        }

        @Test
        @DisplayName("a pageless citation does not match a paged one")
        void absentPageIsNotAWildcard() {
            assertThat(new Citation("manual.pdf", null).matches(new Citation("manual.pdf", 5))).isFalse();
            assertThat(new Citation("manual.pdf", null).matches(new Citation("manual.pdf", null))).isTrue();
        }
    }
}
