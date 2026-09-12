package com.example.subhanmishra.controller;

import com.example.subhanmishra.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

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
            description = "Sends a prompt to the AI and gets a single, non-streaming response. The conversation ID used " +
                    "(newly generated if none was supplied) is returned in the X-Conversation-Id header so it can be " +
                    "passed back in on subsequent calls to continue the same conversation.")
    public ResponseEntity<String> generate(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt,
                           @RequestParam(value = "conversationId", required = false, defaultValue = "") String conversationId) {
        String resolvedConversationId = conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
        String response = chatService.generate(prompt, resolvedConversationId);
        return ResponseEntity.ok()
                .header("X-Conversation-Id", resolvedConversationId)
                .body(response);
    }

    @GetMapping(value = "/generateStream", produces = "text/event-stream")
    @Operation(summary = "Generate a streaming chat response",
            description = "Sends a prompt to the AI and gets a streaming response, suitable for UI updates. The " +
                    "conversation ID used (newly generated if none was supplied) is returned in the X-Conversation-Id " +
                    "header so it can be passed back in on subsequent calls to continue the same conversation.")
    public ResponseEntity<Flux<String>> generateStream(@RequestParam(value = "prompt", defaultValue = "Tell me a joke") String prompt,
                                       @RequestParam(value = "conversationId", required = false, defaultValue = "") String conversationId) {
        String resolvedConversationId = conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
        Flux<String> stream = chatService.generateStream(prompt, resolvedConversationId);
        return ResponseEntity.ok()
                .header("X-Conversation-Id", resolvedConversationId)
                .body(stream);
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