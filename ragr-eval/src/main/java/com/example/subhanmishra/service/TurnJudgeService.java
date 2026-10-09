package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.entity.EvalTurnChunk;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeInput;
import com.example.subhanmishra.service.eval.ChunkGradeEvaluator;
import com.example.subhanmishra.service.eval.CitationSupportEvaluator;
import com.example.subhanmishra.service.eval.CitationSupportEvaluator.Check;
import com.example.subhanmishra.service.eval.ClaimFaithfulnessEvaluator;
import com.example.subhanmishra.service.eval.CompletenessEvaluator;
import com.example.subhanmishra.service.eval.TaskClassifier;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs every judge over one turn, a stage at a time, each call held until chat is idle.
 *
 * <p>Shared by the live worker and the golden suite, so a metric means the same thing on both. The stages,
 * in the order they run:
 *
 * <ol>
 *   <li><b>task</b> - the question's {@code TaskType}.</li>
 *   <li><b>chunk grades</b> - every chunk in the pool, 0/1/2, for precision, pooled recall, MRR and NDCG.
 *       The last stage an ungrounded turn gets. Its answer is never judged - a standing rule: judging
 *       general conversation against no context would drag groundedness down. But its pool is graded: a
 *       relevant chunk there means the threshold kept useful context from the model, the clearest sign it
 *       is too tight (recall 0). A pool with nothing relevant is just a question the corpus does not
 *       cover.</li>
 *   <li><b>relevancy</b> and <b>groundedness</b> - Spring AI's two judges, unchanged so their history
 *       stays comparable.</li>
 *   <li><b>claims</b> - extraction, then one batched verification: faithfulness as a fraction.</li>
 *   <li><b>citation support</b> - each valid citation's sentence against its passage.</li>
 *   <li><b>completeness</b> - whether every part of the question was answered.</li>
 * </ol>
 *
 * <p><b>Cost.</b> Whole grounded turns have taken 42-66 s of judge time; adding up each stage's slowest
 * case gives up to ~90-100 s. Either way the idle gate makes it affordable: it is spent only while nobody
 * is waiting.
 *
 * <p>A stage that times out or returns nonsense leaves its fields null and the turn PARTIAL, and the
 * later stages still run. A worker stopped mid-turn is different: it throws {@link Interrupted}, so the
 * turn goes back in the queue instead of being saved half-judged.
 */
@Service
public class TurnJudgeService {

    private static final Logger log = LoggerFactory.getLogger(TurnJudgeService.class);

