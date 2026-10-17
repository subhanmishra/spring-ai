package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.dto.GoldenRunDto;
import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.exception.GoldenRunInProgressException;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.repository.EvalCaseResultRepository;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.RunPipeline;
import com.example.subhanmishra.service.EvalScoringService.ReferenceScores;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.GoldenDataset;
import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
import com.example.subhanmishra.service.eval.GoldenGate;
import com.example.subhanmishra.service.eval.GoldenRunResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replays a curated dataset through the real chat path, as a background run started over HTTP.
 *
 * <p>It does what live scoring cannot: with known-correct sources it can measure whether retrieval found
 * the answer, and at what rank. And a fixed question set makes two runs comparable, so a regression shows
 * as a number moving.
 *
 * <p>How a run works:
 * <ul>
 *   <li><b>Through ragr-app's real endpoint.</b> Each case is a {@code POST /ai/generate} marked
 *       {@link TurnOrigin#GOLDEN}, so it measures exactly what a user gets.</li>
 *   <li><b>Evaluated like a live turn.</b> The run writes the case AWAITING under the conversation id it
 *       is about to use; the turn listener finds it by that id ({@link #awaitingCase}), scores it against
 *       the case ({@link #scoreCase}) and queues the turn for the judges beside live ones.</li>
 *   <li><b>Done when every case is scored</b>, not judged: the regression verdict ({@link GoldenGate})
 *       uses only rule-based scores, and the judges' verdicts arrive on the run's turns later.</li>
 *   <li><b>A new conversation per case, deleted afterwards</b>, so no case sees another through chat
 *       memory.</li>
 *   <li><b>One case, and one run, at a time.</b> Ollama serves one request at a time.</li>
 * </ul>
 */
@Service
public class GoldenEvalService {

    private static final Logger log = LoggerFactory.getLogger(GoldenEvalService.class);

    /** Well above a grounded answer plus a cold model load, so a hung chat service fails the case. */
    private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration SCORED_POLL_INTERVAL = Duration.ofMillis(500);

    private final RestClient chat;
    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final GoldenDatasetLoader datasetLoader;
    private final EvalRunRepository runs;
    private final EvalCaseResultRepository cases;
    private final EvalTurnRepository turns;
    private final EvalProperties evalProperties;

    private final Semaphore oneRun = new Semaphore(1);
    private volatile @Nullable GoldenDataset dataset;

    public GoldenEvalService(RestClient.Builder restClients,
                             EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             GoldenDatasetLoader datasetLoader,
                             EvalRunRepository runs,
                             EvalCaseResultRepository cases,
                             EvalTurnRepository turns,
                             EvalProperties evalProperties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(ANSWER_TIMEOUT);
        this.chat = restClients.baseUrl(evalProperties.golden().chatUrl().toString())
                               .requestFactory(requestFactory)
                               .build();
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.datasetLoader = datasetLoader;
        this.runs = runs;
        this.cases = cases;
        this.turns = turns;
        this.evalProperties = evalProperties;
    }

    /** The configured dataset, read once: it is on the classpath, so fixed for the deployment. */
    public GoldenDataset dataset() {
        GoldenDataset loaded = dataset;
        if (loaded == null) {
            loaded = datasetLoader.load(evalProperties.golden().datasetLocation());
            dataset = loaded;
        }
        return loaded;
    }

    /**
     * A run left RUNNING by a restart can never finish; fail it, and its waiting cases. Only in the
     * process that runs the judge worker: a context test against the same database, which has it off,
     * must not fail the deployed application's run in progress.
     */
    @EventListener(ApplicationReadyEvent.class)
    void failInterruptedRuns() {
        if (!evalProperties.judge().workerEnabled()) {
            return;
        }
        int failed = runs.failRunning("interrupted by a restart of ragr-eval");
        if (failed > 0) {
            cases.failAllAwaiting("run interrupted by a restart of ragr-eval");
            log.warn("Marked {} golden run(s) left RUNNING by a restart as FAILED", failed);
        }
    }

    // ---------------------------------------------------------------- the run

    /**
     * Starts a run in the background and returns it as saved, RUNNING.
     *
     * @throws GoldenRunInProgressException while another run is in progress
     */
    public GoldenRunDto start() {
        if (!oneRun.tryAcquire()) {
            throw new GoldenRunInProgressException();
        }
        try {
            GoldenDataset suite = dataset();
            EvalRun run = runs.save(EvalRun.starting(suite.suite(), suite.cases().size()));
            Thread.ofVirtual().name("golden-run").start(() -> {
                try {
                    execute(run, suite);
                } finally {
                    oneRun.release();
                }
            });
            return view(run);
        } catch (RuntimeException e) {
            oneRun.release();
            throw e;
        }
    }

    private void execute(EvalRun run, GoldenDataset suite) {
        UUID runId = run.id();
        log.info("Starting golden eval run {} [suite={}, cases={}, chatUrl={}]",
                 runId, suite.suite(), suite.cases().size(), evalProperties.golden().chatUrl());
        try {
            for (GoldenCase goldenCase : suite.cases()) {
                ask(runId, goldenCase);
            }
            awaitScored(runId);

            List<EvalCaseResult> results = cases.findByRunIdOrderByCreatedAtAsc(runId);
            GoldenRunResult result = aggregate(suite, results,
                                               System.currentTimeMillis() - run.startedAt().toEpochMilli());
            List<String> gateFailures = GoldenGate.failures(result);
            RunPipeline pipeline = turns.runPipeline(runId).orElse(new RunPipeline(null, null, null));
            runs.save(run.completed(result, pipeline.chatModel(), pipeline.topK(), pipeline.similarityThreshold(),
                                    gateFailures));

            results.forEach(caseResult -> metricsService.recordGoldenCase(
                    suite.suite(), caseResult.caseId(), caseResult.passed(),
                    caseResult.latencyMillis() != null ? caseResult.latencyMillis() : 0));
            metricsService.recordGoldenRun(result);

            log.info("Golden eval run {} finished [suite={}, passed={}/{}, gate={}, hitRate={}, "
                     + "contextPrecision={}, citedPrecision={}, citationValidity={}, fabrication={}, "
                     + "inventedPages={}, took={}ms]{}",
                     runId, suite.suite(), result.passedCount(), result.caseCount(),
                     gateFailures.isEmpty() ? "passed" : "FAILED " + gateFailures,
                     format(result.hitRate()), format(result.contextPrecision()),
                     format(result.citedContextPrecision()), format(result.citationValidity()),
                     format(result.citationFabrication()), result.inventedPageCount(), result.durationMillis(),
                     result.failures().isEmpty() ? "" : " failures: " + result.failures());

        } catch (RuntimeException e) {
            log.error("Golden eval run {} failed", runId, e);
            cases.failAwaiting(runId, "run failed: " + e.getMessage());
            runs.findById(runId).ifPresent(saved -> runs.save(saved.failed(String.valueOf(e.getMessage()))));
            metricsService.recordGoldenRunFailed(suite.suite());
        }
    }

    /** One case: write it AWAITING, ask chat, then delete the conversation. */
    private void ask(UUID runId, GoldenCase goldenCase) {
        String conversationId = "eval-" + UUID.randomUUID();
        EvalCaseResult awaiting = cases.save(EvalCaseResult.awaiting(runId, goldenCase, conversationId));
        try {
            chat.post()
                .uri("/ai/generate")
                .header(TurnOrigin.HEADER, TurnOrigin.GOLDEN.name())
                .header("X-Conversation-Id", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt", goldenCase.query()))
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Golden case [{}] failed: {}", goldenCase.id(), e.getMessage());
            cases.failIfAwaiting(awaiting.id(), "chat request failed: " + e.getMessage());
        } finally {
            deleteConversation(conversationId);
        }
    }

    /**
     * Waits until the listener has scored every case, or {@code turn-timeout} after the last answer. A
     * turn arrives within milliseconds of its answer; one still missing was dropped by ragr-app's
     * publisher.
     */
    private void awaitScored(UUID runId) {
        long deadline = System.nanoTime() + evalProperties.golden().turnTimeout().toNanos();
        while (cases.countByRunIdAndStatus(runId, EvalCaseResult.Status.AWAITING) > 0
               && System.nanoTime() < deadline) {
            try {
                Thread.sleep(SCORED_POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for turns", e);
            }
        }
        int missing = cases.failAwaiting(runId, "no chat turn arrived on " + evalProperties.topic()
                + " - check rag_chat_turn_events_total{outcome=\"dropped\"} on ragr-app");
        if (missing > 0) {
            log.warn("Golden eval run {}: {} case(s) never received their turn", runId, missing);
        }
    }

    /**
     * Always attempted, even when the case failed, so no suite conversations are left in Redis. A failed
     * delete is logged, not thrown, so it cannot hide the case's own error.
     */
    private void deleteConversation(String conversationId) {
        try {
            chat.delete().uri("/ai/conversations/{id}", conversationId).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not delete eval conversation {}; it will show in the chat API's list", conversationId, e);
        }
    }

    // ---------------------------------------------------------------- the turn listener's half

    /** The case, and the run, waiting for this conversation's turn - empty for any other turn. */
    public Optional<AwaitingCase> awaitingCase(String conversationId) {
        return cases.findByConversationIdAndStatus(conversationId, EvalCaseResult.Status.AWAITING)
                    .flatMap(row -> dataset().cases().stream()
                                             .filter(goldenCase -> goldenCase.id().equals(row.caseId()))
                                             .findFirst()
                                             .map(goldenCase -> new AwaitingCase(row, goldenCase)));
    }

    /**
     * @param row        the case's AWAITING row
     * @param goldenCase what the case expects
     */
    public record AwaitingCase(EvalCaseResult row, GoldenCase goldenCase) {

        public UUID runId() {
            return row.runId();
        }

        public String caseId() {
            return row.caseId();
        }
    }

    /**
     * Scores the case against its expectations, from the turn just stored, and marks it SCORED.
     *
     * @param scores the turn's scores, already taken against the case
     * @param cited  the turn's cited precision; null without retrieval
     */
    public void scoreCase(AwaitingCase awaiting, ChatTurnCompleted turn, EvalScores scores,
                          @Nullable ContextPrecisionScores cited) {
        GoldenCase goldenCase = awaiting.goldenCase();
        List<Document> retrieved = turn.retrievedDocuments();
        List<Document> pool = turn.pool().stream().map(ChatTurnCompleted.RetrievedChunk::toDocument).toList();
        ReferenceScores reference = scoringService.referenceScores(pool, retrieved.size(), goldenCase);
        Long latency = turn.timings() != null ? turn.timings().totalMillis() : null;

        cases.save(awaiting.row().scored(turn.answer(), scores,
                                         scoringService.firstRelevantRank(retrieved, goldenCase),
                                         scoringService.contextPrecision(retrieved, goldenCase),
                                         cited, reference, turn.turnId(), latency));
        log.info("Golden case [{}] scored: {} chunk(s), {} citation(s), {} fabricated, {}",
                 goldenCase.id(), scores.retrieval().retrievedCount(), scores.citations().emitted(),
                 scores.citations().fabricated(),
                 scores.passed() ? "passed" : "failed: " + String.join("; ", scores.failureReasons()));
    }

    // ---------------------------------------------------------------- reading runs

    public GoldenRunDto view(UUID runId) {
        return runs.findById(runId).map(this::view)
                   .orElseThrow(() -> new ResourceNotFoundException("No golden run " + runId));
    }

    public GoldenRunDto latest() {
        return runs.findFirstByOrderByStartedAtDesc().map(this::view)
                   .orElseThrow(() -> new ResourceNotFoundException("No golden run yet"));
    }

    private GoldenRunDto view(EvalRun run) {
        List<EvalCaseResult> results = cases.findByRunIdOrderByCreatedAtAsc(run.id());
        return GoldenRunDto.of(run, results, turns.judgeProgress(run.id()));
    }

    // ---------------------------------------------------------------- aggregation

    /**
     * The run's rule-based aggregate. Each metric averages over the cases it applies to, never over all
     * of them: a case declaring no expected sources has no recall to count.
     */
    static GoldenRunResult aggregate(GoldenDataset suite, List<EvalCaseResult> results, long durationMillis) {
        Map<String, GoldenCase> byId = suite.cases().stream()
                                            .collect(Collectors.toMap(GoldenCase::id, Function.identity()));
        int passed = 0;
        int recallCases = 0;
        int hits = 0;
        double reciprocalRankTotal = 0;
        int citationsEmitted = 0;
        int citationsValid = 0;
        int citationsFabricated = 0;
        int inventedPages = 0;
        List<String> failures = new ArrayList<>();
        Average precision = new Average();
        Average precisionAtK = new Average();
        Average cited = new Average();
        Average citedAtK = new Average();
        Average recall = new Average();
        Average ndcg = new Average();
        Average phrases = new Average();

        for (EvalCaseResult result : results) {
            if (result.passed()) {
                passed++;
            } else {
                failures.add(result.caseId() + ": " + result.failureReasons());
            }
            GoldenCase goldenCase = byId.get(result.caseId());
            // A failed case still counts as a recall miss: retrieval was never shown to work for it.
            if (goldenCase != null && goldenCase.scoresRecall()) {
                recallCases++;
                if (result.firstRelevantRank() > 0) {
                    hits++;
                    reciprocalRankTotal += 1.0 / result.firstRelevantRank();
                }
            }
            if (result.status() != EvalCaseResult.Status.SCORED) {
                continue;
            }
            precision.add(result.contextPrecision());
            precisionAtK.add(result.precisionAtK());
            cited.add(result.citedContextPrecision());
            citedAtK.add(result.citedPrecisionAtK());
            recall.add(result.recallAtK());
            ndcg.add(result.ndcgAtK());
            // A case asserting no phrases has coverage 1.0 by definition and would only dilute it.
            if (goldenCase != null && !goldenCase.mustContain().isEmpty()) {
                phrases.add(result.phraseCoverage());
            }
            citationsEmitted += result.citationsEmitted();
            citationsValid += result.citationsValid();
            citationsFabricated += result.citationsFabricated();
            inventedPages += result.inventedPages();
        }

        return new GoldenRunResult(suite.suite(),
                                   results.size(),
                                   passed,
                                   recallCases > 0 ? (double) hits / recallCases : 0.0,
                                   recallCases > 0 ? reciprocalRankTotal / recallCases : 0.0,
                                   precision.value(),
                                   precisionAtK.value(),
                                   cited.value(),
                                   citedAtK.value(),
                                   recall.value(),
                                   ndcg.value(),
                                   citationsEmitted > 0 ? (double) citationsValid / citationsEmitted : 1.0,
                                   citationsEmitted > 0 ? (double) citationsFabricated / citationsEmitted : 0.0,
                                   citationsEmitted,
                                   inventedPages,
                                   phrases.value(),
                                   durationMillis,
                                   List.copyOf(failures));
    }

    /** A mean over the values present; null when none was. */
    private static final class Average {

        private int count;
        private double total;

        void add(@Nullable Double value) {
            if (value != null) {
                count++;
                total += value;
            }
        }

        @Nullable Double value() {
            return count > 0 ? total / count : null;
        }
    }

    /** "n/a" rather than 0.000 for a metric the run did not measure - the log should not imply a score. */
    private static String format(@Nullable Double value) {
        return value != null ? "%.3f".formatted(value) : "n/a";
    }
}
