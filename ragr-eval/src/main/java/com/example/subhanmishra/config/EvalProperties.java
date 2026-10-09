package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Evaluation settings. Two paths: <em>online</em> scores live traffic and needs no known answers;
 * <em>golden</em> replays a curated dataset, so it can also measure recall against hand-checked pages.
 * Both use the same judges, configured in {@link Judge}.
 *
 * @param enabled           turns all evaluation off - unlike a zero sample rate, which keeps the free
 *                          rule-based metrics
 * @param topic             where ragr-app publishes turns; the live consumer and the golden suite read it
 * @param feedbackTopic     where ragr-app publishes ratings
 * @param judgeModel        the judges' Ollama model: the chat model, on purpose. With it and the embedding
 *                          model loaded the machine has ~1 GB left, so any other judge would evict one of
 *                          them on every call. The price is self-judging bias (see {@code EvalConfig}).
 *                          To move the judges elsewhere, point {@code spring.ai.ollama.base-url} at another
 *                          server, and the idle gate can be turned off.
 * @param judgeTemperature  judges answer YES or NO, so near-deterministic. Spring AI sends nothing unless
 *                          set, and the model's own default is 1.0.
 * @param judgeNumPredict   a verdict is one word or digit; the cap stops a judge that starts explaining
 *                          from holding the one model runner for minutes. The list-writing judges use
 *                          {@link Judge#claimsNumPredict} instead.
 * @param judgeNumCtx       must hold the passages and the answer. Ollama cuts an over-long prompt from the
 *                          start, silently dropping the very context being judged.
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
     * @param enabled          whether live turns are scored at all
     * @param judgeSampleRate  share of turns queued for the judges, 0.0 to 1.0. Zero keeps the free
     *                         rule-based metrics. A grounded turn costs about a minute of judge time (see
     *                         {@code TurnJudgeService}); the queue absorbs bursts, and
     *                         {@link Judge#maxBacklogAge} bounds how far behind it may fall.
     * @param reviewSampleRate random share of live turns put in the human review queue, beside every
     *                         thumbs-down and every user-judge disagreement. Without a random share the
     *                         reviewed set would hold only failures, and judge-human agreement would mean
     *                         nothing.
     * @param rephraseWindow   how soon a follow-up must come to count as the same question asked again
     * @param rephraseOverlap  how much the two turns' pools must overlap (Jaccard) for that. Pools, not text:
     *                         a rephrasing changes the words but finds the same chunks, and needs no model.
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
     *                              which judges its own turns and must not take live ones
     * @param pollInterval          how often an idle worker looks for a PENDING turn
     * @param maxBacklogAge         a PENDING turn older than this is SKIPPED, so the queue never runs hours
     *                              behind. A steadily rising {@code rag.eval.online.judge.skipped.total} means
     *                              the sample rate is too high
     * @param callTimeoutSeconds    the longest one judge call may take; past it the stage is "not measured"
     * @param shutdownWaitSeconds   how long shutdown lets the call in flight finish before interrupting it.
     *                              The turn goes back to PENDING. Must fit inside the container's
     *                              {@code stop_grace_period}
     * @param faithfulnessThreshold share of an answer's claims that must be supported for end-to-end success
     * @param maxClaims             most claims taken from one answer; more are judged on the first ones
     * @param claimsNumPredict      output cap for the two judges that write lists, instead of
     *                              {@code judgeNumPredict}
     * @param idleGate              waiting for chat to be idle before each judge call
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
     * <p>Ollama serves one request at a time, so a judge call running while someone chats makes them wait.
     * Before every call the gate reads ragr-app's {@code rag.chat.generations.active} gauge and waits while
     * it is above zero. A call already running still finishes first, so a user waits at most one call -
     * which is why judge prompts are kept small.
     *
     * @param enabled          off when the judges use a model chat does not
     * @param chatActuatorUrl  ragr-app's actuator - its management port, not the API port
     * @param pollInterval     how often to re-read the gauge while chat is busy
     * @param maxWait          the longest one call is held, so a stuck gauge cannot stop judging forever;
     *                         past it the call goes ahead, and the wait is counted
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
     * @param datasetLocation where the dataset is, as a Spring resource location
     * @param judged          whether a run also asks the judges, on top of the rule-based scores
     * @param persist         whether run, case and turn rows are written to Postgres. Every golden panel
     *                        reads them - the suite runs in its own JVM, which Prometheus never scrapes -
     *                        so with this off a run reaches no dashboard
     * @param chatUrl         the running ragr-app a run drives, through its real {@code /ai/generate}
     * @param turnTimeout     how long a case waits for its turn on Kafka once the answer has returned.
     *                        It arrives within milliseconds; running out means it was dropped, which fails
     *                        the run rather than scoring a case blind
     */
    public record Golden(String datasetLocation, boolean judged, boolean persist, URI chatUrl,
                         Duration turnTimeout) {
    }
}
