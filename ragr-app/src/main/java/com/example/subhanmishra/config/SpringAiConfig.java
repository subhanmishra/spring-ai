package com.example.subhanmishra.config;

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
     * Spring AI's default RAG template, changed in three ways. {@link PooledQuestionAnswerAdvisor}
     * renders it.
     * <ul>
     *   <li><b>The citation rule is repeated right after the passages.</b> In the system prompt alone it
     *       sat thousands of tokens away, and the model ignored it.</li>
     *   <li><b>The closing line is softened.</b> The default ("not prior knowledge ... say you can't
     *       answer") forbids the general-knowledge answers the system prompt allows.</li>
     *   <li><b>The passages are said to come from the library, not the user.</b> They are added to the
     *       <em>user</em> message, so the model thanked the user for "providing the context" - and, with
     *       nothing retrieved, asked for some. The no-preamble rule sits here too, beside the passages.</li>
     * </ul>
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

            Each passage above begins with its source on its own line, in the form
            [filename, p. N], or [filename] when that source has no page numbers.

            When you use a passage, cite it inline as (filename, p. N) using the values from
            that passage's own source line. Do not cite a filename or page number that does
            not appear in a source line above.

            N is always a whole number copied from a source line. A heading inside a passage,
            such as "5.3. Endpoints" or "5.5.1. Configure a Logger", is a section number and
            never a page - do not write (filename, p. 5.3). Cite the page from the source line
            of the passage the heading appears in.

            Prefer the passages over prior knowledge. If part of the answer goes beyond them,
            say so at that point in the answer, not as an opening disclaimer.

            Start directly with the answer: no greeting, no restating the question, no
            acknowledgement, and no closing offer of further help.
            """);

    /**
     * Indented 24 spaces inside the text block on purpose: that is what the model has always been sent.
     * Re-indenting would change every prompt, and with it every measurement so far.
     */
    private static final String SYSTEM_PROMPT = """
                                You are DocAI, an intelligent, versatile AI document intelligence assistant.
                                Your Capabilities:
                                1. Document-Grounded Q&A: When context from the user's uploaded documents is provided, prioritize and base your answer directly on that context, citing document names and page numbers when available. Each retrieved passage begins with its source on its own line, in the form [filename, p. N] (or [filename] when the source has no pages). Use those values verbatim when you cite, and never cite a page number that does not appear in such a line. A page number is always a whole number; a dotted heading number inside a passage, such as "5.3", is a section and must never be cited as a page.
                                2. General Knowledge & Conversation: If the user engages in general conversation (greetings, chit-chat, programming questions, math, explanations, summaries, or general knowledge) that may not be present in the uploaded documents, answer helpfully, accurately, and naturally.
                                3. Hybrid Synthesis: If the document context partially covers a topic, synthesize the document facts with your broader knowledge to give a complete, high-quality answer.
                                4. Tone & Format: Be polite but direct - lead with the answer. No greetings, no restating the question, no thanking the user, no closing offers of further help. Use Markdown (headings, bullet points, bold text, code blocks) to make responses easy to read.

        """;

    /**
     * A short hash of everything the model is told besides the conversation, sent with every turn so
     * evaluation can split a metric at a prompt change. It includes the passage marker: layout changes
     * answers as much as wording does.
     */
    public static final String PROMPT_VERSION = promptVersion(SYSTEM_PROMPT + QA_PROMPT_TEMPLATE.getTemplate()
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
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore, RagProperties ragProperties) {
        var qaAdvisor = new PooledQuestionAnswerAdvisor(vectorStore, QA_PROMPT_TEMPLATE, ragProperties);
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
