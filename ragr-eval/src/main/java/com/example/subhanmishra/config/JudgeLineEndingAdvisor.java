package com.example.subhanmishra.config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Rewrites every line ending in a judge's prompt to {@code \n} just before it is sent, so a judgement
 * does not depend on the platform ragr-eval runs on.
 *
 * <p>Spring AI writes prompt newlines as the platform's line separator, so the same judge prompt reached
 * Ollama with {@code \r\n} on Windows and {@code \n} in the container - and that changed verdicts: the
 * same groundedness check said <em>no</em> with one and <em>yes</em> with the other.
 *
 * <p>An advisor, not a template renderer, because not every judge renders through the client
 * ({@code RelevancyEvaluator} hands it finished text). The finished prompt is the one place all of them
 * pass through.
 */
public final class JudgeLineEndingAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions().stream()
                                       .map(JudgeLineEndingAdvisor::normalise)
                                       .toList();
        return chain.nextCall(request.mutate().prompt(new Prompt(messages, prompt.getOptions())).build());
    }

    private static Message normalise(Message message) {
        // Judges send a single user message; anything else passes through untouched.
        return message instanceof UserMessage user && user.getText() != null
                ? user.mutate().text(user.getText().replace("\r\n", "\n")).build()
                : message;
    }

    @Override
    public String getName() {
        return "judgeLineEnding";
    }

    @Override
    public int getOrder() {
        // Last before the model call, so it sees the prompt exactly as it will be sent.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
