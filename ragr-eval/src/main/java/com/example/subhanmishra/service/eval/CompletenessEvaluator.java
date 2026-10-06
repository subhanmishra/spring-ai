package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Whether the answer addresses every part of the question.
 *
 * <p>Judged from the question and the answer alone. Whether what the answer says is <em>true</em> is the
 * faithfulness judge's job; this one asks only whether anything asked was left unanswered - the failure
 * relevancy cannot see, since an answer to half of a two-part question is perfectly relevant. Leaving the
 * passages out also keeps this the shortest of the answer judges, which matters because a call holds the
 * runner a user may be waiting for.
 *
 * <p>Against a reference answer it would be a correctness judge; the golden dataset has none yet, and
 * live traffic never will, so the same reference-free question is asked of both.
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
