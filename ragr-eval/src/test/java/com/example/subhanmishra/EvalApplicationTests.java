package com.example.subhanmishra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the whole context against the live database and broker, as {@code SpringAiApplicationTests}
 * does for ragr-app - which also means it runs this application's Flyway migrations.
 *
 * <p>The listener is left stopped for the same reason {@code EvalSuiteIT} stops it: a test JVM joining
 * the ragr-eval consumer group would take the partition away from the running application.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class EvalApplicationTests {

    @Test
    void contextLoads() {
    }
}
