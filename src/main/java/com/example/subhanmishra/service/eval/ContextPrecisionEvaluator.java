package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the judge, chunk by chunk, whether a retrieved passage actually contributed to the answer.
 *
 * <p>This is the reference-free half of context precision - RAGAS's
 * {@code LLMContextPrecisionWithoutReference}. It needs no ground truth at all, which is what makes it
 * the only one of the two that could in principle score live traffic, and what makes it a genuine
 * cross-check on the other: {@code EvalScoringService} scores relevance from a golden case's
 * {@code expectedPages}, and that list was curated as <em>pages that contain the answer</em> rather
 * than as an exhaustive relevance labelling. Every page it omits is scored as noise. Where this judge
 * calls a chunk useful that {@code expectedPages} does not cover, the dataset is the thing that is
 * probably wrong.
 *
 * <p><strong>The cost is why this is gated behind {@code app.eval.golden.judged} and will not run
 * online.</strong> Precision is defined per retrieved chunk, so a case costs {@code top-k} judge calls
 * rather than one - at the configured top-k of 5 that is 45 extra calls across the 9-case suite, on
 * top of the 18 the relevancy and groundedness judges already make. Ollama serialises on a single
 * runner slot, so those are strictly sequential. The online path samples judgements at 0.1 with a
 * concurrency bound of 1 precisely because a judge call delays the next user's generation; multiplying
 * that by top-k is not something the chat path can absorb.
 *
 * <p><strong>Where this departs from RAGAS, deliberately.</strong> RAGAS asks its judge for a JSON
 * object carrying a reason and a verdict, and parses it. That assumes a judge that emits reliable
 * structured output, which {@code gemma4:e2b} is not - and {@code EvalConfig} caps the judge at
 * {@code num-predict: 8} with thinking disabled, because a judge that starts explaining itself holds
 * the single runner slot for minutes. So the prompt asks for one word, exactly as the relevancy and
 * groundedness judges do, and the reason is not collected. The verdict is the part that is scored; the
 * reason would be a field nothing reads.
 *
 * <p>The judge sees the passage with its {@code [filename, p. N]} citation header stripped. The header
 * is identical in shape on every chunk and carries no evidence of usefulness, so leaving it in would
 * spend context on a constant.
 *
 * <p>A single failed or unparseable verdict abandons the whole case rather than being guessed at.
 * Precision is a property of the ranked list as a whole, and a list with a hole in it has no defensible
 * score - recording one would be inventing data at exactly the point where the measurement failed.
 */
public class ContextPrecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ContextPrecisionEvaluator.class);

    /**
     * One word out, and the question phrased around <em>use</em> rather than <em>topic</em>.
     *
     * <p>"Is this passage relevant to the question" is the wording to avoid: every chunk the retriever
     * returned cleared a 0.6 similarity threshold against that same question, so it is topically
     * relevant almost by construction, and the judge answers YES to everything. Asking whether the
     * passage supplied information the answer used is the distinction that has any discriminating power
     * left in it.
     */
    private static final String PROMPT = """
            You are checking whether a retrieved passage was used to produce an answer.

            Question:
            {question}

            Answer:
            {answer}

            Passage:
            {passage}

            Did this passage supply information that appears in the answer?
            Reply with exactly one word: YES or NO.""";

    /**
     * Comfortably above the chunk-size budget in {@code application-dev.yaml} - chunks average 279
     * tokens and are capped below 445 - so this truncates nothing in practice and exists only so that
     * an unsplittable oversized table row cannot blow the judge's context window.
     */
    private static final int MAX_PASSAGE_CHARS = 4_000;

    /** The leading word of the verdict. Matched as a whole word so that "not" cannot read as "no". */
    private static final Pattern FIRST_WORD = Pattern.compile("[a-z]+");

    private final ChatClient chatClient;

    public ContextPrecisionEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Judges every retrieved chunk in rank order.
     *
     * @return the scores, or null when the case could not be judged - no context to score, or any
     *         single verdict failing. Null means "not measured" and must never be folded into an
     *         aggregate as a zero.
     */
    public @Nullable ContextPrecisionScores judge(String question, String answer, List<Document> retrieved) {
        if (retrieved.isEmpty()) {
            // Nothing to order, and an out-of-corpus case retrieving nothing is behaving correctly.
            // Scoring it 0.0 would fail a case for doing the right thing - the same trap
            // GoldenEvalService avoids by not judging ungrounded answers at all.
            return null;
        }

        List<Boolean> relevance = new ArrayList<>(retrieved.size());
        for (Document document : retrieved) {
            Boolean verdict = verdict(question, answer, passageOf(document));
            if (verdict == null) {
                log.warn("Context precision abandoned for this case: a chunk verdict could not be obtained");
                return null;
            }
            relevance.add(verdict);
        }
        return ContextPrecisionScores.of(relevance);
    }

    private @Nullable Boolean verdict(String question, String answer, String passage) {
        try {
            // Passed as template parameters rather than concatenated into the prompt, which is what
            // Spring AI's own evaluators do and for a reason that bites here specifically: the corpus is
            // full of property placeholders, and a substituted value is not re-rendered whereas prompt
            // text is.
            String response = chatClient.prompt()
                                        .user(spec -> spec.text(PROMPT)
                                                          .param("question", question)
                                                          .param("answer", answer)
                                                          .param("passage", passage))
                                        .call()
                                        .content();
            return parse(response);
        } catch (RuntimeException e) {
            log.warn("Context precision judgement failed", e);
            return null;
        }
    }

    /** Null for anything that is neither YES nor NO, which is a failed measurement rather than a NO. */
    static @Nullable Boolean parse(@Nullable String response) {
        if (response == null) {
            return null;
        }
        Matcher matcher = FIRST_WORD.matcher(response.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group()) {
            case "yes" -> Boolean.TRUE;
            case "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * The chunk as the judge should see it: citation header removed, and truncated so that five
     * passages plus the answer cannot overrun {@code judge-num-ctx}. Ollama truncates an over-long
     * prompt from the left, which here would silently drop the instruction and leave the judge
     * answering a question it was never asked.
     */
    private static String passageOf(Document document) {
        String stripped = CitationParser.stripHeader(document.getText());
        String text = stripped != null ? stripped : "";
        return text.length() <= MAX_PASSAGE_CHARS ? text : text.substring(0, MAX_PASSAGE_CHARS);
    }
}
