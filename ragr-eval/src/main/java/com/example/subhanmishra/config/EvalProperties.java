package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Settings for the evaluation framework, which scores what the RAG pipeline actually produces.
 *
 * <p>There are two evaluation paths and they answer different questions, so they are configured
 * separately. The <em>online</em> path scores real user traffic as it happens and needs no ground
 * truth; the <em>golden</em> path replays a curated dataset and can therefore also measure recall
 * against pages a person verified. Both go through the same judges, which {@link Judge} configures.
 *
 * @param enabled           master switch. Off, no evaluation runs at all and no eval meters are
 *                          registered - which is different from a zero sample rate, where the
 *                          deterministic metrics still report.
 * @param topic             where ragr-app publishes completed chat turns; the online consumer reads
 *                          it, and so does the golden suite, for its own turns
 * @param feedbackTopic     where ragr-app publishes users' ratings of answers
 * @param judgeModel        the Ollama model the LLM judges use. Defaults to the configured chat model,
 *                          and that default is load-bearing on a memory-constrained host: measured on
 *                          the dev machine, {@code gemma4:e2b} commits ~8.1 GB alongside
 *                          {@code nomic-embed-text} at ~0.5 GB, leaving ~1.1 GB of RAM available.
 *                          Any other judge evicts one of them on every call. Reusing the chat model
 *                          costs nothing because it is already loaded - at the price of self-judging bias, for which
 *                          see {@code EvalConfig}. The endpoint is {@code spring.ai.ollama.base-url}; pointing
 *                          that at a separate Ollama-compatible server moves the judges off the chat
 *                          model's runner, and {@code judge.idle-gate.enabled} can then be turned off.
 * @param judgeTemperature  judges answer YES or NO, so sampling should be effectively deterministic.
 *                          Spring AI sends no temperature unless one is configured, which for Gemma
 *                          means falling back to its Modelfile's 1.0 - the same trap
 *                          {@code application-dev.yaml} documents for the chat path.
 * @param judgeNumPredict   a verdict judge's whole answer is one word or digit. Capping generation is what
 *                          stops a judge that starts explaining itself from occupying Ollama's single
 *                          runner slot for minutes. The claim judges, which must write lists, override it
 *                          per call with {@link Judge#claimsNumPredict}.
 * @param judgeNumCtx       must hold the retrieved context plus the answer being judged. Ollama
 *                          truncates an over-long prompt from the left, which would silently drop the
 *                          very context the judgement is about and produce a confident wrong verdict.
 * @param online            settings for scoring live chat traffic.
 * @param judge             the judge queue and the bounds on each judge call.
 * @param retention         how long evaluated live turns are kept.
 * @param golden            settings for the curated-dataset regression suite.
 */
@ConfigurationProperties(prefix = "app.eval")
public record EvalProperties(boolean enabled,
                             String topic,
                             String feedbackTopic,
                             String judgeModel,
                             double judgeTemperature,
                             int judgeNumPredict,
                             int judgeNumCtx,
                             Online online,
                             Judge judge,
                             Retention retention,
                             Golden golden) {

    /**
     * Scoring of real chat calls.
     *
     * @param enabled          whether live calls are scored at all.
     * @param judgeSampleRate  fraction of turns queued for the LLM judges, 0.0 to 1.0. Zero keeps the
     *                         deterministic metrics - which are free - and turns off only the model calls.
     *                         Every queued turn costs roughly 90-100 s of judge time on this host (see
     *                         {@code TurnJudgeService}); the queue absorbs bursts, and
     *                         {@link Judge#maxBacklogAge} bounds how far behind it may fall.
     * @param reviewSampleRate fraction of live turns drawn at random into the human review queue, beside
     *                         every thumbs-down and every turn where the user and the judge disagree. The
     *                         random share is what keeps the reviewed set from containing only failures,
     *                         which would make judge-versus-human agreement meaningless.
     * @param rephraseWindow   how soon a follow-up in the same conversation must come to count as the user
     *                         asking again
     * @param rephraseOverlap  the Jaccard overlap of the two turns' candidate pools at which a follow-up is
     *                         the same question rephrased. Pools rather than text, because a rephrasing
     *                         changes the words but lands on the same chunks, and it needs no model.
     */
    public record Online(boolean enabled,
                         double judgeSampleRate,
                         double reviewSampleRate,
                         Duration rephraseWindow,
                         double rephraseOverlap) {
    }

    /**
     * The judge queue. Turns are stored as PENDING and judged one at a time by {@code TurnJudgeWorker},
     * only while chat is idle.
     *
     * @param workerEnabled         whether this process runs the worker. Off in the golden suite's test JVM,
     *                              which judges its own turns inline and must not take live ones.
     * @param pollInterval          how often an idle worker looks for a PENDING turn.
     * @param maxBacklogAge         a PENDING turn older than this is marked SKIPPED rather than judged, so the
     *                              queue can never drift hours behind the traffic it describes. Counted as
     *                              {@code rag.eval.online.judge.skipped.total}: a steadily rising count means
     *                              the sample rate is too high for the traffic.
     * @param callTimeoutSeconds    the longest one judge call may take before it is abandoned and that stage
     *                              recorded as unmeasured. Bounds how long a wedged judge holds the worker.
     * @param shutdownWaitSeconds   how long shutdown waits for the call in flight before interrupting it.
     *                              Short, and separate from the timeout: the turn goes back to PENDING and is
     *                              judged after the restart, and the whole shutdown must fit inside the
     *                              container's {@code stop_grace_period}.
     * @param faithfulnessThreshold the share of an answer's claims that must be supported for the answer to
     *                              count as faithful in the end-to-end verdict.
     * @param maxClaims             the most claims taken from one answer. Bounds the verification prompt; an
     *                              answer with more is judged on its first claims, which the judge lists in
     *                              answer order.
     * @param claimsNumPredict      generation cap for the two judges that write lists - claim extraction and
     *                              claim verification - in place of {@code judgeNumPredict}.
     * @param idleGate              waiting for chat to be idle before each judge call.
     */
    public record Judge(boolean workerEnabled,
                        Duration pollInterval,
                        Duration maxBacklogAge,
                        int callTimeoutSeconds,
                        int shutdownWaitSeconds,
                        double faithfulnessThreshold,
                        int maxClaims,
                        int claimsNumPredict,
                        IdleGate idleGate) {
    }

    /**
     * Holding judge calls while a user is waiting on the same model.
     *
     * <p>Ollama serves one request at a time on this host, so a judge call started while a chat generation
     * runs puts that user behind it. Before every judge call the gate reads ragr-app's
     * {@code rag.chat.generations.active} gauge and waits while it is above zero. A judge call already
     * running when a user arrives still finishes first - so the worst a user waits is one call, which is
     * why each judge prompt is kept small.
     *
     * @param enabled          off when the judges run on a model that chat does not use.
     * @param chatActuatorUrl  ragr-app's actuator base URL - its management port, not the API port.
     * @param pollInterval     how often to re-read the gauge while chat is busy.
     * @param maxWait          the longest to hold one call. A stuck gauge must not stop judging forever;
     *                         past this the call goes ahead, and the wait is counted.
     */
    public record IdleGate(boolean enabled, URI chatActuatorUrl, Duration pollInterval, Duration maxWait) {
    }

    /**
     * @param liveDays evaluated live turns, their chunks and feedback older than this are deleted daily.
     *                 Golden turns are kept with their runs.
     */
    public record Retention(int liveDays) {
    }

    /**
     * The curated-dataset regression suite.
     *
     * @param datasetLocation Spring resource location of the dataset.
     * @param judged          whether a run also asks the LLM judges, on top of the deterministic scores.
     * @param persist         whether run and per-case rows are written to Postgres. The Grafana eval
     *                        dashboard's per-case tables read those rows, so turning this off leaves the
     *                        dashboard with trend lines only.
     * @param chatUrl         the running ragr-app a run drives, through its real {@code /ai/generate}
     *                        endpoint. The suite measures the answer a user receives, so it goes through
     *                        exactly what a user goes through - not a replica of the chat path.
     * @param turnTimeout     how long a case waits for its turn to arrive on the topic once the answer
     *                        has returned. The publisher sends within milliseconds; running out means the
     *                        event was dropped, which fails the run rather than scoring a case blind.
     */
    public record Golden(String datasetLocation, boolean judged, boolean persist, URI chatUrl,
                         Duration turnTimeout) {
    }
}
