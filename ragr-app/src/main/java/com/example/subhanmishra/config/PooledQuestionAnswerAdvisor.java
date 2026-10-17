package com.example.subhanmishra.config;

import com.example.subhanmishra.citation.CitationParser;
import com.example.subhanmishra.service.KeywordSearchService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring AI's {@code QuestionAnswerAdvisor}, fetching a bigger pool of chunks than it puts in the prompt
 * so evaluation can see what retrieval left out, and placing keyword matches among the vector chunks
 * ({@link #select}).
 *
 * <p>One vector query asks for {@code pool-size} chunks above {@code pool-floor}; top-k and the threshold
 * are applied here. Without a keyword match the prompt gets exactly the chunks the stock advisor would
 * choose, laid out by {@link #passages}.
 *
 * <p>Context keys:
 * <ul>
 *   <li>{@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS} - the chunks in the prompt. Kept under the
 *       stock key so {@code ChatService} reads them as it always has.</li>
 *   <li>{@link #EXCLUDED_DOCUMENTS} - the rest of the pool, in rank order after them.</li>
 *   <li>{@link #RETRIEVAL_MILLIS} - how long the vector query and keyword search took.</li>
 * </ul>
 *
 * <p>Copied rather than wrapping the stock advisor, whose constructor is package-private and search
 * request final. {@code before} and {@code after} follow it line for line, except how chunks are joined.
 */
public class PooledQuestionAnswerAdvisor implements BaseAdvisor {

    public static final String EXCLUDED_DOCUMENTS = "rag_excluded_documents";
    public static final String RETRIEVAL_MILLIS = "rag_retrieval_millis";

    /** Closes every passage in the prompt; see {@link #passages}. Part of {@code PROMPT_VERSION}. */
    public static final String PASSAGE_END = "(end of passage)";

    private final VectorStore vectorStore;
    private final PromptTemplate promptTemplate;
    private final CitationRules citationRules;
    private final KeywordSearchService keywordSearch;
    private final RagProperties properties;

    public PooledQuestionAnswerAdvisor(VectorStore vectorStore, KeywordSearchService keywordSearch,
                                       PromptTemplate promptTemplate, CitationRules citationRules,
                                       RagProperties properties) {
        this.citationRules = citationRules;
        this.vectorStore = vectorStore;
        this.keywordSearch = keywordSearch;
        this.promptTemplate = promptTemplate;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        UserMessage userMessage = request.prompt().getUserMessage();
        String question = Objects.requireNonNullElse(userMessage.getText(), "");
        SearchRequest search = SearchRequest.builder()
                                            .query(question)
                                            .topK(properties.poolSize())
                                            .similarityThreshold(properties.poolFloor())
                                            .build();
        long started = System.nanoTime();
        List<Document> pool = vectorStore.similaritySearch(search);
        List<Document> keyword = keywordSearch.search(question, null);
        long retrievalMillis = (System.nanoTime() - started) / 1_000_000;

        Selection selection = select(pool, keyword, properties.topK(), properties.similarityThreshold());
        List<Document> retrieved = selection.inContext();
        List<Document> excluded = selection.excluded();

        Map<String, Object> context = new HashMap<>(request.context());
        context.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, retrieved);
        context.put(EXCLUDED_DOCUMENTS, excluded);
        context.put(RETRIEVAL_MILLIS, retrievalMillis);

        String augmented = promptTemplate.render(Map.of("query", userMessage.getText(),
                                                        "question_answer_context", passages(retrieved),
                                                        "citation_rules", citationRules.forPassages(retrieved)));
        return request.mutate()
                      .prompt(request.prompt().augmentUserMessage(augmented))
                      .context(context)
                      .build();
    }

    /**
     * The template's {@code citation_rules}, in two forms.
     *
     * @param paged    for prompts where some passage has a page
     * @param pageless for prompts where none does - no page format is mentioned at all
     */
    public record CitationRules(String paged, String pageless) {

        String forPassages(List<Document> passages) {
            boolean anyPage = passages.stream()
                                      .map(passage -> CitationParser.parseHeader(passage.getText()))
                                      .anyMatch(header -> header != null && header.pageNumber() != null);
            return anyPage ? paged : pageless;
        }
    }

    /**
     * The chunks as the prompt shows them: each closed by {@link #PASSAGE_END}, with a blank line either
     * side of it, so the model never reads a source line as belonging to the passage above it. Always
     * "\n", never the platform line separator, so the prompt is the same on every OS.
     */
    static String passages(List<Document> documents) {
        return documents.stream()
                        .map(document -> document.getText() + "\n\n" + PASSAGE_END)
                        .collect(Collectors.joining("\n\n"));
    }

    /**
     * The prompt's chunks and the rest of the pool, once keyword matches are placed.
     *
     * @param inContext the chunks in the prompt: the vector chunks first, in score order, then the keyword
     *                  matches, best first
     * @param excluded  the rest of the vector pool, in rank order, without any chunk already in the prompt
     */
    public record Selection(List<Document> inContext, List<Document> excluded) {
    }

    /**
     * Places keyword matches among the vector chunks.
     * <ul>
     *   <li>A keyword match already in the vector prompt keeps its place and takes no extra one.</li>
     *   <li>Any other takes the place of the weakest vector chunk, so the prompt never exceeds top-k, and
     *       skips the threshold.</li>
     *   <li>With no keyword match this is exactly {@link #inContextCount}'s prefix of the pool.</li>
     * </ul>
     * The prompt stays a prefix of the pool evaluation stores, which ranks it as prompt then excluded.
     */
    public static Selection select(List<Document> pool, List<Document> keyword, int topK, double similarityThreshold) {
        int vectorCount = inContextCount(pool, topK, similarityThreshold);
        Set<String> vectorIds = new HashSet<>();
        pool.subList(0, vectorCount).forEach(document -> vectorIds.add(document.getId()));
        List<Document> added = keyword.stream().filter(document -> !vectorIds.contains(document.getId())).toList();

        List<Document> inContext = new ArrayList<>(pool.subList(0, Math.min(vectorCount, topK - added.size())));
        inContext.addAll(added);
        Set<String> inContextIds = new HashSet<>();
        inContext.forEach(document -> inContextIds.add(document.getId()));
        List<Document> excluded = pool.stream().filter(document -> !inContextIds.contains(document.getId())).toList();
        return new Selection(List.copyOf(inContext), excluded);
    }

    /**
     * How many of the pool's first chunks go in the prompt: at most top-k, and only those at or above the
     * threshold. The pool is sorted by score, so the prompt is always a prefix of it.
     */
    static int inContextCount(List<Document> pool, int topK, double similarityThreshold) {
        int count = 0;
        while (count < pool.size() && count < topK) {
            Double score = pool.get(count).getScore();
            if (score == null || score < similarityThreshold) {
                break;
            }
            count++;
        }
        return count;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        ChatResponse.Builder builder = ChatResponse.builder();
        if (response.chatResponse() != null) {
            builder.from(response.chatResponse());
        }
        Object retrieved = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (retrieved != null) {
            builder.metadata(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, retrieved);
        }
        return ChatClientResponse.builder()
                                 .chatResponse(builder.build())
                                 .context(response.context())
                                 .build();
    }
}
