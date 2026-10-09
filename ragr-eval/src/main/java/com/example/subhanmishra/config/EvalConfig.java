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
 * Wiring for the judges.
 *
 * <p><b>Every judge gets a fresh, bare {@link ChatClient.Builder}</b>, taken from the
 * {@link ObjectProvider} each time (Spring AI makes it a prototype). A judge must never inherit a
 * retrieval or memory advisor: it would grade the answer against passages the answer never saw, or let
 * each verdict lean on the ones before it - and nothing would throw or look wrong.
 *
 * <p><b>The judges reuse the chat model</b> - a memory decision, not a quality one. With the chat and
 * embedding models loaded the machine has about 1 GB of RAM left, and the smallest dedicated judge
 * ({@code bespoke-minicheck}) needs 4.39 GiB. The resident chat model costs nothing more.
 *
 * <p><b>The price is self-judging bias.</b> A model grading itself is more generous than an independent
 * judge, so the rates are optimistic. They still show <em>change</em>: the bias is roughly constant, so a
 * drop after a prompt or model change is real. Read them as a trend, never as a score to quote.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EvalConfig {

    /**
     * Options applied to every judge call.
     *
     * <ul>
     *   <li><b>Temperature pinned.</b> Spring AI sends none unless set, and the model's default is 1.0, at
     *       which the same answer gets different verdicts run to run.</li>
     *   <li><b>A small {@code num-predict}.</b> A verdict is one word; without a cap a judge that starts
     *       explaining holds the one model runner for minutes.</li>
     *   <li><b>Thinking off.</b> It is returned separately and never read, and it used up the whole output
     *       budget before the YES or NO - an empty verdict.</li>
     * </ul>
     */
    private OllamaChatOptions.Builder judgeOptions(EvalProperties properties) {
        // Unbuilt: defaultOptions takes a builder and builds it itself.
        return OllamaChatOptions.builder()
                                .model(properties.judgeModel())
                                .temperature(properties.judgeTemperature())
                                .numPredict(properties.judgeNumPredict())
                                .numCtx(properties.judgeNumCtx())
                                .disableThinking();
    }

    /**
     * A builder with only the judge options and {@link JudgeLineEndingAdvisor} - no retrieval, no system
     * prompt, no memory. That one advisor is safe: it adds nothing, only fixes line endings, so a
     * judgement comes out the same on Windows and in the container.
     *
     * <p>A new instance per judge, so a default added to one can never leak into another.
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
     * <p>Catches an answer that is grounded and correctly cited but about the wrong thing - when retrieval
     * finds a plausible neighbouring passage and the model summarises it.
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
     * <p>The default prompt, not {@code forBespokeMinicheck}'s, which leaves out the instruction and only
     * works with a model trained for it.
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
     * <p>Called once per chunk rather than once per answer, so the golden suite uses it only on a judged
     * run, where it is the biggest cost.
     */
    @Bean
    public ContextPrecisionEvaluator contextPrecisionEvaluator(ObjectProvider<ChatClient.Builder> builders,
                                                               EvalProperties properties) {
        return new ContextPrecisionEvaluator(judgeClientBuilder(builders, properties));
    }

    /**
     * The same judge client with room for a list: the claim judges write a line per claim, which the
     * one-word cap would cut off.
     */
    private ChatClient.Builder listJudgeClientBuilder(ObjectProvider<ChatClient.Builder> builders,
                                                      EvalProperties properties) {
        return builders.getObject()
                       .defaultOptions(judgeOptions(properties).numPredict(properties.judge().claimsNumPredict()))
                       .defaultAdvisors(new JudgeLineEndingAdvisor());
    }

    /**
     * Most citation checks per answer. Answers cite 2-3 pages on average, so this only bounds outliers.
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
     * Fails at startup when the judge model is not set, instead of Spring AI silently falling back to
     * whatever chat model is configured.
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
