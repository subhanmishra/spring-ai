package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalCaseResult;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvalCaseResultRepository extends ListCrudRepository<EvalCaseResult, UUID> {

    /** Every case belonging to one run, in the order the dataset declares them. */
    List<EvalCaseResult> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** One case's history across runs, newest first - how a specific regression is tracked down. */
    List<EvalCaseResult> findByCaseIdOrderByCreatedAtDesc(String caseId);
}
