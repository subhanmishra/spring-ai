package com.example.subhanmishra.service;

import com.example.subhanmishra.config.EvalProperties;
import com.example.subhanmishra.repository.EvalTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes evaluated live turns, their pools and feedback once they are older than
 * {@code app.eval.retention.live-days}. Golden turns stay with their runs.
 *
 * <p>Daily, because nothing reads a turn by its age closer than that, and a turn is a few KB of chunk text.
 * The live turns are the only place user questions are kept outside chat memory - which keeps its own
 * 24-hour TTL - so this is also the bound on how long they are kept here.
 */
@Service
public class EvalRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EvalRetentionService.class);

    private final EvalTurnRepository turns;
    private final EvalProperties properties;

    public EvalRetentionService(EvalTurnRepository turns, EvalProperties properties) {
        this.turns = turns;
        this.properties = properties;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.retention().liveDays()));
        int deleted = turns.purgeLiveBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} evaluated live turn(s) older than {} days", deleted, properties.retention().liveDays());
        }
    }
}
