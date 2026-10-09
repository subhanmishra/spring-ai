package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the judge, chunk by chunk, whether a retrieved passage actually contributed to the answer.
 *
 * <p>RAGAS's reference-free context precision. It needs no known answer, so it cross-checks the
 * expected-page version in {@code EvalScoringService}: the expected pages hold the answer but are not
 * every useful page, so where this judge calls an unlisted chunk useful, the dataset is probably the one
 * that is wrong.
 *
 * <p><b>Golden suite only, judged runs only.</b> It costs one judge call per chunk - five per case - which
 * is why live turns use {@link ChunkGradeEvaluator} (one short call per chunk, graded for the question)
 * instead.
 *
 * <p><b>One word, not RAGAS's JSON.</b> RAGAS asks for a reason and a verdict as JSON; this model does not
 * produce reliable JSON, and a judge that explains itself holds the model runner. So, like the other
 * judges, it answers YES or NO.
 *
 * <p>The passage is shown without its citation header, which says nothing about usefulness.
 *
 * <p>One failed verdict abandons the whole case: precision belongs to the whole ranked list, and a list
 * with a hole in it has no honest score.
 */
public class ContextPrecisionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ContextPrecisionEvaluator.class);

    /**
     * One word out, and the question phrased around <em>use</em> rather than <em>topic</em>.
     *
     * <p>Not "is this passage relevant": every retrieved chunk cleared the similarity threshold for that
     * question, so the judge says YES to all of them. "Did the answer use it" still tells chunks apart.
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

    private final ChatClient chatClient;

    public ContextPrecisionEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Judges every retrieved chunk in rank order.
     *
     * @return the scores, or null when the case could not be judged - nothing retrieved, or a verdict
     *         failed. Null means "not measured", never zero.
     */
    public @Nullable ContextPrecisionScores judge(String question, String answer, List<Document> retrieved) {
        if (retrieved.isEmpty()) {
            // Nothing to order. An out-of-corpus case that retrieves nothing is behaving correctly, and
            // 0.0 would fail it for that.
            return null;
        }

        List<Boolean> relevance = new ArrayList<>(retrieved.size());
        for (Document document : retrieved) {
            Boolean verdict = verdict(question, answer, JudgeText.passage(document));
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
            // Template parameters, not concatenation: the corpus is full of ${...} placeholders, and a
            // substituted value is not re-rendered whereas prompt text is.
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
        return JudgeText.yesNo(response);
    }
}
