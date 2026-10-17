package com.example.subhanmishra.service;

import com.example.subhanmishra.event.ChatFeedbackSubmitted;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenRunResult;
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
 *   <li><b>Counters and summaries per turn</b>, tagged {@code origin} ({@code live} or {@code golden}):
 *       both kinds of turn are scored and judged the same way, and the tag keeps a golden run out of the
 *       live rates. The useful number is a rate or a ratio of two counters, which stays right however
 *       scrapes line up.</li>
 *   <li><b>Gauges for a golden run's rule-based aggregate.</b> A run is a batch job with one final score;
 *       its judged figures are read from {@code eval_turn} by the dashboard instead.</li>
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
     * Where a golden run's scores are read back from after a restart, which empties the gauges; a run in
     * this process sets them directly.
     */
    private final EvalRunRepository runRepository;

    private final AtomicLong lastRefreshedAt = new AtomicLong();
    private final AtomicReference<Instant> lastAppliedRun = new AtomicReference<>();

    /**
     * Cited precision stays {@code NaN} until a run produces one, so a run that retrieved nothing never
     * shows as 0.0.
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
    private final DoubleAdder goldenCitedContextPrecision = new DoubleAdder();
    private final DoubleAdder goldenCitedPrecisionAtK = new DoubleAdder();
    private final DoubleAdder goldenCitationValidity = new DoubleAdder();
    private final DoubleAdder goldenCitationFabrication = new DoubleAdder();
    private final DoubleAdder goldenPassRate = new DoubleAdder();
    private final AtomicInteger goldenCaseCount = new AtomicInteger();
    private final AtomicLong goldenLastRunEpochSeconds = new AtomicLong();

    /** The tag on every per-turn meter, so live and golden turns can be read apart. */
    private static final List<String> ORIGINS = List.of("live", "golden");

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
        for (String origin : ORIGINS) {
            Tags tags = Tags.of("origin", origin);
            counter(ONLINE + "turns.total", tags);
            counter(ONLINE + "zero.hit.total", tags);
            counter(ONLINE + "uncited.answers.total", tags);
            counter(ONLINE + "refusals.total", tags);
            counter(ONLINE + "instruction.echoes.total", tags);
            counter(ONLINE + "chunks.without.header.total", tags);
            for (String retrieval : List.of("right", "wrong")) {
                for (String answer : List.of("right", "wrong")) {
                    counter(ONLINE + "quadrant.total", tags.and("retrieval", retrieval, "answer", answer));
                }
            }
            for (String outcome : List.of("valid", "fabricated")) {
                counter(ONLINE + "citations.total", tags.and("outcome", outcome));
            }
            for (String metric : JUDGE_METRICS) {
                for (String outcome : List.of("pass", "fail")) {
                    counter(ONLINE + "judgements.total", tags.and("metric", metric, "outcome", outcome));
                }
            }
            // Summaries are created on first use too.
            for (String summary : List.of("retrieved.chunks", "top.score", "score.spread", "answer.chars",
                                          "cited.context.precision", "cited.precision.at.k")) {
                registry.summary(ONLINE + summary, tags);
            }
        }
        counter(ONLINE + "judge.skipped.total", Tags.empty());
        counter(ONLINE + "rephrases.total", Tags.empty());
        for (String rating : List.of("up", "down")) {
            counter(ONLINE + "feedback.total", Tags.of("rating", rating));
        }
        for (String metric : JUDGE_METRICS) {
            counter(ONLINE + "judgements.errors.total", Tags.of("metric", metric));
        }
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
        if (run.citedContextPrecision() != null) {
            set(goldenCitedContextPrecision, run.citedContextPrecision());
            setIfPresent(goldenCitedPrecisionAtK, run.citedPrecisionAtK());
            citedPrecisionEverMeasured.set(true);
        }
        setIfPresent(goldenCitationValidity, run.citationValidity());
        setIfPresent(goldenCitationFabrication, run.citationFabrication());
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
     * Records one turn's rule-based scores, on the Kafka listener thread. It must never throw, or the turn
     * would not be stored.
     */
    public void recordOnline(TurnOrigin origin, EvalScores scores) {
        Tags tags = originTag(origin);
        counter(ONLINE + "turns.total", tags).increment();

        var retrieval = scores.retrieval();
        registry.summary(ONLINE + "retrieved.chunks", tags).record(retrieval.retrievedCount());
        if (retrieval.isZeroHit()) {
            counter(ONLINE + "zero.hit.total", tags).increment();
        } else {
            registry.summary(ONLINE + "top.score", tags).record(retrieval.topScore());
            registry.summary(ONLINE + "score.spread", tags).record(retrieval.scoreSpread());
        }
        // Chunks stored before the citation header cannot be cited. Any here means re-ingest, not prompt
        // tuning.
        int withoutHeader = retrieval.retrievedCount() - retrieval.chunksWithHeader();
        if (withoutHeader > 0) {
            counter(ONLINE + "chunks.without.header.total", tags).increment(withoutHeader);
        }

        var citations = scores.citations();
        if (citations.emitted() > 0) {
            counter(ONLINE + "citations.total", tags.and("outcome", "valid")).increment(citations.valid());
            counter(ONLINE + "citations.total", tags.and("outcome", "fabricated")).increment(citations.fabricated());
        } else {
            // Counted apart: an answer citing nothing would make the validity rate trivially perfect.
            counter(ONLINE + "uncited.answers.total", tags).increment();
        }

        if (scores.answer().refused()) {
            counter(ONLINE + "refusals.total", tags).increment();
        }
        if (scores.answer().echoedInstruction()) {
            counter(ONLINE + "instruction.echoes.total", tags).increment();
        }
        registry.summary(ONLINE + "answer.chars", tags).record(scores.answer().answerChars());
    }

    /**
     * Records one grounded turn's cited context precision - a chunk counts as used when the answer
     * cites its page. Summaries rather than counters because the score is a fraction per turn; the
     * dashboard reads the mean as {@code _sum / _count}.
     */
    public void recordOnlineCitedPrecision(TurnOrigin origin, ContextPrecisionScores cited) {
        registry.summary(ONLINE + "cited.context.precision", originTag(origin)).record(cited.averagePrecision());
        registry.summary(ONLINE + "cited.precision.at.k", originTag(origin)).record(cited.precisionAtK());
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
    public void recordJudgedTurn(TurnOrigin origin, TurnVerdicts verdicts, @Nullable Boolean answerOk,
                                 boolean grounded, long judgeMillis) {
        Tags tags = originTag(origin);
        Tags task = tags.and("task", verdicts.taskType() != null ? verdicts.taskType().tag() : "unknown");
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
                    tags.and("metric", RELEVANCY, "outcome", outcome(verdicts.relevancyPass()))).increment();
        }
        if (verdicts.groundednessPass() != null) {
            counter(ONLINE + "judgements.total",
                    tags.and("metric", GROUNDEDNESS, "outcome", outcome(verdicts.groundednessPass()))).increment();
        }
        if (verdicts.completenessPass() != null) {
            counter(ONLINE + "completeness.total", task.and("outcome", outcome(verdicts.completenessPass())))
                    .increment();
        }
        Integer checked = verdicts.citationsChecked();
        Integer supported = verdicts.citationsSupported();
        if (checked != null && supported != null && checked > 0) {
            counter(ONLINE + "citation.support.total", tags.and("outcome", "supported")).increment(supported);
            counter(ONLINE + "citation.support.total", tags.and("outcome", "unsupported")).increment(checked - supported);
        }
        if (answerOk != null) {
            counter(ONLINE + "e2e.total", task.and("outcome", outcome(answerOk))).increment();
        }
        Boolean retrievalOk = verdicts.retrievalOk();
        if (answerOk != null && retrievalOk != null) {
            counter(ONLINE + "quadrant.total", tags.and("retrieval", retrievalOk ? "right" : "wrong",
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

    private static Tags originTag(TurnOrigin origin) {
        return Tags.of("origin", origin.name().toLowerCase(Locale.ROOT));
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

    /** Publishes the rule-based aggregate of a completed run. */
    public void recordGoldenRun(GoldenRunResult result) {
        set(goldenHitRate, result.hitRate());
        set(goldenMrr, result.meanReciprocalRank());
        setIfPresent(goldenContextPrecision, result.contextPrecision());
        setIfPresent(goldenPrecisionAtK, result.precisionAtK());
        if (result.citedContextPrecision() != null) {
            set(goldenCitedContextPrecision, result.citedContextPrecision());
            setIfPresent(goldenCitedPrecisionAtK, result.citedPrecisionAtK());
            citedPrecisionEverMeasured.set(true);
        }
        set(goldenCitationValidity, result.citationValidity());
        set(goldenCitationFabrication, result.citationFabrication());
        set(goldenPassRate, result.passRate());
        goldenCaseCount.set(result.caseCount());
        goldenLastRunEpochSeconds.set(System.currentTimeMillis() / 1000);

        Timer.builder(GOLDEN + "run.duration")
             .tag("suite", result.suite())
             .register(registry)
             .record(result.durationMillis(), TimeUnit.MILLISECONDS);
        counter(GOLDEN + "runs.total", Tags.of("suite", result.suite(), "outcome", "completed")).increment();
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
        judgedGauge("cited.context.precision", goldenCitedContextPrecision, citedPrecisionEverMeasured,
                    "Rank-weighted context precision with a chunk counted as used when the answer cites its "
                    + "page. Deterministic; under-counts passages used without a citation.");
        judgedGauge("cited.precision.at.k", goldenCitedPrecisionAtK, citedPrecisionEverMeasured,
                    "Fraction of the retrieved context the answer cites.");
        gauge("citation.validity.rate", goldenCitationValidity,
              "Fraction of emitted citations that matched a retrieved chunk");
        gauge("citation.fabrication.rate", goldenCitationFabrication,
              "Fraction of emitted citations the model invented");
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
     * Registers a golden gauge whose value function refreshes from the database before reporting, so the
     * last run survives a restart.
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
     * A golden gauge that reports {@code NaN} until some run has produced a value for it: Prometheus omits
     * NaN, so the panel shows no data rather than a zero nobody measured.
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
