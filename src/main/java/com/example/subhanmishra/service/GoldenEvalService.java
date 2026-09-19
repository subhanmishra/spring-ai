package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.repository.EvalCaseResultRepository;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.GoldenDataset;
import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
import com.example.subhanmishra.service.provenance.PipelineProvenance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replays a curated dataset through the real chat path and scores what comes back.
 *
 * <p>This is the half of evaluation that online scoring cannot do. Recall - did retrieval find the
 * passage that actually contains the answer, and at what rank - is unanswerable without knowing which
 * pages were the right ones, and no amount of live traffic supplies that. Everything else here is also
 * measured online; what a golden run adds is a fixed question set, so two runs are comparable and a
 * regression is visible as a number moving rather than as traffic changing shape.
 *
 * <p>Four things about how a run executes are load-bearing:
 *
 * <ul>
 *   <li><strong>It goes through the real {@code ChatClient}.</strong> Same advisors, same prompt
 *       template, same model, same retrieval settings - the point is to measure the pipeline, not a
 *       replica of it that can drift away from the thing it claims to describe.</li>
 *   <li><strong>Each case gets a fresh conversation id, cleared afterwards.</strong>
 *       {@code MessageWindowChatMemory} would otherwise feed case N's answer into case N+1's prompt,
 *       so cases would contaminate each other and the order of the dataset would change the scores.
 *       Clearing afterwards also keeps runs out of the conversation list the chat API exposes.</li>
 *   <li><strong>Cases run serially.</strong> Ollama pins one runner slot, so parallel cases would not
 *       finish any sooner and would only contend - the same reasoning that keeps bulk document upload
 *       a serial loop.</li>
 *   <li><strong>Generation and judging are two separate phases.</strong> See {@link #execute}.</li>
 * </ul>
 *
 * <p>There is deliberately no HTTP endpoint for this. A run takes minutes - a single grounded answer
 * on this host is 53-70 seconds - which would need async submission, polling and single-flight
 * machinery to expose safely, for something the tagged integration test triggers in one command.
 */
@Service
public class GoldenEvalService {

    private static final Logger log = LoggerFactory.getLogger(GoldenEvalService.class);

    private static final String RELEVANCY = "relevancy";
    private static final String GROUNDEDNESS = "groundedness";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final GoldenDatasetLoader datasetLoader;
    private final RelevancyEvaluator relevancyEvaluator;
    private final FactCheckingEvaluator factCheckingEvaluator;
    private final EvalRunRepository runRepository;
    private final EvalCaseResultRepository caseResultRepository;
    private final EvalProperties evalProperties;
    private final RagProperties ragProperties;
    private final String chatModel;

    public GoldenEvalService(ChatClient chatClient,
                             ChatMemory chatMemory,
                             EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             GoldenDatasetLoader datasetLoader,
                             RelevancyEvaluator relevancyEvaluator,
                             FactCheckingEvaluator factCheckingEvaluator,
                             EvalRunRepository runRepository,
                             EvalCaseResultRepository caseResultRepository,
                             EvalProperties evalProperties,
                             RagProperties ragProperties,
                             @Value("${spring.ai.ollama.chat.model:unknown}") String chatModel) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.datasetLoader = datasetLoader;
        this.relevancyEvaluator = relevancyEvaluator;
        this.factCheckingEvaluator = factCheckingEvaluator;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.evalProperties = evalProperties;
        this.ragProperties = ragProperties;
        this.chatModel = chatModel;
    }

    /** Loads the configured dataset without running it - for inspecting what a run would cover. */
    public GoldenDataset dataset() {
        return datasetLoader.load(evalProperties.golden().datasetLocation());
    }

    public GoldenRunResult run() {
        return run(dataset(), evalProperties.golden().judged());
    }

    /**
     * Executes every case and reports the aggregate.
     *
     * @param judged whether to also ask the LLM judges. Judging roughly triples a run's wall clock, so
     *               a quick regression check on retrieval and citations can skip it entirely and still
     *               get every deterministic metric.
     */
    public GoldenRunResult run(GoldenDataset dataset, boolean judged) {
        EvalRun run = EvalRun.starting(dataset.suite(),
                                       dataset.cases().size(),
                                       chatModel,
                                       judged ? evalProperties.judgeModel() : null,
                                       judged,
                                       ragProperties.topK(),
                                       ragProperties.similarityThreshold(),
                                       PipelineProvenance.CURRENT_VERSION);

        // Persisted before any case executes. A suite takes minutes, and a run that dies partway
        // through would otherwise leave nothing behind at all - an empty table looks exactly like a
        // suite nobody ran.
        EvalRun persisted = persist(run);
        UUID runId = persisted.id();

        log.info("Starting golden eval run [suite={}, cases={}, judged={}, model={}, topK={}, threshold={}]",
                 dataset.suite(), dataset.cases().size(), judged, chatModel,
                 ragProperties.topK(), ragProperties.similarityThreshold());

        try {
            List<CaseOutcome> outcomes = execute(dataset, judged);
            GoldenRunResult result = aggregate(dataset, outcomes, judged,
                                               System.currentTimeMillis() - persisted.startedAt().toEpochMilli());

            if (runId != null) {
                outcomes.forEach(outcome -> persistCase(runId, outcome));
            }
            persist(persisted.completed(result.passedCount(), result.hitRate(), result.meanReciprocalRank(),
                                        result.citationValidity(), result.citationFabrication(),
                                        result.relevancyRate(), result.groundednessRate()));

            metricsService.recordGoldenRun(dataset.suite(), result.caseCount(), result.passRate(),
                                           result.hitRate(), result.meanReciprocalRank(),
                                           result.citationValidity(), result.citationFabrication(),
                                           result.relevancyRate(), result.groundednessRate(),
                                           result.durationMillis());

            log.info("Golden eval run finished [suite={}, passed={}/{}, hitRate={}, citationValidity={}, "
                     + "fabrication={}, took={}ms]",
                     dataset.suite(), result.passedCount(), result.caseCount(),
                     format(result.hitRate()), format(result.citationValidity()),
                     format(result.citationFabrication()), result.durationMillis());
            return result;

        } catch (RuntimeException e) {
            persist(persisted.failed(e.getMessage()));
            metricsService.recordGoldenRunFailed(dataset.suite());
            throw e;
        }
    }

    /**
     * The two phases.
     *
     * <p>Every answer is generated first, and only then is every answer judged. Interleaving them -
     * generate, judge, generate, judge - is the obvious implementation and the wrong one whenever the
     * judge is a different model from the chat model, because Ollama would swap the two models in and
     * out of memory twice per case rather than once per run. With the judge currently being the chat
     * model itself the swap cost is zero, so this ordering buys nothing today; it is kept because the
     * judge model is configurable, and the day someone points it at a dedicated judge this is the
     * difference between one model load and 2N of them.
     *
     * <p>Judging is also synchronous here, unlike the online path. There is no caller waiting on a
     * response to protect, and a run's numbers are only meaningful once every case has been judged.
     */
    private List<CaseOutcome> execute(GoldenDataset dataset, boolean judged) {
        List<CaseOutcome> outcomes = new ArrayList<>();

        // Phase 1 - generate.
        for (GoldenCase goldenCase : dataset.cases()) {
            outcomes.add(generate(goldenCase));
        }

        // Phase 2 - judge.
        if (judged) {
            for (CaseOutcome outcome : outcomes) {
                judge(outcome);
            }
        }
        return outcomes;
    }

    private CaseOutcome generate(GoldenCase goldenCase) {
        // A fresh id per case, so no case can see another's turn through chat memory.
        String conversationId = "eval-" + UUID.randomUUID();
        long startedAt = System.nanoTime();
        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .user(goldenCase.query())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .chatResponse();

            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            String answer = answerOf(chatResponse);
            List<Document> retrieved = retrievedDocuments(chatResponse);

            EvalScores scores = scoringService.score(answer, retrieved, goldenCase);
            int rank = scoringService.firstRelevantRank(retrieved, goldenCase);

            log.info("Eval case [{}] answered in {}ms: {} chunk(s), {} citation(s), {} fabricated",
                     goldenCase.id(), millis, scores.retrieval().retrievedCount(),
                     scores.citations().emitted(), scores.citations().fabricated());

            return new CaseOutcome(goldenCase, answer, retrieved, scores, rank, millis);

        } finally {
            // Always cleared, including when the case threw, so a failed run does not leave eval
            // conversations behind in Redis for the chat API to list.
            chatMemory.clear(conversationId);
        }
    }

    private void judge(CaseOutcome outcome) {
        if (outcome.retrieved().isEmpty()) {
            // Both judges score an answer against its context. With no context there is nothing to
            // score against, and FactCheckingEvaluator would report "not supported" for an answer that
            // was never meant to be grounded - an out-of-corpus case would fail for behaving correctly.
            return;
        }
        EvaluationRequest request =
                new EvaluationRequest(outcome.goldenCase().query(), outcome.retrieved(), outcome.answer());

        Boolean relevancy = verdict(RELEVANCY, relevancyEvaluator, request);
        Boolean groundedness = verdict(GROUNDEDNESS, factCheckingEvaluator, request);
        outcome.applyJudgements(relevancy, groundedness);
    }

    /** A verdict, or null when the judge failed - which must not be recorded as a failed judgement. */
    private @Nullable Boolean verdict(String metric, Evaluator evaluator, EvaluationRequest request) {
        try {
            return evaluator.evaluate(request).isPass();
        } catch (RuntimeException e) {
            log.warn("Golden {} judgement failed; recording it as unjudged rather than failed", metric, e);
            return null;
        }
    }

    private GoldenRunResult aggregate(GoldenDataset dataset,
                                      List<CaseOutcome> outcomes,
                                      boolean judged,
                                      long durationMillis) {
        int caseCount = outcomes.size();
        int passed = 0;
        int recallCases = 0;
        int hits = 0;
        double reciprocalRankTotal = 0;
        int citationsEmitted = 0;
        int citationsValid = 0;
        int citationsFabricated = 0;
        long inventedPages = 0;
        int relevancyJudged = 0;
        int relevancyPassed = 0;
        int groundednessJudged = 0;
        int groundednessPassed = 0;
        List<String> failures = new ArrayList<>();

        for (CaseOutcome outcome : outcomes) {
            EvalScores scores = outcome.scores();
            if (scores.passed()) {
                passed++;
            } else {
                failures.add(outcome.goldenCase().id() + ": " + String.join("; ", scores.failureReasons()));
            }
            metricsService.recordGoldenCase(dataset.suite(), outcome.goldenCase().id(),
                                            scores.passed(), outcome.latencyMillis());

            if (outcome.goldenCase().scoresRecall()) {
                recallCases++;
                if (outcome.firstRelevantRank() > 0) {
                    hits++;
                    reciprocalRankTotal += 1.0 / outcome.firstRelevantRank();
                }
            }

            citationsEmitted += scores.citations().emitted();
            citationsValid += scores.citations().valid();
            citationsFabricated += scores.citations().fabricated();
            inventedPages += scores.citations().fabricatedCitations().stream()
                                     .filter(citation -> !citation.hasMalformedPage())
                                     .count();

            // Counted separately from caseCount: a null verdict means the case was not judged, and
            // folding those into the denominator would report unjudged cases as judged failures.
            if (scores.relevancy() != null) {
                relevancyJudged++;
                if (scores.relevancy()) {
                    relevancyPassed++;
                }
            }
            if (scores.groundedness() != null) {
                groundednessJudged++;
                if (scores.groundedness()) {
                    groundednessPassed++;
                }
            }
        }

        return new GoldenRunResult(dataset.suite(),
                                   caseCount,
                                   passed,
                                   recallCases > 0 ? (double) hits / recallCases : 0.0,
                                   recallCases > 0 ? reciprocalRankTotal / recallCases : 0.0,
                                   citationsEmitted > 0 ? (double) citationsValid / citationsEmitted : 1.0,
                                   citationsEmitted > 0 ? (double) citationsFabricated / citationsEmitted : 0.0,
                                   citationsEmitted,
                                   (int) inventedPages,
                                   judged && relevancyJudged > 0
                                           ? (double) relevancyPassed / relevancyJudged : null,
                                   judged && groundednessJudged > 0
                                           ? (double) groundednessPassed / groundednessJudged : null,
                                   durationMillis,
                                   List.copyOf(failures));
    }

    private EvalRun persist(EvalRun run) {
        return evalProperties.golden().persist() ? runRepository.save(run) : run;
    }

    private void persistCase(UUID runId, CaseOutcome outcome) {
        if (!evalProperties.golden().persist()) {
            return;
        }
        caseResultRepository.save(EvalCaseResult.from(runId,
                                                      outcome.goldenCase().id(),
                                                      outcome.goldenCase().query(),
                                                      outcome.answer(),
                                                      outcome.scores(),
                                                      outcome.firstRelevantRank(),
                                                      outcome.latencyMillis()));
    }

    private static String answerOf(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Document> retrievedDocuments(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return List.of();
        }
        Object documents = chatResponse.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private static String format(double value) {
        return "%.3f".formatted(value);
    }

    /**
     * One case's outcome while a run is in progress.
     *
     * <p>Mutable, unlike everything else here, because the judgements arrive in a second pass over the
     * same list. A class rather than a record for exactly that reason.
     */
    public static final class CaseOutcome {

        private final GoldenCase goldenCase;
        private final String answer;
        private final List<Document> retrieved;
        private final int firstRelevantRank;
        private final long latencyMillis;
        private EvalScores scores;

        CaseOutcome(GoldenCase goldenCase, String answer, List<Document> retrieved,
                    EvalScores scores, int firstRelevantRank, long latencyMillis) {
            this.goldenCase = goldenCase;
            this.answer = answer;
            this.retrieved = retrieved;
            this.scores = scores;
            this.firstRelevantRank = firstRelevantRank;
            this.latencyMillis = latencyMillis;
        }

        void applyJudgements(@Nullable Boolean relevancy, @Nullable Boolean groundedness) {
            this.scores = scores.withJudgements(relevancy, groundedness);
        }

        public GoldenCase goldenCase() {
            return goldenCase;
        }

        public String answer() {
            return answer;
        }

        public List<Document> retrieved() {
            return retrieved;
        }

        public EvalScores scores() {
            return scores;
        }

        public int firstRelevantRank() {
            return firstRelevantRank;
        }

        public long latencyMillis() {
            return latencyMillis;
        }
    }

    /**
     * The aggregate of one run.
     *
     * <p>{@code relevancyRate} and {@code groundednessRate} are null when the run did not judge, rather
     * than zero - a suite run without judging has not scored zero on groundedness, it has not measured
     * it, and the two must not render the same way on a dashboard.
     *
     * @param citationValidity    1.0 when no citations were emitted at all, which is why
     *                            {@code citationsEmitted} sits beside it. An assistant that stopped
     *                            citing entirely would otherwise show perfect validity.
     * @param inventedPageCount   fabricated citations that named a plain page number the context never
     *                            offered - the failure the citation header, the prompts and the parser
     *                            strippers all exist to prevent. Reported separately from
     *                            {@code citationFabrication} because the two behave nothing alike: this
     *                            has been 0 on every run since the footer and contents strippers
     *                            landed, whereas the rate beside it is dominated by the model writing
     *                            section numbers where pages belong, which varies from 0.22 to 0.33 run
     *                            to run on an unchanged pipeline. A regression guard needs the stable
     *                            one; see {@code EvalSuiteIT}.
     */
    public record GoldenRunResult(String suite,
                                  int caseCount,
                                  int passedCount,
                                  double hitRate,
                                  double meanReciprocalRank,
                                  double citationValidity,
                                  double citationFabrication,
                                  int citationsEmitted,
                                  int inventedPageCount,
                                  @Nullable Double relevancyRate,
                                  @Nullable Double groundednessRate,
                                  long durationMillis,
                                  List<String> failures) {

        public double passRate() {
            return caseCount == 0 ? 0.0 : (double) passedCount / caseCount;
        }
    }
}
