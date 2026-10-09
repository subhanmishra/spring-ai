package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalTurn;
import com.example.subhanmishra.entity.EvalTurn.JudgeStatus;
import com.example.subhanmishra.entity.EvalTurnChunk;
import com.example.subhanmishra.service.eval.RetrievalRanking;
import com.example.subhanmishra.service.eval.TurnVerdicts;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code eval_turn}, {@code eval_turn_chunk} and {@code eval_feedback}, in plain SQL.
 *
 * <p>Plain SQL, not Spring Data: a turn is inserted once with forty-odd columns, then updated a few at a
 * time by the judges, and claiming from the queue needs {@code FOR UPDATE SKIP LOCKED}. As an aggregate,
 * every judge call would re-save the whole row, chunk texts and all.
 */
@Repository
public class EvalTurnRepository {

    private final JdbcClient jdbc;

    public EvalTurnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a turn and its pool. Idempotent on {@code turn_id}: a redelivered event changes nothing. */
    @Transactional
    public boolean insert(EvalTurn turn) {
        int inserted = jdbc.sql("""
                INSERT INTO eval_turn (turn_id, origin, run_id, case_id, conversation_id, occurred_at, received_at,
                    query, answer, chat_model, prompt_version, pipeline_version, top_k, similarity_threshold,
                    pool_size, retrieval_millis, first_token_millis, total_millis, streamed, prompt_tokens,
                    completion_tokens, finish_reason, grounded, retrieved_count, pool_count, top_score,
                    score_spread, citations_emitted, citations_valid, citations_fabricated, citations_repaired,
                    citations_abstained, cited_context_precision, cited_precision_at_k, refused,
                    echoed_instruction, answer_chars, judge_status, review_sample)
                VALUES (:turnId, :origin, :runId, :caseId, :conversationId, :occurredAt, :receivedAt,
                    :query, :answer, :chatModel, :promptVersion, :pipelineVersion, :topK, :similarityThreshold,
                    :poolSize, :retrievalMillis, :firstTokenMillis, :totalMillis, :streamed, :promptTokens,
                    :completionTokens, :finishReason, :grounded, :retrievedCount, :poolCount, :topScore,
                    :scoreSpread, :citationsEmitted, :citationsValid, :citationsFabricated, :citationsRepaired,
                    :citationsAbstained, :citedContextPrecision, :citedPrecisionAtK, :refused,
                    :echoedInstruction, :answerChars, :judgeStatus, :reviewSample)
                ON CONFLICT (turn_id) DO NOTHING
                """)
                           .param("turnId", turn.turnId())
                           .param("origin", turn.origin())
                           .param("runId", turn.runId())
                           .param("caseId", turn.caseId())
                           .param("conversationId", turn.conversationId())
                           .param("occurredAt", Timestamp.from(turn.occurredAt()))
                           .param("receivedAt", Timestamp.from(turn.receivedAt()))
                           .param("query", turn.query())
                           .param("answer", turn.answer())
                           .param("chatModel", turn.chatModel())
                           .param("promptVersion", turn.promptVersion())
                           .param("pipelineVersion", turn.pipelineVersion())
                           .param("topK", turn.topK())
                           .param("similarityThreshold", turn.similarityThreshold())
                           .param("poolSize", turn.poolSize())
                           .param("retrievalMillis", turn.retrievalMillis())
                           .param("firstTokenMillis", turn.firstTokenMillis())
                           .param("totalMillis", turn.totalMillis())
                           .param("streamed", turn.streamed())
                           .param("promptTokens", turn.promptTokens())
                           .param("completionTokens", turn.completionTokens())
                           .param("finishReason", turn.finishReason())
                           .param("grounded", turn.grounded())
                           .param("retrievedCount", turn.retrievedCount())
                           .param("poolCount", turn.chunks().size())
                           .param("topScore", turn.topScore())
                           .param("scoreSpread", turn.scoreSpread())
                           .param("citationsEmitted", turn.citationsEmitted())
                           .param("citationsValid", turn.citationsValid())
                           .param("citationsFabricated", turn.citationsFabricated())
                           .param("citationsRepaired", turn.citationsRepaired())
                           .param("citationsAbstained", turn.citationsAbstained())
                           .param("citedContextPrecision", turn.citedContextPrecision())
                           .param("citedPrecisionAtK", turn.citedPrecisionAtK())
                           .param("refused", turn.refused())
                           .param("echoedInstruction", turn.echoedInstruction())
                           .param("answerChars", turn.answerChars())
                           .param("judgeStatus", turn.judgeStatus().name())
                           .param("reviewSample", turn.reviewSample())
                           .update();
        if (inserted == 0) {
            return false;
        }
        for (EvalTurnChunk chunk : turn.chunks()) {
            jdbc.sql("""
                    INSERT INTO eval_turn_chunk (turn_id, rank, chunk_id, document_id, file_name, page, section,
                        pipeline_version, score, in_context, cited, text)
                    VALUES (:turnId, :rank, :chunkId, :documentId, :fileName, :page, :section,
                        :pipelineVersion, :score, :inContext, :cited, :text)
                    """)
                .param("turnId", turn.turnId())
                .param("rank", chunk.rank())
                .param("chunkId", chunk.chunkId())
                .param("documentId", chunk.documentId())
                .param("fileName", chunk.fileName())
                .param("page", chunk.page())
                .param("section", chunk.section())
                .param("pipelineVersion", chunk.pipelineVersion())
                .param("score", chunk.score())
                .param("inContext", chunk.inContext())
                .param("cited", chunk.cited())
                .param("text", chunk.text())
                .update();
        }
        return true;
    }

