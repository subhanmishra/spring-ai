package com.example.subhanmishra.service;

import com.example.subhanmishra.event.ChatFeedbackSubmitted;
import com.example.subhanmishra.repository.EvalTurnRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Stores users' ratings of answers as they arrive from ragr-app.
 *
 * <p>Matched to turns only when queried: a rating can arrive before its turn (they travel on different
 * topics) or after it was purged, and is still a rating. The listener overrides the consumer's default
 * value type, which is the turn event.
 */
@Service
public class FeedbackService {

    private final EvalTurnRepository turns;
    private final EvalMetricsService metricsService;

    public FeedbackService(EvalTurnRepository turns, EvalMetricsService metricsService) {
        this.turns = turns;
        this.metricsService = metricsService;
    }

    @KafkaListener(topics = "${app.eval.feedback-topic}",
                   properties = "spring.json.value.default.type=com.example.subhanmishra.event.ChatFeedbackSubmitted")
    void onFeedback(ChatFeedbackSubmitted feedback) {
        record(feedback);
    }

    public void record(ChatFeedbackSubmitted feedback) {
        turns.insertFeedback(feedback.turnId(), feedback.rating().name(), feedback.reason(), feedback.submittedAt());
        metricsService.recordFeedback(feedback.rating());
    }
}
