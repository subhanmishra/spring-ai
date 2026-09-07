package com.example.subhanmishra.controller;

import com.example.subhanmishra.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/ai")
@Tag(name = "Chat API", description = "Endpoints for interacting with the AI chat functionality")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/generate")
    @Operation(summary = "Generate a chat response",
            description = "Sends a prompt to the AI and gets a single, non-streaming response.")
    public String generate(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt,
                           @RequestParam(value = "conversationId", required = false, defaultValue = "") String conversationId) {
        return chatService.generate(prompt, conversationId);
    }

    @GetMapping(value = "/generateStream", produces = "text/event-stream")
    @Operation(summary = "Generate a streaming chat response",
            description = "Sends a prompt to the AI and gets a streaming response, suitable for UI updates.")
    public Flux<String> generateStream(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt,
                                       @RequestParam(value = "conversationId", required = false, defaultValue = "") String conversationId) {
        return chatService.generateStream(prompt, conversationId);
    }

    @GetMapping("/conversations")
    @Operation(summary = "Get all conversation IDs",
            description = "Retrieves a list of all active conversation IDs stored in memory.")
    public List<String> getAllConversationIds() {
        return chatService.getAllConversationIds();
    }

    @DeleteMapping("/conversations")
    @Operation(summary = "Clear all chat memory",
            description = "Clears all stored chat conversations from memory.")
    public String clearMemory() {
        return chatService.clearMemory();
    }
}