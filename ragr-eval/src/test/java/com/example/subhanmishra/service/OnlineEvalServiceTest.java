package com.example.subhanmishra.service;

import com.example.subhanmishra.entity.EvalCaseResult;
import com.example.subhanmishra.entity.EvalTurn;
import com.example.subhanmishra.entity.EvalTurn.JudgeStatus;
import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.ChatTurnCompleted.RetrievedChunk;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.repository.EvalRunRepository;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.PreviousTurn;
import com.example.subhanmishra.service.GoldenEvalService.AwaitingCase;
import com.example.subhanmishra.service.eval.GoldenCase;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the listener does with a turn: score it deterministically, store it with its pool and the right
 * queue status, mark the previous turn when this one asks it again - and, for a golden turn, link it to
 * its run and case. Judging is not here any
 * more - {@code TurnJudgeWorkerTest} and {@code TurnJudgeServiceTest} cover it.
 */
class OnlineEvalServiceTest {

    private static final RetrievedChunk IN_CONTEXT = new RetrievedChunk(
            "chunk-1", "[manual.pdf, p. 1]\n\nGraceful shutdown waits for requests.",
            Map.of("fileName", "manual.pdf", "pageNumber", 1, "section", "5.1. Shutdown"), 0.81);
    private static final RetrievedChunk EXCLUDED = new RetrievedChunk(
            "chunk-2", "[manual.pdf, p. 9]\n\nPorts.", Map.of("fileName", "manual.pdf", "pageNumber", 9), 0.42);

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final EvalTurnRepository turns = mock(EvalTurnRepository.class);
    private final GoldenEvalService golden = mock(GoldenEvalService.class);

    @BeforeEach
    void storeEverything() {
        when(turns.insert(any())).thenReturn(true);
        when(turns.previousTurn(anyString(), any(), any())).thenReturn(Optional.empty());
        when(golden.awaitingCase(anyString())).thenReturn(Optional.empty());
    }

    private OnlineEvalService service(double judgeSampleRate) {
        return service(judgeSampleRate, 0.0);
    }

    private OnlineEvalService service(double judgeSampleRate, double reviewSampleRate) {
        EvalMetricsService metrics = new EvalMetricsService(registry, mock(EvalRunRepository.class), null);
        return new OnlineEvalService(new EvalScoringService(), metrics, turns, golden,
                                     EvalPropertiesFixture.properties(judgeSampleRate, reviewSampleRate, 120, 20,
                                                                      false));
    }

    private static ChatTurnCompleted turn(String answer, List<RetrievedChunk> retrieved, List<RetrievedChunk> excluded) {
        return turn(TurnOrigin.LIVE, answer, retrieved, excluded);
    }

    private static ChatTurnCompleted turn(TurnOrigin origin, String answer, List<RetrievedChunk> retrieved,
                                          List<RetrievedChunk> excluded) {
        return new ChatTurnCompleted(UUID.randomUUID(), origin, Instant.now(), "conv-1",
                                     "How does graceful shutdown work?", answer, retrieved,
                                     "gemma4:e2b", 5, 0.6, ChatTurnCompleted.SCHEMA_VERSION, excluded, 10,
                                     new ChatTurnCompleted.Timings(2L, null, 20_000L, false),
                                     new ChatTurnCompleted.GenerationUsage(900, 60, "stop"), "abc123");
    }

