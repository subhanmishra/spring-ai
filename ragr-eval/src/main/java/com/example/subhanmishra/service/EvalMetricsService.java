package com.example.subhanmishra.service;

import com.example.subhanmishra.event.ChatFeedbackSubmitted;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.RetrievalRanking;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Publishes evaluation results as Micrometer meters, which Prometheus scrapes and Grafana renders.
 *
 * <p>Two kinds of instrument, and mixing them up makes a dashboard lie:
 * <ul>
 *   <li><b>Counters for live traffic.</b> Turns keep arriving, so the useful number is a rate or a ratio
 *       of two counters, which stays right however scrapes line up. A gauge would show only whatever the
 *       last turn did.</li>
 *   <li><b>Gauges for the golden suite.</b> A run is a batch job with one final score.</li>
 * </ul>
 *
 * <p><b>A gauge keeps its last value forever</b>, so a run from months ago looks as current as one from
 * an hour ago. {@code rag.eval.golden.last.run.timestamp} exists so the dashboard can show how old the
 * scores are, right beside them.
 *
 * <p>No percentile histograms here: the timers export {@code _count}, {@code _sum} and {@code _max},
 * enough for rates and averages.
 */
@Service
public class EvalMetricsService {

    private static final Logger log = LoggerFactory.getLogger(EvalMetricsService.class);

    private static final String ONLINE = "rag.eval.online.";
    private static final String GOLDEN = "rag.eval.golden.";

    /**
     * How long the golden scores read from the database are trusted before being read again.
     *
     * <p>The read happens when Prometheus scrapes a gauge, not on a schedule, so it costs nothing while
     * nobody is looking. Runs are rare, so 15 minutes of lag loses nothing; the first scrape after
     * startup always reads.
     *
     * <p>The query must fetch one row, filtered and limited in SQL on {@code eval_run_status_started_idx}.
     * Picking the newest run in Java would scan the whole table on every refresh.
     */
    private static final Duration GOLDEN_REFRESH_INTERVAL = Duration.ofMinutes(15);

    private final MeterRegistry registry;

    /**
     * Where a golden run's scores are read back from.
     *
     * <p>The suite runs as a test in its <em>own</em> JVM, whose meters Prometheus never sees. So the
     * {@code eval_run} table is the record, and these gauges report its latest row, whichever process
     * wrote it. A run in this process also sets them directly, so it shows at once.
     *
     * <p><b>So the golden gauges need {@code app.eval.golden.persist=true}.</b> Without it a run still
     * logs its result, but nothing reaches Grafana.
     */
    private final EvalRunRepository runRepository;

    private final AtomicLong lastRefreshedAt = new AtomicLong();
    private final AtomicReference<Instant> lastAppliedRun = new AtomicReference<>();

    /**
     * Whether any run has ever produced a judge verdict.
     *
     * <p>Until then the judged gauges report {@code NaN}, which Grafana shows as "no data", not 0.0, which
     * reads as "nothing passed". Judging is off by default, so a 0.0 would be a red alarm about something
     * nobody measured.
     */
    private final AtomicBoolean relevancyEverJudged = new AtomicBoolean();
    private final AtomicBoolean groundednessEverJudged = new AtomicBoolean();

    /**
     * The same for judged context precision. A 0.0 beside the other precisions would read as the judge
     * disagreeing completely, when nobody asked it.
     */
    private final AtomicBoolean contextPrecisionEverJudged = new AtomicBoolean();

    /**
     * Cited precision is measured on every run, but older runs have none, so it too stays NaN until a run
     * produces one.
     */
    private final AtomicBoolean citedPrecisionEverMeasured = new AtomicBoolean();

