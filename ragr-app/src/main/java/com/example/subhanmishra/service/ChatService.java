package com.example.subhanmishra.service;

import com.example.subhanmishra.config.PooledQuestionAnswerAdvisor;
import com.example.subhanmishra.config.SpringAiProperties;
import com.example.subhanmishra.dto.ChatAnswerDto;
import com.example.subhanmishra.dto.ChatDoneDto;
import com.example.subhanmishra.dto.ChatMessageDto;
import com.example.subhanmishra.dto.ChatSourcesDto;
import com.example.subhanmishra.dto.CitationDto;
import com.example.subhanmishra.dto.ConversationDto;
import com.example.subhanmishra.dto.SourceDto;
import com.example.subhanmishra.dto.UsageDto;
import com.example.subhanmishra.event.ChatTurnCompleted.Timings;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.exception.ResourceNotFoundException;
import com.example.subhanmishra.chunk.ChunkMetadata;
import com.example.subhanmishra.citation.AnswerCitations;
import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationParser;
import com.example.subhanmishra.citation.CitationResolver;
import com.example.subhanmishra.citation.CitationResolver.Repair;
import com.example.subhanmishra.citation.CitationResolver.Resolution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SpringAiProperties springAiProperties;
    private final ChatTurnPublisher chatTurnPublisher;

    /**
     * Generations running right now, published as {@code rag.chat.generations.active}.
     *
     * <p>ragr-eval reads it before every judge call and waits while it is above zero: the judges and chat
     * share one Ollama runner that serves one request at a time, so a judge call would make the user
     * wait. Counted from the start of retrieval to the last token.
     */
    private final AtomicInteger activeGenerations = new AtomicInteger();

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       ChatMemoryRepository chatMemoryRepository,
                       SpringAiProperties springAiProperties,
                       ChatTurnPublisher chatTurnPublisher,
                       MeterRegistry registry) {
        this.chatClient = chatClient;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemory = chatMemory;
        this.springAiProperties = springAiProperties;
        this.chatTurnPublisher = chatTurnPublisher;
        Gauge.builder("rag.chat.generations.active", activeGenerations, AtomicInteger::get)
             .description("Chat generations in flight; ragr-eval holds its judges while this is above zero")
             .register(registry);
    }

    /**
     * Answers a prompt, and publishes the turn for evaluation on the way out.
     *
     * <ol>
     *   <li>Asks the model, reading the whole {@code ChatClientResponse} rather than just its text: the
     *       chunks the answer was built on are in its context, under
     *       {@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS}. Searching again would not be the same
     *       search.</li>
     *   <li>{@link CitationResolver} turns section numbers cited as pages back into pages.</li>
     *   <li>{@link ChatTurnPublisher} hands the turn to Kafka and returns at once, so evaluation is never
     *       on this path - not even when Kafka is down.</li>
     *   <li>{@link AnswerCitations} takes the inline citations out of the text; the caller gets them as
     *       {@code citations}.</li>
     * </ol>
     */
    public ChatAnswerDto generate(String prompt, String conversationId, TurnOrigin origin) {
        long started = System.nanoTime();
        UUID turnId = UUID.randomUUID();
        ChatClientResponse response;
        activeGenerations.incrementAndGet();
        try {
            response = chatClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .chatClientResponse();
        } finally {
            activeGenerations.decrementAndGet();
        }

        List<Document> retrieved = retrievedDocuments(response);
        Resolution resolution = CitationResolver.resolve(answerOf(response.chatResponse()), retrieved);
        Timings timings = new Timings(retrievalMillis(response), null, millisSince(started), false);
        chatTurnPublisher.publish(new ChatTurnPublisher.Turn(turnId, origin, conversationId, prompt, resolution,
                                                             retrieved, excludedDocuments(response),
                                                             response.chatResponse(), timings));

        AnswerCitations.Context context = AnswerCitations.Context.of(retrieved);
        List<CitationDto> citations = citations(resolution, retrieved, context);
        return new ChatAnswerDto(turnId,
                                 AnswerCitations.strip(resolution.answer(), context),
                                 !retrieved.isEmpty(),
                                 sources(retrieved, citations),
                                 citations,
                                 usage(response.chatResponse(), started));
    }

    /**
     * Streams an answer as server-sent events, publishing the turn once the stream completes.
     *
     * <p>The text arrives as unnamed events, with citations removed as it goes by
     * {@link AnswerCitations.StreamingStripper}. Then come {@code sources}, and {@code done} with the
     * citations and usage.
     *
     * <p>The retrieved chunks are read from the advisor context, which every streamed chunk carries - the
     * response metadata has them only on the last one. Citations can be recognised mid-stream only because
     * of this: "(manual.docx)" is a citation only if that file was retrieved.
     *
     * <p>The whole answer is also collected, for evaluation and the citation report, which run once the
     * stream completes. A cancelled or failed stream is not published: half an answer is not an answer.
     */
    public Flux<ServerSentEvent<?>> generateStream(String prompt, String conversationId, TurnOrigin origin) {
        long started = System.nanoTime();
        UUID turnId = UUID.randomUUID();
        StringBuilder answer = new StringBuilder();
        AtomicReference<List<Document>> retrieved = new AtomicReference<>(List.of());
        AtomicReference<List<Document>> excluded = new AtomicReference<>(List.of());
        AtomicReference<@Nullable Long> retrievalMillis = new AtomicReference<>();
        AtomicReference<@Nullable Long> firstTokenMillis = new AtomicReference<>();
        AtomicReference<AnswerCitations.Context> context = new AtomicReference<>(AnswerCitations.Context.EMPTY);
        AtomicReference<@Nullable ChatResponse> last = new AtomicReference<>();
        AnswerCitations.StreamingStripper stripper = new AnswerCitations.StreamingStripper();

        Flux<ServerSentEvent<?>> tokens = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatClientResponse()
                .doOnSubscribe(subscription -> activeGenerations.incrementAndGet())
                .doFinally(signal -> activeGenerations.decrementAndGet())
                .concatMap(response -> {
                    List<Document> documents = retrievedDocuments(response);
                    // The same list arrives on every chunk; the context is built from it once.
                    if (!documents.isEmpty() && documents != retrieved.get()) {
                        retrieved.set(documents);
                        context.set(AnswerCitations.Context.of(documents));
                    }
                    if (retrievalMillis.get() == null) {
                        retrievalMillis.set(retrievalMillis(response));
                        excluded.set(excludedDocuments(response));
                    }
                    if (response.chatResponse() != null) {
                        last.set(response.chatResponse());
                    }
                    String text = answerOf(response.chatResponse());
                    if (!text.isEmpty() && firstTokenMillis.get() == null) {
                        firstTokenMillis.set(millisSince(started));
                    }
                    answer.append(text);
                    return token(stripper.accept(text, context.get()));
                });

        Flux<ServerSentEvent<?>> closing = Flux.defer(() -> {
            List<Document> documents = retrieved.get();
            Resolution resolution = CitationResolver.resolve(answer.toString(), documents);
            Timings timings = new Timings(retrievalMillis.get(), firstTokenMillis.get(), millisSince(started), true);
            chatTurnPublisher.publish(new ChatTurnPublisher.Turn(turnId, origin, conversationId, prompt, resolution,
                                                                 documents, excluded.get(), last.get(), timings));

            List<CitationDto> citations = citations(resolution, documents, context.get());
            return token(stripper.finish(context.get())).concatWith(Flux.just(
                    ServerSentEvent.builder(new ChatSourcesDto(!documents.isEmpty(), sources(documents, citations)))
                                   .event("sources").build(),
                    ServerSentEvent.builder(new ChatDoneDto(turnId, citations, usage(last.get(), started)))
                                   .event("done").build()));
        });

        return tokens.concatWith(closing);
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    /** The rest of the candidate pool, which {@link PooledQuestionAnswerAdvisor} kept out of the prompt. */
    @SuppressWarnings("unchecked")
    private static List<Document> excludedDocuments(ChatClientResponse response) {
        Object documents = response.context().get(PooledQuestionAnswerAdvisor.EXCLUDED_DOCUMENTS);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private static @Nullable Long retrievalMillis(ChatClientResponse response) {
        return response.context().get(PooledQuestionAnswerAdvisor.RETRIEVAL_MILLIS) instanceof Long millis
                ? millis
                : null;
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
     * The chunks in this turn's prompt, or empty when nothing was retrieved - normal for a general
     * question.
     */
    @SuppressWarnings("unchecked")
    private static List<Document> retrievedDocuments(ChatClientResponse response) {
        Object documents = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
    }

    /**
     * One entry per distinct source cited, checked by the same rules evaluation uses. Bare section
     * references such as "(5.3)" come last, as REPAIRED to their heading's page; evaluation does not count
     * those.
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
                                      ChunkMetadata.asString(document.getMetadata().get(ChunkMetadata.DOCUMENT_ID)),
                                      key.fileName(),
                                      key.page(),
                                      ChunkMetadata.asString(document.getMetadata().get(ChunkMetadata.BLOCK_TYPE)),
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
     * Where a chunk comes from: its citation header, which is what the model saw, or else its metadata.
     */
    private record SourceKey(@Nullable String fileName, @Nullable Integer page) {

        static SourceKey of(Document document) {
            Citation header = CitationParser.parseHeader(document.getText());
            if (header != null) {
                return new SourceKey(header.fileName(), header.pageNumber());
            }
            return new SourceKey(ChunkMetadata.asString(document.getMetadata().get(ChunkMetadata.FILE_NAME)),
                                 ChunkMetadata.asInteger(document.getMetadata().get(ChunkMetadata.PAGE_NUMBER)));
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
     * <p>Through the repository, not {@link ChatMemory#get}: the repository says what is stored,
     * {@code ChatMemory} what the next turn would be given. They agree today, but would not if the
     * memory ever trimmed on read.
     *
     * @throws ResourceNotFoundException if nothing is stored under the id - also how a deleted
     *                                   conversation looks, since Redis keeps no record of deletions
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
     * Deletes one conversation. An unknown id is not an error, so deleting twice is safe.
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
