package com.example.subhanmishra.event;

import com.example.subhanmishra.citation.Citation;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One completed chat turn, as the chat path hands it to evaluation.
 *
 * <p>It carries everything evaluation needs, so evaluation never queries the vector store. That matters
 * for correctness: a document can be deleted or re-indexed before the turn is scored, and a turn must be
 * scored against the chunks the model actually saw. It also lets a stored turn be replayed through a
 * new metric later.
 *
 * <p>It travels as JSON without type headers. Adding a field is safe; renaming or removing one breaks
 * the other side.
 *
 * @param turnId              unique per turn; the consumer's idempotency key
 * @param origin              whether a real user asked, or the golden suite - which the online
 *                            evaluation leaves out of the live metrics
 * @param occurredAt          when the answer finished, not when the event was sent
 * @param conversationId      also the Kafka record key, so one conversation's turns stay in order
 * @param query               the user's prompt
 * @param answer              the answer after {@code CitationResolver}, inline citations still in place -
 *                            what evaluation scores, not the stripped text the caller reads
 * @param citationsRepaired   section-number citations the resolver rewrote into pages
 * @param citationsAbstained  section-number citations it could not place, and left as written
 * @param unresolved          the citations behind {@code citationsAbstained}
 * @param retrieved           the chunks in the prompt, in rank order; empty for an ungrounded turn
 * @param chatModel           the model that answered, when the response reported it
 * @param topK                how many chunks retrieval was asked for
 * @param similarityThreshold the minimum score a chunk needed to be retrieved
 * @param schemaVersion       {@link #SCHEMA_VERSION} when sent; null on a version-1 event, which has
 *                            none of the fields below
 * @param excluded            the rest of the candidate pool: chunks the same query returned that the
 *                            model was <em>not</em> shown, below the threshold or past top-k. Together
 *                            with {@code retrieved} it is the pool in rank order, which is how
 *                            evaluation measures what retrieval left out
 * @param poolSize            how many candidates the pool query asked for
 * @param timings             where the turn's time went
 * @param usage               what the generation cost and how it ended
 * @param promptVersion       a short hash of the system prompt and the RAG template, so a metric can be
 *                            split at a prompt change
 */
public record ChatTurnCompleted(UUID turnId,
                                TurnOrigin origin,
                                Instant occurredAt,
                                String conversationId,
                                String query,
                                String answer,
                                int citationsRepaired,
                                int citationsAbstained,
                                List<Citation> unresolved,
                                List<RetrievedChunk> retrieved,
                                @Nullable String chatModel,
                                int topK,
                                double similarityThreshold,
                                @Nullable Integer schemaVersion,
                                @Nullable List<RetrievedChunk> excluded,
                                @Nullable Integer poolSize,
                                @Nullable Timings timings,
                                @Nullable GenerationUsage usage,
                                @Nullable String promptVersion) {

    /**
     * Version 2 added the candidate pool, timings, usage and the prompt version - all as nullable boxed
     * types, because a version-1 event has none of them and Jackson 3 fails on a missing primitive.
     */
    public static final int SCHEMA_VERSION = 2;

    public ChatTurnCompleted {
        unresolved = List.copyOf(unresolved);
        retrieved = List.copyOf(retrieved);
        excluded = excluded != null ? List.copyOf(excluded) : List.of();
    }

    /** The retrieved chunks as Spring AI documents, the shape the scorer and the judges take. */
    public List<Document> retrievedDocuments() {
        return retrieved.stream().map(RetrievedChunk::toDocument).toList();
    }

    /** The whole candidate pool in rank order: what the model saw, then what it did not. */
    public List<RetrievedChunk> pool() {
        return excluded.isEmpty()
                ? retrieved
                : Stream.concat(retrieved.stream(), excluded.stream()).toList();
    }

    /**
     * Where a turn's time went, in milliseconds.
     *
     * @param retrievalMillis  the pool query alone
     * @param firstTokenMillis request start to the first streamed token; null for a non-streamed turn,
     *                         whose caller sees nothing until the end
     * @param totalMillis      request start to the finished answer
     * @param streamed         whether the caller used the streaming endpoint
     */
    public record Timings(@Nullable Long retrievalMillis,
                          @Nullable Long firstTokenMillis,
                          long totalMillis,
                          boolean streamed) {
    }

    /**
     * Token counts and the finish reason the model reported, each null when it did not. A finish reason
     * of {@code length} means the answer was cut off.
     */
    public record GenerationUsage(@Nullable Integer promptTokens,
                                  @Nullable Integer completionTokens,
                                  @Nullable String finishReason) {
    }

    /**
     * One retrieved chunk, with its {@code [filename, p. N]} header kept: that is what the model saw,
     * and what citations are checked against.
     */
    public record RetrievedChunk(String id, String text, Map<String, Object> metadata, @Nullable Double score) {

        public RetrievedChunk {
            metadata = Map.copyOf(metadata);
        }

        public static RetrievedChunk of(Document document) {
            String text = document.getText();
            return new RetrievedChunk(document.getId(), text != null ? text : "", document.getMetadata(),
                                      document.getScore());
        }

        public Document toDocument() {
            return Document.builder().id(id).text(text).metadata(metadata).score(score).build();
        }
    }
}
