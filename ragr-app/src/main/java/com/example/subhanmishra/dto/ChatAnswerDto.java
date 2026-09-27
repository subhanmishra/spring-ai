package com.example.subhanmishra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The response to a chat turn: the answer, and the evidence for it as data rather than as text. */
public record ChatAnswerDto(

        @Schema(description = "The answer, in Markdown, with its inline citations removed - they are "
                + "reported in citations instead")
        String answer,

        @Schema(description = "Whether anything was retrieved from the uploaded documents. False means "
                + "the answer came from the model's general knowledge alone")
        boolean grounded,

        List<SourceDto> sources,

        List<CitationDto> citations,

        UsageDto usage) {
}
