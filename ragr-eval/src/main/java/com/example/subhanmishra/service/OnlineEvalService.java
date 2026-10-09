package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.entity.EvalTurn;
import com.example.subhanmishra.entity.EvalTurn.JudgeStatus;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scores real chat traffic as it arrives from Kafka, stores it, and queues it for the judges.
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
 * <p>Golden-suite turns are skipped: the suite reads its own turns back and stores them with its run.
 */
@Service
public class OnlineEvalService {

    private static final Logger log = LoggerFactory.getLogger(OnlineEvalService.class);

    private final EvalScoringService scoringService;
    private final EvalMetricsService metricsService;
    private final EvalTurnRepository turns;
    private final EvalProperties properties;

    public OnlineEvalService(EvalScoringService scoringService,
                             EvalMetricsService metricsService,
                             EvalTurnRepository turns,
                             EvalProperties properties) {
        this.scoringService = scoringService;
        this.metricsService = metricsService;
        this.turns = turns;
        this.properties = properties;
    }

    @KafkaListener(topics = "${app.eval.topic}")
    void onTurn(ChatTurnCompleted turn) {
        if (turn.origin() == TurnOrigin.GOLDEN) {
            return;
        }
        record(turn);
    }

    /**
     * Scores, stores and queues one live turn. A failure to store is rethrown, so the listener container
     * retries the record rather than committing past a turn that was never saved.
     */
    public void record(ChatTurnCompleted turn) {
        if (!properties.enabled() || !properties.online().enabled()) {
            return;
        }
        metricsService.recordCitationResolution(turn.citationsRepaired(), turn.citationsAbstained());
        if (!turn.unresolved().isEmpty()) {
            log.debug("Left {} section-number citation(s) unresolved - they match no retrieved chunk: {}",
                      turn.citationsAbstained(), turn.unresolved());
        }

        List<Document> retrieved = turn.retrievedDocuments();
        EvalScores scores = scoringService.score(turn.answer(), retrieved);
        metricsService.recordOnline(scores);
        ContextPrecisionScores cited = scoringService.citedPrecision(turn.answer(), retrieved);
        if (cited != null) {
            metricsService.recordOnlineCitedPrecision(cited);
        }

        boolean stored = turns.insert(EvalTurn.from(turn, scores, cited, null, null,
                                                    sampled(properties.online().judgeSampleRate())
                                                            ? JudgeStatus.PENDING : JudgeStatus.NOT_QUEUED,
                                                    sampled(properties.online().reviewSampleRate())));
        if (stored) {
            detectRephrase(turn);
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
