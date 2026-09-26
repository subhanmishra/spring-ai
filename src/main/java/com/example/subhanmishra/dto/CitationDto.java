package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A citation the model wrote into the answer, checked against the sources it was given.
 *
 * <p>The model cites inline and the service removes those citations from the answer text, so this is
 * where they are reported. One entry per distinct source cited, in the order the answer first cites it -
 * followed by any bare section references the model wrote without a filename, "(5.3)", each REPAIRED to
 * the page that heading sits on unless an earlier entry already cites that page.
 */
public record CitationDto(

        @Schema(example = "spring-boot-reference.pdf")
        String fileName,

        @Schema(description = "The page cited, after any repair. Absent when the citation names no page, "
                + "or names something that is not a page number", example = "42")
        Integer page,

        Status status,

        @Schema(description = "The ref of the first source this citation points at, absent when UNVERIFIED",
                example = "1")
        Integer sourceRef,

        @Schema(description = "What the model wrote where the page belongs, when it was not a page number: "
                + "the section number a REPAIRED citation was resolved from, or the unresolvable "
                + "label of an UNVERIFIED one", example = "5.3")
        String writtenPage) {

    public enum Status {
        /** Points at a source the answer was actually given, exactly as the model wrote it. */
        VERIFIED,
        /** The model wrote a section number as the page; it was resolved to the page that heading is on. */
        REPAIRED,
        /** Points at nothing the answer was given - a page or document the model invented. */
        UNVERIFIED
    }
}
