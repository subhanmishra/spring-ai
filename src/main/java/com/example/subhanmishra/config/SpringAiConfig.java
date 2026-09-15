package com.example.subhanmishra.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

@Configuration
public class SpringAiConfig {

    /**
     * Mirrors {@code QuestionAnswerAdvisor}'s own default template, with the citation rule restated
     * immediately after the context.
     *
     * <p>The rule is already in the system prompt, but llama3.2 ignored it there: it sits in item 1 of a
     * four-item capability list, thousands of tokens away from the passages it refers to, and a 3B model
     * does not reliably carry an instruction that far. With the headers present and correctly attached in
     * the prompt, the model still answered "According to the reference documentation" and cited nothing.
     * Restating the rule adjacent to the data is what makes it stick.
     *
     * <p>The default template's closing line ("not prior knowledge ... inform the user that you can't
     * answer") is deliberately softened here, because it contradicts the system prompt's Hybrid Synthesis
     * and General Knowledge capabilities - the stock wording would forbid answers this assistant is
     * explicitly meant to give.
     */
    private static final PromptTemplate QA_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            Context information is below, surrounded by ---------------------

            ---------------------
            {question_answer_context}
            ---------------------

            Each passage above begins with its source on its own line, in the form
            [filename, p. N], or [filename] when that source has no page numbers.

            When you use a passage, cite it inline as (filename, p. N) using the values from
            that passage's own source line. Do not cite a filename or page number that does
            not appear in a source line above. Prefer the context over prior knowledge; if
            you go beyond it, say which part is not from the documents.
            """);

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore, RagProperties ragProperties) {
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                                             .searchRequest(SearchRequest.builder()
                                                                         .similarityThreshold(ragProperties.similarityThreshold())
                                                                         .topK(ragProperties.topK())
                                                                         .build())
                                             .promptTemplate(QA_PROMPT_TEMPLATE)
                                             .build();
        return builder.defaultSystem("""
                                        You are DocAI, an intelligent, versatile, and friendly AI document intelligence assistant.
                                        Your Capabilities:
                                        1. Document-Grounded Q&A: When context from the user's uploaded documents is provided, prioritize and base your answer directly on that context, citing document names and page numbers when available. Each retrieved passage begins with its source on its own line, in the form [filename, p. N] (or [filename] when the source has no pages). Use those values verbatim when you cite, and never cite a page number that does not appear in such a line.
                                        2. General Knowledge & Conversation: If the user engages in general conversation (greetings, chit-chat, programming questions, math, explanations, summaries, or general knowledge) that may not be present in the uploaded documents, answer helpfully, accurately, and naturally.
                                        3. Hybrid Synthesis: If the document context partially covers a topic, synthesize the document facts with your broader knowledge to give a complete, high-quality answer.
                                        4. Tone & Format: Always be warm, professional, clear, and structured. Use Markdown (headings, bullet points, bold text, code blocks) to make responses easy to read.

                """).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
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

    @Bean
    public TokenTextSplitter tokenTextSplitter(RagProperties ragProperties) {
        return TokenTextSplitter.builder()
                .withChunkSize(ragProperties.chunkSize())
                .withMinChunkSizeChars(ragProperties.minChunkSizeChars())
                // Deliberately 1, not app.rag.min-chunk-length-to-embed. TokenTextSplitter enforces that
                // floor by DISCARDING a short piece, and the short pieces are ones it manufactures by
                // cutting an over-budget chunk - which silently deleted real content. The floor is applied
                // in DocumentParserService instead, by merging a short piece into the one before it.
                .withMinChunkLengthToEmbed(1)
                .withMaxNumChunks(ragProperties.maxNumChunks())
                .withKeepSeparator(true)
                .build();
    }
}