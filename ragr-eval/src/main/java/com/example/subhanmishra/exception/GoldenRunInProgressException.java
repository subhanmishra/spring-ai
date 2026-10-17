package com.example.subhanmishra.exception;

/** A golden run was requested while another is in progress; runs share one chat model, so one at a time. */
public class GoldenRunInProgressException extends RuntimeException {

    public GoldenRunInProgressException() {
        super("A golden run is already in progress; GET /eval/golden/runs/latest shows it");
    }
}