    /**
     * What the golden gauges read. Micrometer holds gauges' sources only weakly, so these must be fields
     * of this singleton - a local one is garbage-collected and the gauge silently reports NaN.
     */
    private final DoubleAdder goldenHitRate = new DoubleAdder();
    private final DoubleAdder goldenMrr = new DoubleAdder();
    private final DoubleAdder goldenContextPrecision = new DoubleAdder();
    private final DoubleAdder goldenPrecisionAtK = new DoubleAdder();
    private final DoubleAdder goldenJudgedContextPrecision = new DoubleAdder();
    private final DoubleAdder goldenJudgedPrecisionAtK = new DoubleAdder();
    private final DoubleAdder goldenCitedContextPrecision = new DoubleAdder();
    private final DoubleAdder goldenCitedPrecisionAtK = new DoubleAdder();
    private final DoubleAdder goldenCitationValidity = new DoubleAdder();
    private final DoubleAdder goldenCitationFabrication = new DoubleAdder();
    private final DoubleAdder goldenRelevancy = new DoubleAdder();
    private final DoubleAdder goldenGroundedness = new DoubleAdder();
    private final DoubleAdder goldenPassRate = new DoubleAdder();
    private final AtomicInteger goldenCaseCount = new AtomicInteger();
    private final AtomicLong goldenLastRunEpochSeconds = new AtomicLong();

    /** The two Spring AI judges, as their verdict counters are tagged. */
    public static final String RELEVANCY = "relevancy";
    public static final String GROUNDEDNESS = "groundedness";
    private static final List<String> JUDGE_METRICS = List.of(RELEVANCY, GROUNDEDNESS);

    /** How long the judge queue gauges trust one read of {@code eval_turn}. */
    private static final Duration QUEUE_REFRESH_INTERVAL = Duration.ofSeconds(15);

    private final @Nullable EvalTurnRepository turnRepository;
    private final AtomicLong queueRefreshedAt = new AtomicLong();
    private final AtomicLong queuePending = new AtomicLong();
    private final AtomicLong queueOldestAgeSeconds = new AtomicLong();

    /**
     * @param turnRepository where the judge queue gauges read from; null in tests that do not need them
     */
    public EvalMetricsService(MeterRegistry registry, EvalRunRepository runRepository,
                              @Nullable EvalTurnRepository turnRepository) {
        this.registry = registry;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        registerGoldenGauges();
        preRegisterOnlineMeters();
        registerQueueGauges();
    }

    /**
     * Creates every fixed-tag online meter at zero, so its series exists before the first event.
     *
     * <p>Micrometer creates a counter on first use, so a counter for something that has not happened has
     * no series, and Grafana shows "No data" instead of 0. For health signals whose good state is zero -
     * refusals, instruction echoes, chunks without a header - "nothing went wrong" then looked like "the
     * metric is broken".
     *
     * <p>Registering is idempotent, so this never resets a counter. Tagged counters are registered for
     * every tag combination, which works only for closed sets of values - so the golden per-case and
     * per-suite counters are not here; they appear on the first run, and no panel needs them.
     */
    private void preRegisterOnlineMeters() {
        counter(ONLINE + "turns.total", Tags.empty());
        counter(ONLINE + "zero.hit.total", Tags.empty());
        counter(ONLINE + "uncited.answers.total", Tags.empty());
        counter(ONLINE + "refusals.total", Tags.empty());
        counter(ONLINE + "instruction.echoes.total", Tags.empty());
        counter(ONLINE + "chunks.without.header.total", Tags.empty());
        counter(ONLINE + "judge.skipped.total", Tags.empty());
        counter(ONLINE + "rephrases.total", Tags.empty());
        for (String rating : List.of("up", "down")) {
            counter(ONLINE + "feedback.total", Tags.of("rating", rating));
        }
        for (String retrieval : List.of("right", "wrong")) {
            for (String answer : List.of("right", "wrong")) {
                counter(ONLINE + "quadrant.total", Tags.of("retrieval", retrieval, "answer", answer));
            }
        }

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

        // Summaries are created on first use too.
        registry.summary(ONLINE + "retrieved.chunks");
        registry.summary(ONLINE + "top.score");
        registry.summary(ONLINE + "score.spread");
        registry.summary(ONLINE + "answer.chars");
        registry.summary(ONLINE + "cited.context.precision");
        registry.summary(ONLINE + "cited.precision.at.k");
    }

