package com.example.subhanmishra.config;

import com.example.subhanmishra.service.eval.GoldenDatasetLoader;
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
 * quality one. Measured on the dev host: {@code gemma4:e2b} is resident at 1.59 GiB and
 * {@code nomic-embed-text} at 0.30 GiB, leaving 0.74 GB free of 15.63 GB total, against 2.81 GB with
 * nothing loaded. The smallest purpose-built grounded-factuality judge,
 * {@code bespoke-minicheck}, ships only at 7B with a 4.39 GiB weights layer - it does not fit even
 * after evicting both resident models, so every judgement would page. Reusing the resident chat model
 * costs nothing and evicts nothing.
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
     * A builder carrying only the judge's options - no advisors, no system prompt, no memory.
     *
     * <p>Taken from the provider on each call so that a prototype instance is created per judge. Two
     * judges sharing one builder would be harmless today but is exactly the kind of thing that stops
     * being harmless when someone adds a default to one of them.
     */
    private ChatClient.Builder judgeClientBuilder(ObjectProvider<ChatClient.Builder> builders,
                                                  EvalProperties properties) {
        return builders.getObject().defaultOptions(judgeOptions(properties));
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
            if (properties.online().maxConcurrentJudgements() < 1) {
                throw new IllegalStateException("app.eval.online.max-concurrent-judgements must be at least 1");
            }
        }
    }
}
