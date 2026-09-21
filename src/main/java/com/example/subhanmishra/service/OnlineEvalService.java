package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.service.eval.CitationResolver;
import com.example.subhanmishra.service.eval.CitationResolver.Resolution;
import com.example.subhanmishra.service.eval.EvalScores;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Scores real chat traffic as it happens.
 *
 * <p>The contract with the chat path is absolute: <strong>nothing here may delay a response.</strong>
 * A user waits 53-70 seconds for a grounded answer already, and evaluation is observability - it
 * cannot be allowed to add to that. The class is split along exactly that line.
 *
 * <p><strong>Deterministic scoring runs inline</strong> on every turn. It is a few regex passes over
 * the answer and a comparison against at most top-k retrieved chunks - no network, no model, no
 * database - so it costs microseconds and there is nothing to gain by deferring it. Running it inline
 * is also what gives these metrics 100% coverage, with no sampling and no drops.
 *
 * <p><strong>LLM judging is fired and forgotten.</strong> The answer has already been returned to the
 * caller by the time a judgement starts. Three bounds keep that from becoming a liability:
 *
 * <ul>
 *   <li><em>Sampling.</em> Judging is not free to the system even though it is free to the caller.
 *       Ollama pins one runner slot, so a judge call occupies the chat model and the <em>next</em>
 *       user's generation queues behind it. The sample rate is the dial for how much of that
 *       interference is acceptable.</li>
 *   <li><em>Non-blocking admission.</em> A permit is taken with {@code tryAcquire} <em>before</em> a
 *       thread is started, and the judgement is dropped if none is free. Virtual threads are cheap
 *       enough that submitting first and blocking on the permit inside would happily accumulate
 *       thousands of parked threads under load - the bound has to be enforced at the door.</li>
 *   <li><em>Timeout.</em> A wedged judge would otherwise hold its permit forever and silently disable
 *       judging for the life of the process.</li>
 * </ul>
 *
 * <p>Drops are counted rather than logged per occurrence: under load they are expected, and a log line
 * each would be noise. A rising {@code rag.eval.online.judgements.dropped.total} means the sample rate
 * is set too high for the traffic.
 */
@Service
public class OnlineEvalService {

    private static final Logger log = LoggerFactory.getLogger(OnlineEvalService.class);

    private static final String RELEVANCY = "relevancy";
    private static final String GROUNDEDNESS = "groundedness";

    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final RelevancyEvaluator relevancyEvaluator;
    private final FactCheckingEvaluator factCheckingEvaluator;
    private final EvalProperties properties;

    /**
     * Bounds judgements in flight. One permit means the worst case is a single generation queued behind
     * a judge call, rather than a pile-up that would make the chat endpoint appear to have stalled.
     */
    private final Semaphore judgePermits;

