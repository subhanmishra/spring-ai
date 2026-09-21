package com.example.subhanmishra.service;

import com.example.subhanmishra.service.eval.EvalScores;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.entity.EvalRunStatus;
import com.example.subhanmishra.repository.EvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Publishes evaluation results as Micrometer meters, which Prometheus scrapes and Grafana renders.
 *
 * <p>Two different shapes of instrument are used, and mixing them up produces a dashboard that lies:
 *
 * <ul>
 *   <li><strong>Counters</strong> carry the online path. Live traffic arrives one turn at a time and
 *       forever, so the useful question is a rate or a ratio over a window - and a ratio of two
 *       counters stays correct however Prometheus aligns its scrapes. A gauge holding "the last
 *       answer's citation validity" would show whatever the most recent single request happened to do.</li>
 *   <li><strong>Gauges</strong> carry the golden path. A suite run is a batch job that finishes, and
 *       its aggregate score is a single number with no rate interpretation at all.</li>
 * </ul>
 *
 * <p>The consequence of that second choice is worth stating plainly, because it is the classic way a
 * batch-job dashboard misleads: <strong>a gauge holds its last value forever.</strong> The scores from
 * a run in March are still being scraped in June, looking exactly as current as a run from an hour ago.
 * {@code rag.eval.golden.last.run.timestamp} exists solely so a panel can say how old the number it is
 * showing actually is, and the Grafana dashboard puts that age next to the scores rather than in a
 * corner.
 *
 * <p>None of these meters get percentile histograms. {@code application-dev.yaml} enables
 * {@code percentiles-histogram} for an explicit list of meter-name prefixes and {@code rag.eval} is
 * not among them, so the timers here export {@code _count}, {@code _sum} and {@code _max} only - rates
 * and averages work, quantiles do not. That is intentional: the histogram buckets were already 92% of
 * the scrape body before this feature existed.
 */
@Service
public class EvalMetricsService {

    private static final Logger log = LoggerFactory.getLogger(EvalMetricsService.class);

    private static final String ONLINE = "rag.eval.online.";
    private static final String GOLDEN = "rag.eval.golden.";

    /**
     * How long a golden snapshot read from the database is trusted before it is re-read.
     *
     * <p>Refresh is driven by scrapes rather than by a scheduler: a Micrometer gauge's value function
     * is evaluated when Prometheus scrapes it, so consulting the database from there needs no
     * {@code @EnableScheduling} and costs nothing while nobody is looking. Prometheus scrapes every
     * 15s, so this bounds the work at roughly one indexed single-row query per 30s.
     */
    private static final Duration GOLDEN_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final MeterRegistry registry;

    /**
     * Where a golden run's scores are read back from.
     *
     * <p>This exists because of a gap that only showed up when the dashboard was checked against a real
     * run: the suite is triggered by a tagged JUnit test, which runs in its OWN JVM with its own meter
     * registry. Prometheus scrapes the application, not the test, so a run executed that way published
     * its gauges into a registry that was discarded when the test ended - and the dashboard sat at zero
     * while the results were sitting in Postgres all along.
     *
     * <p>So the durable record is the {@code eval_run} table, and these gauges report the latest row
     * from it, whichever process produced it. An in-process run additionally sets the fields directly,
     * so it shows up immediately rather than at the next refresh.
     *
     * <p>The consequence to know: <strong>golden gauges require {@code app.eval.golden.persist=true}.</strong>
     * With persistence off a run still logs and still returns its result, but nothing reaches Grafana.
     */
    private final EvalRunRepository runRepository;

    private final AtomicLong lastRefreshedAt = new AtomicLong();
    private final AtomicReference<Instant> lastAppliedRun = new AtomicReference<>();

