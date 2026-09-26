package com.example.subhanmishra.service.eval;

import com.example.subhanmishra.service.eval.CitationResolver.Repair;
import com.example.subhanmishra.service.eval.CitationResolver.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCitationsTest {

    private static final AnswerCitations.Context RETRIEVED = new AnswerCitations.Context(
            Set.of("spring-boot-reference.pdf", "notes.docx"),
            Map.of("5.3", new Citation("spring-boot-reference.pdf", 299),
                   "5.5.1", new Citation("spring-boot-reference.pdf", 306)));

    @Nested
    @DisplayName("strip")
    class Strip {

        @Test
        @DisplayName("removes a citation together with the space that led into it")
        void removesCitationAndLeadingSpace() {
            assertThat(AnswerCitations.strip(
                    "Starters bundle dependencies (spring-boot-reference.pdf, p. 42). They are optional.", RETRIEVED))
                    .isEqualTo("Starters bundle dependencies. They are optional.");
        }

        @Test
        @DisplayName("removes a span holding several citations")
        void removesMultiCitationSpan() {
            assertThat(AnswerCitations.strip(
                    "Exposed over HTTP (spring-boot-reference.pdf, p. 283; spring-boot-reference.pdf, p. 299), by default.",
                    RETRIEVED))
                    .isEqualTo("Exposed over HTTP, by default.");
        }

        @Test
        @DisplayName("removes square-bracket, pageless and section-number citations alike")
        void removesEveryCitationForm() {
            assertThat(AnswerCitations.strip("A [spring-boot-reference.pdf, page 7] B (notes.docx) C "
                                             + "(spring-boot-reference.pdf, p. 5.3) D", RETRIEVED))
                    .isEqualTo("A B C D");
        }

        @Test
        @DisplayName("takes the following space instead when the citation opens a line")
        void citationAtLineStart() {
            assertThat(AnswerCitations.strip("Intro\n(spring-boot-reference.pdf, p. 3) Starters exist.", RETRIEVED))
                    .isEqualTo("Intro\nStarters exist.");
        }

        @Test
        @DisplayName("leaves a filename named in prose alone")
        void leavesProseFilename() {
            String answer = "Set it in your configuration (application.properties) and restart.";
            assertThat(AnswerCitations.strip(answer, RETRIEVED)).isEqualTo(answer);
        }

        @Test
        @DisplayName("leaves a span that mixes prose with a citation whole")
        void leavesMixedSpan() {
            String answer = "Defaults apply (see the table in spring-boot-reference.pdf, p. 42, for the full list).";
            assertThat(AnswerCitations.strip(answer, RETRIEVED)).isEqualTo(answer);
        }

        @Test
        @DisplayName("removes bare section references that head a retrieved page, in every written form")
        void removesBareSectionReferences() {
            assertThat(AnswerCitations.strip("Exposed over HTTP (5.3). Loggers (Section 5.5.1) and "
                                             + "paths (see 5.3, 5.5.1) too.", RETRIEVED))
                    .isEqualTo("Exposed over HTTP. Loggers and paths too.");
        }

        @Test
        @DisplayName("leaves a dotted number that heads no retrieved page - a version, a decimal")
        void leavesUnknownNumbers() {
            String answer = "Needs Java (17.0) and Spring Framework (6.1), with a ratio (0.75); see (5.3.1.2).";
            assertThat(AnswerCitations.strip(answer, RETRIEVED)).isEqualTo(answer);
        }

        @Test
        @DisplayName("leaves a span mixing a known section with anything unknown")
        void leavesPartlyKnownSectionSpan() {
            String answer = "Compare (5.3, 6.1) and (5.3 onwards).";
            assertThat(AnswerCitations.strip(answer, RETRIEVED)).isEqualTo(answer);
        }

        @Test
        @DisplayName("reports the bare references it strips, once each, in order")
        void reportsBareReferences() {
            assertThat(AnswerCitations.bareSectionReferences(
                    "A (5.5.1). B (5.3). C (see 5.5.1). D (3.14).", RETRIEVED))
                    .containsExactly("5.5.1", "5.3");
        }

        @Test
        @DisplayName("still strips a fabricated citation - it is reported, not shown")
        void stripsFabricatedCitation() {
            assertThat(AnswerCitations.strip("Invented (other-manual.pdf, p. 12).", RETRIEVED))
                    .isEqualTo("Invented.");
        }
    }

    @Nested
    @DisplayName("StreamingStripper")
    class Streaming {

        private static final String ANSWER = """
                Starters bundle dependencies (spring-boot-reference.pdf, p. 42). Set them in
                your configuration (application.properties) [spring-boot-reference.pdf, page 7].
                (notes.docx) Exposure is over HTTP (spring-boot-reference.pdf, p. 283; spring-boot-reference.pdf, p. 5.3).
                A version (3.14) is not a citation, and (see spring-boot-reference.pdf, p. 1, for more) stays.
                Paths are configurable (5.3), as are loggers (Section 5.5.1).""";

        @ParameterizedTest(name = "tokens of {0} characters")
        @ValueSource(ints = {1, 2, 3, 5, 7, 11, 40, 1000})
        @DisplayName("releases exactly what strip produces, however the answer is split")
        void matchesBatchStrip(int tokenLength) {
            AnswerCitations.StreamingStripper stripper = new AnswerCitations.StreamingStripper();
            StringBuilder released = new StringBuilder();
            for (int i = 0; i < ANSWER.length(); i += tokenLength) {
                released.append(stripper.accept(ANSWER.substring(i, Math.min(ANSWER.length(), i + tokenLength)), RETRIEVED));
            }
            released.append(stripper.finish(RETRIEVED));

            assertThat(released.toString()).isEqualTo(AnswerCitations.strip(ANSWER, RETRIEVED));
        }

        @Test
        @DisplayName("holds only from an open bracket, and releases prose immediately")
        void holdsOnlyTheOpenSpan() {
            AnswerCitations.StreamingStripper stripper = new AnswerCitations.StreamingStripper();

            assertThat(stripper.accept("Starters bundle", RETRIEVED)).isEqualTo("Starters bundle");
            assertThat(stripper.accept(" dependencies (spring-boot", RETRIEVED)).isEqualTo(" dependencies");
            assertThat(stripper.accept("-reference.pdf, p. 42).", RETRIEVED)).isEqualTo(".");
            assertThat(stripper.finish(RETRIEVED)).isEmpty();
        }

        @Test
        @DisplayName("releases a bracket as prose once a line break shows it cannot be a span")
        void releasesBrokenSpan() {
            AnswerCitations.StreamingStripper stripper = new AnswerCitations.StreamingStripper();

            assertThat(stripper.accept("a (b", RETRIEVED)).isEqualTo("a");
            assertThat(stripper.accept("\nc", RETRIEVED)).isEqualTo(" (b\nc");
        }
    }

    @Nested
    @DisplayName("CitationResolver repairs")
    class Repairs {

        private static final Document PAGE_299 = new Document("""
                [spring-boot-reference.pdf, p. 299]

                5.3. Monitoring and Management over HTTP

                If you are developing a web application, Spring Boot Actuator auto-configures all
                enabled endpoints to be exposed over HTTP.""");

        @Test
        @DisplayName("records what each repair changed, once per distinct citation")
        void recordsRepairs() {
            Resolution resolution = CitationResolver.resolve(
                    "Exposed (spring-boot-reference.pdf, p. 5.3). Again (spring-boot-reference.pdf, p. 5.3).",
                    List.of(PAGE_299));

            assertThat(resolution.repairs())
                    .containsExactly(new Repair("spring-boot-reference.pdf", "5.3", 299));
            assertThat(resolution.repairOf(new Citation("spring-boot-reference.pdf", 299)))
                    .isEqualTo(new Repair("spring-boot-reference.pdf", "5.3", 299));
            assertThat(resolution.repairOf(new Citation("spring-boot-reference.pdf", 42))).isNull();
        }

        @Test
        @DisplayName("names each resolvable section with the file and page its heading sits on")
        void resolvableSections() {
            assertThat(CitationResolver.resolvableSections(List.of(PAGE_299)))
                    .containsExactlyEntriesOf(Map.of("5.3", new Citation("spring-boot-reference.pdf", 299)));
        }
    }
}
