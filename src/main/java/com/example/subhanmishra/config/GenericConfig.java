package com.example.subhanmishra.config;

import com.example.subhanmishra.dto.DocumentMetadataDto;
import com.example.subhanmishra.entity.DocumentMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GenericConfig {

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT);

        // Custom mapping for DocumentMetadata to DocumentMetadataDto (Record)
        modelMapper.createTypeMap(DocumentMetadata.class, DocumentMetadataDto.class)
                .setConverter(context -> {
                    DocumentMetadata source = context.getSource();
                    // Assuming DocumentMetadataDto has a canonical constructor matching these fields
                    return new DocumentMetadataDto(
                            source.getId(),
                            source.getFilename(),
                            source.getContentType(),
                            source.getFileSize(),
                            source.getTotalPages(),
                            source.getTotalChunks(),
                            source.getStatus(),
                            source.getErrorMessage(),
                            source.getCreatedAt(),
                            source.getUpdatedAt()
                    );
                });

        return modelMapper;
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
                                        You are DocAI, an intelligent, versatile, and friendly AI document intelligence assistant.                        
                                        Your Capabilities:
                                        1. Document-Grounded Q&A: When context from the user's uploaded documents is provided, prioritize and base your answer directly on that context, citing document names and page numbers when available.
                                        2. General Knowledge & Conversation: If the user engages in general conversation (greetings, chit-chat, programming questions, math, explanations, summaries, or general knowledge) that may not be present in the uploaded documents, answer helpfully, accurately, and naturally.
                                        3. Hybrid Synthesis: If the document context partially covers a topic, synthesize the document facts with your broader knowledge to give a complete, high-quality answer.
                                        4. Tone & Format: Always be warm, professional, clear, and structured. Use Markdown (headings, bullet points, bold text, code blocks) to make responses easy to read.
                
                """).build();
    }

    @Bean
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }

    @Bean
    public OpenAPI openAPI() {

        return new OpenAPI().info(new Info().title("DocAI — AI Document Intelligence & RAG backend")
                .description("REST API for DocAI: Multi-format document ingestion, vector embeddings with PostgreSQL pgvector, and hybrid conversational Q&A with OpenAI.")
                .version("1.0.0")
                .contact(new Contact().name("Subhankar Mishra")
                        .email("subhan.mishra@gmail.com")
                        .url("https://subhanmishra.com")));


    }
}