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
 * <pre>{@code ./mvnw test -Dgroups=eval}</pre>
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
     * A ratchet documenting a known model behaviour, not a target. Read the whole of this before
     * changing it, because the number has meant three different things.
     *
     * <p><strong>What has been fixed.</strong> The suite's first run measured 0.25, and every one of
     * those was the corpus offering the model a second, wrong number rather than the model inventing
     * anything. Twice over:
     * <ul>
     *   <li>The <em>printed</em> page number in the page footer, which differs from the PDF page by 19
     *       in this manual - PDF 299 is printed 280. It sat at the end of the passage because the
     *       reader extracts it with the content, so the model was offered two numbers and preferred the
     *       one that looked like part of the document. {@code PageFooterStripper} removed it: 0.250 to
     *       0.176.</li>
     *   <li>The page number at the end of a table-of-contents line. With the footers gone the model
     *       cited page 263, having read {@code "5.2.4. Configuring Endpoints . . . 263"} off a contents
     *       page it had retrieved. {@code TocEntryStripper} removed those: 0.176 to 0.105.</li>
     * </ul>
     * Both are gone and should stay gone. <strong>No run since has produced an invented page number.</strong>
     *
     * <p><strong>What remains, and why this is not zero.</strong> Every residual fabrication is the
     * model writing a <em>section</em> number where a page belongs - "(spring-boot-reference.pdf, p.
     * 5.3)", where 5.3 is the heading "5.3. Endpoints" inside a passage that came from page 277. Two
     * cases reproduce it reliably: {@code actuator-http-exposure} emits 5.3, 5.2.5 and 5.3.1 on every
     * run, and {@code logging-level-property} emits 5.5.1. The same answers cite real pages correctly
     * alongside them, so the model is mixing two conventions rather than guessing.
     *
     * <p>Measured after both parser fixes: <strong>0.250, 0.222, 0.333, 0.222</strong>, at 6 to 7 of 9
     * cases passing, with hit rate and MRR at 1.000 throughout. Note those numbers are <em>not</em>
     * comparable with the 0.105 above: {@code CitationParser} used to truncate "p. 5.3" to page 5, which
     * both mis-named the failure and collapsed three distinct section citations into one. The rate went
     * up when the scorer stopped under-counting; nothing about the model changed.
     *
     * <p><strong>Adding an instruction to the prompt was tried and did not work.</strong> Both
     * {@code SpringAiConfig.QA_PROMPT_TEMPLATE} and the system prompt now say a page is a whole number
     * and a dotted heading number is a section, with "do not write (filename, p. 5.3)" spelled out. The
     * two cases above were unchanged across both runs afterwards. Keep the instruction - it costs
     * nothing and the failure is at least described - but do not expect the next wording to succeed
     * where this one did not. Consistent with the rest of this pipeline, the chat model is the
     * load-bearing half of citation fidelity; a fix is more likely to come from a different model, or
     * from validating citations against the retrieved headers after generation, than from prompt
     * wording.
     *
     * <p><strong>This is the loose backstop, not the real guard.</strong> Four runs on an unchanged
     * pipeline measured 0.250, 0.222, 0.333 and 0.222, because how many section numbers the model emits
     * varies from run to run - {@code profiles-activation} contributed two on one run and none on the
     * other three. A threshold
     * tight enough to be meaningful would fail on that variance alone, so the assertion that actually
     * catches a regression is {@link #MAX_INVENTED_PAGES} below. This one is set above the worst
     * observed run and only catches the rate running away entirely.
     */
    private static final double MAX_CITATION_FABRICATION = 0.40;

    /**
     * Zero, and unlike the rate above it has <em>been</em> zero on every run since the parser strippers
     * landed - four runs and 75 emitted citations, every fabrication among them a dotted section
     * number.
     *
     * <p>This is the assertion with teeth. An invented page number is a citation naming a plain page the
     * retrieved context never offered, which is the failure the citation header, both prompts and both
     * strippers exist to prevent, and a reader following one lands somewhere unrelated. It is also
     * stable in a way the overall fabrication rate is not, because it excludes the section-number
     * behaviour described above - which is a real defect, but a known one that no longer tells you
     * anything when it moves.
     *
     * <p>If this fails, something is genuinely broken. Check in this order: is the corpus stale (a
     * document ingested by an older pipeline version still carries page footers, so its citations are 19
     * pages out); did chunking change the page boundaries the dataset was verified against; only then
     * suspect the model.
     */
    private static final int MAX_INVENTED_PAGES = 0;

    /** Retrieval must find the expected page for most recall cases. Well below the observed level. */
    private static final double MIN_HIT_RATE = 0.6;

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
                        citations emitted  : {}
                        citation validity  : {}
                        fabrication rate   : {}
                        invented pages     : {}
                        duration           : {} ms
                        failures           : {}
                        ========================================================""",
                 result.suite(), result.caseCount(), result.passedCount(),
                 Math.round(result.passRate() * 100), fmt(result.hitRate()), fmt(result.meanReciprocalRank()),
                 result.citationsEmitted(), fmt(result.citationValidity()), fmt(result.citationFabrication()),
                 result.inventedPageCount(), result.durationMillis(), result.failures());

        assertThat(result.inventedPageCount())
                .as("the model cited plain page numbers the context never offered, which the citation "
                    + "header and the parser strippers exist to prevent: %s", result.failures())
                .isLessThanOrEqualTo(MAX_INVENTED_PAGES);

        assertThat(result.citationFabrication())
                .as("citation fabrication has run away beyond the known section-number behaviour: %s",
                    result.failures())
                .isLessThanOrEqualTo(MAX_CITATION_FABRICATION);

        assertThat(result.hitRate())
                .as("retrieval is not finding the expected pages - check the corpus is indexed and that "
                    + "chunking has not changed the page boundaries the dataset was verified against")
                .isGreaterThanOrEqualTo(MIN_HIT_RATE);
    }

    private static String fmt(double value) {
        return "%.3f".formatted(value);
    }
}
