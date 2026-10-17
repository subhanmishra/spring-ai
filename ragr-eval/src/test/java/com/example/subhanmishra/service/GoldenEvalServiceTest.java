package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.exception.GoldenRunInProgressException;
import com.example.subhanmishra.repository.EvalCaseResultRepository;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.GoldenDataset;
import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
import com.example.subhanmishra.service.eval.GoldenRunResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A run's own half: aggregating scored cases, one run at a time, and failing cases whose turn never came.
 * The listener's half - scoring a case from its turn - is in {@code OnlineEvalServiceTest}.
 */
class GoldenEvalServiceTest {

    private static final UUID RUN = UUID.randomUUID();

    private static GoldenCase goldenCase(String id, List<String> expectedFiles, List<String> mustContain) {
        return new GoldenCase(id, "q " + id, "", null, null, mustContain, null, null, null, null, null, null, null,
                              expectedFiles, null, null);
    }

    private static final GoldenCase RECALL = goldenCase("recall", List.of("a.md"), List.of("port"));
    private static final GoldenCase NO_RECALL = goldenCase("no-recall", List.of(), List.of());
    private static final GoldenCase BROKEN = goldenCase("broken", List.of("b.md"), List.of());
    private static final GoldenDataset DATASET = new GoldenDataset("suite", "corpus", "", List.of(RECALL, NO_RECALL, BROKEN));

    /** A scored row with the fields aggregation reads; the rest at their defaults. */
    private static EvalCaseResult scored(GoldenCase goldenCase, boolean passed, int rank, Double precision,
                                         Double recall, int emitted, int fabricated, int invented, Double phrases) {
        return new EvalCaseResult(UUID.randomUUID(), RUN, goldenCase.id(), goldenCase.query(), "answer", "conv",
                                  EvalCaseResult.Status.SCORED, 5, 0.8, 0.1, null, rank, precision, precision,
                                  0.5, 0.4, "1,0", recall, recall, null, UUID.randomUUID(), emitted,
                                  emitted - fabricated, fabricated, invented, phrases, false, passed,
                                  passed ? null : "a reason", 1_000L, Instant.now());
    }

    private static EvalCaseResult failed(GoldenCase goldenCase) {
        return new EvalCaseResult(UUID.randomUUID(), RUN, goldenCase.id(), goldenCase.query(), null, "conv",
                                  EvalCaseResult.Status.FAILED, 0, null, null, null, 0, null, null, null, null,
                                  null, null, null, null, null, 0, 0, 0, 0, null, false, false,
                                  "no chat turn arrived", null, Instant.now());
    }

    @Test
    @DisplayName("each metric averages over the cases it applies to, and a failed case is a recall miss")
    void aggregatesOverApplicableCases() {
        GoldenRunResult result = GoldenEvalService.aggregate(DATASET, List.of(
                scored(RECALL, true, 2, 0.8, 1.0, 4, 1, 1, 0.5),
                scored(NO_RECALL, false, 0, null, null, 2, 0, 0, 1.0),
                failed(BROKEN)), 9_000);

        assertThat(result.caseCount()).isEqualTo(3);
        assertThat(result.passedCount()).isEqualTo(1);
        assertThat(result.hitRate()).as("one hit over the two recall cases").isEqualTo(0.5);
        assertThat(result.meanReciprocalRank()).isEqualTo(0.25);
        assertThat(result.contextPrecision()).as("only the case that lists files").isEqualTo(0.8);
        assertThat(result.phraseCoverage()).as("only the case asserting phrases").isEqualTo(0.5);
        assertThat(result.citationsEmitted()).isEqualTo(6);
        assertThat(result.citationFabrication()).isEqualTo(1.0 / 6);
        assertThat(result.inventedPageCount()).isEqualTo(1);
        assertThat(result.failures()).containsExactly("no-recall: a reason", "broken: no chat turn arrived");
    }

    // ---------------------------------------------------------------- the run

    private final EvalRunRepository runs = mock(EvalRunRepository.class);
    private final EvalCaseResultRepository cases = mock(EvalCaseResultRepository.class);
    private final EvalTurnRepository turns = mock(EvalTurnRepository.class);

    /** Chat at the discard port, so every request fails fast and nothing real is asked. */
    private GoldenEvalService service(Duration turnTimeout) {
        GoldenDatasetLoader loader = mock(GoldenDatasetLoader.class);
        when(loader.load(anyString())).thenReturn(new GoldenDataset("suite", "corpus", "", List.of(RECALL)));
        EvalProperties base = EvalPropertiesFixture.properties(1.0);
        EvalProperties properties = new EvalProperties(true, base.topic(), base.feedbackTopic(), base.judgeModel(),
                                                       base.judgeTemperature(), base.judgeNumPredict(),
                                                       base.judgeNumCtx(), base.online(), base.judge(),
                                                       base.retention(),
                                                       new EvalProperties.Golden("classpath:x.yaml",
                                                                                 URI.create("http://localhost:9"),
                                                                                 turnTimeout));
        when(runs.save(any())).thenAnswer(invocation -> {
            EvalRun run = invocation.getArgument(0);
            return run.id() != null ? run : withId(run);
        });
        when(runs.findById(RUN)).thenReturn(Optional.empty());
        when(cases.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(turns.runPipeline(any())).thenReturn(Optional.empty());
        when(turns.judgeProgress(any())).thenReturn(new EvalTurnRepository.JudgeProgress(0, 0, 0));
        EvalMetricsService metrics = new EvalMetricsService(new SimpleMeterRegistry(), runs, null);
        return new GoldenEvalService(RestClient.builder(), new EvalScoringService(), metrics, loader, runs, cases,
                                     turns, properties);
    }

    private static EvalRun withId(EvalRun run) {
        return new EvalRun(RUN, run.suite(), run.status(), run.startedAt(), run.finishedAt(), run.caseCount(),
                           run.passedCount(), null, null, null, null, null, null, null, null, null, null, null,
                           null, null, 0, 0, null, null, null, null, null);
    }

    @Test
    @DisplayName("a second run is refused while one is in progress")
    void oneRunAtATime() throws InterruptedException {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The run blocks while it waits for its cases to be scored.
        when(cases.countByRunIdAndStatus(eq(RUN), eq(EvalCaseResult.Status.AWAITING))).thenAnswer(invocation -> {
            waiting.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 0;
        });
        GoldenEvalService service = service(Duration.ofSeconds(5));

        service.start();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(service::start).isInstanceOf(GoldenRunInProgressException.class);

        release.countDown();
        verify(runs, timeout(5_000).atLeast(2)).save(any());
    }

    @Test
    @DisplayName("cases still waiting for their turn after the timeout are failed, and the run still completes")
    void unscoredCasesFail() {
        when(cases.countByRunIdAndStatus(eq(RUN), eq(EvalCaseResult.Status.AWAITING))).thenReturn(1);

        service(Duration.ofMillis(200)).start();

        verify(cases, timeout(5_000)).failAwaiting(eq(RUN), org.mockito.ArgumentMatchers.contains("no chat turn"));
        verify(runs, timeout(5_000).atLeast(2)).save(any());
    }
}
