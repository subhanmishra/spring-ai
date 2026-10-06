package com.example.subhanmishra.service;

import com.example.subhanmishra.event.ChatFeedbackSubmitted;
import com.example.subhanmishra.repository.EvalTurnRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Stores users' ratings of answers as they arrive from ragr-app.
 *
 * <p>Joined to their turns only at query time: a rating can arrive before its turn has been stored (the
 * two travel on different topics) or after the turn has been purged, and in both cases it is still a
 * rating. The listener overrides the consumer's default value type, which is the chat-turn event.
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
