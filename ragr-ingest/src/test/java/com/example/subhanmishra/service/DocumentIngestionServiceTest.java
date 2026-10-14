package com.example.subhanmishra.service;

import com.example.subhanmishra.chunk.ChunkMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers what the {@code Section:} line of a chunk's header names. The product and version matter because
 * several projects share pages word for word, and the section line is the only text that differs.
 */
class DocumentIngestionServiceTest {

    @Test
    @DisplayName("the line names the product and version, then the heading path")
    void productVersionAndPath() {
        Map<String, Object> metadata = Map.of(ChunkMetadata.SECTION, "Task Execution and Scheduling > Customizing the Executor",
                                              ChunkMetadata.PROJECT, "Spring Boot",
                                              ChunkMetadata.PROJECT_VERSION, "4.1.1");

        assertThat(DocumentIngestionService.sectionLine(metadata))
                .isEqualTo("Spring Boot 4.1.1 > Task Execution and Scheduling > Customizing the Executor");
    }

    @Test
    @DisplayName("an unversioned page names its product alone")
    void unversionedPage() {
        Map<String, Object> metadata = Map.of(ChunkMetadata.SECTION, "Circular dependency between beans > Fix",
                                              ChunkMetadata.PROJECT, "Troubleshooting: playbook");

        assertThat(DocumentIngestionService.sectionLine(metadata))
                .isEqualTo("Troubleshooting: playbook > Circular dependency between beans > Fix");
    }

    @Test
    @DisplayName("Markdown without front matter gets the heading path alone")
    void noFrontMatter() {
        assertThat(DocumentIngestionService.sectionLine(Map.of(ChunkMetadata.SECTION, "Notes > Setup")))
                .isEqualTo("Notes > Setup");
    }

    @Test
    @DisplayName("a chunk with no section - every PDF and Tika chunk - gets no line, even with a product")
    void noSectionNoLine() {
        assertThat(DocumentIngestionService.sectionLine(Map.of(ChunkMetadata.PROJECT, "Spring Boot"))).isNull();
        assertThat(DocumentIngestionService.sectionLine(Map.of(ChunkMetadata.PAGE_NUMBER, 12))).isNull();
    }
}
