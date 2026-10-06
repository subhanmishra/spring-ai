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
 * <p>The queue is {@code eval_turn} itself rather than Kafka. Judging a turn takes a minute or more of
 * model time and waits on idle chat for as long as users keep it busy; holding a Kafka record that long
 * would mean pausing the consumer and fighting {@code max.poll.interval.ms}, and a restart would lose its
 * place. A row survives restarts, its age is one query away, and the deterministic scores are already
 * stored beside it by the time it waits.
 *
 * <p>This replaces the drop-on-contention admission the online path used to have. Dropping kept judging
 * from piling up behind live chat, but it also meant a busy hour was the least judged one. Queueing behind
 * the idle gate judges every sampled turn, and {@code max-backlog-age} is the bound that dropping used to
 * provide: a turn still waiting after that is SKIPPED and counted, so the backlog never describes traffic
 * from hours ago.
 *
 * <p>One worker, so judge calls stay strictly sequential - Ollama would serialise them anyway, and a second
 * worker would only add a second call for chat to queue behind.
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
                // The database blinked, or something unforeseen; a dead worker would silently stop all
                // judging, so back off and carry on.
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
            // Saved as PARTIAL with whatever it has, rather than requeued: a turn that breaks the judges
            // once will break them again, and retrying it forever would block the queue behind it.
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
