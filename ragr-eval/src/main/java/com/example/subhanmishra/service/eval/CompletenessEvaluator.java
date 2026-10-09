package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Whether the answer addresses every part of the question.
 *
 * <p>From the question and answer alone. Whether the answer is <em>true</em> is the faithfulness judge's
 * job; this asks only whether part of the question went unanswered - which relevancy misses, since half an
 * answer is still relevant. Without the passages it is also the shortest answer judge.
 *
 * <p>With reference answers it could judge correctness, but live traffic will never have them, so both
 * paths ask the same reference-free question.
 */
public class CompletenessEvaluator {

    private static final String PROMPT = """
            You are checking whether an answer is complete.

            Question:
            {question}

            Answer:
            {answer}

            Does the answer address every part of the question? An answer that covers only some of
            what was asked, or that says it cannot answer part of it, is not complete.
            Reply with exactly one word: YES or NO.""";

    private final ChatClient chatClient;

    public CompletenessEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public @Nullable Boolean judge(String question, String answer) {
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(PROMPT)
                                                      .param("question", question)
                                                      .param("answer", answer))
                                    .call()
                                    .content();
        return JudgeText.yesNo(response);
    }
}
