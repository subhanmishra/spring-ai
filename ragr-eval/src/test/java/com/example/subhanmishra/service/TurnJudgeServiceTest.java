package com.example.subhanmishra.service;

import com.example.subhanmishra.entity.EvalTurnChunk;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeInput;
import com.example.subhanmishra.service.eval.ChunkGradeEvaluator;
import com.example.subhanmishra.service.eval.CitationSupportEvaluator;
import com.example.subhanmishra.service.eval.ClaimFaithfulnessEvaluator;
import com.example.subhanmishra.service.eval.CompletenessEvaluator;
import com.example.subhanmishra.service.eval.TaskClassifier;
import com.example.subhanmishra.service.eval.TaskType;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stages over one turn, with every judge stubbed: which stages an ungrounded turn gets, how the
 * verdicts combine, and what a judge that never answers does to the rest of the turn.
 */
class TurnJudgeServiceTest {

    private static final EvaluationResponse PASS = new EvaluationResponse(true, "", Map.of());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TaskClassifier taskClassifier = mock(TaskClassifier.class);
    private final ChunkGradeEvaluator chunkGrades = mock(ChunkGradeEvaluator.class);
    private final RelevancyEvaluator relevancy = mock(RelevancyEvaluator.class);
    private final FactCheckingEvaluator groundedness = mock(FactCheckingEvaluator.class);
    private final ClaimFaithfulnessEvaluator claims = mock(ClaimFaithfulnessEvaluator.class);
    private final CitationSupportEvaluator citations = mock(CitationSupportEvaluator.class);
    private final CompletenessEvaluator completeness = mock(CompletenessEvaluator.class);
    private final CountDownLatch judgeInterrupted = new CountDownLatch(1);
    private TurnJudgeService service;

    @AfterEach
    void shutDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private TurnJudgeService service(int callTimeoutSeconds) {
        var properties = EvalPropertiesFixture.properties(1.0, callTimeoutSeconds, 1, false);
        ChatIdleGate gate = new ChatIdleGate(properties, RestClient.builder(), registry);
        EvalMetricsService metrics = new EvalMetricsService(registry, mock(EvalRunRepository.class), null);
        service = new TurnJudgeService(gate, taskClassifier, chunkGrades, relevancy, groundedness, claims,
                                       citations, completeness, metrics, properties);
        return service;
    }

    private static JudgeInput input(int retrievedCount, String... pages) {
        List<EvalTurnChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            chunks.add(new EvalTurnChunk(i + 1, "c" + i, null, "manual.pdf", Integer.valueOf(pages[i]), null, null,
                                         0.8 - i * 0.1, i < retrievedCount, false, null,
                                         "[manual.pdf, p. " + pages[i] + "]\n\nText of page " + pages[i] + "."));
        }
        return new JudgeInput(UUID.randomUUID(), "How does graceful shutdown work?",
                              retrievedCount > 0 ? "It waits for requests (manual.pdf, p. 1)." : "Hi there.",
                              retrievedCount, 0, chunks);
    }

    private void everythingPasses() {
        when(taskClassifier.classify(anyString())).thenReturn(TaskType.HOW_TO);
        when(chunkGrades.grade(anyString(), any())).thenReturn(2, 0, 0);
        when(relevancy.evaluate(any())).thenReturn(PASS);
        when(groundedness.evaluate(any())).thenReturn(PASS);
        when(claims.extractClaims(anyString())).thenReturn(List.of("It waits for requests."));
        when(claims.verify(anyList(), anyList())).thenReturn(List.of(true));
        when(citations.checks(anyString(), anyList())).thenReturn(List.of());
        when(completeness.judge(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("an ungrounded turn gets its task classified and nothing else")
    void ungroundedOnlyClassified() {
        when(taskClassifier.classify(anyString())).thenReturn(TaskType.GENERAL);

        TurnVerdicts verdicts = service(5).judge(input(0, "9"));

        assertThat(verdicts.taskType()).isEqualTo(TaskType.GENERAL);
        verify(chunkGrades, never()).grade(anyString(), any());
        verify(relevancy, never()).evaluate(any());
        assertThat(verdicts.ranking()).isNull();
        assertThat(verdicts.anyStageUnmeasured()).isFalse();
    }

    @Test
    @DisplayName("a grounded turn is graded over its whole pool and every answer judge runs")
    void groundedFullyJudged() {
        everythingPasses();

        TurnVerdicts verdicts = service(5).judge(input(2, "1", "2", "3"));

        assertThat(verdicts.grades()).containsExactly(2, 0, 0);
        assertThat(verdicts.ranking().precisionAtK()).isEqualTo(0.5);
        assertThat(verdicts.ranking().recallAtK()).isEqualTo(1.0);
        assertThat(verdicts.faithfulness()).isEqualTo(1.0);
        assertThat(verdicts.answerOk(0.8, 0)).isTrue();
        assertThat(verdicts.retrievalOk()).isTrue();
        assertThat(verdicts.anyStageUnmeasured()).isFalse();
    }

    @Test
    @DisplayName("a wedged judge is abandoned at the timeout, its stage left unmeasured, and the later stages still run")
    void wedgedStageTimesOut() throws InterruptedException {
        everythingPasses();
        when(relevancy.evaluate(any())).thenAnswer(wedged());

        TurnVerdicts verdicts = service(1).judge(input(1, "1"));

        assertThat(judgeInterrupted.await(5, TimeUnit.SECONDS)).as("the wedged call was interrupted").isTrue();
        assertThat(verdicts.relevancyPass()).isNull();
        assertThat(verdicts.groundednessPass()).isTrue();
        assertThat(verdicts.completenessPass()).isTrue();
        assertThat(verdicts.anyStageUnmeasured()).isTrue();
        assertThat(verdicts.answerOk(0.8, 0)).as("unmeasured, not failed").isNull();
        assertThat(registry.get("rag.eval.online.judgements.errors.total").tag("metric", "relevancy").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unreadable verdict is counted as an error and recorded as unmeasured")
    void unreadableVerdict() {
        everythingPasses();
        when(completeness.judge(anyString(), anyString())).thenReturn(null);

        TurnVerdicts verdicts = service(5).judge(input(1, "1"));

        assertThat(verdicts.completenessPass()).isNull();
        assertThat(verdicts.anyStageUnmeasured()).isTrue();
    }

    private Answer<EvaluationResponse> wedged() {
        return invocation -> {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                judgeInterrupted.countDown();
                throw new IllegalStateException("judge call interrupted", e);
            }
            throw new AssertionError("a wedged judge must not return");
        };
    }
}
