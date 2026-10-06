package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.TurnReviewRequestDto;
import com.example.subhanmishra.service.TurnReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Human review of evaluated turns. The queue to review is a table on the evaluation dashboard. */
@RestController
@RequestMapping("/eval/turns")
public class TurnReviewController {

    private final TurnReviewService reviewService;

    public TurnReviewController(TurnReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PutMapping("/{turnId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable UUID turnId, @Valid @RequestBody TurnReviewRequestDto request) {
        reviewService.review(turnId, request.verdict(), request.notes());
    }
}
