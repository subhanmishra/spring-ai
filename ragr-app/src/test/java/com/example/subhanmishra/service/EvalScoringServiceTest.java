package com.example.subhanmishra.service;

import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvalScoringServiceTest {

    private final EvalScoringService service = new EvalScoringService();

    /** A retrieved chunk as the pipeline stores it: citation header, blank line, content. */
    private static Document chunk(String fileName, int page, String body, double score) {
        return Document.builder()
                       .text("[%s, p. %d]\n\n%s".formatted(fileName, page, body))
                       .metadata(Map.of("fileName", fileName, "pageNumber", page))
                       .score(score)
                       .build();
    }

    private static GoldenCase caseOf(String id, String query, List<Integer> pages, List<String> mustContain) {
        return new GoldenCase(id, query, null, "manual.pdf", pages, mustContain, List.of(), true, false);
    }

    @Nested
    @DisplayName("citation scoring")
    class Citations {

        @Test
        @DisplayName("a citation matching a retrieved chunk is valid")
        void validCitation() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81));

            EvalScores scores = service.score("See (manual.pdf, p. 277) for details.", retrieved);

            assertThat(scores.citations().emitted()).isEqualTo(1);
            assertThat(scores.citations().valid()).isEqualTo(1);
            assertThat(scores.citations().fabricated()).isZero();
            assertThat(scores.citations().validityRate()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a page that was never retrieved is a fabrication")
        void fabricatedCitation() {
            // The failure this whole metric exists for: a page number the model invented is
            // indistinguishable from a real one to a reader, and looks like diligence.
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81));

            EvalScores scores = service.score("As stated in (manual.pdf, p. 999).", retrieved);

            assertThat(scores.citations().fabricated()).isEqualTo(1);
            assertThat(scores.citations().fabricationRate()).isEqualTo(1.0);
            assertThat(scores.failureReasons()).anyMatch(reason -> reason.contains("fabricated"));
        }

        @Test
        @DisplayName("a config filename in prose is not scored as a fabricated citation")
        void ignoresProseFilenames() {
            // The false positive that would otherwise swamp the fabrication rate. Answers about this
            // corpus mention application.properties and pom.xml constantly, in parentheses, as prose.
            List<Document> retrieved = List.of(chunk("manual.pdf", 111, "logging.level", 0.75));

            EvalScores scores = service.score(
                    "Set logging.level.root in your config (application.properties) or (pom.xml).", retrieved);

            assertThat(scores.citations().emitted()).isZero();
            assertThat(scores.citations().fabricated()).isZero();
        }

        @Test
        @DisplayName("but a page number attached to an unretrieved file IS a fabrication")
        void pageNumberOnProseFilenameStillCounts() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 111, "logging.level", 0.75));

            EvalScores scores = service.score("See (application.properties, p. 12).", retrieved);

            assertThat(scores.citations().emitted()).isEqualTo(1);
            assertThat(scores.citations().fabricated()).isEqualTo(1);
        }

        @Test
        @DisplayName("a pageless citation to a retrieved document is imprecise, not fabricated")
        void pagelessCitationToRetrievedDocumentIsValid() {
            // Found by the eval suite flagging a bare "[spring-boot-reference.pdf]" as fabricated. The
            // document really was retrieved, so nothing was invented - and for a Tika source, which has
            // no pages at all, this is the only correct citation form.
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81));

            EvalScores scores = service.score("As described in (manual.pdf).", retrieved);

            assertThat(scores.citations().emitted()).isEqualTo(1);
            assertThat(scores.citations().valid()).isEqualTo(1);
            assertThat(scores.citations().fabricated()).isZero();
        }

        @Test
        @DisplayName("a pageless citation to a document never retrieved is still fabricated")
        void pagelessCitationToUnretrievedDocumentIsFabricated() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81));

            EvalScores scores = service.score("As described in (other-manual.pdf, p. 3).", retrieved);

            assertThat(scores.citations().fabricated()).isEqualTo(1);
        }

        @Test
        @DisplayName("a section number cited as a page is fabricated, not an imprecise citation")
        void sectionNumberCitedAsAPageIsFabricated() {
            // The model did claim a location, so the leniency granted to a bare filename does not
            // apply - and the location it claimed is not one the context offered.
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "5.3. Endpoints", 0.81));

            EvalScores scores = service.score("Exposed over HTTP (manual.pdf, p. 5.3).", retrieved);

            assertThat(scores.citations().emitted()).isEqualTo(1);
            assertThat(scores.citations().fabricated()).isEqualTo(1);
            assertThat(scores.citations().fabricatedCitations())
                    .extracting(Object::toString)
                    .containsExactly("manual.pdf p.5.3");
        }

        @Test
        @DisplayName("an answer citing nothing is vacuously valid, which is why coverage exists")
        void uncitedAnswer() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator", 0.81));

            EvalScores scores = service.score("Actuator endpoints are exposed under /actuator.", retrieved);

            assertThat(scores.citations().emitted()).isZero();
            // Perfect validity despite citing nothing - an assistant that stopped citing entirely would
            // show a flawless rate here. Coverage is the metric that catches it.
            assertThat(scores.citations().validityRate()).isEqualTo(1.0);
            assertThat(scores.citations().coverage()).isZero();
        }
    }

    @Nested
    @DisplayName("retrieval scoring")
    class Retrieval {

        @Test
        void reportsCountScoresAndPages() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "a", 0.81),
                                               chunk("manual.pdf", 278, "b", 0.68));

            EvalScores scores = service.score("answer", retrieved);

            assertThat(scores.retrieval().retrievedCount()).isEqualTo(2);
            assertThat(scores.retrieval().topScore()).isEqualTo(0.81);
            assertThat(scores.retrieval().scoreSpread()).isEqualTo(0.81 - 0.68, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(scores.retrieval().pagesRetrieved()).containsExactly(277, 278);
            assertThat(scores.retrieval().chunksWithHeader()).isEqualTo(2);
        }

        @Test
        @DisplayName("chunks ingested before citation headers are counted")
        void countsPreHeaderChunks() {
            // A non-zero gap here means citation metrics are being scored against a context that was
            // never fully citable, and the fix is re-ingestion rather than prompt tuning.
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "a", 0.81),
                                               Document.builder().text("legacy chunk, no header").score(0.7).build());

            EvalScores scores = service.score("answer", retrieved);

            assertThat(scores.retrieval().retrievedCount()).isEqualTo(2);
            assertThat(scores.retrieval().chunksWithHeader()).isEqualTo(1);
        }

        @Test
        void ungroundedTurnIsZeroHit() {
            EvalScores scores = service.score("Hello there.", List.of());

            assertThat(scores.retrieval().isZeroHit()).isTrue();
            assertThat(scores.retrieval().topScore()).isZero();
        }
    }

    @Nested
    @DisplayName("golden case assertions")
    class GoldenAssertions {

        @Test
        void reportsMissingExpectedPhrases() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 111, "logging.level", 0.75));
            GoldenCase goldenCase = caseOf("logging", "how do I set log levels", List.of(111),
                                           List.of("logging.level", "spring.profiles.active"));

            EvalScores scores = service.score("Use logging.level.root=WARN.", retrieved, goldenCase);

            assertThat(scores.answer().matchedPhrases()).containsExactly("logging.level");
            assertThat(scores.answer().missingPhrases()).containsExactly("spring.profiles.active");
            assertThat(scores.answer().phraseCoverage()).isEqualTo(0.5);
            assertThat(scores.passed()).isFalse();
        }

        @Test
        void rankOfFirstExpectedPage() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 999, "irrelevant", 0.8),
                                               chunk("manual.pdf", 111, "logging.level", 0.75));
            GoldenCase goldenCase = caseOf("logging", "q", List.of(111), List.of());

            assertThat(service.firstRelevantRank(retrieved, goldenCase)).isEqualTo(2);
        }

        @Test
        void rankIsZeroWhenNoExpectedPageRetrieved() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 999, "irrelevant", 0.8));
            GoldenCase goldenCase = caseOf("logging", "q", List.of(111), List.of());

            assertThat(service.firstRelevantRank(retrieved, goldenCase)).isZero();
        }

        @Test
        @DisplayName("a case declaring no expected pages does not score recall")
        void noRecallScoringWithoutExpectedPages() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 111, "x", 0.75));
            GoldenCase goldenCase = caseOf("general", "q", List.of(), List.of());

            assertThat(goldenCase.scoresRecall()).isFalse();
            assertThat(service.firstRelevantRank(retrieved, goldenCase)).isZero();
        }
    }

    @Nested
    @DisplayName("declared expectations")
    class Expectations {

        @Test
        @DisplayName("refusing a question the assistant was expected to answer is a failure")
        void unexpectedRefusal() {
            // Guards the system prompt's General Knowledge capability. This is the case that breaks if
            // anyone restores QuestionAnswerAdvisor's stock "not prior knowledge" closing line.
            GoldenCase goldenCase = new GoldenCase("general", "what is a queue", null, null,
                                                   List.of(), List.of(), List.of(), false, false);

            EvalScores scores = service.score(
                    "I don't have enough information to answer that.", List.of(), goldenCase);

            assertThat(scores.answer().refused()).isTrue();
            assertThat(scores.expectations().refusalUnexpected()).isTrue();
            assertThat(scores.failureReasons()).anyMatch(r -> r.contains("declined to answer"));
        }

        @Test
        @DisplayName("retrieving nothing for a question the corpus should answer is a failure")
        void missingGrounding() {
            GoldenCase goldenCase = caseOf("actuator", "how are endpoints exposed", List.of(277), List.of());

            EvalScores scores = service.score("Some answer.", List.of(), goldenCase);

            assertThat(scores.expectations().groundingMissing()).isTrue();
            assertThat(scores.failureReasons()).anyMatch(r -> r.contains("retrieved nothing"));
        }

        @Test
        @DisplayName("an out-of-corpus case is not failed for retrieving nothing")
        void outOfCorpusCaseToleratesNoRetrieval() {
            GoldenCase goldenCase = new GoldenCase("out-of-corpus", "Acme revenue 2019", null, null,
                                                   List.of(), List.of(), List.of(), false, false);

            EvalScores scores = service.score("I don't have data on Acme Corporation.", List.of(), goldenCase);

            assertThat(scores.expectations().groundingMissing()).isFalse();
        }

        @Test
        @DisplayName("live traffic declares no expectations")
        void liveTrafficHasNoExpectations() {
            EvalScores scores = service.score("anything", List.of());
            assertThat(scores.expectations().anyUnmet()).isFalse();
        }
    }

    @Nested
    class AnswerShape {

        @Test
        @DisplayName("an answer that parrots the citation instruction is flagged")
        void detectsEchoedInstruction() {
            // Observed in production with llama3.2, which answered "Remember to cite your sources..."
            // in place of citing anything. Reads as a normal answer to every other metric.
            EvalScores scores = service.score(
                    "Remember to cite your sources when referencing configuration values.", List.of());

            assertThat(scores.answer().echoedInstruction()).isTrue();
            assertThat(scores.failureReasons()).anyMatch(r -> r.contains("echoed"));
        }

        @Test
        @DisplayName("a caveat late in a long answer is not a refusal")
        void refusalIsOnlyDetectedAtTheOpening() {
            String answer = "Actuator endpoints are exposed under /actuator. ".repeat(10)
                            + "Beyond that the context does not contain further detail.";

            assertThat(service.score(answer, List.of()).answer().refused()).isFalse();
        }
    }

    @Nested
    @DisplayName("context precision against the dataset's expected pages")
    class ContextPrecision {

        @Test
        @DisplayName("the expected page at rank 1 with noise below scores a perfect average")
        void expectedPageRankedFirst() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81),
                                               chunk("manual.pdf", 146, "unrelated", 0.64),
                                               chunk("manual.pdf", 299, "unrelated", 0.62));
            GoldenCase goldenCase = caseOf("actuator", "how are endpoints exposed", List.of(277), List.of());

            ContextPrecisionScores scores = service.contextPrecision(retrieved, goldenCase);

            assertThat(scores).isNotNull();
            // Nothing is mis-ordered, so the ranking score is perfect - while precision@k reports that
            // two thirds of what was retrieved was not on an expected page. Both readings are correct
            // and they are why the pair is kept together.
            assertThat(scores.averagePrecision()).isEqualTo(1.0);
            assertThat(scores.precisionAtK()).isCloseTo(1.0 / 3, within(1e-9));
            assertThat(scores.relevanceAsString()).isEqualTo("1,0,0");
        }

        @Test
        @DisplayName("the same page found late scores lower, which hit rate and MRR cannot show")
        void expectedPageRankedLast() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 146, "unrelated", 0.81),
                                               chunk("manual.pdf", 299, "unrelated", 0.70),
                                               chunk("manual.pdf", 277, "actuator endpoints", 0.64));
            GoldenCase goldenCase = caseOf("actuator", "how are endpoints exposed", List.of(277), List.of());

            ContextPrecisionScores scores = service.contextPrecision(retrieved, goldenCase);

            assertThat(scores).isNotNull();
            assertThat(scores.averagePrecision()).isCloseTo(1.0 / 3, within(1e-9));
            assertThat(service.firstRelevantRank(retrieved, goldenCase)).isEqualTo(3);
        }

        @Test
        @DisplayName("a case declaring no expected pages is not scored at all")
        void notScoredWithoutExpectedPages() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 277, "actuator endpoints", 0.81));
            GoldenCase goldenCase = caseOf("conversational", "hello", List.of(), List.of());

            // Null rather than 0.0. A capability case has not scored badly on retrieval; it has not
            // asserted anything about retrieval, and averaging it in would drag the suite down.
            assertThat(service.contextPrecision(retrieved, goldenCase)).isNull();
        }

        @Test
        @DisplayName("a page from the wrong document is not relevant")
        void wrongDocumentIsNotRelevant() {
            List<Document> retrieved = List.of(chunk("other.pdf", 277, "coincidental page number", 0.81));
            GoldenCase goldenCase = caseOf("actuator", "how are endpoints exposed", List.of(277), List.of());

            ContextPrecisionScores scores = service.contextPrecision(retrieved, goldenCase);

            assertThat(scores).isNotNull();
            assertThat(scores.relevantCount()).isZero();
        }

        @Test
        @DisplayName("rank and precision agree, because they read one definition of relevance")
        void rankAndPrecisionAgree() {
            List<Document> retrieved = List.of(chunk("manual.pdf", 111, "logging.level", 0.79),
                                               chunk("manual.pdf", 112, "TRACE to OFF", 0.75));
            GoldenCase goldenCase = caseOf("logging", "which property sets the log level",
                                           List.of(111, 112), List.of());

            ContextPrecisionScores scores = service.contextPrecision(retrieved, goldenCase);

            assertThat(scores).isNotNull();
            assertThat(scores.relevantCount()).isEqualTo(2);
            assertThat(scores.averagePrecision()).isEqualTo(1.0);
            assertThat(scores.precisionAtK()).isEqualTo(1.0);
            assertThat(service.firstRelevantRank(retrieved, goldenCase)).isEqualTo(1);
        }
    }
}
