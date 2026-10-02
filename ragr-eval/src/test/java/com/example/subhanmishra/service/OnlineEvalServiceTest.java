package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.repository.EvalRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.mockito.stubbing.Answer;
import org.springframework.ai.evaluation.EvaluationResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bounds on online judging that only show up when a judge stops answering: the per-verdict timeout
 * and the shutdown wait. The judges are stubbed - one that never answers stands in for a wedged Ollama,
 * which is the case these bounds exist for and the one a live run almost never produces.
 */
class OnlineEvalServiceTest {

    private static final List<Document> RETRIEVED =
            List.of(Document.builder().text("[manual.pdf, p. 1]\n\nGraceful shutdown waits for requests.").build());
    private static final EvaluationResponse PASS = new EvaluationResponse(true, "", Map.of());

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RelevancyEvaluator relevancy = mock(RelevancyEvaluator.class);
    private final FactCheckingEvaluator groundedness = mock(FactCheckingEvaluator.class);

    /** Set once a stubbed judge call has started, and once it has been interrupted. */
    private final CountDownLatch judgeStarted = new CountDownLatch(1);
    private final CountDownLatch judgeInterrupted = new CountDownLatch(1);

    private OnlineEvalService service;

    @AfterEach
    void shutDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private OnlineEvalService service(int judgeTimeoutSeconds, int judgeShutdownWaitSeconds) {
        EvalProperties.Online online = new EvalProperties.Online(true, 1.0, 1, judgeTimeoutSeconds,
                                                                 judgeShutdownWaitSeconds);
        EvalProperties properties = new EvalProperties(true, "rag.chat.turn.completed", "judge", 0.0, 8, 8192,
                                                       online, null);
        EvalMetricsService metrics = new EvalMetricsService(registry, mock(EvalRunRepository.class));
        service = new OnlineEvalService(new EvalScoringService(), metrics, relevancy, groundedness, properties);
        return service;
    }

    /** A judge call that never returns on its own - only an interrupt ends it. */
    private Answer<EvaluationResponse> wedged() {
        return invocation -> {
            judgeStarted.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                judgeInterrupted.countDown();
                throw new IllegalStateException("judge call interrupted", e);
            }
            throw new AssertionError("a wedged judge must not return");
        };
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("both verdicts are recorded when the judges answer")
    void bothVerdictsRecorded() throws InterruptedException {
        when(relevancy.evaluate(any())).thenReturn(PASS);
        when(groundedness.evaluate(any())).thenReturn(PASS);

        service(5, 5).evaluate("How does graceful shutdown work?", "It waits for requests.", RETRIEVED);

        awaitTrue(() -> counter("rag.eval.online.judgements.total", "metric", "groundedness", "outcome", "pass") == 1.0);
        assertThat(counter("rag.eval.online.judgements.total", "metric", "relevancy", "outcome", "pass")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a wedged verdict is interrupted at the timeout, the rest skipped, and the permit released")
    void wedgedVerdictTimesOut() throws InterruptedException {
        when(relevancy.evaluate(any())).thenAnswer(wedged()).thenReturn(PASS);
        when(groundedness.evaluate(any())).thenReturn(PASS);
        OnlineEvalService service = service(1, 5);

        service.evaluate("How does graceful shutdown work?", "It waits for requests.", RETRIEVED);

        assertThat(judgeInterrupted.await(10, TimeUnit.SECONDS)).as("the wedged call was interrupted").isTrue();
        awaitTrue(() -> counter("rag.eval.online.judgements.errors.total", "metric", "relevancy") == 1.0);
        verify(groundedness, never()).evaluate(any());

        // One permit: had the abandoned judgement kept it, every later turn would be dropped and none
        // judged. The permit is released just after the error is counted, so a turn arriving in between
        // may still be dropped - retry until one is admitted.
        awaitTrue(() -> {
            service.evaluate("How does graceful shutdown work?", "It waits for requests.", RETRIEVED);
            return counter("rag.eval.online.judgements.total", "metric", "groundedness", "outcome", "pass") > 0;
        });
    }

    @Test
    @DisplayName("shutdown waits only the shutdown wait, not the judge timeout, before interrupting")
    void shutdownInterruptsJudgementInFlight() throws InterruptedException {
        when(relevancy.evaluate(any())).thenAnswer(wedged());
        OnlineEvalService service = service(120, 1);

        service.evaluate("How does graceful shutdown work?", "It waits for requests.", RETRIEVED);
        assertThat(judgeStarted.await(10, TimeUnit.SECONDS)).as("the judge call started").isTrue();

        long startedAt = System.nanoTime();
        service.shutdown();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);

        assertThat(seconds).as("shutdown took the 1s wait, not the 120s timeout").isLessThan(5);
        assertThat(judgeInterrupted.await(5, TimeUnit.SECONDS)).as("the judge call was interrupted").isTrue();
        verify(groundedness, never()).evaluate(any());
    }
}
