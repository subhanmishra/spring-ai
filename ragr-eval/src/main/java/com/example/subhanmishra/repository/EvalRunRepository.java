package com.example.subhanmishra.repository;

import com.example.subhanmishra.entity.EvalRun;
import com.example.subhanmishra.entity.EvalRunStatus;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvalRunRepository extends ListCrudRepository<EvalRun, UUID> {

    /**
     * The most recent run in a given state, newest first - in practice the latest COMPLETED one, which
     * is what the golden gauges report.
     *
     * <p>Filtered and limited in SQL, because it runs during Prometheus scrapes:
     * {@code eval_run_status_started_idx} makes it a one-row lookup, where picking the newest run in Java
     * would scan the whole table, slower with every run.
     */
    Optional<EvalRun> findFirstByStatusOrderByStartedAtDesc(EvalRunStatus status);

    /** The newest run in any state. */
    Optional<EvalRun> findFirstByOrderByStartedAtDesc();

    /** Fails every RUNNING run; only safe when no run is in progress, i.e. at startup. */
    @Modifying
    @Query("""
            UPDATE eval_run SET status = 'FAILED', finished_at = now(), error_message = :reason
            WHERE status = 'RUNNING'
            """)
    int failRunning(@Param("reason") String reason);
}
