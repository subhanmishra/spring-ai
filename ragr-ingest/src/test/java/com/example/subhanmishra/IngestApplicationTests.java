package com.example.subhanmishra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the whole context against the live database, as {@code SpringAiApplicationTests} does for
 * ragr-app - which also means it runs this application's Flyway migrations.
 */
@SpringBootTest
class IngestApplicationTests {

    @Test
    void contextLoads() {
    }
}
