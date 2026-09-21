package com.example.subhanmishra.service.eval;

import com.example.subhanmishra.service.eval.CitationResolver.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures here are cut from the real corpus rather than invented, because the whole claim this class
 * rests on is a measured one: every section number {@code gemma4:e2b} has been observed to write where
 * a page belongs resolves to exactly one retrieved chunk. The headings, their wording and the pages
 * they sit on are the ones in the indexed Spring Boot manual, so a chunking change that moved them
 * would break these tests - which is the intent.
 */
class CitationResolverTest {

    /** Page 283, which heads section 5.2.5. Retrieved by {@code actuator-http-exposure} on every run. */
    private static final Document PAGE_283 = new Document("""
            [spring-boot-reference.pdf, p. 283]

            The prefix management.endpoint.<name> is used to uniquely identify the endpoint
            that is being configured.

            5.2.5. Hypermedia for Actuator Web Endpoints

            A "discovery page" is added with links to all the endpoints.""");

    /** Page 299, which heads both 5.3 and 5.3.1 - two resolvable sections in one chunk. */
    private static final Document PAGE_299 = new Document("""
            [spring-boot-reference.pdf, p. 299]

            5.3. Monitoring and Management over HTTP

            If you are developing a web application, Spring Boot Actuator auto-configures all
            enabled endpoints to be exposed over HTTP.

            5.3.1. Customizing the Management Endpoint Paths

            Sometimes, it is useful to customize the prefix for the management endpoints.""");

    /** Page 306, which heads 5.5.1. Retrieved by {@code logging-level-property} on every run. */
    private static final Document PAGE_306 = new Document("""
            [spring-boot-reference.pdf, p. 306]

            A value of null indicates that there is no explicit configuration.

            5.5.1. Configure a Logger

            To configure a given logger, set the logging.level.<logger-name> property.""");

    /** Page 277, carrying no heading at all - the common case, and nothing to resolve against. */
    private static final Document PAGE_277 = new Document("""
            [spring-boot-reference.pdf, p. 277]

            exposure via HTTP, where the ID of the endpoint along with a prefix of /actuator is
            mapped to a URL. For example, by default, the health endpoint is mapped to
            /actuator/health.""");

    private static final List<Document> ACTUATOR_CONTEXT = List.of(PAGE_277, PAGE_283, PAGE_299);

    @Nested
    @DisplayName("resolving a section number to its page")
    class Resolving {

        @Test
        @DisplayName("the label the suite sees on every actuator run")
        void resolvesTheObservedActuatorLabels() {
            // Taken from a real answer, trimmed. All three of these fail the suite on every run.
            String answer = """
                    A discovery page is available at /actuator (spring-boot-reference.pdf, p. 5.2.5).
                    Endpoints are exposed over HTTP (spring-boot-reference.pdf, p. 5.3).
                    Customize the prefix with management.endpoints.web.base-path
                    (spring-boot-reference.pdf, p. 5.3.1).""";

            Resolution resolution = CitationResolver.resolve(answer, ACTUATOR_CONTEXT);

            assertThat(resolution.repaired()).isEqualTo(3);
            assertThat(resolution.abstained()).isZero();
            assertThat(resolution.answer())
                    .contains("(spring-boot-reference.pdf, p. 283)")
                    .contains("(spring-boot-reference.pdf, p. 299)")
                    .doesNotContain("5.2.5")
                    .doesNotContain("5.3.1");
        }

        @Test
        @DisplayName("the label the suite sees on every logging run")
        void resolvesTheObservedLoggingLabel() {
            Resolution resolution = CitationResolver.resolve(
                    "Set logging.level.<logger-name> (spring-boot-reference.pdf, p. 5.5.1).",
                    List.of(PAGE_306));

            assertThat(resolution.answer())
                    .isEqualTo("Set logging.level.<logger-name> (spring-boot-reference.pdf, p. 306).");
            assertThat(resolution.repaired()).isEqualTo(1);
        }

        @Test
        @DisplayName("only the page reference changes - the rest of the answer is untouched")
        void rewritesNothingElse() {
            String answer = "Before. See (spring-boot-reference.pdf, p. 5.5.1) for 5.5.1 itself. After.";

            Resolution resolution = CitationResolver.resolve(answer, List.of(PAGE_306));

            // The bare "5.5.1" in prose is not inside a citation and must survive verbatim.
            assertThat(resolution.answer())
                    .isEqualTo("Before. See (spring-boot-reference.pdf, p. 306) for 5.5.1 itself. After.");
        }

