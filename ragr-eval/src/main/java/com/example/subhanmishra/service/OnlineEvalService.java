package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.entity.EvalTurn;
import com.example.subhanmishra.entity.EvalTurn.JudgeStatus;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.service.GoldenEvalService.AwaitingCase;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scores every chat turn as it arrives from Kafka - live and golden alike - stores it, and queues it for
 * the judges.
 *
 * <p>The answer was returned before the event was even sent, so nothing here can delay one. What this
 * application still shares with chat is the model, so the listener never calls it: it stores the turn
 * as PENDING, and {@link TurnJudgeWorker} judges it later, while chat is idle.
 *
 * <p>On every turn:
 * <ul>
 *   <li><b>Rule-based scores</b> - regex over the answer and a comparison with its chunks, no model. They
 *       reach Prometheus at once, so they cover every turn within seconds however far the judges lag.</li>
 *   <li><b>One row</b> per turn plus one per pool chunk - the queue entry, and what a reviewer reads.</li>
 *   <li><b>Asked again?</b> If the conversation's previous turn, moments ago, landed on mostly the same
 *       chunks, that answer is marked {@code rephrased} - the implicit thumbs-down.</li>
 * </ul>
 *
 * <p>A golden turn takes the same path, so live and golden figures mean the same. On top, the turn is
 * linked to its run and case and scored against the case's expectations ({@link GoldenEvalService});
 * it is never drawn for human review, since the case already has a checked reference, and never
 * compared for a rephrase, since each case has its own conversation.
 */
@Service
public class OnlineEvalService {

    private static final Logger log = LoggerFactory.getLogger(OnlineEvalService.class);

    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final EvalTurnRepository turns;
    private final GoldenEvalService golden;
    private final EvalProperties properties;

    public OnlineEvalService(EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             EvalTurnRepository turns,
                             GoldenEvalService golden,
                             EvalProperties properties) {
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.turns = turns;
        this.golden = golden;
        this.properties = properties;
    }

    /** One transaction, so a golden turn is never stored without its case being scored, or the reverse. */
    @KafkaListener(topics = "${app.eval.topic}")
    @Transactional
    public void onTurn(ChatTurnCompleted turn) {
        record(turn);
    }

    /**
     * Scores, stores and queues one turn. A failure to store is rethrown, so the listener container
     * retries the record rather than committing past a turn that was never saved.
     */
    public void record(ChatTurnCompleted turn) {
        boolean live = turn.origin() == TurnOrigin.LIVE;
        if (!properties.enabled() || (live && !properties.online().enabled())) {
            return;
        }
        // A golden turn from a run is scored against its case; one sent by hand has no case to check.
        Optional<AwaitingCase> awaiting = live ? Optional.empty() : golden.awaitingCase(turn.conversationId());
        if (!live && awaiting.isEmpty()) {
            log.info("Golden turn {} belongs to no waiting run; stored without a case", turn.turnId());
        }

        List<Document> retrieved = turn.retrievedDocuments();
        EvalScores scores = scoringService.score(turn.answer(), retrieved,
                                                 awaiting.map(AwaitingCase::goldenCase).orElse(null));
        metricsService.recordOnline(turn.origin(), scores);
        ContextPrecisionScores cited = scoringService.citedPrecision(turn.answer(), retrieved);
        if (cited != null) {
            metricsService.recordOnlineCitedPrecision(turn.origin(), cited);
        }

        // Golden turns are always judged: a run's judged figures would otherwise cover a random part of it.
        JudgeStatus status = !live || sampled(properties.online().judgeSampleRate())
                ? JudgeStatus.PENDING : JudgeStatus.NOT_QUEUED;
        boolean stored = turns.insert(EvalTurn.from(turn, scores, cited,
                                                    awaiting.map(AwaitingCase::runId).orElse(null),
                                                    awaiting.map(AwaitingCase::caseId).orElse(null),
                                                    status,
                                                    live && sampled(properties.online().reviewSampleRate())));
        if (!stored) {
            return;
        }
        if (live) {
            detectRephrase(turn);
        } else {
            awaiting.ifPresent(found -> golden.scoreCase(found, turn, scores, cited));
        }
    }

    private void detectRephrase(ChatTurnCompleted turn) {
        Set<String> current = chunkIds(turn);
        if (current.isEmpty()) {
            return;
        }
        turns.previousTurn(turn.conversationId(), turn.occurredAt(), properties.online().rephraseWindow())
             .ifPresent(previous -> {
                 if (jaccard(current, new HashSet<>(previous.chunkIds())) >= properties.online().rephraseOverlap()) {
                     turns.markRephrased(previous.turnId());
                     metricsService.recordRephrase();
                 }
             });
    }

    private static Set<String> chunkIds(ChatTurnCompleted turn) {
        Set<String> ids = new HashSet<>();
        for (ChatTurnCompleted.RetrievedChunk chunk : turn.pool()) {
            ids.add(chunk.id());
        }
        return ids;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    private static boolean sampled(double rate) {
        return rate > 0.0 && (rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate);
    }
}