    /**
     * The previous turn of a conversation within {@code window} of {@code before}, with its pool's chunk
     * ids - what rephrase detection compares a new turn against.
     */
    public Optional<PreviousTurn> previousTurn(String conversationId, Instant before, Duration window) {
        return jdbc.sql("""
                SELECT t.turn_id, array_to_string(array_agg(c.chunk_id ORDER BY c.rank), ',') AS chunk_ids
                FROM eval_turn t LEFT JOIN eval_turn_chunk c ON c.turn_id = t.turn_id
                WHERE t.conversation_id = :conversationId AND t.occurred_at < :before AND t.occurred_at >= :since
                GROUP BY t.turn_id, t.occurred_at
                ORDER BY t.occurred_at DESC
                LIMIT 1
                """)
                   .param("conversationId", conversationId)
                   .param("before", Timestamp.from(before))
                   .param("since", Timestamp.from(before.minus(window)))
                   .query((rs, _) -> new PreviousTurn(rs.getObject("turn_id", UUID.class),
                                                      rs.getString("chunk_ids") == null || rs.getString("chunk_ids").isEmpty()
                                                              ? List.of()
                                                              : List.of(rs.getString("chunk_ids").split(","))))
                   .optional();
    }

    public record PreviousTurn(UUID turnId, List<String> chunkIds) {
    }

    public void markRephrased(UUID turnId) {
        jdbc.sql("UPDATE eval_turn SET rephrased = TRUE WHERE turn_id = :turnId").param("turnId", turnId).update();
    }

    /** Marks PENDING turns older than {@code maxAge} as SKIPPED, returning how many. */
    public int skipStale(Duration maxAge) {
        return jdbc.sql("""
                UPDATE eval_turn SET judge_status = 'SKIPPED'
                WHERE judge_status = 'PENDING' AND occurred_at < :cutoff
                """)
                   .param("cutoff", Timestamp.from(Instant.now().minus(maxAge)))
                   .update();
    }

    /** Puts turns a stopped worker left RUNNING back in the queue. Called once, at worker start. */
    public int requeueRunning() {
        return jdbc.sql("UPDATE eval_turn SET judge_status = 'PENDING', judge_started_at = NULL "
                        + "WHERE judge_status = 'RUNNING'")
                   .update();
    }

    /** Puts one turn back in the queue - a stage interrupted by shutdown is retried from the start. */
    public void requeue(UUID turnId) {
        jdbc.sql("UPDATE eval_turn SET judge_status = 'PENDING', judge_started_at = NULL WHERE turn_id = :turnId")
            .param("turnId", turnId)
            .update();
    }

