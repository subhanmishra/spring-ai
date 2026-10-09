package com.example.subhanmishra.service.eval;


import java.util.List;

/**
 * A named set of {@link GoldenCase}s, loaded from YAML.
 *
 * <p>The suite name tags every metric a run publishes; changing it starts a new trend line.
 *
 * @param suite       identifies the dataset in metrics and in {@code eval_run}
 * @param corpus      the documents the suite needs stored, named in the error when they are not - a suite
 *                    scoring zero for a missing corpus would look exactly like a regression
 * @param description what the suite is for
 * @param cases       the cases, run in declaration order
 */
public record GoldenDataset(String suite, String corpus, String description, List<GoldenCase> cases) {

    public GoldenDataset {
        cases = cases != null ? List.copyOf(cases) : List.of();
    }
}
