package com.example.subhanmishra.service;

import com.example.subhanmishra.config.SpringAiProperties;
import com.example.subhanmishra.dto.ChatMessageDto;
import com.example.subhanmishra.dto.ConversationDto;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.service.eval.CitationResolver;
import com.example.subhanmishra.service.eval.CitationResolver.Resolution;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SpringAiProperties springAiProperties;
    private final OnlineEvalService onlineEvalService;

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       ChatMemoryRepository chatMemoryRepository,
                       SpringAiProperties springAiProperties,
                       OnlineEvalService onlineEvalService) {
        this.chatClient = chatClient;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
        this.springAiProperties = springAiProperties;
        this.onlineEvalService = onlineEvalService;
    }

    /**
     * Answers a prompt, and scores the turn on the way out.
     *
     * <p>Takes the {@code ChatResponse} rather than {@code content()} so the retrieved documents can be
     * read back. {@code QuestionAnswerAdvisor.after} copies the chunks it retrieved into the response
     * metadata under {@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS}, which is the only way to see
     * the context an answer was actually built on without re-running the search - and a second search
     * would be a different search, since it would not share this one's filters or timing.
     *
     * <p>The answer is passed through {@link CitationResolver} before it is either returned or scored.
     * That rewrites a section number the model wrote where a page belongs - "(…, p. 5.3)" - into the
     * page of the retrieved chunk whose heading it names, which is the difference between a citation a
     * reader can follow and one they cannot. Scoring the resolved text rather than the raw text is
     * deliberate: it is what the caller received.
     *
     * <p>The evaluation call returns immediately: deterministic scoring is a few regex passes, and any
     * LLM judging is handed to a virtual thread. Nothing about it is on this method's critical path.
     */
    public String generate(String prompt, String conversationId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatResponse();

        List<Document> retrieved = retrievedDocuments(chatResponse);
        Resolution resolution = CitationResolver.resolve(answerOf(chatResponse), retrieved);
        onlineEvalService.evaluate(prompt, resolution, retrieved);
        return resolution.answer();
    }

    /**
     * Streams an answer, scoring the turn once the stream completes.
     *
     * <p>The accumulation is unavoidable rather than lazy design. {@code BaseAdvisor.adviseStream} calls
     * the advisor's {@code after} only on the chunk carrying a finish reason, so the retrieved documents
     * arrive attached to the <em>final</em> response rather than to an aggregate, and the answer text
     * exists only as the concatenation of every chunk that came before it. Both halves therefore have
     * to be collected as they go past.
     *
     * <p>The evaluation hangs off {@code doOnComplete}, so it runs after the subscriber has seen the
     * last element. A cancelled or failed stream is not scored at all: a half-delivered answer is not
     * an answer, and judging one would report a truncation as a quality problem.
     *
     * <p><strong>The streamed text is not citation-resolved, unlike {@link #generate}'s.</strong> Only
     * the accumulated answer is, and only for scoring, so the two paths report the same numbers while a
     * streaming caller still sees whatever the model wrote - "(…, p. 5.3)" and all. Repairing the
     * stream itself would mean withholding elements until a citation could not still be split across a
     * chunk boundary ("p. 5." then "3)"), which is to say buffering the answer, which is to say not
     * streaming it. Between an unrepaired citation and a streaming endpoint that does not stream, the
     * former is the smaller defect - and the blocking endpoint, which every current caller uses, is
     * unaffected.
     */
    public Flux<String> generateStream(String prompt, String conversationId) {
        StringBuilder answer = new StringBuilder();
        AtomicReference<List<Document>> retrieved = new AtomicReference<>(List.of());

        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatResponse()
                .map(chatResponse -> {
                    List<Document> documents = retrievedDocuments(chatResponse);
                    if (!documents.isEmpty()) {
                        retrieved.set(documents);
                    }
                    String text = answerOf(chatResponse);
                    answer.append(text);
                    return text;
                })
                .doOnComplete(() -> onlineEvalService.evaluate(
                        prompt, CitationResolver.resolve(answer.toString(), retrieved.get()), retrieved.get()));
    }

    private static String answerOf(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    /**
     * The chunks the advisor retrieved for this turn, or empty when the turn was not grounded - which
     * is normal, since the assistant also answers general conversation with no retrieval behind it.
     */
    @SuppressWarnings("unchecked")
    private static List<Document> retrievedDocuments(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return List.of();
        }
        Object documents = chatResponse.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
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
