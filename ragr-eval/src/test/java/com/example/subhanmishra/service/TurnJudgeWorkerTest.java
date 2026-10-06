package com.example.subhanmishra.service;

import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeInput;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The queue loop: claim, judge, save - and what happens when judging breaks or the worker is stopped. */
class TurnJudgeWorkerTest {

    private static final UUID TURN = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final JudgeInput INPUT = new JudgeInput(TURN, "q", "a", 1, 0, List.of());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EvalTurnRepository turns = mock(EvalTurnRepository.class);
    private final TurnJudgeService turnJudge = mock(TurnJudgeService.class);
    private TurnJudgeWorker worker;

    @BeforeEach
    void oneTurnQueued() {
        when(turns.claimNext()).thenReturn(Optional.of(TURN), Optional.empty());
        when(turns.judgeInput(TURN)).thenReturn(Optional.of(INPUT));
    }

    @AfterEach
    void stop() {
        if (worker != null) {
            worker.stop();
        }
    }

    private TurnJudgeWorker start(int shutdownWaitSeconds) {
        EvalMetricsService metrics = new EvalMetricsService(registry, mock(EvalRunRepository.class), null);
        worker = new TurnJudgeWorker(turns, turnJudge, metrics,
                                     EvalPropertiesFixture.properties(1.0, 5, shutdownWaitSeconds, false));
        worker.start();
        return worker;
    }

    @Test
    @DisplayName("a claimed turn is judged and saved as one, after putting back anything a previous worker left")
    void judgesAndSaves() {
        when(turnJudge.judge(INPUT)).thenReturn(new TurnVerdicts());

        start(5);

        verify(turns).requeueRunning();
        verify(turns, timeout(5_000)).saveVerdicts(eq(TURN), any(), any(), anyLong(), eq("judge"));
    }

    @Test
    @DisplayName("a turn that breaks the judges is saved PARTIAL rather than retried forever")
    void failureSavedPartial() {
        when(turnJudge.judge(INPUT)).thenThrow(new IllegalStateException("boom"));

        start(5);

        ArgumentCaptor<TurnVerdicts> saved = ArgumentCaptor.forClass(TurnVerdicts.class);
        verify(turns, timeout(5_000)).saveVerdicts(eq(TURN), saved.capture(), any(), anyLong(), anyString());
        assertThat(saved.getValue().anyStageUnmeasured()).isTrue();
    }

    @Test
    @DisplayName("stopping mid-turn waits the shutdown wait, then interrupts and puts the turn back in the queue")
    void stopRequeues() throws InterruptedException {
        CountDownLatch judging = new CountDownLatch(1);
        when(turnJudge.judge(INPUT)).thenAnswer(invocation -> {
            judging.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TurnJudgeService.Interrupted();
            }
            throw new AssertionError("must be interrupted");
        });
        start(1);
        assertThat(judging.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        worker.stop();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);

        assertThat(seconds).as("the 1s shutdown wait, not the judge's 5 minutes").isLessThan(5);
        verify(turns).requeue(TURN);
    }

    @Test
    @DisplayName("turns older than the backlog limit are skipped and counted")
    void staleSkipped() {
        when(turns.claimNext()).thenReturn(Optional.empty());
        when(turns.skipStale(any())).thenReturn(3, 0);

        start(5);

        verify(turns, timeout(5_000).atLeastOnce()).skipStale(any());
        assertThat(registry.get("rag.eval.online.judge.skipped.total").counter().count()).isEqualTo(3.0);
    }
}