    /** Claims the oldest PENDING turn for judging, or empty when the queue is empty. */
    @Transactional
    public Optional<UUID> claimNext() {
        return jdbc.sql("""
                UPDATE eval_turn SET judge_status = 'RUNNING', judge_started_at = now()
                WHERE turn_id = (SELECT turn_id FROM eval_turn WHERE judge_status = 'PENDING'
                                 ORDER BY occurred_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING turn_id
                """)
                   .query(UUID.class)
                   .optional();
    }

    /** What the judges need of a turn: the question, the answer and the pool. */
    public Optional<JudgeInput> judgeInput(UUID turnId) {
        Optional<JudgeInput> head = jdbc.sql("""
                SELECT query, answer, retrieved_count, citations_fabricated FROM eval_turn WHERE turn_id = :turnId
                """)
                                        .param("turnId", turnId)
                                        .query((rs, _) -> new JudgeInput(turnId, rs.getString("query"),
                                                                         rs.getString("answer"),
                                                                         rs.getInt("retrieved_count"),
                                                                         rs.getInt("citations_fabricated"),
                                                                         List.of()))
                                        .optional();
        return head.map(input -> new JudgeInput(input.turnId(), input.query(), input.answer(),
                                                input.retrievedCount(), input.citationsFabricated(),
                                                chunks(turnId)));
    }

    /**
     * @param chunks the pool in rank order; the first {@code retrievedCount} were in the prompt
     */
    public record JudgeInput(UUID turnId,
                             String query,
                             @Nullable String answer,
                             int retrievedCount,
                             int citationsFabricated,
                             List<EvalTurnChunk> chunks) {

        public List<EvalTurnChunk> inContext() {
            return chunks.subList(0, Math.min(retrievedCount, chunks.size()));
        }

        /** A turn not yet stored - the golden suite judges before it persists. */
        public static JudgeInput of(EvalTurn turn) {
            return new JudgeInput(turn.turnId(), turn.query(), turn.answer(), turn.retrievedCount(),
                                  turn.citationsFabricated(), turn.chunks());
        }
    }

    private List<EvalTurnChunk> chunks(UUID turnId) {
        return jdbc.sql("""
                SELECT rank, chunk_id, document_id, file_name, page, section, pipeline_version, score, in_context,
                       cited, judge_grade, text
                FROM eval_turn_chunk WHERE turn_id = :turnId ORDER BY rank
                """)
                   .param("turnId", turnId)
                   .query((rs, _) -> new EvalTurnChunk(rs.getInt("rank"),
                                                       rs.getString("chunk_id"),
                                                       rs.getString("document_id"),
                                                       rs.getString("file_name"),
                                                       rs.getObject("page", Integer.class),
                                                       rs.getString("section"),
                                                       rs.getString("pipeline_version"),
                                                       rs.getObject("score", Double.class),
                                                       rs.getBoolean("in_context"),
                                                       rs.getBoolean("cited"),
                                                       rs.getObject("judge_grade", Integer.class),
                                                       rs.getString("text")))
                   .list();
    }