    /**
     * Whether any run has ever produced a judge verdict.
     *
     * <p>Until one has, the two judged gauges report {@code NaN} rather than 0.0, because Prometheus
     * renders NaN as absent and 0.0 as "zero percent passed". Judging is off by default - it roughly
     * triples a run's wall clock - so the overwhelmingly common case is no verdicts at all, and
     * reporting that as total failure would put two alarming red zeroes on the dashboard describing
     * something nobody measured.
     */
    private final AtomicBoolean relevancyEverJudged = new AtomicBoolean();
    private final AtomicBoolean groundednessEverJudged = new AtomicBoolean();

    /**
     * Gauge backing state for the golden path. Micrometer holds only a weak reference to whatever a
     * gauge reads, so these have to be strong fields on a singleton - a locally created holder is
     * collected and the gauge silently starts reporting NaN.
     */
    private final DoubleAdder goldenHitRate = new DoubleAdder();
    private final DoubleAdder goldenMrr = new DoubleAdder();
    private final DoubleAdder goldenCitationValidity = new DoubleAdder();
    private final DoubleAdder goldenCitationFabrication = new DoubleAdder();
    private final DoubleAdder goldenRelevancy = new DoubleAdder();
    private final DoubleAdder goldenGroundedness = new DoubleAdder();
    private final DoubleAdder goldenPassRate = new DoubleAdder();
    private final AtomicInteger goldenCaseCount = new AtomicInteger();
    private final AtomicLong goldenLastRunEpochSeconds = new AtomicLong();

    /** The two LLM-judged metrics, as they are tagged. */
    private static final List<String> JUDGE_METRICS = List.of("relevancy", "groundedness");

    public EvalMetricsService(MeterRegistry registry, EvalRunRepository runRepository) {
        this.registry = registry;
        this.runRepository = runRepository;
        registerGoldenGauges();
        preRegisterOnlineMeters();
    }

    /**
     * Creates every fixed-tag online meter at zero, so its series exists before the first event.
     *
     * <p>Without this the dashboard lies by omission. A Micrometer counter is created lazily on first
     * use, so a counter for something that has not happened yet has no series at all - Prometheus
     * returns nothing and Grafana renders "No data" rather than 0. For the health signals that is
     * precisely backwards: the good state of {@code refusals.total},
     * {@code instruction.echoes.total} and {@code chunks.without.header.total} is zero, and "nothing
     * has gone wrong" ended up indistinguishable from "this metric is broken". Three panels read as
     * empty on a working system.
     *
     * <p>{@code register()} is idempotent - it returns the existing meter when one is already there -
     * so this only forces creation and never resets a counter, including on a re-registration.
     *
     * <p>The tagged counters have to be enumerated over their whole tag cross-product, because a series
     * exists per tag combination rather than per name. That is only tractable where the tag values are
     * a closed set, which is why the golden {@code cases.total{suite,case}} and {@code runs.total{suite}}
     * counters are deliberately absent here: their tags come from whichever dataset is run, so they
     * cannot be enumerated in advance. No panel depends on them - the golden row reads the gauges, which
     * are registered eagerly - and they appear on the first suite run.
     */
    private void preRegisterOnlineMeters() {
        counter(ONLINE + "turns.total", Tags.empty());
        counter(ONLINE + "zero.hit.total", Tags.empty());
        counter(ONLINE + "uncited.answers.total", Tags.empty());
        counter(ONLINE + "refusals.total", Tags.empty());
        counter(ONLINE + "instruction.echoes.total", Tags.empty());
        counter(ONLINE + "chunks.without.header.total", Tags.empty());
        counter(ONLINE + "judgements.dropped.total", Tags.empty());

        for (String outcome : List.of("valid", "fabricated")) {
            counter(ONLINE + "citations.total", Tags.of("outcome", outcome));
        }
        for (String outcome : List.of("repaired", "abstained")) {
            counter(ONLINE + "citations.resolved.total", Tags.of("outcome", outcome));
        }
        for (String metric : JUDGE_METRICS) {
            counter(ONLINE + "judgements.errors.total", Tags.of("metric", metric));
            for (String outcome : List.of("pass", "fail")) {
                counter(ONLINE + "judgements.total", Tags.of("metric", metric, "outcome", outcome));
            }
        }

        // Summaries have the same lazy-creation behaviour, so the retrieval-quality panel would also
        // read as empty until the first chat turn on a freshly started application.
        registry.summary(ONLINE + "retrieved.chunks");
        registry.summary(ONLINE + "top.score");
        registry.summary(ONLINE + "score.spread");
        registry.summary(ONLINE + "answer.chars");
    }

