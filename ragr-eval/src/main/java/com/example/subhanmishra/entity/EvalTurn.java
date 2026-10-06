package com.example.subhanmishra.entity;

import com.example.subhanmishra.event.ChatTurnCompleted;
import com.example.subhanmishra.event.ChatTurnCompleted.GenerationUsage;
import com.example.subhanmishra.event.ChatTurnCompleted.Timings;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.RetrievalScores;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One chat turn as ragr-eval stores it on arrival: what was asked and answered, how, and its
 * deterministic scores. The judged columns of {@code eval_turn} are filled later by the judge worker and
 * are not part of this record; {@code EvalTurnRepository} writes them stage by stage.
 *
 * @param chunks the whole candidate pool in rank order; the first {@code retrievedCount} were in the prompt
 */
public record EvalTurn(UUID turnId,
                       String origin,
                       @Nullable UUID runId,
                       @Nullable String caseId,
                       String conversationId,
                       Instant occurredAt,
                       Instant receivedAt,
                       String query,
                       @Nullable String answer,
                       @Nullable String chatModel,
                       @Nullable String promptVersion,
                       @Nullable String pipelineVersion,
                       @Nullable Integer topK,
                       @Nullable Double similarityThreshold,
                       @Nullable Integer poolSize,
                       @Nullable Long retrievalMillis,
                       @Nullable Long firstTokenMillis,
                       @Nullable Long totalMillis,
                       @Nullable Boolean streamed,
                       @Nullable Integer promptTokens,
                       @Nullable Integer completionTokens,
                       @Nullable String finishReason,
                       int retrievedCount,
                       @Nullable Double topScore,
                       @Nullable Double scoreSpread,
                       int citationsEmitted,
                       int citationsValid,
                       int citationsFabricated,
                       int citationsRepaired,
                       int citationsAbstained,
                       @Nullable Double citedContextPrecision,
                       @Nullable Double citedPrecisionAtK,
                       boolean refused,
                       boolean echoedInstruction,
                       int answerChars,
                       JudgeStatus judgeStatus,
                       boolean reviewSample,
                       List<EvalTurnChunk> chunks) {

    public EvalTurn {
        chunks = List.copyOf(chunks);
    }

    /**
     * A turn off the topic, scored, as it is stored.
     *
     * @param cited  the answer's cited-precision vector, whose relevance flags mark which in-context chunks
     *               it cites; null for an ungrounded turn
     * @param runId  the golden run it belongs to, or null for a live turn
     * @param caseId the golden case, or null
     */
    public static EvalTurn from(ChatTurnCompleted turn,
                                EvalScores scores,
                                @Nullable ContextPrecisionScores cited,
                                @Nullable UUID runId,
                                @Nullable String caseId,
                                JudgeStatus judgeStatus,
                                boolean reviewSample) {
        List<ChatTurnCompleted.RetrievedChunk> pool = turn.pool();
        int inContext = turn.retrieved().size();
        List<EvalTurnChunk> chunks = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            boolean isCited = cited != null && i < cited.relevance().size() && cited.relevance().get(i);
            chunks.add(EvalTurnChunk.of(i + 1, pool.get(i).toDocument(), i < inContext, isCited));
        }
        String pipelineVersion = chunks.stream()
                                       .filter(EvalTurnChunk::inContext)
                                       .map(EvalTurnChunk::pipelineVersion)
                                       .filter(Objects::nonNull)
                                       .distinct()
                                       .sorted()
                                       .collect(Collectors.joining(","));
        Timings timings = turn.timings();
        GenerationUsage usage = turn.usage();
        RetrievalScores retrieval = scores.retrieval();
        return new EvalTurn(turn.turnId(),
                            turn.origin().name(),
                            runId,
                            caseId,
                            turn.conversationId(),
                            turn.occurredAt(),
                            Instant.now(),
                            turn.query(),
                            turn.answer(),
                            turn.chatModel(),
                            turn.promptVersion(),
                            pipelineVersion.isEmpty() ? null : pipelineVersion,
                            turn.topK(),
                            turn.similarityThreshold(),
                            turn.poolSize(),
                            timings != null ? timings.retrievalMillis() : null,
                            timings != null ? timings.firstTokenMillis() : null,
                            timings != null ? timings.totalMillis() : null,
                            timings != null ? timings.streamed() : null,
                            usage != null ? usage.promptTokens() : null,
                            usage != null ? usage.completionTokens() : null,
                            usage != null ? usage.finishReason() : null,
                            inContext,
                            retrieval.isZeroHit() ? null : retrieval.topScore(),
                            retrieval.isZeroHit() ? null : retrieval.scoreSpread(),
                            scores.citations().emitted(),
                            scores.citations().valid(),
                            scores.citations().fabricated(),
                            turn.citationsRepaired(),
                            turn.citationsAbstained(),
                            cited != null ? cited.averagePrecision() : null,
                            cited != null ? cited.precisionAtK() : null,
                            scores.answer().refused(),
                            scores.answer().echoedInstruction(),
                            scores.answer().answerChars(),
                            judgeStatus,
                            reviewSample,
                            chunks);
    }

    public boolean grounded() {
        return retrievedCount > 0;
    }

    /**
     * Where a turn is in the judge queue.
     *
     * <p>{@code NOT_QUEUED} is a turn the sample rate left out: stored and deterministically scored, never
     * judged. {@code PARTIAL} means at least one stage could not be measured - a timeout or an unreadable
     * verdict - and its columns are null, not failed.
     */
    public enum JudgeStatus {
        PENDING,
        RUNNING,
        DONE,
        PARTIAL,
        SKIPPED,
        NOT_QUEUED
    }
}