        @Test
        @DisplayName("every occurrence is rewritten, not just the first")
        void rewritesEveryOccurrence() {
            // Counted by occurrence rather than by distinct citation: each one is a place a reader could
            // follow, and leaving the second and third would be a half-repair.
            String answer = "One (manual.pdf, p. 5.3). Two (manual.pdf, p. 5.3). Three (manual.pdf, p. 5.3).";

            Resolution resolution = CitationResolver.resolve(answer, List.of(renamed(PAGE_299, "manual.pdf")));

            assertThat(resolution.repaired()).isEqualTo(3);
            assertThat(resolution.answer()).isEqualTo(
                    "One (manual.pdf, p. 299). Two (manual.pdf, p. 299). Three (manual.pdf, p. 299).");
        }

        @Test
        @DisplayName("several citations inside one pair of brackets are each resolved")
        void resolvesWithinASingleSpan() {
            String answer = "Both (spring-boot-reference.pdf, p. 5.2.5; spring-boot-reference.pdf, p. 5.3).";

            Resolution resolution = CitationResolver.resolve(answer, ACTUATOR_CONTEXT);

            assertThat(resolution.answer()).isEqualTo(
                    "Both (spring-boot-reference.pdf, p. 283; spring-boot-reference.pdf, p. 299).");
            assertThat(resolution.repaired()).isEqualTo(2);
        }

        @Test
        @DisplayName("a resolved answer scores as valid, which is the point of doing this")
        void theResolvedAnswerCitesSomethingRetrieved() {
            Resolution resolution = CitationResolver.resolve(
                    "See (spring-boot-reference.pdf, p. 5.2.5).", ACTUATOR_CONTEXT);

            List<Citation> citations = CitationParser.parseAnswerCandidates(resolution.answer());

            assertThat(citations).hasSize(1);
            assertThat(citations.getFirst().hasMalformedPage()).isFalse();
            assertThat(citations.getFirst().pageNumber()).isEqualTo(283);
            assertThat(CitationParser.availableCitations(ACTUATOR_CONTEXT))
                    .anyMatch(available -> available.matches(citations.getFirst()));
        }
    }

    @Nested
    @DisplayName("abstaining")
    class Abstaining {

        @Test
        @DisplayName("a section number matching no retrieved chunk is left exactly as written")
        void leavesAnUnmatchedLabelAlone() {
            // This is the case that keeps the fabrication metric honest. A model that has started
            // guessing produces labels nothing can place, and those must go on being counted.
            String answer = "See (spring-boot-reference.pdf, p. 12.7.4).";

            Resolution resolution = CitationResolver.resolve(answer, ACTUATOR_CONTEXT);

            assertThat(resolution.answer()).isEqualTo(answer);
            assertThat(resolution.repaired()).isZero();
            assertThat(resolution.abstained()).isEqualTo(1);
            assertThat(resolution.unresolved()).extracting(Citation::pageLabel).containsExactly("12.7.4");
        }

        @Test
        @DisplayName("a section heading two pages both claim is ambiguous, so neither is chosen")
        void leavesAnAmbiguousLabelAlone() {
            Document duplicate = new Document("""
                    [spring-boot-reference.pdf, p. 500]

                    5.3. Monitoring and Management over HTTP

                    A second page claiming the same heading.""");

            Resolution resolution = CitationResolver.resolve(
                    "See (spring-boot-reference.pdf, p. 5.3).", List.of(PAGE_299, duplicate));

            assertThat(resolution.answer()).isEqualTo("See (spring-boot-reference.pdf, p. 5.3).");
            assertThat(resolution.abstained()).isEqualTo(1);
        }

        @Test
        @DisplayName("a repair and an abstention in the same answer are both reported")
        void mixesRepairsAndAbstentions() {
            String answer = "One (spring-boot-reference.pdf, p. 5.3) and two (spring-boot-reference.pdf, p. 9.9.9).";

            Resolution resolution = CitationResolver.resolve(answer, ACTUATOR_CONTEXT);

            assertThat(resolution.repaired()).isEqualTo(1);
            assertThat(resolution.abstained()).isEqualTo(1);
            assertThat(resolution.answer())
                    .contains("p. 299")
                    .contains("p. 9.9.9");
        }