    /**
     * Re-reads the latest persisted run when the cached snapshot has gone stale.
     *
     * <p>Called from every golden gauge's value function, so it runs on the scrape thread. It must
     * therefore never throw and never block for long: a failure here would break the whole
     * {@code /actuator/prometheus} response, taking out every unrelated metric with it. On any error
     * the previous values simply stand.
     */
    private void refreshGoldenIfStale() {
        long now = System.currentTimeMillis();
        long last = lastRefreshedAt.get();
        if (now - last < GOLDEN_REFRESH_INTERVAL.toMillis() || !lastRefreshedAt.compareAndSet(last, now)) {
            return;
        }
        try {
            runRepository.findAllByOrderByStartedAtDesc().stream()
                         .filter(run -> run.status() == EvalRunStatus.COMPLETED)
                         .findFirst()
                         .ifPresent(this::applyIfNewer);
        } catch (RuntimeException e) {
            log.debug("Could not refresh golden eval gauges from the database; keeping previous values", e);
        }
    }

    private void applyIfNewer(EvalRun run) {
        Instant applied = lastAppliedRun.get();
        if (applied != null && !run.startedAt().isAfter(applied)) {
            return;
        }
        lastAppliedRun.set(run.startedAt());

        setIfPresent(goldenHitRate, run.hitRate());
        setIfPresent(goldenMrr, run.meanReciprocalRank());
        setIfPresent(goldenCitationValidity, run.citationValidity());
        setIfPresent(goldenCitationFabrication, run.citationFabrication());
        if (run.relevancyRate() != null) {
            set(goldenRelevancy, run.relevancyRate());
            relevancyEverJudged.set(true);
        }
        if (run.groundednessRate() != null) {
            set(goldenGroundedness, run.groundednessRate());
            groundednessEverJudged.set(true);
        }
        if (run.caseCount() > 0) {
            set(goldenPassRate, (double) run.passedCount() / run.caseCount());
        }
        goldenCaseCount.set(run.caseCount());
        goldenLastRunEpochSeconds.set((run.finishedAt() != null ? run.finishedAt() : run.startedAt())
                                              .getEpochSecond());
        log.info("Golden eval gauges refreshed from run started at {} [{}/{} passed]",
                 run.startedAt(), run.passedCount(), run.caseCount());
    }

    // ---------------------------------------------------------------- online

    /**
     * Records one live chat turn's deterministic scores. Called on the request thread, so it must stay
     * allocation-light and must never throw - a metrics failure cannot be allowed to fail a chat
     * response that has already been generated.
     */
    public void recordOnline(EvalScores scores) {
        counter(ONLINE + "turns.total", Tags.empty()).increment();

        var retrieval = scores.retrieval();
        registry.summary(ONLINE + "retrieved.chunks").record(retrieval.retrievedCount());
        if (retrieval.isZeroHit()) {
            counter(ONLINE + "zero.hit.total", Tags.empty()).increment();
        } else {
            registry.summary(ONLINE + "top.score").record(retrieval.topScore());
            registry.summary(ONLINE + "score.spread").record(retrieval.scoreSpread());
        }
        // Chunks that predate the citation header cannot be cited. A non-zero count here means the
        // citation metrics below are being scored against a context that was never fully citable, and
        // the fix is to re-ingest, not to tune the prompt.
        int withoutHeader = retrieval.retrievedCount() - retrieval.chunksWithHeader();
        if (withoutHeader > 0) {
            counter(ONLINE + "chunks.without.header.total", Tags.empty()).increment(withoutHeader);
        }

        var citations = scores.citations();
        if (citations.emitted() > 0) {
            counter(ONLINE + "citations.total", Tags.of("outcome", "valid")).increment(citations.valid());
            counter(ONLINE + "citations.total", Tags.of("outcome", "fabricated")).increment(citations.fabricated());
        } else {
            // Counted separately rather than folded into the validity rate, which an answer citing
            // nothing would otherwise make vacuously perfect.
            counter(ONLINE + "uncited.answers.total", Tags.empty()).increment();
        }

        if (scores.answer().refused()) {
            counter(ONLINE + "refusals.total", Tags.empty()).increment();
        }
        if (scores.answer().echoedInstruction()) {
            counter(ONLINE + "instruction.echoes.total", Tags.empty()).increment();
        }
        registry.summary(ONLINE + "answer.chars").record(scores.answer().answerChars());
    }