    private EvalTurn stored() {
        ArgumentCaptor<EvalTurn> captor = ArgumentCaptor.forClass(EvalTurn.class);
        verify(turns).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a grounded turn is stored with its whole pool, in rank order, and queued for the judges")
    void storesPoolAndQueues() {
        service(1.0).record(turn("It waits (manual.pdf, p. 1).", List.of(IN_CONTEXT), List.of(EXCLUDED)));

        EvalTurn turn = stored();
        assertThat(turn.judgeStatus()).isEqualTo(JudgeStatus.PENDING);
        assertThat(turn.retrievedCount()).isEqualTo(1);
        assertThat(turn.chunks()).extracting("rank", "inContext", "cited", "page", "section")
                                 .containsExactly(tuple(1, true, true, 1, "5.1. Shutdown"),
                                                  tuple(2, false, false, 9, null));
        assertThat(turn.totalMillis()).isEqualTo(20_000L);
        assertThat(turn.finishReason()).isEqualTo("stop");
        assertThat(turn.promptVersion()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("a turn the sample rate leaves out is still stored and scored, but not queued")
    void unsampledNotQueued() {
        service(0.0).record(turn("It waits.", List.of(IN_CONTEXT), List.of()));

        assertThat(stored().judgeStatus()).isEqualTo(JudgeStatus.NOT_QUEUED);
        assertThat(registry.get("rag.eval.online.turns.total").tag("origin", "live").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("cited precision is recorded on a grounded turn, as 0 when the answer cites nothing, and not without retrieval")
    void citedPrecisionRecorded() {
        OnlineEvalService service = service(1.0);

        service.record(turn("It waits for requests (manual.pdf, p. 1).", List.of(IN_CONTEXT), List.of()));
        service.record(turn("It waits for requests.", List.of(IN_CONTEXT), List.of()));
        service.record(turn("Hi there.", List.of(), List.of()));

        DistributionSummary precision = registry.find("rag.eval.online.cited.context.precision").tag("origin", "live").summary();
        assertThat(precision.count()).as("the ungrounded turn is not scored").isEqualTo(2);
        assertThat(precision.totalAmount()).as("1.0 for the cited turn, 0.0 for the uncited one").isEqualTo(1.0);
    }

    @Test
    @DisplayName("a follow-up landing on the same chunks marks the previous turn rephrased")
    void rephraseMarksPrevious() {
        UUID previous = UUID.randomUUID();
        when(turns.previousTurn(anyString(), any(), any()))
                .thenReturn(Optional.of(new PreviousTurn(previous, List.of("chunk-1", "chunk-2"))));

        service(1.0).record(turn("It waits.", List.of(IN_CONTEXT), List.of(EXCLUDED)));

        verify(turns).markRephrased(previous);
        assertThat(registry.get("rag.eval.online.rephrases.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a follow-up on different chunks is a new question, not a rephrase")
    void differentChunksNotRephrase() {
        when(turns.previousTurn(anyString(), any(), any()))
                .thenReturn(Optional.of(new PreviousTurn(UUID.randomUUID(), List.of("chunk-7", "chunk-8"))));

        service(1.0).record(turn("It waits.", List.of(IN_CONTEXT), List.of(EXCLUDED)));

        verify(turns, never()).markRephrased(any());
    }

    @Test
    @DisplayName("a redelivered turn, already stored, is not compared again")
    void redeliveryIgnored() {
        when(turns.insert(any())).thenReturn(false);

        service(1.0).record(turn("It waits.", List.of(IN_CONTEXT), List.of()));

        verify(turns, never()).previousTurn(anyString(), any(), any());
    }

    @Test
    @DisplayName("a golden turn from a run is linked to its case, always queued, never reviewed, and scored for the case")
    void goldenTurnFromRun() {
        UUID runId = UUID.randomUUID();
        GoldenCase goldenCase = new GoldenCase("shutdown", "How does graceful shutdown work?", "", null, null, null,
                                               null, null, null, null, null, null, null, null, null, null);
        AwaitingCase awaiting = new AwaitingCase(EvalCaseResult.awaiting(runId, goldenCase, "conv-1"), goldenCase);
        when(golden.awaitingCase("conv-1")).thenReturn(Optional.of(awaiting));

        // Sample rates of 0 and 1 would leave a live turn unqueued and drawn for review.
        service(0.0, 1.0).record(turn(TurnOrigin.GOLDEN, "It waits (manual.pdf, p. 1).", List.of(IN_CONTEXT),
                                      List.of()));

        EvalTurn stored = stored();
        assertThat(stored.runId()).isEqualTo(runId);
        assertThat(stored.caseId()).isEqualTo("shutdown");
        assertThat(stored.judgeStatus()).isEqualTo(JudgeStatus.PENDING);
        assertThat(stored.reviewSample()).isFalse();
        verify(golden).scoreCase(eq(awaiting), any(), any(), any());
        verify(turns, never()).previousTurn(anyString(), any(), any());
        assertThat(registry.get("rag.eval.online.turns.total").tag("origin", "golden").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.eval.online.turns.total").tag("origin", "live").counter().count()).isZero();
    }

    @Test
    @DisplayName("a golden turn no run is waiting for is stored without a case")
    void goldenTurnWithoutRun() {
        service(1.0).record(turn(TurnOrigin.GOLDEN, "It waits.", List.of(IN_CONTEXT), List.of()));

        assertThat(stored().runId()).isNull();
        verify(golden, never()).scoreCase(any(), any(), any(), any());
    }

    @Test
    @DisplayName("overlap is intersection over union, and two empty pools are not a match")
    void jaccard() {
        assertThat(OnlineEvalService.jaccard(Set.of("a", "b", "c"), Set.of("b", "c", "d"))).isEqualTo(0.5);
        assertThat(OnlineEvalService.jaccard(Set.of(), Set.of())).isZero();
    }
}
