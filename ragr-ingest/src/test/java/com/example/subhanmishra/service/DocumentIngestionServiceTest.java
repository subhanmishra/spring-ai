package com.example.subhanmishra.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the headings that move a chunk's section on. The case behind it: the manual's appendices are
 * numbered "10.A.11." and titled "Appendix A: ...", neither of which a numbered-heading match saw, so all
 * 315 appendix chunks were tagged with the last how-to section, "Use Testcontainers for integration testing".
 */
class DocumentIngestionServiceTest {

    @Test
    @DisplayName("chapter and appendix titles count as headings, in order with the numbered ones")
    void chapterAndAppendixTitles() {
        String text = """
                testing values to the Spring Environment.

                Chapter 10. Appendices

                Appendix A: Common Application properties

                10.A.1. Core Properties
                | debug | false | Enable debug logs. |
                """;

        assertThat(DocumentIngestionService.sectionHeadings(text))
                .containsExactly("Chapter 10. Appendices", "Appendix A: Common Application properties",
                                 "10.A.1. Core Properties");
    }

    @Test
    @DisplayName("an appendix with no numbered sections still names itself")
    void appendixWithoutNumberedSections() {
        String text = """
                Appendix C: Auto-configuration Classes

                This appendix contains details of all of the auto-configuration classes.
                """;

        assertThat(DocumentIngestionService.sectionHeadings(text))
                .containsExactly("Appendix C: Auto-configuration Classes");
    }

    @Test
    @DisplayName("a title broken across lines at a hyphen is joined")
    void hyphenatedTitle() {
        String text = """
                Chapter 5. Spring Boot Actuator: Production-

                ready Features

                Spring Boot includes a number of additional features.
                """;

        assertThat(DocumentIngestionService.sectionHeadings(text))
                .containsExactly("Chapter 5. Spring Boot Actuator: Production-ready Features");
    }

    @Test
    @DisplayName("a mention of a chapter inside a sentence is not a heading")
    void mentionInProse() {
        assertThat(DocumentIngestionService.sectionHeadings("As described in Chapter 4. Spring Boot Features, set it."))
                .isEmpty();
    }
}
