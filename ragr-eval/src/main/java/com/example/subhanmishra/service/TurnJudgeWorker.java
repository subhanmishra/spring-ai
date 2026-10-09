package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.repository.EvalTurnRepository;
import com.example.subhanmishra.repository.EvalTurnRepository.JudgeInput;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Drains the judge queue: one PENDING turn at a time, oldest first, on a single virtual thread.
 *
 * <p><b>The queue is the {@code eval_turn} table, not Kafka.</b> Judging takes a minute or more and waits
 * for chat to be idle; holding a Kafka record that long would fight the consumer's poll timeout and lose
 * its place on a restart. A row survives restarts, and its age is one query away.
 *
 * <p>Every sampled turn is judged, however busy chat is. {@code max-backlog-age} is the bound: a turn
 * still waiting after it is SKIPPED and counted, so the queue never describes traffic from hours ago.
 *
 * <p>One worker, so judge calls are strictly one at a time - Ollama would serialise them anyway, and a
 * second worker would only give chat a second call to wait behind.
 */
@Service
public class TurnJudgeWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TurnJudgeWorker.class);

    private final EvalTurnRepository turns;
    private final TurnJudgeService turnJudge;
    private final EvalMetricsService metricsService;
    private final EvalProperties properties;

    private volatile boolean running;
    private volatile Thread thread;

    public TurnJudgeWorker(EvalTurnRepository turns,
                           TurnJudgeService turnJudge,
                           EvalMetricsService metricsService,
                           EvalProperties properties) {
        this.turns = turns;
        this.turnJudge = turnJudge;
        this.metricsService = metricsService;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.enabled() || !properties.judge().workerEnabled()) {
            log.info("Judge worker disabled; stored turns stay PENDING until a worker runs");
            return;
        }
        int requeued = turns.requeueRunning();
        if (requeued > 0) {
            log.info("Requeued {} turn(s) a previous worker left mid-judgement", requeued);
        }
        running = true;
        thread = Thread.ofVirtual().name("eval-judge-worker").start(this::drain);
    }

    private void drain() {
        while (running) {
            try {
                int skipped = turns.skipStale(properties.judge().maxBacklogAge());
                if (skipped > 0) {
                    metricsService.recordJudgeSkipped(skipped);
                    log.warn("Skipped {} turn(s) older than {} without judging them", skipped,
                             properties.judge().maxBacklogAge());
                }
                Optional<UUID> next = turns.claimNext();
                if (next.isEmpty()) {
                    Thread.sleep(properties.judge().pollInterval());
                    continue;
                }
                judge(next.get());
            } catch (InterruptedException | TurnJudgeService.Interrupted e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A dead worker would silently stop all judging, so back off and carry on.
                log.warn("Judge worker iteration failed; retrying", e);
                try {
                    Thread.sleep(properties.judge().pollInterval());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void judge(UUID turnId) {
        Optional<JudgeInput> input = turns.judgeInput(turnId);
        if (input.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            TurnVerdicts verdicts = turnJudge.judge(input.get());
            Boolean answerOk = verdicts.answerOk(properties.judge().faithfulnessThreshold(),
                                                 input.get().citationsFabricated());
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            turns.saveVerdicts(turnId, verdicts, answerOk, millis, properties.judgeModel());
            metricsService.recordJudgedTurn(verdicts, answerOk, input.get().retrievedCount() > 0, millis);
            log.debug("Judged turn {} in {}ms", turnId, millis);
        } catch (TurnJudgeService.Interrupted e) {
            turns.requeue(turnId);
            throw e;
        } catch (RuntimeException e) {
            // Saved as PARTIAL, not requeued: a turn that breaks the judges once will again, and would block
            // the queue forever.
            TurnVerdicts empty = new TurnVerdicts();
            empty.markUnmeasured();
            turns.saveVerdicts(turnId, empty, null, (System.nanoTime() - startedAt) / 1_000_000,
                               properties.judgeModel());
            log.warn("Judging turn {} failed; saved as PARTIAL", turnId, e);
        }
    }

    /**
     * Lets the turn in flight finish if it can within {@code shutdown-wait-seconds}, then interrupts it;
     * an interrupted turn goes back to PENDING and is judged from the start after the restart.
     */
    @Override
    public void stop() {
        running = false;
        Thread worker = thread;
        if (worker == null) {
            return;
        }
        try {
            if (!worker.join(Duration.ofSeconds(properties.judge().shutdownWaitSeconds()))) {
                worker.interrupt();
                worker.join(Duration.ofSeconds(2));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
