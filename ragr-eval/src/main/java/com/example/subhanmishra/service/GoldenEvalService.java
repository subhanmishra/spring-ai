package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.entity.EvalTurn;
import com.example.subhanmishra.entity.EvalTurn.JudgeStatus;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.repository.EvalCaseResultRepository;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeInput;
import com.example.subhanmishra.service.EvalScoringService.ReferenceScores;
import com.example.subhanmishra.service.eval.ContextPrecisionEvaluator;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.GoldenDataset;
import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replays a curated dataset through the real chat path and scores what comes back.
 *
 * <p>It does what live scoring cannot: with known-correct pages it can measure whether retrieval found
 * the answer, and at what rank. And a fixed question set makes two runs comparable, so a regression shows
 * as a number moving.
 *
 * <p>How a run works, and why:
 * <ul>
 *   <li><b>Through ragr-app's real endpoint.</b> Each case is a {@code POST /ai/generate}, so it measures
 *       exactly what a user gets, not a copy that could drift.</li>
 *   <li><b>Each case reads its own turn back from Kafka.</b> The HTTP response has its citations taken
 *       out; the event has the answer as the model cited it and the chunks it used. The request is marked
 *       {@link TurnOrigin#GOLDEN}, so live metrics leave it out.</li>
 *   <li><b>A new conversation per case, deleted afterwards.</b> Otherwise chat memory would feed one
 *       case's answer into the next, and the dataset's order would change the scores.</li>
 *   <li><b>One case at a time.</b> Ollama serves one request at a time; parallel cases would only queue.</li>
 *   <li><b>All answers first, then all judging.</b> See {@link #execute}.</li>
 * </ul>
 *
 * <p>Deliberately no HTTP endpoint: a run takes minutes, and exposing that safely would need async
 * submission and polling, for something one test command already does.
 */
@Service
public class GoldenEvalService {

    private static final Logger log = LoggerFactory.getLogger(GoldenEvalService.class);

    /**
     * Well above a grounded answer (about a minute) plus a cold model load (~35 s). It exists so a hung
     * chat service fails the run instead of hanging it.
     */
    private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(5);

    private final RestClient chat;
    private final ConsumerFactory<String, ChatTurnCompleted> consumerFactory;
    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final GoldenDatasetLoader datasetLoader;
    private final TurnJudgeService turnJudge;
    private final EvalTurnRepository turns;
    private final ContextPrecisionEvaluator contextPrecisionEvaluator;
    private final EvalRunRepository runRepository;
    private final EvalCaseResultRepository caseResultRepository;
    private final EvalProperties evalProperties;

    public GoldenEvalService(RestClient.Builder restClients,
                             ConsumerFactory<String, ChatTurnCompleted> consumerFactory,
                             EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             GoldenDatasetLoader datasetLoader,
                             TurnJudgeService turnJudge,
                             EvalTurnRepository turns,
                             ContextPrecisionEvaluator contextPrecisionEvaluator,
                             EvalRunRepository runRepository,
                             EvalCaseResultRepository caseResultRepository,
                             EvalProperties evalProperties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(ANSWER_TIMEOUT);
        this.chat = restClients.baseUrl(evalProperties.golden().chatUrl().toString())
                               .requestFactory(requestFactory)
                               .build();
        this.consumerFactory = consumerFactory;
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.datasetLoader = datasetLoader;
        this.turnJudge = turnJudge;
        this.turns = turns;
        this.contextPrecisionEvaluator = contextPrecisionEvaluator;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.evalProperties = evalProperties;
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
     * @param judged whether to also ask the AI judges. That takes several times longer; without it a run
     *               still gets every rule-based metric.
     */
    public GoldenRunResult run(GoldenDataset dataset, boolean judged) {
        EvalRun run = EvalRun.starting(dataset.suite(),
                                       dataset.cases().size(),
                                       judged ? evalProperties.judgeModel() : null,
                                       judged);

        // Saved before any case runs, so a run that dies part-way still leaves a trace.
        EvalRun persisted = persist(run);
        UUID runId = persisted.id();

        log.info("Starting golden eval run [suite={}, cases={}, judged={}, chatUrl={}]",
                 dataset.suite(), dataset.cases().size(), judged, evalProperties.golden().chatUrl());

        try {
            List<CaseOutcome> outcomes = execute(dataset, judged, runId);

            // Chat's model and retrieval settings are taken from its first turn, not from config here.
            if (!outcomes.isEmpty()) {
                ChatTurnCompleted first = outcomes.getFirst().turn();
                persisted = persist(persisted.withPipeline(first.chatModel(), first.topK(),
                                                           first.similarityThreshold()));
                log.info("Golden eval run answered by [model={}, topK={}, threshold={}]",
                         first.chatModel(), first.topK(), first.similarityThreshold());
            }
            GoldenRunResult result = aggregate(dataset, outcomes, judged,
                                               System.currentTimeMillis() - persisted.startedAt().toEpochMilli());

            if (runId != null) {
                outcomes.forEach(outcome -> persistCase(runId, outcome));
            }
            persist(persisted.completed(result.passedCount(), result.hitRate(), result.meanReciprocalRank(),
                                        result.contextPrecision(), result.precisionAtK(),
                                        result.judgedContextPrecision(), result.judgedPrecisionAtK(),
                                        result.citedContextPrecision(), result.citedPrecisionAtK(),
                                        result.recallAtK(), result.ndcgAtK(),
                                        result.citationValidity(), result.citationFabrication(),
                                        result.relevancyRate(), result.groundednessRate(), result.phraseCoverage()));

            metricsService.recordGoldenRun(dataset.suite(), result.caseCount(), result.passRate(),
                                           result.hitRate(), result.meanReciprocalRank(),
                                           result.contextPrecision(), result.precisionAtK(),
                                           result.judgedContextPrecision(), result.judgedPrecisionAtK(),
                                           result.citedContextPrecision(), result.citedPrecisionAtK(),
                                           result.citationValidity(), result.citationFabrication(),
                                           result.relevancyRate(), result.groundednessRate(),
                                           result.durationMillis());

            log.info("Golden eval run finished [suite={}, passed={}/{}, hitRate={}, contextPrecision={}, "
                     + "precisionAtK={}, citedPrecision={}, citationValidity={}, fabrication={}, took={}ms]",
                     dataset.suite(), result.passedCount(), result.caseCount(),
                     format(result.hitRate()), format(result.contextPrecision()),
                     format(result.precisionAtK()), format(result.citedContextPrecision()),
                     format(result.citationValidity()), format(result.citationFabrication()),
                     result.durationMillis());
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
     * <p>Every answer first, then every judgement. With a separate judge model, interleaving them would
     * swap the two models in and out of memory twice per case instead of once per run. Today the judge is
     * the chat model, so it costs nothing either way; the order is kept for the day it is not.
     *
     * <p>Judging is synchronous here, unlike live: no user is waiting, and a run's numbers mean nothing
     * until every case is judged.
     */
    private List<CaseOutcome> execute(GoldenDataset dataset, boolean judged, @Nullable UUID runId) {
        List<CaseOutcome> outcomes = new ArrayList<>();

        // Phase 1 - generate. The feed is positioned at the end of the topic before the first request,
        // so every turn this run produces is read and nothing older is.
        try (TurnFeed feed = new TurnFeed()) {
            for (GoldenCase goldenCase : dataset.cases()) {
                outcomes.add(generate(goldenCase, feed, runId));
            }
        }

        // Phase 2 - judge.
        if (judged) {
            for (CaseOutcome outcome : outcomes) {
                judge(outcome);
            }
        }
        return outcomes;
    }

    private CaseOutcome generate(GoldenCase goldenCase, TurnFeed feed, @Nullable UUID runId) {
        // A fresh id per case, so no case can see another's turn through chat memory.
        String conversationId = "eval-" + UUID.randomUUID();
        long startedAt = System.nanoTime();
        try {
            // The body is what a user reads - citations stripped out - so it is not what gets scored.
            chat.post()
                .uri("/ai/generate")
                .header(TurnOrigin.HEADER, TurnOrigin.GOLDEN.name())
                .header("X-Conversation-Id", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt", goldenCase.query()))
                .retrieve()
                .toBodilessEntity();
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            ChatTurnCompleted turn = feed.await(conversationId, evalProperties.golden().turnTimeout());
            List<Document> retrieved = turn.retrievedDocuments();

            // Already resolved by ragr-app, like the user's copy: the suite measures what users receive.
            String answer = turn.answer();

            EvalScores scores = scoringService.score(answer, retrieved, goldenCase);
            int rank = scoringService.firstRelevantRank(retrieved, goldenCase);

            // Free - no model call - so it runs on every case, judged or not.
            ContextPrecisionScores precision = scoringService.contextPrecision(retrieved, goldenCase);
            // Free as well - the answer's own citations against the retrieved headers.
            ContextPrecisionScores cited = scoringService.citedPrecision(answer, retrieved);
            // And the same rank metrics live traffic gets from the judge, from expected pages over the
            // whole candidate pool.
            List<Document> pool = turn.pool().stream().map(ChatTurnCompleted.RetrievedChunk::toDocument).toList();
            ReferenceScores reference = scoringService.referenceScores(pool, retrieved.size(), goldenCase);
            EvalTurn evalTurn = EvalTurn.from(turn, scores, cited, runId, goldenCase.id(), JudgeStatus.NOT_QUEUED,
                                              false);

            log.info("Eval case [{}] answered in {}ms: {} chunk(s), {} citation(s), {} fabricated, "
                     + "{} section number(s) resolved, {} left unresolved, context precision {}, cited {}",
                     goldenCase.id(), millis, scores.retrieval().retrievedCount(),
                     scores.citations().emitted(), scores.citations().fabricated(),
                     turn.citationsRepaired(), turn.citationsAbstained(),
                     precision != null ? format(precision.averagePrecision()) : "n/a",
                     cited != null ? cited.relevanceAsString() : "n/a");

            return new CaseOutcome(goldenCase, turn, evalTurn, answer, retrieved, scores, rank, precision, cited,
                                   reference, millis);

        } finally {
            deleteConversation(conversationId);
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

    /**
     * The golden run's own view of the chat-turn topic.
     *
     * <p>Assigned, not subscribed, and never committed: it is outside the live consumer's group, so it
     * neither takes its partition nor moves its offsets. It starts at the end of the topic, so it sees only
     * this run's turns.
     *
     * <p>Every golden turn is kept by conversation id, not just the one awaited, so a late turn from an
     * earlier case that timed out is never mistaken for the current one.
     */
    private final class TurnFeed implements AutoCloseable {

        private final Consumer<String, ChatTurnCompleted> consumer;
        private final Map<String, ChatTurnCompleted> arrived = new HashMap<>();

        TurnFeed() {
            this.consumer = consumerFactory.createConsumer("ragr-eval-golden", null, "-golden", null);
            String topic = evalProperties.topic();
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                                                      .map(info -> new TopicPartition(topic, info.partition()))
                                                      .toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            // seekToEnd is lazy; reading the position now fixes "the end" before the first request.
            partitions.forEach(consumer::position);
        }

        ChatTurnCompleted await(String conversationId, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!arrived.containsKey(conversationId)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("No chat turn arrived on " + evalProperties.topic()
                            + " for conversation " + conversationId + " within " + timeout
                            + " - was the broker down when ragr-app answered? Check "
                            + "rag_chat_turn_events_total{outcome=\"dropped\"} on ragr-app.");
                }
                for (ConsumerRecord<String, ChatTurnCompleted> record : consumer.poll(Duration.ofMillis(500))) {
                    ChatTurnCompleted turn = record.value();
                    // Null when the record could not be deserialized; the error-handling deserializer
                    // has already recorded why.
                    if (turn != null && turn.origin() == TurnOrigin.GOLDEN) {
                        arrived.put(turn.conversationId(), turn);
                    }
                }
            }
            return arrived.remove(conversationId);
        }

        @Override
        public void close() {
            consumer.close();
        }
    }

    /**
     * The same judges, in the same order, as live turns get from {@link TurnJudgeWorker}, so golden and
     * live numbers mean the same. An ungrounded case gets only its task classified and its pool graded:
     * the answer judges compare an answer with its context, and an out-of-corpus case would fail them for
     * behaving correctly.
     *
     * <p>Then, for grounded cases, the judged context precision, which has no live counterpart. Last on
     * purpose: it is several calls, so an interrupted run has already recorded the rest.
     */
    private void judge(CaseOutcome outcome) {
        TurnVerdicts verdicts = turnJudge.judge(JudgeInput.of(outcome.evalTurn()));
        outcome.applyVerdicts(verdicts);
        if (outcome.retrieved().isEmpty()) {
            return;
        }
        outcome.applyJudgedPrecision(contextPrecisionEvaluator.judge(outcome.goldenCase().query(),
                                                                     outcome.answer(),
                                                                     outcome.retrieved()));
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

        // The two precisions average over DIFFERENT sets of cases: expected-page precision over cases that
        // list pages, judged precision over any case that retrieved something. Dividing either by
        // caseCount would count cases it never scored.
        int precisionCases = 0;
        double precisionTotal = 0;
        double precisionAtKTotal = 0;
        int judgedPrecisionCases = 0;
        double judgedPrecisionTotal = 0;
        double judgedPrecisionAtKTotal = 0;
        // Cited applies where judged does - any case that retrieved something - but on every run.
        int citedPrecisionCases = 0;
        double citedPrecisionTotal = 0;
        double citedPrecisionAtKTotal = 0;
        // Reference recall and NDCG over the cases declaring expected pages; phrase coverage over the cases
        // declaring phrases - a case asserting none has coverage 1.0 by definition and would only dilute it.
        int referenceCases = 0;
        double recallTotal = 0;
        int ndcgCases = 0;
        double ndcgTotal = 0;
        int phraseCases = 0;
        double phraseTotal = 0;

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

            ContextPrecisionScores precision = outcome.contextPrecision();
            if (precision != null) {
                precisionCases++;
                precisionTotal += precision.averagePrecision();
                precisionAtKTotal += precision.precisionAtK();
            }
            ContextPrecisionScores judgedPrecision = outcome.judgedContextPrecision();
            if (judgedPrecision != null) {
                judgedPrecisionCases++;
                judgedPrecisionTotal += judgedPrecision.averagePrecision();
                judgedPrecisionAtKTotal += judgedPrecision.precisionAtK();
            }
            ReferenceScores reference = outcome.reference();
            if (reference != null) {
                referenceCases++;
                recallTotal += reference.pageRecall();
                if (reference.ranking().ndcgAtK() != null) {
                    ndcgCases++;
                    ndcgTotal += reference.ranking().ndcgAtK();
                }
            }
            if (!outcome.goldenCase().mustContain().isEmpty()) {
                phraseCases++;
                phraseTotal += scores.answer().phraseCoverage();
            }
            ContextPrecisionScores citedPrecision = outcome.citedContextPrecision();
            if (citedPrecision != null) {
                citedPrecisionCases++;
                citedPrecisionTotal += citedPrecision.averagePrecision();
                citedPrecisionAtKTotal += citedPrecision.precisionAtK();
            }

            citationsEmitted += scores.citations().emitted();
            citationsValid += scores.citations().valid();
            citationsFabricated += scores.citations().fabricated();
            inventedPages += scores.citations().fabricatedCitations().stream()
                                     .filter(citation -> !citation.hasMalformedPage())
                                     .count();

            // Not caseCount: a null verdict means not judged, which must not count as a failure.
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
                                   precisionCases > 0 ? precisionTotal / precisionCases : null,
                                   precisionCases > 0 ? precisionAtKTotal / precisionCases : null,
                                   judgedPrecisionCases > 0
                                           ? judgedPrecisionTotal / judgedPrecisionCases : null,
                                   judgedPrecisionCases > 0
                                           ? judgedPrecisionAtKTotal / judgedPrecisionCases : null,
                                   citedPrecisionCases > 0
                                           ? citedPrecisionTotal / citedPrecisionCases : null,
                                   citedPrecisionCases > 0
                                           ? citedPrecisionAtKTotal / citedPrecisionCases : null,
                                   referenceCases > 0 ? recallTotal / referenceCases : null,
                                   ndcgCases > 0 ? ndcgTotal / ndcgCases : null,
                                   citationsEmitted > 0 ? (double) citationsValid / citationsEmitted : 1.0,
                                   citationsEmitted > 0 ? (double) citationsFabricated / citationsEmitted : 0.0,
                                   citationsEmitted,
                                   (int) inventedPages,
                                   judged && relevancyJudged > 0
                                           ? (double) relevancyPassed / relevancyJudged : null,
                                   judged && groundednessJudged > 0
                                           ? (double) groundednessPassed / groundednessJudged : null,
                                   phraseCases > 0 ? phraseTotal / phraseCases : null,
                                   durationMillis,
                                   List.copyOf(failures));
    }

    private EvalRun persist(EvalRun run) {
        return evalProperties.golden().persist() ? runRepository.save(run) : run;
    }

    /**
     * The case's turn into {@code eval_turn} - with its judged columns when the run judged - and then its
     * case row pointing at it. The turn row is what puts golden and live results in one table for the
     * dashboard.
     */
    private void persistCase(UUID runId, CaseOutcome outcome) {
        if (!evalProperties.golden().persist()) {
            return;
        }
        EvalTurn evalTurn = outcome.evalTurn();
        turns.insert(evalTurn);
        TurnVerdicts verdicts = outcome.verdicts();
        if (verdicts != null) {
            turns.saveVerdicts(evalTurn.turnId(), verdicts,
                               verdicts.answerOk(evalProperties.judge().faithfulnessThreshold(),
                                                 evalTurn.citationsFabricated()),
                               0, evalProperties.judgeModel());
        }
        caseResultRepository.save(EvalCaseResult.from(runId,
                                                      outcome.goldenCase().id(),
                                                      outcome.goldenCase().query(),
                                                      outcome.answer(),
                                                      outcome.scores(),
                                                      outcome.firstRelevantRank(),
                                                      outcome.contextPrecision(),
                                                      outcome.judgedContextPrecision(),
                                                      outcome.citedContextPrecision(),
                                                      outcome.reference(),
                                                      evalTurn.turnId(),
                                                      outcome.latencyMillis()));
    }

    private static String format(double value) {
        return "%.3f".formatted(value);
    }

    /** "n/a" rather than 0.000 for a metric the run did not measure - the log should not imply a score. */
    private static String format(@Nullable Double value) {
        return value != null ? format(value.doubleValue()) : "n/a";
    }

    /**
     * One case's outcome while a run is in progress.
     *
     * <p>A mutable class, not a record, because the judgements are added in a second pass.
     */
    public static final class CaseOutcome {

        private final GoldenCase goldenCase;
        private final ChatTurnCompleted turn;
        private final EvalTurn evalTurn;
        private final String answer;
        private final List<Document> retrieved;
        private final int firstRelevantRank;
        private final @Nullable ContextPrecisionScores contextPrecision;
        private final @Nullable ContextPrecisionScores citedContextPrecision;
        private final @Nullable ReferenceScores reference;
        private final long latencyMillis;
        private EvalScores scores;
        private @Nullable ContextPrecisionScores judgedContextPrecision;
        private @Nullable TurnVerdicts verdicts;

        CaseOutcome(GoldenCase goldenCase, ChatTurnCompleted turn, EvalTurn evalTurn, String answer,
                    List<Document> retrieved, EvalScores scores, int firstRelevantRank,
                    @Nullable ContextPrecisionScores contextPrecision,
                    @Nullable ContextPrecisionScores citedContextPrecision,
                    @Nullable ReferenceScores reference, long latencyMillis) {
            this.goldenCase = goldenCase;
            this.turn = turn;
            this.evalTurn = evalTurn;
            this.answer = answer;
            this.retrieved = retrieved;
            this.scores = scores;
            this.firstRelevantRank = firstRelevantRank;
            this.contextPrecision = contextPrecision;
            this.citedContextPrecision = citedContextPrecision;
            this.reference = reference;
            this.latencyMillis = latencyMillis;
        }

        /** The judges' verdicts; relevancy and groundedness also feed the case's pass/fail, as before. */
        void applyVerdicts(TurnVerdicts verdicts) {
            this.verdicts = verdicts;
            this.scores = scores.withJudgements(verdicts.relevancyPass(), verdicts.groundednessPass());
        }

        /** The case's turn as it is stored in {@code eval_turn}. */
        public EvalTurn evalTurn() {
            return evalTurn;
        }

        public @Nullable TurnVerdicts verdicts() {
            return verdicts;
        }

        /** Expected-page rank metrics and page recall; null when the case declares no expected pages. */
        public @Nullable ReferenceScores reference() {
            return reference;
        }

        void applyJudgedPrecision(@Nullable ContextPrecisionScores judged) {
            this.judgedContextPrecision = judged;
        }

        public GoldenCase goldenCase() {
            return goldenCase;
        }

        /** The turn as ragr-app published it: what was answered, from what, and by which settings. */
        public ChatTurnCompleted turn() {
            return turn;
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

        public @Nullable ContextPrecisionScores contextPrecision() {
            return contextPrecision;
        }

        public @Nullable ContextPrecisionScores judgedContextPrecision() {
            return judgedContextPrecision;
        }

        public @Nullable ContextPrecisionScores citedContextPrecision() {
            return citedContextPrecision;
        }

        public long latencyMillis() {
            return latencyMillis;
        }
    }

    /**
     * The aggregate of one run.
     *
     * <p>{@code relevancyRate} and {@code groundednessRate} are null, not zero, when the run did not
     * judge: "not measured" must not look like "scored zero".
     *
     * @param contextPrecision    rank-weighted context precision over the cases that list expected pages,
     *                            with {@code precisionAtK} (relevant / k) beside it; null when no case
     *                            lists pages. A <em>floor</em>: the lists hold the pages with the answer,
     *                            not every useful page. Compare runs, not levels.
     * @param judgedContextPrecision the same, with the judge deciding each chunk - free of that bias but
     *                            not of self-judging; null unless the run judged. Where the two disagree,
     *                            the dataset is more often the one that is wrong.
     * @param citedContextPrecision the same, with a chunk counted as used when the answer cites its page -
     *                            free, exact and on every run, so a steady check on the judge. It
     *                            under-counts: a passage used but not cited scores as unused.
     * @param citationValidity    1.0 when nothing was cited at all, which is why {@code citationsEmitted}
     *                            sits beside it.
     * @param inventedPageCount   citations naming a plain page number the context never offered - the
     *                            failure the citation header and the strippers exist to prevent. Kept
     *                            apart from {@code citationFabrication}, which moves from run to run with
     *                            the model's section-number habit; this one is stable, so the build fails
     *                            on it (see {@code EvalSuiteIT}).
     */
    public record GoldenRunResult(String suite,
                                  int caseCount,
                                  int passedCount,
                                  double hitRate,
                                  double meanReciprocalRank,
                                  @Nullable Double contextPrecision,
                                  @Nullable Double precisionAtK,
                                  @Nullable Double judgedContextPrecision,
                                  @Nullable Double judgedPrecisionAtK,
                                  @Nullable Double citedContextPrecision,
                                  @Nullable Double citedPrecisionAtK,
                                  @Nullable Double recallAtK,
                                  @Nullable Double ndcgAtK,
                                  double citationValidity,
                                  double citationFabrication,
                                  int citationsEmitted,
                                  int inventedPageCount,
                                  @Nullable Double relevancyRate,
                                  @Nullable Double groundednessRate,
                                  @Nullable Double phraseCoverage,
                                  long durationMillis,
                                  List<String> failures) {

        public double passRate() {
            return caseCount == 0 ? 0.0 : (double) passedCount / caseCount;
        }
    }
}