    /** Writes every judged column of a turn and its chunk grades, and closes it as DONE or PARTIAL. */
    @Transactional
    public void saveVerdicts(UUID turnId, TurnVerdicts verdicts, @Nullable Boolean answerOk, long judgeMillis,
                             String judgeModel) {
        RetrievalRanking ranking = verdicts.ranking();
        jdbc.sql("""
                UPDATE eval_turn SET
                    judge_status = :status, judged_at = now(), judge_millis = :judgeMillis, judge_model = :judgeModel,
                    task_type = :taskType,
                    precision_at_k = :precisionAtK, recall_at_k = :recallAtK, mrr = :mrr, ndcg_at_k = :ndcgAtK,
                    relevant_in_context = :relevantInContext, relevant_in_pool = :relevantInPool,
                    relevant_cut_off = :relevantCutOff,
                    relevancy_pass = :relevancyPass, groundedness_pass = :groundednessPass,
                    claims_total = :claimsTotal, claims_supported = :claimsSupported, faithfulness = :faithfulness,
                    citations_checked = :citationsChecked, citations_supported = :citationsSupported,
                    completeness_pass = :completenessPass,
                    retrieval_ok = :retrievalOk, answer_ok = :answerOk
                WHERE turn_id = :turnId
                """)
            .param("turnId", turnId)
            .param("status", (verdicts.anyStageUnmeasured() ? JudgeStatus.PARTIAL : JudgeStatus.DONE).name())
            .param("judgeMillis", judgeMillis)
            .param("judgeModel", judgeModel)
            .param("taskType", verdicts.taskType() != null ? verdicts.taskType().tag() : null)
            .param("precisionAtK", ranking != null ? ranking.precisionAtK() : null)
            .param("recallAtK", ranking != null ? ranking.recallAtK() : null)
            .param("mrr", ranking != null ? ranking.reciprocalRank() : null)
            .param("ndcgAtK", ranking != null ? ranking.ndcgAtK() : null)
            .param("relevantInContext", ranking != null ? ranking.relevantInContext() : null)
            .param("relevantInPool", ranking != null ? ranking.relevantInPool() : null)
            .param("relevantCutOff", ranking != null ? ranking.relevantCutOff() : null)
            .param("relevancyPass", verdicts.relevancyPass())
            .param("groundednessPass", verdicts.groundednessPass())
            .param("claimsTotal", verdicts.claimsTotal())
            .param("claimsSupported", verdicts.claimsSupported())
            .param("faithfulness", verdicts.faithfulness())
            .param("citationsChecked", verdicts.citationsChecked())
            .param("citationsSupported", verdicts.citationsSupported())
            .param("completenessPass", verdicts.completenessPass())
            .param("retrievalOk", verdicts.retrievalOk())
            .param("answerOk", answerOk)
            .update();
        List<@Nullable Integer> grades = verdicts.grades();
        for (int i = 0; i < grades.size(); i++) {
            jdbc.sql("UPDATE eval_turn_chunk SET judge_grade = :grade WHERE turn_id = :turnId AND rank = :rank")
                .param("grade", grades.get(i))
                .param("turnId", turnId)
                .param("rank", i + 1)
                .update();
        }
    }

    public int pendingCount() {
        return jdbc.sql("SELECT count(*) FROM eval_turn WHERE judge_status = 'PENDING'").query(Integer.class).single();
    }

    /** Seconds since the oldest PENDING turn occurred; 0 when the queue is empty. */
    public double oldestPendingAgeSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(occurred_at)), 0)
                FROM eval_turn WHERE judge_status = 'PENDING'
                """)
                   .query(Double.class)
                   .single();
    }

    public void insertFeedback(UUID turnId, String rating, @Nullable String reason, Instant submittedAt) {
        jdbc.sql("""
                INSERT INTO eval_feedback (turn_id, rating, reason, submitted_at, received_at)
                VALUES (:turnId, :rating, :reason, :submittedAt, now())
                """)
            .param("turnId", turnId)
            .param("rating", rating)
            .param("reason", reason)
            .param("submittedAt", Timestamp.from(submittedAt))
            .update();
    }

    /** Records a reviewer's verdict; false when no such turn is stored. */
    public boolean saveReview(UUID turnId, String verdict, @Nullable String notes) {
        return jdbc.sql("""
                UPDATE eval_turn SET human_verdict = :verdict, human_notes = :notes, reviewed_at = now()
                WHERE turn_id = :turnId
                """)
                   .param("turnId", turnId)
                   .param("verdict", verdict)
                   .param("notes", notes)
                   .update() > 0;
    }

    /** Deletes live turns (chunks cascade) and feedback older than {@code cutoff}; returns the turns deleted. */
    @Transactional
    public int purgeLiveBefore(Instant cutoff) {
        jdbc.sql("DELETE FROM eval_feedback WHERE submitted_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
        return jdbc.sql("DELETE FROM eval_turn WHERE origin = 'LIVE' AND occurred_at < :cutoff")
                   .param("cutoff", Timestamp.from(cutoff))
                   .update();
    }
}
