package com.example.subhanmishra.dto;

import java.util.List;

/** The {@code sources} event a streamed answer ends with: {@link ChatAnswerDto}'s grounding half. */
public record ChatSourcesDto(boolean grounded, List<SourceDto> sources) {
}
