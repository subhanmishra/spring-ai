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
 * <p>It carries everything the scorer and the judges need, so the consumer never has to query the
 * vector store. That is a correctness property, not only a convenience: a document can be deleted or
 * re-indexed between the turn and its evaluation, and a turn has to be scored against the chunks the
 * model actually saw, not whatever the store holds by the time the event is read. It is also what
 * makes a retained turn replayable through a new metric or judge later.
 *
 * <p>Shared between the publisher and the consumer so the two cannot disagree about its shape. It
 * travels as JSON with no type headers, which keeps it readable by a consumer that deserializes into
 * its own copy of this record - add fields freely, but renaming or removing one breaks the other side.
 *
 * @param turnId              unique per turn; the consumer's idempotency key
 * @param origin              whether a real user asked, or the golden suite - which the online
 *                            evaluation leaves out of the live metrics
 * @param occurredAt          when the answer finished, not when the event was sent
 * @param conversationId      also the Kafka record key, so one conversation's turns stay in order
 * @param query               the user's prompt
 * @param answer              the answer after {@code CitationResolver}, with its inline citations still
 *                            in place: the text the evaluation scores, not the stripped text the caller
 *                            reads
 * @param citationsRepaired   section-number citations the resolver rewrote into pages
 * @param citationsAbstained  section-number citations it could not place, and left as written
 * @param unresolved          the citations behind {@code citationsAbstained}
 * @param retrieved           the chunks the answer was built on, in rank order; empty for an ungrounded
 *                            turn, which evaluation skips rather than scores
 * @param chatModel           the model that answered, when the response reported it
 * @param topK                how many chunks retrieval was asked for
 * @param similarityThreshold the minimum score a chunk needed to be retrieved
 * @param schemaVersion       {@link #SCHEMA_VERSION} when sent; null on a version-1 event, which has
 *                            none of the fields below
 * @param excluded            the rest of the candidate pool: chunks the same vector query returned that
 *                            the model was <em>not</em> shown, because they fell below the threshold or
 *                            past top-k. Pool rank is implicit - {@code retrieved} holds ranks
 *                            1..m and this list m+1..n, both in score order - which is what lets
 *                            evaluation measure recall against chunks retrieval cut off
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
     * Version 2 added the candidate pool, timings, usage and the prompt version. Every field it added is
     * a nullable box rather than a primitive: an event already on the topic when the consumer upgrades
     * has none of them, and Jackson 3 fails on a missing primitive rather than defaulting it to zero.
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
     * Token counts and the finish reason the model reported, each null when the response did not.
     * A finish reason of {@code length} is a truncated answer, which reads as an incomplete one to every
     * judge and is cheaper to catch here.
     */
    public record GenerationUsage(@Nullable Integer promptTokens,
                                  @Nullable Integer completionTokens,
                                  @Nullable String finishReason) {
    }

    /**
     * One retrieved chunk. Its text keeps the {@code [filename, p. N]} citation header it was stored
     * with, because that header is what the model saw and what citations are checked against.
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
