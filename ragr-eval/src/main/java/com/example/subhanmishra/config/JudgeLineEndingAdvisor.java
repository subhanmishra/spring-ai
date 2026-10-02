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
 * <p>Spring AI renders prompts with StringTemplate, which writes each newline - in the template and in
 * every multi-line value it inserts - as the platform's line separator, and
 * {@code Evaluator.doGetSupportingData} joins the retrieved documents with it too. So on Windows a judge
 * prompt reaches Ollama with {@code \r\n} throughout, and in the Linux container with {@code \n}, from
 * identical inputs. That is not cosmetic for {@code gemma4:e2b}. Measured 2 Oct 2026 on the golden case
 * {@code actuator-port-separation}, whose answer the retrieved page 419 states almost verbatim: the
 * groundedness judge said <em>no</em> at 98% with {@code \r\n} and <em>yes</em> at 100% with {@code \n},
 * everything else byte-identical. Every judge call a golden run made from the Windows test JVM was
 * affected, and an IntelliJ-launched ragr-eval would have been too. The tab Spring AI's text-block
 * prompts put before each line was tested separately and made no difference, so it is left alone.
 *
 * <p>An advisor rather than a template renderer because the judges do not all render the same way:
 * {@code FactCheckingEvaluator} and {@code ContextPrecisionEvaluator} pass parameters for the client to
 * render, but {@code RelevancyEvaluator} renders its private default template itself and hands the
 * client finished text. A renderer on the client fixed the first two and missed the third - measured, 7
 * of 7 relevancy prompts still carried {@code \r\n}. The finished prompt is the one place all three meet.
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
