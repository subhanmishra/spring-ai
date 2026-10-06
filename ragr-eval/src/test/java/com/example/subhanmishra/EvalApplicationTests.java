package com.example.subhanmishra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the whole context against the live database and broker, as {@code SpringAiApplicationTests}
 * does for ragr-app - which also means it runs this application's Flyway migrations.
 *
 * <p>The listeners and the judge worker are left stopped for the same reason {@code EvalSuiteIT} stops
 * them: a test JVM joining the ragr-eval consumer group would take the partition away from the running
 * application, and a second worker would take its queued turns.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "app.eval.judge.worker-enabled=false"})
class EvalApplicationTests {

    @Test
    void contextLoads() {
    }
}
