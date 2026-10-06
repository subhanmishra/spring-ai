package com.example.subhanmishra.config;

import com.example.subhanmishra.service.eval.ChunkGradeEvaluator;
import com.example.subhanmishra.service.eval.CitationSupportEvaluator;
import com.example.subhanmishra.service.eval.ClaimFaithfulnessEvaluator;
import com.example.subhanmishra.service.eval.CompletenessEvaluator;
import com.example.subhanmishra.service.eval.ContextPrecisionEvaluator;
import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
import com.example.subhanmishra.service.eval.TaskClassifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Wiring for the evaluation framework.
 *
 * <p>The single most important thing here is that each judge is built from a <strong>fresh</strong>
 * {@link ChatClient.Builder}, taken from the {@link ObjectProvider} on every call. Spring AI declares
 * that builder bean {@code @Scope("prototype")}, which is what makes this safe: the builder
 * {@code SpringAiConfig.chatClient} consumed already carries the {@code QuestionAnswerAdvisor} and the
 * chat-memory advisor, and a judge inheriting those would be catastrophic in two distinct ways. It
 * would run its own similarity search and splice retrieved passages into the grading prompt, so the
 * judge would be marking the answer against context the answer never saw; and it would accumulate
 * conversation history, so each verdict would be influenced by the ones before it. Neither failure
 * would throw, log, or look wrong - the scores would just quietly stop meaning anything.
 *
 * <p>Judging reuses the chat model rather than a dedicated one, and that is a memory decision, not a
 * quality one. Measured on the dev host: {@code gemma4:e2b}'s {@code llama-server} commits ~8.1 GB
 * (Ollama's scheduler predicts 6.9 GiB; {@code /api/ps} reports only the 1.70 GB placed on the iGPU)
 * and {@code nomic-embed-text} ~0.5 GB, leaving ~1.1 GB of RAM available of 15.63 GB total and ~2.7 GB
 * of commit headroom - tight enough that a cold load of gemma already evicts the embedder once. The
 * smallest purpose-built grounded-factuality judge, {@code bespoke-minicheck}, ships only at 7B with a
 * 4.39 GiB weights layer - there is no room for it alongside the pair, so every judgement would evict
 * a model or page. Reusing the resident chat model loads nothing new.
 *
 * <p>The price is <strong>self-judging bias</strong>, and it should not be glossed over: a model
 * grading its own output is measurably more generous than an independent judge, so these rates are
 * optimistic in absolute terms. They are still useful, because what an eval is for is detecting
 * <em>change</em> - the bias is a roughly constant offset, so a drop in groundedness after a prompt or
 * model change is real even though the absolute level is flattering. Read them as a trend line, never
 * as a quality score to quote.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EvalConfig {

    /**
     * Options applied to every judge call.
     *
     * <p>Temperature is pinned because Spring AI sends no value unless one is configured, and Gemma's
     * own Modelfile defaults to 1.0 - the same trap {@code application-dev.yaml} documents at length
     * for the chat path. A judge sampling at 1.0 returns a different verdict for the same answer on
     * consecutive runs, which turns a regression signal into noise.
     *
     * <p>{@code num-predict} is small because a judge's entire answer is one word. Without a cap, a
     * judge that starts explaining its reasoning holds Ollama's single runner slot for minutes, and on
     * the online path that delays a real user's generation.
     *
     * <p>{@code think} is disabled for the same reason it is on the chat path: Spring AI 2.0.1 returns
     * Gemma's reasoning in a separate {@code OllamaApi.Message.thinking} field that nothing here reads,
     * so leaving it on both discards those tokens and spends the whole {@code num-predict} budget
     * before the actual YES or NO is emitted - producing an empty verdict that scores as a failure.
     */
    private OllamaChatOptions.Builder judgeOptions(EvalProperties properties) {
        // Returned unbuilt: ChatClient.Builder.defaultOptions takes a ChatOptions.Builder and builds it
        // itself, so calling build() here would not type-check.
        return OllamaChatOptions.builder()
                                .model(properties.judgeModel())
                                .temperature(properties.judgeTemperature())
                                .numPredict(properties.judgeNumPredict())
                                .numCtx(properties.judgeNumCtx())
                                .disableThinking();
    }

    /**
     * A builder carrying only the judge's options and {@link JudgeLineEndingAdvisor} - no retrieval, no
     * system prompt, no memory. That advisor is the one exception to "no advisors" and is safe for the
     * reason the others are not: it adds nothing to the prompt, only rewrites its line endings, so the
     * same judgement passes or fails alike on Windows and in the container.
     *
     * <p>Taken from the provider on each call so that a prototype instance is created per judge. Two
     * judges sharing one builder would be harmless today but is exactly the kind of thing that stops
     * being harmless when someone adds a default to one of them.
     */
    private ChatClient.Builder judgeClientBuilder(ObjectProvider<ChatClient.Builder> builders,
                                                  EvalProperties properties) {
        return builders.getObject()
                       .defaultOptions(judgeOptions(properties))
                       .defaultAdvisors(new JudgeLineEndingAdvisor());
    }

    /**
     * Judges whether the answer addresses the question, given the retrieved context.
     *
     * <p>Catches a specific and otherwise invisible failure: an answer that is perfectly grounded and
     * correctly cited, but about the wrong thing - which happens when retrieval returns a plausible
     * neighbouring passage and the model dutifully summarises it.
     */
    @Bean
    public RelevancyEvaluator relevancyEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                 EvalProperties properties) {
        return RelevancyEvaluator.builder()
                                 .chatClientBuilder(judgeClientBuilder(builders, properties))
                                 .build();
    }

    /**
     * Judges whether the answer's claims are supported by the retrieved context.
     *
     * <p>Uses the default document/claim prompt rather than {@code forBespokeMinicheck}, whose stripped
     * prompt omits the instruction entirely and relies on a model fine-tuned to infer the task from the
     * bare format. A general chat model given that prompt has no idea what it is being asked.
     */
    @Bean
    public FactCheckingEvaluator factCheckingEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                       EvalProperties properties) {
        return FactCheckingEvaluator.builder(judgeClientBuilder(builders, properties)).build();
    }

    /**
     * Judges whether each retrieved chunk actually contributed to the answer, which is what context
     * precision is computed from.
     *
     * <p>Built from the same bare builder as the other two judges and for the same reason - a judge
     * inheriting the {@code QuestionAnswerAdvisor} would retrieve its own context and grade a chunk
     * against passages the answer never saw.
     *
     * <p>Unlike them, this one is called {@code top-k} times per case rather than once. The bean always
     * exists; {@code GoldenEvalService} only invokes it on a judged run, because on this host that
     * multiplication is the dominant cost of the suite.
     */
    @Bean
    public ContextPrecisionEvaluator contextPrecisionEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                               EvalProperties properties) {
        return new ContextPrecisionEvaluator(judgeClientBuilder(builders, properties));
    }

    /**
     * The same judge client with a generation cap long enough for a list. The claim judges write one line
     * per claim, and the one-word cap every other judge runs with would cut that list off after its first
     * few tokens.
     */
    private ChatClient.Builder listJudgeClientBuilder(ObjectProvider<ChatClient.Builder> builders,
                                                      EvalProperties properties) {
        return builders.getObject()
                       .defaultOptions(judgeOptions(properties).numPredict(properties.judge().claimsNumPredict()))
                       .defaultAdvisors(new JudgeLineEndingAdvisor());
    }

    /**
     * Citation checks per answer, at most. A grounded answer here cites 2-3 pages on average (82 citations
     * across the five verification runs of 9 cases each), so this bounds an outlier without truncating
     * the usual answer.
     */
    private static final int MAX_CITATION_CHECKS = 6;

    /** Grades every chunk of a turn's pool for the question - the source of live retrieval metrics. */
    @Bean
    public ChunkGradeEvaluator chunkGradeEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                   EvalProperties properties) {
        return new ChunkGradeEvaluator(judgeClientBuilder(builders, properties));
    }

    @Bean
    public TaskClassifier taskClassifier(ObjectProvider<ChatClient.Builder> builders, EvalProperties properties) {
        return new TaskClassifier(judgeClientBuilder(builders, properties));
    }

    @Bean
    public CompletenessEvaluator completenessEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                       EvalProperties properties) {
        return new CompletenessEvaluator(judgeClientBuilder(builders, properties));
    }

    @Bean
    public ClaimFaithfulnessEvaluator claimFaithfulnessEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                                 EvalProperties properties) {
        return new ClaimFaithfulnessEvaluator(listJudgeClientBuilder(builders, properties),
                                              properties.judge().maxClaims());
    }

    @Bean
    public CitationSupportEvaluator citationSupportEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                             EvalProperties properties) {
        return new CitationSupportEvaluator(judgeClientBuilder(builders, properties), MAX_CITATION_CHECKS);
    }

    @Bean
    public GoldenDatasetLoader goldenDatasetLoader(ResourceLoader resourceLoader) {
        return new GoldenDatasetLoader(resourceLoader);
    }

    /**
     * Fails fast when the judge model is not configured, rather than letting Spring AI fall back to the
     * chat model's own configured name and silently judge with whatever is set there.
     */
    @Bean
    public EvalPropertiesValidator evalPropertiesValidator(EvalProperties properties) {
        return new EvalPropertiesValidator(properties);
    }

    /** Startup validation of the eval settings, separated so the checks are testable. */
    public record EvalPropertiesValidator(EvalProperties properties) {

        public EvalPropertiesValidator {
            if (!StringUtils.hasText(properties.judgeModel())) {
                throw new IllegalStateException("app.eval.judge-model must be set when evaluation is enabled");
            }
            double rate = properties.online().judgeSampleRate();
            if (rate < 0.0 || rate > 1.0) {
                throw new IllegalStateException(
                        "app.eval.online.judge-sample-rate must be between 0.0 and 1.0, was " + rate);
            }
            double reviewRate = properties.online().reviewSampleRate();
            if (reviewRate < 0.0 || reviewRate > 1.0) {
                throw new IllegalStateException(
                        "app.eval.online.review-sample-rate must be between 0.0 and 1.0, was " + reviewRate);
            }
            if (properties.judge().maxClaims() < 1) {
                throw new IllegalStateException("app.eval.judge.max-claims must be at least 1");
            }
        }
    }
}
