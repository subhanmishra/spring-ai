package com.example.subhanmishra;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;

    @Autowired
    public ChatController(ChatClient.Builder builder, ChatMemory memory) {
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @GetMapping("/generate")
    public String generate(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @GetMapping("/generateStream")
    public Flux<String> generateStream(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt) {

        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}
