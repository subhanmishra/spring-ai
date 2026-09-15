package com.example.subhanmishra.service;

import com.example.subhanmishra.config.SpringAiProperties;
import com.example.subhanmishra.dto.ChatMessageDto;
import com.example.subhanmishra.dto.ConversationDto;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SpringAiProperties springAiProperties;

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       ChatMemoryRepository chatMemoryRepository,
                       SpringAiProperties springAiProperties) {
        this.chatClient = chatClient;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
        this.springAiProperties = springAiProperties;
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

    /**
     * Reads back a stored conversation, oldest message first.
     *
     * <p>Reads through the repository rather than {@link ChatMemory#get}, because the two answer
     * different questions even though {@code MessageWindowChatMemory} currently answers them the same
     * way. The repository reports what is stored; {@code ChatMemory} reports what the next turn would
     * be given, and a memory implementation that windowed on read instead of on write would silently
     * truncate this endpoint.
     *
     * @throws ResourceNotFoundException if nothing is stored under that id - which is also what an
     *                                   already-cleared conversation looks like, since Redis keeps no
     *                                   tombstone to tell the two apart
     */
    public ConversationDto getConversation(String conversationId) {
        List<Message> messages = this.chatMemoryRepository.findByConversationId(conversationId);
        if (messages == null || messages.isEmpty()) {
            throw new ResourceNotFoundException("No conversation found with ID " + conversationId + ".");
        }
        return new ConversationDto(conversationId,
                                   messages.size(),
                                   springAiProperties.maxChatMessages(),
                                   messages.stream().map(ChatService::toDto).toList());
    }

    /**
     * Drops one conversation. Silent when the id is unknown, so that the endpoint calling this stays
     * idempotent - deleting an already-deleted conversation is not an error.
     */
    public void clearConversation(String conversationId) {
        this.chatMemory.clear(conversationId);
    }

    public String clearMemory() {
        this.chatMemoryRepository.findConversationIds().forEach(chatMemory::clear);
        return "Chat memory cleared.";
    }

    private static ChatMessageDto toDto(Message message) {
        MessageType type = message.getMessageType();
        return new ChatMessageDto(type != null ? type.getValue() : null, message.getText());
    }
}
