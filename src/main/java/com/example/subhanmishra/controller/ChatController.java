package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.ChatRequestDto;
import com.example.subhanmishra.dto.ConversationDto;
import com.example.subhanmishra.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate a chat response",
            description = "Sends a prompt to the AI and gets a single, non-streaming response. The conversation ID used " +
                    "(newly generated if none was supplied) is returned in the X-Conversation-Id header so it can be " +
                    "passed back in on subsequent calls to continue the same conversation. The prompt travels in the " +
                    "request body rather than a query parameter so it stays out of access logs, browser history and " +
                    "proxy logs, and is not bounded by URL length limits.")
    public ResponseEntity<String> generate(@Valid @RequestBody ChatRequestDto request) {
        String resolvedConversationId = resolveConversationId(request.conversationId());
        String response = chatService.generate(request.prompt(), resolvedConversationId);
        return ResponseEntity.ok()
                .header("X-Conversation-Id", resolvedConversationId)
                .body(response);
    }

    @PostMapping(value = "/generateStream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/event-stream")
    @Operation(summary = "Generate a streaming chat response",
            description = "Sends a prompt to the AI and gets a streaming response, suitable for UI updates. The " +
                    "conversation ID used (newly generated if none was supplied) is returned in the X-Conversation-Id " +
                    "header so it can be passed back in on subsequent calls to continue the same conversation. Note " +
                    "that because this is a POST, a browser client cannot consume it with the native EventSource API, " +
                    "which only issues GET requests - use fetch with a ReadableStream instead.")
    public ResponseEntity<Flux<String>> generateStream(@Valid @RequestBody ChatRequestDto request) {
        String resolvedConversationId = resolveConversationId(request.conversationId());
        Flux<String> stream = chatService.generateStream(request.prompt(), resolvedConversationId);
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

    @GetMapping("/conversations/{conversationId}")
    @Operation(summary = "Read back one conversation",
            description = "Returns the messages stored for a conversation, oldest first, so a client can "
                    + "reload a chat it started earlier. Only the most recent messages survive: chat memory "
                    + "trims to app.ai.max-chat-messages when it writes, so older turns are already gone from "
                    + "Redis and cannot be recovered. The response reports that limit alongside the count, so "
                    + "a short conversation can be told apart from a truncated one. 404 if nothing is stored "
                    + "under the id - which is also how an already-cleared conversation reads.")
    public ResponseEntity<ConversationDto> getConversation(@PathVariable String conversationId) {
        return ResponseEntity.ok(chatService.getConversation(conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "Delete one conversation",
            description = "Drops a single conversation from chat memory, leaving every other conversation "
                    + "intact. Idempotent: deleting an unknown or already-deleted conversation also returns "
                    + "204 rather than 404.")
    public ResponseEntity<Void> clearConversation(@PathVariable String conversationId) {
        chatService.clearConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/conversations")
    @Operation(summary = "Clear all chat memory",
            description = "Clears all stored chat conversations from memory. To drop just one, delete it by "
                    + "id instead.")
    public String clearMemory() {
        return chatService.clearMemory();
    }

    /**
     * The conversation id to use for this turn, generating one when the caller supplied none.
     *
     * <p>This lives in the controller on purpose: {@code ChatService} does not generate ids, so that
     * every id a caller sees is one the controller also returned in {@code X-Conversation-Id}.
     */
    private String resolveConversationId(String suppliedConversationId) {
        return suppliedConversationId == null || suppliedConversationId.isBlank()
                ? UUID.randomUUID().toString()
                : suppliedConversationId;
    }
}