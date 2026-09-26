package com.example.subhanmishra;

import com.example.subhanmishra.service.GoldenEvalService;
import com.example.subhanmishra.service.GoldenEvalService.GoldenRunResult;
import com.example.subhanmishra.service.eval.GoldenDataset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the curated evaluation suite against the live corpus and asserts the pipeline has not
 * regressed.
 *
 * <p><strong>Excluded from {@code ./mvnw test}</strong> by the surefire {@code excludedGroups}
 * configuration in {@code pom.xml}. It needs Postgres, Redis and Ollama running with the reference
 * manual already indexed, and it takes minutes - a single grounded answer on the dev host is 53-70
 * seconds. Run it deliberately:
 *
 * <pre>{@code ./mvnw test -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT}</pre>
 *
 * <p>Note that {@code -Dgroups=eval} alone does <em>not</em> work. In JUnit 5 tag filtering an
 * exclusion beats an inclusion, so the tag stays excluded however it is included; the exclusion itself
 * has to be cleared, which is why {@code excludedGroups} is bound to a property in {@code pom.xml}.
 *
 * <p>The thresholds below are intentionally loose. This is a <em>regression</em> guard, not a quality
 * bar: generation is not deterministic even at a low temperature, so an assertion tuned to the current
 * scores would fail on an unchanged pipeline roughly as often as on a broken one. The numbers to watch
 * are the ones on the Grafana dashboard over time; what this test catches is a pipeline that has
 * broken outright - retrieval returning nothing, or the model inventing citations.
 *
 * <p>Judging is left off. It roughly triples the wall clock and its verdicts come from the chat model
 * grading its own output, which is too soft a signal to gate a build on. Enable it deliberately when
 * investigating a quality change, by passing {@code true} to
 * {@link GoldenEvalService#run(GoldenDataset, boolean)}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Tag("eval")
class EvalSuiteIT {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteIT.class);

    /**
     * A ratchet, and one that has moved a long way. Read the whole of this before changing it, because
     * the number has meant four different things and its history is the history of this pipeline.
     *
     * <p><strong>Every fabrication ever measured here came from the corpus offering the model a second,
     * wrong number - never from the model inventing one.</strong> That held across three distinct
     * causes, each found only once the one before it was fixed:
     * <ul>
     *   <li>The <em>printed</em> page number in the page footer, which differs from the PDF page by 19
     *       in this manual - PDF 299 is printed 280. It sat at the end of the passage because the
     *       reader extracts it with the content, so the model was offered two numbers and preferred the
     *       one that looked like part of the document. {@code PageFooterStripper} removed it: 0.250 to
     *       0.176.</li>
     *   <li>The page number at the end of a table-of-contents line. With the footers gone the model
     *       cited page 263, having read {@code "5.2.4. Configuring Endpoints . . . 263"} off a contents
     *       page it had retrieved. {@code TocEntryStripper} removed those: 0.176 to 0.105.</li>
     *   <li>The <em>section heading</em> inside a passage - "(spring-boot-reference.pdf, p. 5.3)",
     *       where 5.3 is the heading "5.3. Monitoring and Management over HTTP". Unlike the first two
     *       this one could not be stripped at parse time, because a heading is real content. It is
     *       fixed after generation instead, by {@code CitationResolver}: 0.222 to <strong>0.000</strong>.</li>
     * </ul>
     *
     * <p><strong>Why the third fix is a rewrite rather than a looser rule.</strong> The model was never
     * guessing - measured across the corpus, no section number heads more than one page, and every
     * label the suite ever saw it emit resolved to exactly one <em>retrieved</em> chunk. It was naming
     * the right passage with the wrong kind of identifier, so the answer is rewritten to the page that
     * heading sits on, before the reader sees it and before it is scored. A label that resolves to
     * nothing, or to two pages, is left alone and still counts here - which is what stops this class of
     * fix from quietly absorbing a model that really has started guessing.
     *
     * <p><strong>Prompt wording was tried first and did not work.</strong> Both
     * {@code SpringAiConfig.QA_PROMPT_TEMPLATE} and the system prompt say a page is a whole number and a
     * dotted heading number is a section, with "do not write (filename, p. 5.3)" spelled out. Two runs
     * afterwards were entirely unchanged, and the model still emits section numbers today - 47 of them
     * across the five runs below, every one now resolved rather than prevented. Keep the instruction,
     * it costs nothing; do not expect a better wording to succeed where that one did not.
     *
     * <p><strong>Where the number stands.</strong> Five runs with the resolver in place measured 0.000
     * every time, across 82 emitted citations, at 9 of 9 cases passing, with hit rate and MRR at 1.000
     * - so this threshold has gone from 0.40 to 0.10. It is not set to zero deliberately: generation is
     * not deterministic even at temperature 0.2, and a single stray citation should not break a build.
     * At 0.10 a typical 16-citation run tolerates one and fails on two, which catches a rate running
     * away without failing on noise.
     *
     * <p>The fifth run is worth separating from the other four, because it is the only one whose corpus
     * was not identical: it followed re-ingesting a second document, so retrieval was searching 957
     * chunks rather than 946. Every retrieved page was still from the manual and every page set matched
     * the earlier runs exactly, which is the evidence that an unrelated document does not perturb these
     * cases - not merely that the scores happened to repeat.
     *
     * <p>If this does fail, the first thing to check is
     * {@code rag.eval.online.citations.resolved.total{outcome="abstained"}}: section numbers the
     * resolver could not place are the model genuinely guessing, and no amount of resolving will fix
     * that.
     */
    private static final double MAX_CITATION_FABRICATION = 0.10;

    /**
     * Zero, and it has <em>been</em> zero on every run since the parser strippers landed - nine runs
     * and 157 emitted citations.
     *
     * <p>This is the assertion with teeth, and it kept its teeth through the period when the rate above
     * could not. An invented page number is a citation naming a plain page the retrieved context never
     * offered, which is the failure the citation header, both prompts, both strippers and the resolver
     * all exist to prevent, and a reader following one lands somewhere unrelated.
     *
     * <p>It is kept separate from the fabrication rate even though the two now agree, because they are
     * different assertions about different failures and only happen to coincide while the pipeline is
     * healthy. This one says no reader was sent to a page that does not exist; the one above says
     * citations in general are sound. Collapsing them would lose the distinction exactly when it
     * matters.
     *
     * <p>If this fails, something is genuinely broken. Check in this order: is the corpus stale (a
     * document ingested by an older pipeline version still carries page footers, so its citations are 19
     * pages out); did chunking change the page boundaries the dataset was verified against; only then
     * suspect the model.
     */
    private static final int MAX_INVENTED_PAGES = 0;

    /** Retrieval must find the expected page for most recall cases. Well below the observed level. */
    private static final double MIN_HIT_RATE = 0.6;

    // Context precision is logged and not asserted, deliberately, and it should stay that way until
    // several runs have established what it does on an unchanged pipeline. This suite already learned
    // that lesson the expensive way: MAX_CITATION_FABRICATION was set from a single run's score and
    // then failed on variance alone, because the underlying rate moved between 0.222 and 0.333 with
    // nothing changed. MAX_INVENTED_PAGES was the assertion that held, and it was chosen only after
    // four runs showed which of the two numbers was stable.
    //
    // There is a second reason to wait here specifically. The reference-based precision is bounded
    // above by how complete the dataset's expectedPages lists are, not by how well retrieval ranks, so
    // its ceiling is unknown until the judged run says what the judge considers useful. A threshold
    // set before that comparison would be a threshold on the dataset's curation.

    @Autowired
    private GoldenEvalService goldenEvalService;

    @Test
    @DisplayName("the golden suite runs and the pipeline has not regressed")
    void goldenSuiteHasNotRegressed() {
        GoldenDataset dataset = goldenEvalService.dataset();
        assertThat(dataset.cases())
                .as("the dataset should not be empty - check %s", dataset.suite())
                .isNotEmpty();

        GoldenRunResult result = goldenEvalService.run(dataset, false);

        log.info("""

                        ==================== eval suite: {} ====================
                        cases              : {}
                        passed             : {} ({}%)
                        hit rate           : {}
                        MRR                : {}
                        context precision  : {}  (precision@k {})
                        judged precision   : {}  (precision@k {})
                        citations emitted  : {}
                        citation validity  : {}
                        fabrication rate   : {}
                        invented pages     : {}
                        duration           : {} ms
                        failures           : {}
                        ========================================================""",
                 result.suite(), result.caseCount(), result.passedCount(),
                 Math.round(result.passRate() * 100), fmt(result.hitRate()), fmt(result.meanReciprocalRank()),
                 fmt(result.contextPrecision()), fmt(result.precisionAtK()),
                 fmt(result.judgedContextPrecision()), fmt(result.judgedPrecisionAtK()),
                 result.citationsEmitted(), fmt(result.citationValidity()), fmt(result.citationFabrication()),
                 result.inventedPageCount(), result.durationMillis(), result.failures());

        assertThat(result.inventedPageCount())
                .as("the model cited plain page numbers the context never offered, which the citation "
                    + "header and the parser strippers exist to prevent: %s", result.failures())
                .isLessThanOrEqualTo(MAX_INVENTED_PAGES);

        assertThat(result.citationFabrication())
                .as("citations are no longer sound - check whether CitationResolver is abstaining, "
                    + "which means the model is emitting section numbers matching nothing it was "
                    + "shown: %s", result.failures())
                .isLessThanOrEqualTo(MAX_CITATION_FABRICATION);

        assertThat(result.hitRate())
                .as("retrieval is not finding the expected pages - check the corpus is indexed and that "
                    + "chunking has not changed the page boundaries the dataset was verified against")
                .isGreaterThanOrEqualTo(MIN_HIT_RATE);
    }

    private static String fmt(double value) {
        return "%.3f".formatted(value);
    }

    /** "n/a" for a metric this run did not measure, which is not the same as one that scored zero. */
    private static String fmt(Double value) {
        return value != null ? fmt(value.doubleValue()) : "n/a";
    }
}
