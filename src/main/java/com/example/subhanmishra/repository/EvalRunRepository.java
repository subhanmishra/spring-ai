package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalRun;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvalRunRepository extends ListCrudRepository<EvalRun, UUID> {

    /** Runs newest first, for the run-history panel and for listing over the API. */
    List<EvalRun> findAllByOrderByStartedAtDesc();

    List<EvalRun> findBySuiteOrderByStartedAtDesc(String suite);

    /**
     * The most recent run of a suite, whatever its outcome.
     *
     * <p>Deliberately not filtered to COMPLETED. A run left RUNNING is one that was killed partway
     * through, and hiding it would make a suite that has been failing to finish look simply idle.
     */
    Optional<EvalRun> findFirstBySuiteOrderByStartedAtDesc(String suite);
}
