package com.example.subhanmishra.service;

import com.example.subhanmishra.config.SpringAiProperties;
import com.example.subhanmishra.dto.ChatAnswerDto;
import com.example.subhanmishra.dto.ChatDoneDto;
import com.example.subhanmishra.dto.ChatMessageDto;
import com.example.subhanmishra.dto.ChatSourcesDto;
import com.example.subhanmishra.dto.CitationDto;
import com.example.subhanmishra.dto.ConversationDto;
import com.example.subhanmishra.dto.SourceDto;
import com.example.subhanmishra.dto.UsageDto;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.service.eval.AnswerCitations;
import com.example.subhanmishra.service.eval.Citation;
import com.example.subhanmishra.service.eval.CitationParser;
import com.example.subhanmishra.service.eval.CitationResolver;
import com.example.subhanmishra.service.eval.CitationResolver.Repair;
import com.example.subhanmishra.service.eval.CitationResolver.Resolution;
import com.example.subhanmishra.service.parse.ChunkMetadata;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * <p>Takes the {@code ChatClientResponse} rather than {@code content()} so the retrieved documents
     * can be read back. {@code QuestionAnswerAdvisor} puts the chunks it retrieved into the advisor
     * context under {@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS}, which is the only way to see the
     * context an answer was actually built on without re-running the search - and a second search would
     * be a different search, since it would not share this one's filters or timing.
     *
     * <p>The answer is passed through {@link CitationResolver} before it is scored or reported. That
     * rewrites a section number the model wrote where a page belongs - "(…, p. 5.3)" - into the page of
     * the retrieved chunk whose heading it names. The model still cites inline, and the evaluation scores
     * those inline citations; the caller receives them as {@code citations} instead, with the answer text
     * stripped of them by {@link AnswerCitations}.
     *
     * <p>The evaluation call returns immediately: deterministic scoring is a few regex passes, and any
     * LLM judging is handed to a virtual thread. Nothing about it is on this method's critical path.
     */
    public ChatAnswerDto generate(String prompt, String conversationId) {
        long started = System.nanoTime();
        ChatClientResponse response = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        List<Document> retrieved = retrievedDocuments(response);
        Resolution resolution = CitationResolver.resolve(answerOf(response.chatResponse()), retrieved);
        onlineEvalService.evaluate(prompt, resolution, retrieved);

        AnswerCitations.Context context = AnswerCitations.Context.of(retrieved);
        List<CitationDto> citations = citations(resolution, retrieved, context);
        return new ChatAnswerDto(AnswerCitations.strip(resolution.answer(), context),
                                 !retrieved.isEmpty(),
                                 sources(retrieved, citations),
                                 citations,
                                 usage(response.chatResponse(), started));
    }

    /**
     * Streams an answer as server-sent events, scoring the turn once the stream completes.
     *
     * <p>The answer text arrives as unnamed events, with its inline citations removed as it goes by
     * {@link AnswerCitations.StreamingStripper} - which holds back a span only from its opening bracket
     * until its closing one, so the stream still streams. Two named events follow the last token:
     * {@code sources}, then {@code done} carrying the citations and usage.
     *
     * <p>The retrieved documents are read from the advisor context, which
     * {@code ChatModelStreamAdvisor} copies onto every chunk - not from the response metadata, which
     * {@code QuestionAnswerAdvisor.after} fills only on the final chunk. That is what lets citations be
     * recognised mid-stream: a pageless one, "(manual.docx)", is a citation only if that file was
     * retrieved.
     *
     * <p>The answer is still accumulated whole, because the evaluation and the citation report need the
     * text as the model wrote it. They run only once the tokens have all been delivered: a cancelled or
     * failed stream is neither scored nor reported, since a half-delivered answer is not an answer, and
     * judging one would report a truncation as a quality problem.
     */
    public Flux<ServerSentEvent<?>> generateStream(String prompt, String conversationId) {
        long started = System.nanoTime();
        StringBuilder answer = new StringBuilder();
        AtomicReference<List<Document>> retrieved = new AtomicReference<>(List.of());
        AtomicReference<AnswerCitations.Context> context = new AtomicReference<>(AnswerCitations.Context.EMPTY);
        AtomicReference<@Nullable ChatResponse> last = new AtomicReference<>();
        AnswerCitations.StreamingStripper stripper = new AnswerCitations.StreamingStripper();

        Flux<ServerSentEvent<?>> tokens = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatClientResponse()
                .concatMap(response -> {
                    List<Document> documents = retrievedDocuments(response);
                    // The same list arrives on every chunk; the context is built from it once.
                    if (!documents.isEmpty() && documents != retrieved.get()) {
                        retrieved.set(documents);
                        context.set(AnswerCitations.Context.of(documents));
                    }
                    if (response.chatResponse() != null) {
                        last.set(response.chatResponse());
                    }
                    String text = answerOf(response.chatResponse());
                    answer.append(text);
                    return token(stripper.accept(text, context.get()));
                });

        Flux<ServerSentEvent<?>> closing = Flux.defer(() -> {
            List<Document> documents = retrieved.get();
            Resolution resolution = CitationResolver.resolve(answer.toString(), documents);
            onlineEvalService.evaluate(prompt, resolution, documents);

            List<CitationDto> citations = citations(resolution, documents, context.get());
            return token(stripper.finish(context.get())).concatWith(Flux.just(
                    ServerSentEvent.builder(new ChatSourcesDto(!documents.isEmpty(), sources(documents, citations)))
                                   .event("sources").build(),
                    ServerSentEvent.builder(new ChatDoneDto(citations, usage(last.get(), started)))
                                   .event("done").build()));
        });

        return tokens.concatWith(closing);
    }

    private static Flux<ServerSentEvent<?>> token(String text) {
        return text.isEmpty() ? Flux.empty() : Flux.just(ServerSentEvent.builder(text).build());
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
    private static List<Document> retrievedDocuments(ChatClientResponse response) {
        Object documents = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
    }

    /**
     * The citations in the answer, one per distinct source cited, checked against the retrieved chunks
     * by the same rules the evaluation scores them with - followed by any bare section references,
     * "(5.3)", reported as REPAIRED against the page that heading sits on. Those come last and are not
     * counted by the evaluation, which scores file citations only.
     */
    private static List<CitationDto> citations(Resolution resolution, List<Document> retrieved,
                                               AnswerCitations.Context context) {
        List<Citation> available = CitationParser.availableCitations(retrieved);
        Set<String> availableNames = context.fileNames();
        List<SourceKey> sourceKeys = retrieved.stream().map(SourceKey::of).toList();

        List<CitationDto> citations = new ArrayList<>();
        for (Citation citation : CitationParser.parseAnswerCandidates(resolution.answer())) {
            if (!AnswerCitations.isCitation(citation, availableNames)) {
                continue;
            }
            if (!AnswerCitations.isSupported(citation, available, availableNames)) {
                citations.add(new CitationDto(citation.fileName(), citation.pageNumber(),
                                              CitationDto.Status.UNVERIFIED, null, citation.pageLabel()));
                continue;
            }
            Repair repair = resolution.repairOf(citation);
            citations.add(new CitationDto(citation.fileName(), citation.pageNumber(),
                                          repair != null ? CitationDto.Status.REPAIRED : CitationDto.Status.VERIFIED,
                                          firstSourceRef(citation, sourceKeys),
                                          repair != null ? repair.section() : null));
        }
        for (String section : AnswerCitations.bareSectionReferences(resolution.answer(), context)) {
            Citation source = context.sections().get(section);
            boolean alreadyCited = citations.stream().anyMatch(citation -> citation.status() != CitationDto.Status.UNVERIFIED
                    && source.fileName().equalsIgnoreCase(citation.fileName())
                    && source.pageNumber().equals(citation.page()));
            if (!alreadyCited) {
                citations.add(new CitationDto(source.fileName(), source.pageNumber(), CitationDto.Status.REPAIRED,
                                              firstSourceRef(source, sourceKeys), section));
            }
        }
        return List.copyOf(citations);
    }

    private static @Nullable Integer firstSourceRef(Citation citation, List<SourceKey> sourceKeys) {
        for (int i = 0; i < sourceKeys.size(); i++) {
            if (sourceKeys.get(i).citedBy(citation)) {
                return i + 1;
            }
        }
        return null;
    }

    /** Every retrieved chunk, in rank order, marked with whether any verified citation points at it. */
    private static List<SourceDto> sources(List<Document> retrieved, List<CitationDto> citations) {
        List<SourceDto> sources = new ArrayList<>(retrieved.size());
        for (int i = 0; i < retrieved.size(); i++) {
            Document document = retrieved.get(i);
            SourceKey key = SourceKey.of(document);
            boolean cited = citations.stream().anyMatch(citation -> citation.status() != CitationDto.Status.UNVERIFIED
                    && key.citedBy(new Citation(citation.fileName(), citation.page())));
            sources.add(new SourceDto(i + 1,
                                      RetrievalDiagnosticsService.asString(document.getMetadata().get("documentId")),
                                      key.fileName(),
                                      key.page(),
                                      RetrievalDiagnosticsService.asString(document.getMetadata().get(ChunkMetadata.BLOCK_TYPE)),
                                      document.getScore(),
                                      CitationParser.stripHeader(document.getText()),
                                      cited));
        }
        return List.copyOf(sources);
    }

    private static UsageDto usage(@Nullable ChatResponse chatResponse, long startedNanos) {
        long latencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return new UsageDto(null, null, null, latencyMillis);
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata.getUsage();
        return new UsageDto(metadata.getModel(),
                            usage != null ? usage.getPromptTokens() : null,
                            usage != null ? usage.getCompletionTokens() : null,
                            latencyMillis);
    }

    /**
     * Where a retrieved chunk says it comes from: its citation header when it has one - that is what the
     * model saw and cites - and its metadata otherwise.
     */
    private record SourceKey(@Nullable String fileName, @Nullable Integer page) {

        static SourceKey of(Document document) {
            Citation header = CitationParser.parseHeader(document.getText());
            if (header != null) {
                return new SourceKey(header.fileName(), header.pageNumber());
            }
            return new SourceKey(RetrievalDiagnosticsService.asString(document.getMetadata().get("fileName")),
                                 RetrievalDiagnosticsService.asInteger(document.getMetadata().get("pageNumber")));
        }

        /** A pageless citation names the whole file, so it points at every chunk of it. */
        boolean citedBy(Citation citation) {
            return fileName != null
                    && fileName.equalsIgnoreCase(citation.fileName())
                    && (citation.pageNumber() == null || citation.pageNumber().equals(page));
        }
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
