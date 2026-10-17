package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.GoldenRunDto;
import com.example.subhanmishra.service.GoldenEvalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Golden runs: start one in the background, then follow it by id. A run completes once every case is
 * scored; the judges work through its turns afterwards, as they do live turns.
 */
@RestController
@RequestMapping("/eval/golden/runs")
public class GoldenRunController {

    private final GoldenEvalService goldenEvalService;

    public GoldenRunController(GoldenEvalService goldenEvalService) {
        this.goldenEvalService = goldenEvalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GoldenRunDto start() {
        return goldenEvalService.start();
    }

    @GetMapping("/latest")
    public GoldenRunDto latest() {
        return goldenEvalService.latest();
    }

    @GetMapping("/{runId}")
    public GoldenRunDto run(@PathVariable UUID runId) {
        return goldenEvalService.view(runId);
    }
}
