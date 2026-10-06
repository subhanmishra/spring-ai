package com.example.subhanmishra.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every judge {@link EvalConfig} builds must send Ollama {@code \n} line endings whatever the platform -
 * see {@link JudgeLineEndingAdvisor} for the verdict that flipped on {@code \r\n}. All three are checked
 * because they render their prompts in two different ways, and a fix that covered one way missed the
 * other. The inputs carry {@code \r\n} explicitly, so the test means the same on Linux as on the Windows
 * host where the template engine adds them itself.
 */
class JudgeLineEndingAdvisorTest {

    private static final List<Document> RETRIEVED = List.of(
            Document.builder().text("[manual.pdf, p. 419]\r\n\r\nSet management.server.port.\r\nOr the address.").build(),
            Document.builder().text("[manual.pdf, p. 294]\r\n\r\nA separate management context.").build());
    private static final EvaluationRequest REQUEST =
            new EvaluationRequest("Which property?\r\nOn which port?", RETRIEVED,
                                  "Set management.server.port.\r\nIt binds elsewhere.");

    private final ChatModel model = mock(ChatModel.class);
    private final EvalConfig config = new EvalConfig();
    private final EvalProperties properties =
            new EvalProperties(true, "rag.chat.turn.completed", "rag.chat.feedback", "judge", 0.0, 8, 8192, null,
                               null, null, null);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatClient.Builder> builders = mock(ObjectProvider.class);

    @BeforeEach
    void stubModel() {
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("yes")))));
        when(builders.getObject()).thenAnswer(invocation -> ChatClient.builder(model));
    }

    private List<String> sentPrompts() {
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).call(sent.capture());
        return sent.getAllValues().stream().map(Prompt::getContents).toList();
    }

    @Test
    @DisplayName("relevancy - which renders its own template - sends no carriage returns")
    void relevancy() {
        assertThat(config.relevancyEvaluator(builders, properties).evaluate(REQUEST).isPass()).isTrue();
        assertThat(sentPrompts()).singleElement().asString()
                                 .contains("management.server.port").doesNotContain("\r");
    }

    @Test
    @DisplayName("groundedness - which the client renders - sends no carriage returns")
    void groundedness() {
        assertThat(config.factCheckingEvaluator(builders, properties).evaluate(REQUEST).isPass()).isTrue();
        assertThat(sentPrompts()).singleElement().asString()
                                 .contains("management.server.port").doesNotContain("\r");
    }

    @Test
    @DisplayName("context precision - one call per chunk - sends no carriage returns")
    void contextPrecision() {
        config.contextPrecisionEvaluator(builders, properties)
              .judge(REQUEST.getUserText(), REQUEST.getResponseContent(), RETRIEVED);
        assertThat(sentPrompts()).hasSize(RETRIEVED.size())
                                 .allSatisfy(prompt -> assertThat(prompt).doesNotContain("\r"));
    }
}
