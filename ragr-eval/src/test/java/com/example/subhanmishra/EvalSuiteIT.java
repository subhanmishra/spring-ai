package com.example.subhanmishra;

import com.example.subhanmishra.config.EvalProperties;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the curated evaluation suite against the live corpus and asserts the pipeline has not
 * regressed.
 *
 * <p><strong>Excluded from {@code ./mvnw test}</strong> by the surefire {@code excludedGroups}
 * configuration in {@code pom.xml}. It drives the running {@code ragr-app} over HTTP and reads its
 * turns back from Kafka, so it needs {@code ragr-app} up with the whole compose stack and Ollama, and
 * the Markdown library already indexed. It takes minutes - a single grounded answer on the dev host
 * is 53-70 seconds. Run it deliberately:
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
 * <p>Judging follows {@code app.eval.golden.judged}, off by default. It roughly triples the wall clock
 * and its verdicts come from the chat model grading its own output, which is too soft a signal to gate
 * a build on - so nothing below asserts on it. Switch it on deliberately when investigating a quality
 * change, or to fill the dashboard's LLM-judged precision:
 *
 * <pre>{@code ./mvnw test -pl ragr-eval -am -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT -Dapp.eval.golden.judged=true}</pre>
 */
// The online listener and the judge worker are left stopped: this JVM runs the golden suite, and a second
// member of the ragr-eval consumer group would take the partition away from the running ragr-eval
// application, while a second worker would take its queued live turns.
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "app.eval.judge.worker-enabled=false"})
@Tag("eval")
class EvalSuiteIT {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteIT.class);

    /**
     * A ratchet. The share of emitted citations that point at nothing the turn retrieved.
     *
     * <p><strong>On the PDF manual every fabrication measured here came from the corpus offering the model
     * a second, wrong number - never from the model inventing a source.</strong> A printed footer page, a
     * contents-page number and a section heading were each cited as a page, and each was fixed in turn
     * (two parser strippers, then a post-generation rewrite): 0.25 to 0.000 over five runs. The history
     * is in the evaluation context document.
     *
     * <p><strong>The Markdown corpus has no pages,</strong> so none of those numbers exist to be miscited:
     * a citation is the file name alone, and a fabrication is a file the turn never retrieved - or any
     * page at all. The rewrite was removed with the PDF corpus. Prompt wording never fixed the section
     * habit, so if a PDF corpus returns, expect it back.
     *
     * <p>Not zero, deliberately: generation is not deterministic even at temperature 0.2, and one stray
     * citation should not break a build. A typical run of 16-20 citations tolerates one and fails on two.
     *
     * <p>If this fails, read the cited names in the failures first. The file names are long
     * ({@code <folder>__<file>.md}), and one copied with a typo is a different problem from one invented
     * outright.
     */
    private static final double MAX_CITATION_FABRICATION = 0.10;

    /**
     * Zero. It held on every PDF run once the parser strippers landed - nine runs and 157 emitted
     * citations - and against page-less sources any page cited is invented by definition.
     *
     * <p>It is kept separate from the fabrication rate because they are different assertions about
     * different failures: this one says no reader was sent to a page that does not exist; the one above
     * says citations in general are sound.
     *
     * <p>If this fails, something is genuinely broken. Check whether the corpus was re-ingested after the
     * last pipeline change, then whether chunk headers still carry the citation line, and only then
     * suspect the model.
     */
    private static final int MAX_INVENTED_PAGES = 0;

    /** Retrieval must find an expected file or page for most recall cases. Well below the observed level. */
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

    @Autowired
    private EvalProperties evalProperties;

    @Test
    @DisplayName("the golden suite runs and the pipeline has not regressed")
    void goldenSuiteHasNotRegressed() {
        GoldenDataset dataset = goldenEvalService.dataset();
        assertThat(dataset.cases())
                .as("the dataset should not be empty - check %s", dataset.suite())
                .isNotEmpty();

        GoldenRunResult result = goldenEvalService.run(dataset, evalProperties.golden().judged());

        log.info("""

                        ==================== eval suite: {} ====================
                        cases              : {}
                        passed             : {} ({}%)
                        hit rate           : {}
                        MRR                : {}
                        context precision  : {}  (precision@k {})
                        judged precision   : {}  (precision@k {})
                        cited precision    : {}  (precision@k {})
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
                 fmt(result.citedContextPrecision()), fmt(result.citedPrecisionAtK()),
                 result.citationsEmitted(), fmt(result.citationValidity()), fmt(result.citationFabrication()),
                 result.inventedPageCount(), result.durationMillis(), result.failures());

        assertThat(result.inventedPageCount())
                .as("the model cited page numbers the context never offered - against page-less "
                    + "sources, any page at all: %s", result.failures())
                .isLessThanOrEqualTo(MAX_INVENTED_PAGES);

        assertThat(result.citationFabrication())
                .as("citations are no longer sound - the model cited files or pages it was not shown; "
                    + "compare the cited names with the retrieved ones: %s", result.failures())
                .isLessThanOrEqualTo(MAX_CITATION_FABRICATION);

        assertThat(result.hitRate())
                .as("retrieval is not finding the expected files or pages - check the corpus is indexed, "
                    + "under the upload names the dataset uses")
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
