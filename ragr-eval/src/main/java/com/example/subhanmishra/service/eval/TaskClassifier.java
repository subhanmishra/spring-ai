package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Labels a question with its {@link TaskType}, from the question alone - it runs on ungrounded turns too,
 * which keeps "how much of the traffic is general conversation" measurable without judging those
 * answers. The cheapest call there is: a question and a one-word reply.
 */
public class TaskClassifier {

    private static final String PROMPT = """
            Classify what this question to a software documentation assistant asks for.

            Question:
            {question}

            config = it asks for a specific setting, property, name or value
            howto = it asks for the steps to accomplish something
            troubleshoot = it asks why something fails or how to fix an error
            concept = it asks what something is, what it does, or why
            general = a greeting, small talk, or nothing to do with software documentation
            Reply with exactly one word: config, howto, troubleshoot, concept or general.""";

    private final ChatClient chatClient;

    public TaskClassifier(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public @Nullable TaskType classify(String question) {
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(PROMPT).param("question", question))
                                    .call()
                                    .content();
        return TaskType.fromReply(response);
    }
}
