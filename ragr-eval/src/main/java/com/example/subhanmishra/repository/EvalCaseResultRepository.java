package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalCaseResult;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvalCaseResultRepository extends ListCrudRepository<EvalCaseResult, UUID> {

    /** Every case belonging to one run, in the order the dataset declares them. */
    List<EvalCaseResult> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** One case's history across runs, newest first - how a specific regression is tracked down. */
    List<EvalCaseResult> findByCaseIdOrderByCreatedAtDesc(String caseId);

    /** The case waiting for the turn of this conversation, if a run is waiting for it. */
    Optional<EvalCaseResult> findByConversationIdAndStatus(String conversationId, EvalCaseResult.Status status);

    int countByRunIdAndStatus(UUID runId, EvalCaseResult.Status status);

    /** Fails one case whose request failed, unless its turn has been scored meanwhile. */
    @Modifying
    @Query("""
            UPDATE eval_case_result SET status = 'FAILED', passed = FALSE, failure_reasons = :reason
            WHERE id = :id AND status = 'AWAITING'
            """)
    int failIfAwaiting(@Param("id") UUID id, @Param("reason") String reason);

    /** Fails every case of a run still waiting for its turn. */
    @Modifying
    @Query("""
            UPDATE eval_case_result SET status = 'FAILED', passed = FALSE, failure_reasons = :reason
            WHERE run_id = :runId AND status = 'AWAITING'
            """)
    int failAwaiting(@Param("runId") UUID runId, @Param("reason") String reason);

    /** Fails every waiting case; only safe when no run is in progress, i.e. at startup. */
    @Modifying
    @Query("""
            UPDATE eval_case_result SET status = 'FAILED', passed = FALSE, failure_reasons = :reason
            WHERE status = 'AWAITING'
            """)
    int failAllAwaiting(@Param("reason") String reason);
}
