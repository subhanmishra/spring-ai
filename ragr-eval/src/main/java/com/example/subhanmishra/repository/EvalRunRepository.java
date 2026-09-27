package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.entity.EvalRunStatus;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvalRunRepository extends ListCrudRepository<EvalRun, UUID> {

    /**
     * The most recent run in a given state, newest first - in practice the latest COMPLETED one, which
     * is what the golden gauges report.
     *
     * <p>Filtered and limited in SQL rather than in the caller, because this runs on the Prometheus
     * scrape thread for as long as the application is up. {@code eval_run_status_started_idx} serves it as a single-row
     * lookup; selecting the history and picking the newest COMPLETED row in Java scans and sorts the
     * whole table instead, at a cost that grows with every run ever recorded.
     */
    Optional<EvalRun> findFirstByStatusOrderByStartedAtDesc(EvalRunStatus status);
}
