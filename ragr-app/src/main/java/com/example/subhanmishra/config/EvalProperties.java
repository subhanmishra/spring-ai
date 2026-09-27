package com.example.subhanmishra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the evaluation framework, which scores what the RAG pipeline actually produces.
 *
 * <p>There are two evaluation paths and they answer different questions, so they are configured
 * separately. The <em>online</em> path scores real user traffic as it happens and needs no ground
 * truth; the <em>golden</em> path replays a curated dataset and can therefore also measure recall,
 * which is impossible without knowing which pages should have been retrieved.
 *
 * @param enabled           master switch. Off, no evaluation runs at all and no eval meters are
 *                          registered - which is different from a zero sample rate, where the
 *                          deterministic metrics still report.
 * @param judgeModel        the Ollama model the LLM judges use. Defaults to the configured chat model,
 *                          and that default is load-bearing on a memory-constrained host: measured on
 *                          the dev machine, {@code gemma4:e2b} is resident at 1.59 GiB alongside
 *                          {@code nomic-embed-text} at 0.30 GiB, leaving 0.74 GB free. Any other judge
 *                          evicts one of them on every call. Reusing the chat model costs nothing
 *                          because it is already loaded - at the price of self-judging bias, for which
 *                          see {@code OnlineEvalService}.
 * @param judgeTemperature  judges answer YES or NO, so sampling should be effectively deterministic.
 *                          Spring AI sends no temperature unless one is configured, which for Gemma
 *                          means falling back to its Modelfile's 1.0 - the same trap
 *                          {@code application-dev.yaml} documents for the chat path.
 * @param judgeNumPredict   a judge's whole answer is one word. Capping generation is what stops a
 *                          judge that starts explaining itself from occupying Ollama's single runner
 *                          slot for minutes.
 * @param judgeNumCtx       must hold the retrieved context plus the answer being judged. Ollama
 *                          truncates an over-long prompt from the left, which would silently drop the
 *                          very context the judgement is about and produce a confident wrong verdict.
 * @param online            settings for scoring live chat traffic.
 * @param golden            settings for the curated-dataset regression suite.
 */
@ConfigurationProperties(prefix = "app.eval")
public record EvalProperties(boolean enabled,
                             String judgeModel,
                             double judgeTemperature,
                             int judgeNumPredict,
                             int judgeNumCtx,
                             Online online,
                             Golden golden) {

    /**
     * Scoring of real chat calls.
     *
     * @param enabled            whether live calls are scored at all.
     * @param judgeSampleRate    fraction of answers sent to the LLM judges, 0.0 to 1.0. Zero keeps the
     *                           deterministic metrics - which are free - and turns off only the model
     *                           calls. This is not primarily about cost: Ollama serialises on a single
     *                           runner slot, so a judge call occupies the chat model and the next user's
     *                           generation queues behind it. The judgement never blocks the caller that
     *                           triggered it, but it is not free to the system.
     * @param maxConcurrentJudgements how many judgements may be in flight at once. One means the worst
     *                           case is a single queued generation rather than a pile-up. Admission is
     *                           non-blocking: over this bound a judgement is dropped and counted, never
     *                           queued, because a queue on this path would grow without limit under load.
     * @param judgeTimeout       seconds to wait for a judge verdict before abandoning it. Bounds how long
     *                           a wedged judge can hold its permit.
     */
    public record Online(boolean enabled,
                         double judgeSampleRate,
                         int maxConcurrentJudgements,
                         int judgeTimeoutSeconds) {
    }

    /**
     * The curated-dataset regression suite.
     *
     * @param datasetLocation Spring resource location of the dataset.
     * @param judged          whether a run also asks the LLM judges, on top of the deterministic scores.
     * @param persist         whether run and per-case rows are written to Postgres. The Grafana eval
     *                        dashboard's per-case tables read those rows, so turning this off leaves the
     *                        dashboard with trend lines only.
     */
    public record Golden(String datasetLocation, boolean judged, boolean persist) {
    }
}