    private final ChatIdleGate idleGate;
    private final TaskClassifier taskClassifier;
    private final ChunkGradeEvaluator chunkGradeEvaluator;
    private final RelevancyEvaluator relevancyEvaluator;
    private final FactCheckingEvaluator factCheckingEvaluator;
    private final ClaimFaithfulnessEvaluator claimFaithfulnessEvaluator;
    private final CitationSupportEvaluator citationSupportEvaluator;
    private final CompletenessEvaluator completenessEvaluator;
    private final EvalMetricsService metricsService;
    private final EvalProperties properties;
    private final ExecutorService callExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("eval-judge-call-", 0).factory());
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    public TurnJudgeService(ChatIdleGate idleGate,
                            TaskClassifier taskClassifier,
                            ChunkGradeEvaluator chunkGradeEvaluator,
                            RelevancyEvaluator relevancyEvaluator,
                            FactCheckingEvaluator factCheckingEvaluator,
                            ClaimFaithfulnessEvaluator claimFaithfulnessEvaluator,
                            CitationSupportEvaluator citationSupportEvaluator,
                            CompletenessEvaluator completenessEvaluator,
                            EvalMetricsService metricsService,
                            EvalProperties properties) {
        this.idleGate = idleGate;
        this.taskClassifier = taskClassifier;
        this.chunkGradeEvaluator = chunkGradeEvaluator;
        this.relevancyEvaluator = relevancyEvaluator;
        this.factCheckingEvaluator = factCheckingEvaluator;
        this.claimFaithfulnessEvaluator = claimFaithfulnessEvaluator;
        this.citationSupportEvaluator = citationSupportEvaluator;
        this.completenessEvaluator = completenessEvaluator;
        this.metricsService = metricsService;
        this.properties = properties;
    }

    /**
     * Judges one turn.
     *
     * @throws Interrupted when the calling thread is interrupted - the worker stopping
     */
    public TurnVerdicts judge(JudgeInput input) {
        TurnVerdicts verdicts = new TurnVerdicts();
        String query = input.query();
        String answer = input.answer();

        verdicts.taskType(call("task", () -> taskClassifier.classify(query)));
        if (answer == null || answer.isBlank()) {
            return verdicts;
        }

        List<@Nullable Integer> grades = new ArrayList<>(input.chunks().size());
        for (EvalTurnChunk chunk : input.chunks()) {
            Document document = chunk.toDocument();
            grades.add(call("chunk_grade", () -> chunkGradeEvaluator.grade(query, document)));
        }
        verdicts.grades(grades, input.retrievedCount());
        if (input.retrievedCount() == 0) {
            return verdicts;
        }

        List<Document> inContext = input.inContext().stream().map(EvalTurnChunk::toDocument).toList();
        EvaluationRequest request = new EvaluationRequest(query, inContext, answer);
        verdicts.relevancyPass(call(EvalMetricsService.RELEVANCY, () -> relevancyEvaluator.evaluate(request).isPass()));
        verdicts.groundednessPass(call(EvalMetricsService.GROUNDEDNESS,
                                       () -> factCheckingEvaluator.evaluate(request).isPass()));

        List<String> claims = call("claims_extract", () -> claimFaithfulnessEvaluator.extractClaims(answer));
        verdicts.claims(claims == null
                                ? null
                                : call("claims_verify", () -> claimFaithfulnessEvaluator.verify(claims, inContext)));

        List<@Nullable Boolean> supported = new ArrayList<>();
        for (Check check : citationSupportEvaluator.checks(answer, inContext)) {
            supported.add(call("citation_support", () -> citationSupportEvaluator.judge(check)));
        }
        verdicts.citations(supported);

        verdicts.completenessPass(call("completeness", () -> completenessEvaluator.judge(query, answer)));
        return verdicts;
    }

    /**
     * One judge call: wait for idle chat, run it with a deadline, time it. Null for a timeout, an
     * exception or an unreadable reply - "not measured", never a failing verdict.
     */
    private <T> @Nullable T call(String stage, Callable<@Nullable T> judge) {
        try {
            idleGate.awaitIdle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted();
        }
        long startedAt = System.nanoTime();
        Future<T> future;
        try {
            future = callExecutor.submit(contextSnapshotFactory.captureAll().wrap(judge));
        } catch (RejectedExecutionException e) {
            throw new Interrupted();
        }
        int timeout = properties.judge().callTimeoutSeconds();
        try {
            T result = future.get(timeout, TimeUnit.SECONDS);
            metricsService.recordJudgeCall(stage, (System.nanoTime() - startedAt) / 1_000_000);
            if (result == null) {
                metricsService.recordJudgementError(stage);
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            metricsService.recordJudgementError(stage);
            log.warn("Abandoned the {} judge call after {}s without a verdict", stage, timeout);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Interrupted();
        } catch (ExecutionException e) {
            metricsService.recordJudgementError(stage);
            log.debug("The {} judge call failed", stage, e.getCause());
            return null;
        }
    }

    /** The judging thread was interrupted: the turn is unfinished, not judged. */
    public static final class Interrupted extends RuntimeException {
        Interrupted() {
            super("judging interrupted", null, false, false);
        }
    }

    /**
     * By the time this runs, {@link TurnJudgeWorker#stop} has already given the call in flight its
     * {@code shutdown-wait-seconds} and interrupted it; anything still running here belongs to a turn that
     * is already back in the queue.
     */
    @PreDestroy
    void shutdown() {
        callExecutor.shutdownNow();
    }
}