        @Test
        @DisplayName("the same unresolvable label twice is one entry but two abstentions")
        void deduplicatesTheUnresolvedList() {
            Resolution resolution = CitationResolver.resolve(
                    "A (manual.pdf, p. 9.9.9). B (manual.pdf, p. 9.9.9).", ACTUATOR_CONTEXT);

            assertThat(resolution.abstained()).isEqualTo(2);
            assertThat(resolution.unresolved()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what must never be touched")
    class LeavesAlone {

        @Test
        @DisplayName("a plain page number is not this class's business")
        void ignoresPlainPageNumbers() {
            // Whether 999 is real or fabricated is for EvalScoringService to decide. Resolving has
            // nothing to say about a citation that is already the right shape.
            String answer = "Right (spring-boot-reference.pdf, p. 277). Wrong (spring-boot-reference.pdf, p. 999).";

            Resolution resolution = CitationResolver.resolve(answer, ACTUATOR_CONTEXT);

            assertThat(resolution.answer()).isEqualTo(answer);
            assertThat(resolution.changed()).isFalse();
            assertThat(resolution.abstained()).isZero();
        }

        @Test
        @DisplayName("a version number in prose is not a heading")
        void doesNotTreatAVersionNumberAsAHeading() {
            // Measured: "5.3" occurs in 11 chunks of the live corpus as a substring - mostly as a Spring
            // Framework version - and in exactly one as a heading. Anchoring on the heading form is what
            // makes the mapping unambiguous, so this is the test that guards the anchor.
            Document versionMention = new Document("""
                    [spring-boot-reference.pdf, p. 42]

                    Spring Boot requires Spring Framework 5.3.1 or later, and works with
                    5.3 as a baseline.""");

            Resolution resolution = CitationResolver.resolve(
                    "See (spring-boot-reference.pdf, p. 5.3).", List.of(versionMention));

            assertThat(resolution.answer()).isEqualTo("See (spring-boot-reference.pdf, p. 5.3).");
            assertThat(resolution.repaired()).isZero();
            assertThat(CitationResolver.sectionsOffered(List.of(versionMention))).isEmpty();
        }

        @Test
        @DisplayName("a chunk with no citation header offers no page to resolve to")
        void ignoresChunksWithoutAHeader() {
            Document headerless = new Document("5.3. Monitoring and Management over HTTP\n\nbody text");

            assertThat(CitationResolver.sectionsOffered(List.of(headerless))).isEmpty();
            assertThat(CitationResolver.resolve("See (manual.pdf, p. 5.3).", List.of(headerless)).changed())
                    .isFalse();
        }

        @Test
        @DisplayName("a pageless source cannot be resolved to")
        void ignoresSourcesWithoutPages() {
            // Tika sources - DOCX, XLSX, HTML - carry no page attribution, so there is no page to
            // rewrite a section number into even when the heading is right there.
            Document tikaChunk = new Document("[quarterly-report.docx]\n\n5.3. Revenue\n\nbody");

            assertThat(CitationResolver.sectionsOffered(List.of(tikaChunk))).isEmpty();
        }

        @Test
        void leavesAnAnswerWithNoCitationsAlone() {
            String answer = "A stack is last-in-first-out; a queue is first-in-first-out.";
            assertThat(CitationResolver.resolve(answer, ACTUATOR_CONTEXT).answer()).isEqualTo(answer);
        }

        @Test
        @DisplayName("an ungrounded turn has nothing to resolve against")
        void handlesEmptyRetrieval() {
            String answer = "See (manual.pdf, p. 5.3).";

            assertThat(CitationResolver.resolve(answer, List.of()).answer()).isEqualTo(answer);
            assertThat(CitationResolver.resolve(answer, null).answer()).isEqualTo(answer);
        }

        @Test
        void handlesNullAndBlank() {
            assertThat(CitationResolver.resolve(null, ACTUATOR_CONTEXT).answer()).isEmpty();
            assertThat(CitationResolver.resolve("   ", ACTUATOR_CONTEXT).answer()).isEqualTo("   ");
        }
    }

    /** The same chunk under a different filename, for tests that do not care about the corpus name. */
    private static Document renamed(Document document, String fileName) {
        String text = document.getText();
        return new Document(text.replaceFirst("^\\[[^\\]]*,", "[" + fileName + ","));
    }
}