    private final ExecutorService judgeExecutor;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public OnlineEvalService(EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             RelevancyEvaluator relevancyEvaluator,
                             FactCheckingEvaluator factCheckingEvaluator,
                             EvalProperties properties) {
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.relevancyEvaluator = relevancyEvaluator;
        this.factCheckingEvaluator = factCheckingEvaluator;
        this.properties = properties;
        this.judgePermits = new Semaphore(properties.online().maxConcurrentJudgements());

        ThreadFactory factory = Thread.ofVirtual().name("eval-judge-", 0).factory();
        this.judgeExecutor = Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * Scores a turn whose citations have already been through {@link CitationResolver}, recording what
     * the resolver did before scoring the text it produced.
     *
     * <p>Scoring the resolved answer rather than the raw one is the point: it is what the caller
     * received, so it is what the citation metrics should describe. The abstention count published here
     * is what keeps that honest - every section number the resolver could not place is still counted as
     * a fabrication by {@code EvalScoringService} a moment later.
     */
    public void evaluate(String query, Resolution resolution, @Nullable List<Document> retrieved) {
        if (!properties.enabled() || !properties.online().enabled()) {
            return;
        }
        metricsService.recordCitationResolution(resolution.repaired(), resolution.abstained());
        if (!resolution.unresolved().isEmpty()) {
            log.debug("Left {} section-number citation(s) unresolved - they match no retrieved chunk: {}",
                      resolution.abstained(), resolution.unresolved());
        }
        evaluate(query, resolution.answer(), retrieved);
    }

    /**
     * Scores one completed chat turn. Returns immediately.
     *
     * <p>Wrapped in a catch-all because the answer has already been generated and, on the blocking
     * path, is about to be returned. There is no failure here worth turning a successful response into
     * an error, so anything that goes wrong is logged and swallowed.
     */
    public void evaluate(String query, @Nullable String answer, @Nullable List<Document> retrieved) {
        if (!properties.enabled() || !properties.online().enabled()) {
            return;
        }
        try {
            EvalScores scores = scoringService.score(answer, retrieved);
            metricsService.recordOnline(scores);

            if (shouldJudge(answer, retrieved)) {
                submitJudgements(query, answer, retrieved);
            }
        } catch (RuntimeException e) {
            log.warn("Online evaluation failed for a chat turn; the answer itself was unaffected", e);
        }
    }

    /**
     * Whether this turn goes to the LLM judges.
     *
     * <p>An ungrounded turn is skipped regardless of the sample rate. Both judges score an answer
     * <em>against its context</em>, so with no retrieved documents the context is empty and the verdict
     * is meaningless - {@code FactCheckingEvaluator} would be asked whether a claim is supported by a
     * blank document, and would say no. Feeding those in would drag groundedness down in proportion to
     * how much general conversation the assistant handles, which is a capability its system prompt
     * explicitly promises rather than a defect.
     */
    private boolean shouldJudge(@Nullable String answer, @Nullable List<Document> retrieved) {
        if (answer == null || answer.isBlank() || retrieved == null || retrieved.isEmpty()) {
            return false;
        }
        double rate = properties.online().judgeSampleRate();
        return rate > 0.0 && (rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate);
    }

    private void submitJudgements(String query, String answer, List<Document> retrieved) {
        // Admission first: a refused permit costs nothing, whereas starting a thread and then blocking
        // on the permit would let the backlog grow without limit.
        if (!judgePermits.tryAcquire()) {
            metricsService.recordJudgementDropped();
            return;
        }

        // Captured on the caller's thread. Without it the judge's log lines reach Loki with an empty
        // traceId and cannot be correlated back to the chat turn they are about - the same reason
        // DocumentIngestionService wraps its batch threads.
        ContextSnapshot snapshot = contextSnapshotFactory.captureAll();

        try {
            judgeExecutor.execute(snapshot.wrap(() -> {
                try {
                    runJudgements(query, answer, retrieved);
                } finally {
                    judgePermits.release();
                }
            }));
        } catch (RuntimeException e) {
            // Submission itself failed, e.g. the executor is shutting down. Release the permit we took,
            // or it is leaked for the life of the process.
            judgePermits.release();
            metricsService.recordJudgementDropped();
            log.debug("Could not submit an online judgement", e);
        }
    }

    /**
     * Both verdicts, in sequence on one thread.
     *
     * <p>Sequential on purpose. Ollama serialises on a single runner slot, so running the two judges
     * concurrently would not overlap any work - it would only double the number of requests competing
     * with live traffic for that slot.
     */
    private void runJudgements(String query, String answer, List<Document> retrieved) {
        EvaluationRequest request = new EvaluationRequest(query, retrieved, answer);
        judge(RELEVANCY, relevancyEvaluator, request);
        judge(GROUNDEDNESS, factCheckingEvaluator, request);
    }

    private void judge(String metric, Evaluator evaluator, EvaluationRequest request) {
        long startedAt = System.nanoTime();
        try {
            EvaluationResponse response = evaluator.evaluate(request);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            metricsService.recordOnlineJudgement(metric, response.isPass(), millis);
        } catch (RuntimeException e) {
            metricsService.recordJudgementError(metric);
            log.debug("Online {} judgement failed", metric, e);
        }
    }

    /**
     * Gives in-flight judgements a bounded chance to finish at shutdown, then abandons them. They are
     * observability, so losing one on shutdown is acceptable; blocking the shutdown on a model call
     * that takes the better part of a minute is not.
     */
    @PreDestroy
    void shutdown() {
        judgeExecutor.shutdown();
        try {
            if (!judgeExecutor.awaitTermination(properties.online().judgeTimeoutSeconds(), TimeUnit.SECONDS)) {
                judgeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            judgeExecutor.shutdownNow();
        }
    }
}
