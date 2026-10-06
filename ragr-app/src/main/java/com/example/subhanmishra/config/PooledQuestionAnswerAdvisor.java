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
 * {@code QuestionAnswerAdvisor}, retrieving a deeper candidate pool than it puts in the prompt.
 *
 * <p>Evaluation needs to know what retrieval <em>left out</em>. Recall and NDCG on live traffic are
 * measured against chunks the judge calls relevant, and a relevant chunk the threshold or top-k cut off
 * is exactly the miss those metrics exist to see - but the stock advisor discards everything past its
 * own top-k inside the vector store, so nothing downstream can ever see it.
 *
 * <p>So this makes the <strong>one</strong> vector query the stock advisor would make, asking for
 * {@code pool-size} candidates above {@code pool-floor} instead, and then applies top-k and the
 * similarity threshold itself. The prompt is built from exactly the chunks the stock advisor would have
 * chosen, in the same order and with the same template, so the model's input is unchanged. Measured on
 * the 946-chunk corpus, 6 Oct 2026: LIMIT 5 and LIMIT 10 both take about 0.8 ms warm, LIMIT 20 about
 * 3.8 ms - against a generation of 16-75 s.
 *
 * <p>Context keys:
 * <ul>
 *   <li>{@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS} - the chunks in the prompt. Kept under the
 *       stock key so {@code ChatService} reads them as it always has.</li>
 *   <li>{@link #EXCLUDED_DOCUMENTS} - the rest of the pool, in rank order after them.</li>
 *   <li>{@link #RETRIEVAL_MILLIS} - how long the vector query took.</li>
 * </ul>
 *
 * <p>Written out rather than wrapping the stock advisor because its constructor is package-private and
 * its search request is final; the parts copied are {@code before}'s prompt rendering and {@code after}'s
 * metadata hand-off, line for line, including joining chunks with the platform line separator.
 */
public class PooledQuestionAnswerAdvisor implements BaseAdvisor {

    public static final String EXCLUDED_DOCUMENTS = "rag_excluded_documents";
    public static final String RETRIEVAL_MILLIS = "rag_retrieval_millis";

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

        String documentContext = retrieved.stream()
                                          .map(Document::getText)
                                          .collect(Collectors.joining(System.lineSeparator()));
        String augmented = promptTemplate.render(Map.of("query", userMessage.getText(),
                                                        "question_answer_context", documentContext));
        return request.mutate()
                      .prompt(request.prompt().augmentUserMessage(augmented))
                      .context(context)
                      .build();
    }

    /**
     * How many of the pool's leading chunks go in the prompt: at most top-k, and only those at or above
     * the threshold. The pool arrives in descending score order, so the cut is always a prefix - which
     * is what keeps pool rank implicit in the event.
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
