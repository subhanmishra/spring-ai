package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A citation the model wrote into the answer, checked against the sources it was given.
 *
 * <p>Citations are removed from the answer text, so this is where they are reported: one entry per
 * distinct source, in the order first cited.
 */
public record CitationDto(

        @Schema(example = "01-spring-boot__028-reference-features-task-execution-and-scheduling.md")
        String fileName,

        @Schema(description = "The page cited. Absent when the citation names no page - always, for a "
                + "source without pages - or names something that is not a page number", example = "42")
        Integer page,

        Status status,

        @Schema(description = "The ref of the first source this citation points at, absent when UNVERIFIED",
                example = "1")
        Integer sourceRef,

        @Schema(description = "What the model wrote where the page belongs on an UNVERIFIED citation, when "
                + "it was not a page number - a section number, say", example = "5.3")
        String writtenPage) {

    public enum Status {
        /** Points at a source the answer was actually given, exactly as the model wrote it. */
        VERIFIED,
        /** Points at nothing the answer was given - a page or document the model invented. */
        UNVERIFIED
    }
}
