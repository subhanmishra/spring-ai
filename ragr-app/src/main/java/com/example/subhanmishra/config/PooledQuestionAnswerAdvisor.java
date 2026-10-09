package com.example.subhanmishra.config;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Spring AI's {@code QuestionAnswerAdvisor}, but fetching a bigger pool of chunks than it puts in the
 * prompt.
 *
 * <p><b>Why.</b> Evaluation needs to see what retrieval <em>left out</em>: recall is a relevant chunk the
 * threshold or top-k cut off. The stock advisor never lets anything past its top-k out of the vector
 * store.
 *
 * <p><b>How.</b> The same single vector query, asking for {@code pool-size} chunks above
 * {@code pool-floor}; then top-k and the threshold are applied here. The prompt gets exactly the chunks
 * the stock advisor would have chosen, in the same order and template - only laid out differently, see
 * {@link #passages}. A pool of 10 costs the same as 5.
 *
 * <p>Context keys:
 * <ul>
 *   <li>{@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS} - the chunks in the prompt. Kept under the
 *       stock key so {@code ChatService} reads them as it always has.</li>
 *   <li>{@link #EXCLUDED_DOCUMENTS} - the rest of the pool, in rank order after them.</li>
 *   <li>{@link #RETRIEVAL_MILLIS} - how long the vector query took.</li>
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
    private final RagProperties properties;

    public PooledQuestionAnswerAdvisor(VectorStore vectorStore, PromptTemplate promptTemplate,
                                       RagProperties properties) {
        this.vectorStore = vectorStore;
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
        SearchRequest search = SearchRequest.builder()
                                            .query(Objects.requireNonNullElse(userMessage.getText(), ""))
                                            .topK(properties.poolSize())
                                            .similarityThreshold(properties.poolFloor())
                                            .build();
        long started = System.nanoTime();
        List<Document> pool = vectorStore.similaritySearch(search);
        long retrievalMillis = (System.nanoTime() - started) / 1_000_000;

        int inContext = inContextCount(pool, properties.topK(), properties.similarityThreshold());
        List<Document> retrieved = List.copyOf(pool.subList(0, inContext));
        List<Document> excluded = List.copyOf(pool.subList(inContext, pool.size()));

        Map<String, Object> context = new HashMap<>(request.context());
        context.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, retrieved);
        context.put(EXCLUDED_DOCUMENTS, excluded);
        context.put(RETRIEVAL_MILLIS, retrievalMillis);

        String augmented = promptTemplate.render(Map.of("query", userMessage.getText(),
                                                        "question_answer_context", passages(retrieved)));
        return request.mutate()
                      .prompt(request.prompt().augmentUserMessage(augmented))
                      .context(context)
                      .build();
    }

    /**
     * The chunks as the prompt shows them: each closed by {@link #PASSAGE_END}, with a blank line either
     * side of it.
     * <p>
     * Joined the stock way, each passage ran straight into the next one's {@code [filename, p. N]} line,
     * and the model read that line as belonging to the text <em>above</em> it - so it cited a passage's
     * content to the next passage's page. The end marker fixed that; a blank line did not, and a "---"
     * rule garbled filenames.
     * <p>
     * Always "\n", never the platform line separator, so the prompt is the same on every OS.
     */
    static String passages(List<Document> documents) {
        return documents.stream()
                        .map(document -> document.getText() + "\n\n" + PASSAGE_END)
                        .collect(Collectors.joining("\n\n"));
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
