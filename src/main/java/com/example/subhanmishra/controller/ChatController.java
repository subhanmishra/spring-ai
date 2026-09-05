package com.example.subhanmishra.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    @Autowired
    public ChatController(ChatClient.Builder builder, ChatMemory chatMemory,ChatMemoryRepository chatMemoryRepository, VectorStore vectorStore) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.7d)
                        .topK(6)
                        .build())
                .build();
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(qaAdvisor)
                .build();
    }

    @GetMapping("/generate")
    public String generate(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @GetMapping(value = "/generateStream", produces = "text/event-stream")
    public Flux<String> generateStream(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt) {

        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }

    @GetMapping("/chat/memory")
    public List<String> getAllConversationIds() {
        return this.chatMemoryRepository.findConversationIds();
    }

    @DeleteMapping("/chat/memory")
    public String clearMemory() {
        this.chatMemoryRepository.findConversationIds().forEach(chatMemory::clear);
        //this.chatMemory.clear(ChatMemory.DEFAULT_CONVERSATION_ID);
        return "Chat memory cleared.";
    }
}
