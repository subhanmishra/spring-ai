package com.example.subhanmishra.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    public ChatService(ChatClient chatClient, ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatClient = chatClient;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
    }

    public String generate(String prompt, String conversationId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    public Flux<String> generateStream(String prompt, String conversationId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    public List<String> getAllConversationIds() {
        return this.chatMemoryRepository.findConversationIds();
    }

    public String clearMemory() {
        this.chatMemoryRepository.findConversationIds().forEach(chatMemory::clear);
        return "Chat memory cleared.";
    }
}
