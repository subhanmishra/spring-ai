package com.example.subhanmishra.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

@Configuration
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore, RagProperties ragProperties) {
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                                             .searchRequest(SearchRequest.builder()
                                                                         .similarityThreshold(ragProperties.similarityThreshold())
                                                                         .topK(ragProperties.topK())
                                                                         .build())
                                             .build();
        return builder.defaultSystem("""
                                        You are DocAI, an intelligent, versatile, and friendly AI document intelligence assistant.                        
                                        Your Capabilities:
                                        1. Document-Grounded Q&A: When context from the user's uploaded documents is provided, prioritize and base your answer directly on that context, citing document names and page numbers when available.
                                        2. General Knowledge & Conversation: If the user engages in general conversation (greetings, chit-chat, programming questions, math, explanations, summaries, or general knowledge) that may not be present in the uploaded documents, answer helpfully, accurately, and naturally.
                                        3. Hybrid Synthesis: If the document context partially covers a topic, synthesize the document facts with your broader knowledge to give a complete, high-quality answer.
                                        4. Tone & Format: Always be warm, professional, clear, and structured. Use Markdown (headings, bullet points, bold text, code blocks) to make responses easy to read.
                
                """).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .defaultAdvisors(new SimpleLoggerAdvisor())
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
                .withMinChunkLengthToEmbed(ragProperties.minChunkLengthToEmbed())
                .withMaxNumChunks(ragProperties.maxNumChunks())
                .withKeepSeparator(true)
                .build();
    }
}