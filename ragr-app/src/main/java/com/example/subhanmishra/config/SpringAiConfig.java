package com.example.subhanmishra.config;

import com.example.subhanmishra.service.KeywordSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Configuration
public class SpringAiConfig {

    /**
     * Spring AI's default RAG template, rendered by {@link PooledQuestionAnswerAdvisor}, adapted so the
     * citation rules sit right after the passages, general knowledge stays allowed, and the passages are
     * attributed to the document library rather than the user. {@code citation_rules} is one of
     * {@link #CITATION_RULES}, chosen by whether any passage has a page.
     */
    private static final PromptTemplate QA_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            Passages retrieved automatically from the document library are below, surrounded by
            ---------------------. The user did not write or see them, so never thank the user for
            them or refer to "the context you provided". If there are none, answer from general
            knowledge.

            ---------------------
            {question_answer_context}
            ---------------------

            {citation_rules}

            Prefer the passages over prior knowledge. If part of the answer goes beyond them,
            say so at that point in the answer, not as an opening disclaimer.

            Start directly with the answer: no greeting, no restating the question, no
            acknowledgement, and no closing offer of further help.
            """);

    /**
     * How to cite, in two forms: page-less passages are never shown a page format to imitate.
     */
    private static final PooledQuestionAnswerAdvisor.CitationRules CITATION_RULES =
            new PooledQuestionAnswerAdvisor.CitationRules("""
                    Each passage above begins with its source on its own line, in square brackets:
                    either [filename] or [filename, p. N].

                    When you use a passage, cite it inline by copying that passage's source line exactly,
                    with round brackets instead of square ones:
                    - [filename] becomes (filename) - no page, because the source has none;
                    - [filename, p. N] becomes (filename, p. N).
                    Copy the whole filename, including any number and folder prefix at its start. Never
                    add a page number that is not in the source line, and do not cite a filename that
                    does not appear in a source line above.

                    A heading or number inside a passage, such as "5.3. Endpoints", is never a page.""", """
                    Each passage above begins with its source on its own line, as [filename].

                    When you use a passage, cite it inline by copying that passage's source line exactly,
                    with round brackets instead of square ones: [filename] becomes (filename). Copy the
                    whole filename, including any number and folder prefix at its start, and do not cite
                    a filename that does not appear in a source line above.""");

    /** Indented 24 spaces on purpose: re-indenting changes the prompt the model is sent. */
    private static final String SYSTEM_PROMPT = """
                                You are DocAI, an intelligent, versatile AI document intelligence assistant.
                                Your Capabilities:
                                1. Document-Grounded Q&A: When context from the user's uploaded documents is provided, prioritize and base your answer directly on that context, citing each passage you use by copying its source line, as the instructions beside the passages describe.
                                2. General Knowledge & Conversation: If the user engages in general conversation (greetings, chit-chat, programming questions, math, explanations, summaries, or general knowledge) that may not be present in the uploaded documents, answer helpfully, accurately, and naturally.
                                3. Hybrid Synthesis: If the document context partially covers a topic, synthesize the document facts with your broader knowledge to give a complete, high-quality answer.
                                4. Tone & Format: Be polite but direct - lead with the answer. No greetings, no restating the question, no thanking the user, no closing offers of further help. Use Markdown (headings, bullet points, bold text, code blocks) to make responses easy to read.

        """;

    /**
     * A short hash of everything the model is told besides the conversation, passage marker included,
     * sent with every turn so evaluation can split a metric at a prompt change.
     */
    public static final String PROMPT_VERSION = promptVersion(SYSTEM_PROMPT + QA_PROMPT_TEMPLATE.getTemplate()
                                                              + CITATION_RULES.paged() + CITATION_RULES.pageless()
                                                              + PooledQuestionAnswerAdvisor.PASSAGE_END);

    private static String promptVersion(String prompts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(prompts.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore,
                                 KeywordSearchService keywordSearch, RagProperties ragProperties) {
        var qaAdvisor = new PooledQuestionAnswerAdvisor(vectorStore, keywordSearch, QA_PROMPT_TEMPLATE, CITATION_RULES,
                                                        ragProperties);
        return builder.defaultSystem(SYSTEM_PROMPT)
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .defaultAdvisors(qaAdvisor)
                    .build();
    }

    @Bean
    ChatMemoryRepository chatMemoryRepository(RedisClient redisClient) {
        return RedisChatMemoryRepository.builder()
                .jedisClient(redisClient)
                .indexName("my-chat-index")
                .keyPrefix("my-chat:")
                .timeToLive(Duration.ofHours(24))
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, SpringAiProperties springAiProperties) {
        return MessageWindowChatMemory.builder()
                                      .chatMemoryRepository(chatMemoryRepository)
                                      .maxMessages(springAiProperties.maxChatMessages())
                                      .build();
    }
}