    /**
     * Records what {@code CitationResolver} did to one answer's section-number citations.
     *
     * <p>This pair is why the resolver cannot quietly mask a degrading model. A repair is a section
     * number that resolved to a page the model was actually shown; an abstention is one that did not,
     * and which therefore went on being counted as fabricated. <strong>Watch the ratio, not either
     * count alone.</strong> Repairs rising on their own is the known {@code gemma4:e2b} behaviour
     * being corrected as designed; abstentions rising is the model emitting section numbers that
     * correspond to nothing it was given, which is the genuine hallucination this pipeline exists to
     * prevent and which no amount of resolving will fix.
     */
    public void recordCitationResolution(int repaired, int abstained) {
        if (repaired > 0) {
            counter(ONLINE + "citations.resolved.total", Tags.of("outcome", "repaired")).increment(repaired);
        }
        if (abstained > 0) {
            counter(ONLINE + "citations.resolved.total", Tags.of("outcome", "abstained")).increment(abstained);
        }
    }

    /** Records an LLM judge verdict from the online path. */
    public void recordOnlineJudgement(String metric, boolean passed, long durationMillis) {
        counter(ONLINE + "judgements.total", Tags.of("metric", metric, "outcome", passed ? "pass" : "fail"))
                .increment();
        Timer.builder(ONLINE + "judge.duration")
             .tag("metric", metric)
             .register(registry)
             .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * A judgement that never ran. Dropped means admission was refused because the concurrency bound was
     * already taken; these are expected under load and are the signal that the sample rate is set too
     * high for the traffic. Errors are judge calls that started and broke.
     */
    public void recordJudgementDropped() {
        counter(ONLINE + "judgements.dropped.total", Tags.empty()).increment();
    }

    public void recordJudgementError(String metric) {
        counter(ONLINE + "judgements.errors.total", Tags.of("metric", metric)).increment();
    }

    // ---------------------------------------------------------------- golden

    /** Publishes the aggregate scores of a completed suite run. */
    public void recordGoldenRun(String suite,
                                int caseCount,
                                double passRate,
                                double hitRate,
                                double mrr,
                                double citationValidity,
                                double citationFabrication,
                                @Nullable Double relevancyRate,
                                @Nullable Double groundednessRate,
                                long durationMillis) {
        set(goldenHitRate, hitRate);
        set(goldenMrr, mrr);
        set(goldenCitationValidity, citationValidity);
        set(goldenCitationFabrication, citationFabrication);
        set(goldenPassRate, passRate);
        // Left at their previous value when a run does not judge, rather than reset to zero, which
        // would render as a catastrophic quality drop on the dashboard instead of "not measured".
        if (relevancyRate != null) {
            set(goldenRelevancy, relevancyRate);
            relevancyEverJudged.set(true);
        }
        if (groundednessRate != null) {
            set(goldenGroundedness, groundednessRate);
            groundednessEverJudged.set(true);
        }
        goldenCaseCount.set(caseCount);
        goldenLastRunEpochSeconds.set(System.currentTimeMillis() / 1000);

        Timer.builder(GOLDEN + "run.duration")
             .tag("suite", suite)
             .register(registry)
             .record(durationMillis, TimeUnit.MILLISECONDS);
        counter(GOLDEN + "runs.total", Tags.of("suite", suite, "outcome", "completed")).increment();
    }

    public void recordGoldenRunFailed(String suite) {
        counter(GOLDEN + "runs.total", Tags.of("suite", suite, "outcome", "failed")).increment();
    }

    /** One case's outcome, tagged by case id so a dashboard can name which case regressed. */
    public void recordGoldenCase(String suite, String caseId, boolean passed, long latencyMillis) {
        counter(GOLDEN + "cases.total", Tags.of("suite", suite, "case", caseId, "outcome", passed ? "pass" : "fail"))
                .increment();
        Timer.builder(GOLDEN + "case.duration")
             .tag("suite", suite)
             .register(registry)
             .record(latencyMillis, TimeUnit.MILLISECONDS);
    }

    private void registerGoldenGauges() {
        gauge("hit.rate", goldenHitRate,
              "Fraction of recall-scoring cases where an expected page was retrieved");
        gauge("mrr", goldenMrr,
              "Mean reciprocal rank of the first expected page");
        gauge("citation.validity.rate", goldenCitationValidity,
              "Fraction of emitted citations that matched a retrieved chunk");
        gauge("citation.fabrication.rate", goldenCitationFabrication,
              "Fraction of emitted citations the model invented");
        judgedGauge("relevancy.rate", goldenRelevancy, relevancyEverJudged,
                    "Fraction judged relevant. NaN until some run has actually judged.");
        judgedGauge("groundedness.rate", goldenGroundedness, groundednessEverJudged,
                    "Fraction judged grounded in the context. NaN until some run has actually judged.");
        gauge("pass.rate", goldenPassRate, "Fraction of cases with no failure reason");

        io.micrometer.core.instrument.Gauge
                .builder(GOLDEN + "last.run.cases", goldenCaseCount, count -> {
                    refreshGoldenIfStale();
                    return count.get();
                })
                .description("Cases in the most recent run")
                .register(registry);

        io.micrometer.core.instrument.Gauge
                .builder(GOLDEN + "last.run.timestamp", goldenLastRunEpochSeconds, epochSeconds -> {
                    refreshGoldenIfStale();
                    return epochSeconds.get();
                })
                .description("Unix time of the most recent run. Every other golden gauge holds its last "
                             + "value indefinitely, so this is the only way to tell a current score from "
                             + "one left over from a run weeks ago.")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Registers a golden gauge whose value function refreshes from the database before reporting, so a
     * run executed in another process (the tagged test) still reaches Prometheus.
     */
    private void gauge(String name, DoubleAdder state, String description) {
        io.micrometer.core.instrument.Gauge
                .builder(GOLDEN + name, state, adder -> {
                    refreshGoldenIfStale();
                    return adder.sum();
                })
                .description(description)
                .register(registry);
    }

    /**
     * A golden gauge that reports {@code NaN} until some run has actually produced a verdict for it.
     *
     * <p>The distinction between "not measured" and "measured as zero" has to survive all the way to
     * the dashboard. Prometheus omits NaN, so the panel shows no data; 0.0 would show a confident
     * scarlet zero for a metric nobody ran.
     */
    private void judgedGauge(String name, DoubleAdder state, AtomicBoolean everJudged, String description) {
        io.micrometer.core.instrument.Gauge
                .builder(GOLDEN + name, state, adder -> {
                    refreshGoldenIfStale();
                    return everJudged.get() ? adder.sum() : Double.NaN;
                })
                .description(description)
                .register(registry);
    }

    /** DoubleAdder has no set(), so a replacement is "add the difference". */
    private static void set(DoubleAdder adder, double value) {
        adder.add(value - adder.sum());
    }

    private static void setIfPresent(DoubleAdder adder, @Nullable Double value) {
        if (value != null) {
            set(adder, value);
        }
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