    /**
     * Re-reads the latest persisted run when the cached snapshot has gone stale.
     *
     * <p>Runs during a Prometheus scrape, so it must never throw or block for long: a failure would break
     * the whole scrape, every other metric with it. On error the previous values stand.
     */
    private void refreshGoldenIfStale() {
        long now = System.currentTimeMillis();
        long last = lastRefreshedAt.get();
        if (now - last < GOLDEN_REFRESH_INTERVAL.toMillis() || !lastRefreshedAt.compareAndSet(last, now)) {
            return;
        }
        try {
            runRepository.findFirstByStatusOrderByStartedAtDesc(EvalRunStatus.COMPLETED)
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
        setIfPresent(goldenContextPrecision, run.contextPrecision());
        setIfPresent(goldenPrecisionAtK, run.precisionAtK());
        if (run.judgedContextPrecision() != null) {
            set(goldenJudgedContextPrecision, run.judgedContextPrecision());
            setIfPresent(goldenJudgedPrecisionAtK, run.judgedPrecisionAtK());
            contextPrecisionEverJudged.set(true);
        }
        if (run.citedContextPrecision() != null) {
            set(goldenCitedContextPrecision, run.citedContextPrecision());
            setIfPresent(goldenCitedPrecisionAtK, run.citedPrecisionAtK());
            citedPrecisionEverMeasured.set(true);
        }
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
     * Records one live turn's rule-based scores, on the Kafka listener thread. It must never throw, or the
     * turn would not be stored.
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
        // Chunks stored before the citation header cannot be cited. Any here means re-ingest, not prompt
        // tuning.
        int withoutHeader = retrieval.retrievedCount() - retrieval.chunksWithHeader();
        if (withoutHeader > 0) {
            counter(ONLINE + "chunks.without.header.total", Tags.empty()).increment(withoutHeader);
        }

        var citations = scores.citations();
        if (citations.emitted() > 0) {
            counter(ONLINE + "citations.total", Tags.of("outcome", "valid")).increment(citations.valid());
            counter(ONLINE + "citations.total", Tags.of("outcome", "fabricated")).increment(citations.fabricated());
        } else {
            // Counted apart: an answer citing nothing would make the validity rate trivially perfect.
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
     * Records one grounded live turn's cited context precision - a chunk counts as used when the answer
     * cites its page. Summaries rather than counters because the score is a fraction per turn; the
     * dashboard reads the mean as {@code _sum / _count}.
     */
    public void recordOnlineCitedPrecision(ContextPrecisionScores cited) {
        registry.summary(ONLINE + "cited.context.precision").record(cited.averagePrecision());
        registry.summary(ONLINE + "cited.precision.at.k").record(cited.precisionAtK());
    }

    /**
     * Records what {@code CitationResolver} did to one answer's section-number citations.
     *
     * <p>A repair is a section number that resolved to a page the model was shown; an abstention is one
     * that did not, and still counts as fabricated. <b>Watch the ratio.</b> Rising repairs are the known
     * habit being corrected; rising abstentions are the model citing sections it was never given - real
     * hallucination, which resolving cannot fix.
     */
    public void recordCitationResolution(int repaired, int abstained) {
        if (repaired > 0) {
            counter(ONLINE + "citations.resolved.total", Tags.of("outcome", "repaired")).increment(repaired);
        }
        if (abstained > 0) {
            counter(ONLINE + "citations.resolved.total", Tags.of("outcome", "abstained")).increment(abstained);
        }
    }

    /**
     * One judge call's time, tagged {@code metric} with the stage name. This sizes the judge budget.
     */
    public void recordJudgeCall(String stage, long durationMillis) {
        Timer.builder(ONLINE + "judge.duration")
             .tag("metric", stage)
             .register(registry)
             .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /** A judge call that timed out, threw, or replied with something unreadable. */
    public void recordJudgementError(String stage) {
        counter(ONLINE + "judgements.errors.total", Tags.of("metric", stage)).increment();
    }

    /**
     * Turns that waited too long and were skipped. A steadily rising count means the sample rate is too
     * high for the traffic.
     */
    public void recordJudgeSkipped(int count) {
        counter(ONLINE + "judge.skipped.total", Tags.empty()).increment(count);
    }

    /**
     * Everything the judges concluded about one turn, tagged by its task type.
     *
     * <p>Recorded when judging finishes, possibly minutes after the turn, so these describe when turns were
     * <em>judged</em>. Quality panels read {@code eval_turn} by when the turn happened instead; these are
     * for alerting and per-task rates. The task tag is a closed set, so the series count stays fixed.
     */
    public void recordJudgedTurn(TurnVerdicts verdicts, @Nullable Boolean answerOk, boolean grounded,
                                 long judgeMillis) {
        Tags task = Tags.of("task", verdicts.taskType() != null ? verdicts.taskType().tag() : "unknown");
        Timer.builder(ONLINE + "judge.turn.duration")
             .register(registry)
             .record(judgeMillis, TimeUnit.MILLISECONDS);
        counter(ONLINE + "judged.turns.total", task.and("grounded", String.valueOf(grounded))).increment();

        // Retrieval first, ungrounded turns included: a relevant chunk in their pool is a recall of 0.
        // Their precision, MRR and NDCG are null (the prompt got no chunks), so they are left out.
        RetrievalRanking ranking = verdicts.ranking();
        if (ranking != null) {
            recordIfPresent(ONLINE + "precision.at.k", task, ranking.precisionAtK());
            recordIfPresent(ONLINE + "recall.at.k", task, ranking.recallAtK());
            recordIfPresent(ONLINE + "mrr", task, ranking.reciprocalRank());
            recordIfPresent(ONLINE + "ndcg.at.k", task, ranking.ndcgAtK());
            if (ranking.relevantInPool() == 0) {
                counter(ONLINE + "nothing.relevant.total", task).increment();
            }
        }
        if (!grounded) {
            return;
        }
        recordIfPresent(ONLINE + "faithfulness", task, verdicts.faithfulness());
        if (verdicts.relevancyPass() != null) {
            counter(ONLINE + "judgements.total",
                    Tags.of("metric", RELEVANCY, "outcome", outcome(verdicts.relevancyPass()))).increment();
        }
        if (verdicts.groundednessPass() != null) {
            counter(ONLINE + "judgements.total",
                    Tags.of("metric", GROUNDEDNESS, "outcome", outcome(verdicts.groundednessPass()))).increment();
        }
        if (verdicts.completenessPass() != null) {
            counter(ONLINE + "completeness.total", task.and("outcome", outcome(verdicts.completenessPass())))
                    .increment();
        }
        Integer checked = verdicts.citationsChecked();
        Integer supported = verdicts.citationsSupported();
        if (checked != null && supported != null && checked > 0) {
            counter(ONLINE + "citation.support.total", Tags.of("outcome", "supported")).increment(supported);
            counter(ONLINE + "citation.support.total", Tags.of("outcome", "unsupported")).increment(checked - supported);
        }
        if (answerOk != null) {
            counter(ONLINE + "e2e.total", task.and("outcome", outcome(answerOk))).increment();
        }
        Boolean retrievalOk = verdicts.retrievalOk();
        if (answerOk != null && retrievalOk != null) {
            counter(ONLINE + "quadrant.total", Tags.of("retrieval", retrievalOk ? "right" : "wrong",
                                                       "answer", answerOk ? "right" : "wrong")).increment();
        }
    }

    /** A follow-up that asked the previous question again - the implicit thumbs-down. */
    public void recordRephrase() {
        counter(ONLINE + "rephrases.total", Tags.empty()).increment();
    }

    public void recordFeedback(ChatFeedbackSubmitted.Rating rating) {
        counter(ONLINE + "feedback.total", Tags.of("rating", rating.name().toLowerCase(Locale.ROOT))).increment();
    }

    private void recordIfPresent(String name, Tags tags, @Nullable Double value) {
        if (value != null) {
            DistributionSummary.builder(name).tags(tags).register(registry).record(value);
        }
    }

    private static String outcome(boolean passed) {
        return passed ? "pass" : "fail";
    }

    /**
     * The judge queue's length and the age of its oldest turn, read from {@code eval_turn} during a scrape.
     * Cached for {@link #QUEUE_REFRESH_INTERVAL}, and never throws - a failure would break the scrape.
     */
    private void registerQueueGauges() {
        io.micrometer.core.instrument.Gauge
                .builder(ONLINE + "judge.queue.pending", queuePending, pending -> {
                    refreshQueueIfStale();
                    return pending.get();
                })
                .description("Turns waiting for the judges")
                .register(registry);
        io.micrometer.core.instrument.Gauge
                .builder(ONLINE + "judge.queue.oldest.age", queueOldestAgeSeconds, age -> {
                    refreshQueueIfStale();
                    return age.get();
                })
                .description("How long the oldest waiting turn has waited; 0 when none is")
                .baseUnit("seconds")
                .register(registry);
    }

    private void refreshQueueIfStale() {
        long now = System.currentTimeMillis();
        long last = queueRefreshedAt.get();
        if (turnRepository == null || now - last < QUEUE_REFRESH_INTERVAL.toMillis()
                || !queueRefreshedAt.compareAndSet(last, now)) {
            return;
        }
        try {
            queuePending.set(turnRepository.pendingCount());
            queueOldestAgeSeconds.set((long) turnRepository.oldestPendingAgeSeconds());
        } catch (RuntimeException e) {
            log.debug("Could not read the judge queue; keeping the previous values", e);
        }
    }

    // ---------------------------------------------------------------- golden

    /** Publishes the aggregate scores of a completed suite run. */
    public void recordGoldenRun(String suite,
                                int caseCount,
                                double passRate,
                                double hitRate,
                                double mrr,
                                @Nullable Double contextPrecision,
                                @Nullable Double precisionAtK,
                                @Nullable Double judgedContextPrecision,
                                @Nullable Double judgedPrecisionAtK,
                                @Nullable Double citedContextPrecision,
                                @Nullable Double citedPrecisionAtK,
                                double citationValidity,
                                double citationFabrication,
                                @Nullable Double relevancyRate,
                                @Nullable Double groundednessRate,
                                long durationMillis) {
        set(goldenHitRate, hitRate);
        set(goldenMrr, mrr);
        setIfPresent(goldenContextPrecision, contextPrecision);
        setIfPresent(goldenPrecisionAtK, precisionAtK);
        if (judgedContextPrecision != null) {
            set(goldenJudgedContextPrecision, judgedContextPrecision);
            setIfPresent(goldenJudgedPrecisionAtK, judgedPrecisionAtK);
            contextPrecisionEverJudged.set(true);
        }
        if (citedContextPrecision != null) {
            set(goldenCitedContextPrecision, citedContextPrecision);
            setIfPresent(goldenCitedPrecisionAtK, citedPrecisionAtK);
            citedPrecisionEverMeasured.set(true);
        }
        set(goldenCitationValidity, citationValidity);
        set(goldenCitationFabrication, citationFabrication);
        set(goldenPassRate, passRate);
        // Unjudged runs leave these alone; a reset to zero would look like a collapse in quality.
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
        gauge("context.precision", goldenContextPrecision,
              "Rank-weighted context precision against the dataset's expected pages. A floor: pages the "
              + "dataset omits count as noise, so compare runs rather than reading the level.");
        gauge("precision.at.k", goldenPrecisionAtK,
              "Fraction of the retrieved context on an expected page - the noise measure, unweighted by rank");
        judgedGauge("judged.context.precision", goldenJudgedContextPrecision, contextPrecisionEverJudged,
                    "Rank-weighted context precision with per-chunk relevance decided by the LLM judge. "
                    + "NaN until some run has judged.");
        judgedGauge("judged.precision.at.k", goldenJudgedPrecisionAtK, contextPrecisionEverJudged,
                    "Fraction of the retrieved context the judge called useful. NaN until some run has judged.");
        judgedGauge("cited.context.precision", goldenCitedContextPrecision, citedPrecisionEverMeasured,
                    "Rank-weighted context precision with a chunk counted as used when the answer cites its "
                    + "page. Deterministic; under-counts passages used without a citation.");
        judgedGauge("cited.precision.at.k", goldenCitedPrecisionAtK, citedPrecisionEverMeasured,
                    "Fraction of the retrieved context the answer cites.");
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
