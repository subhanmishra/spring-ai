package com.example.subhanmishra.service.eval;


import java.util.List;

/**
 * A named set of {@link GoldenCase}s, loaded from YAML.
 *
 * <p>The suite name tags every metric a run publishes, so it is what separates one dataset's trend
 * line from another's in Grafana. Changing it starts a new series rather than continuing the old one.
 *
 * @param suite       identifies the dataset in metrics and in the {@code eval_run} table
 * @param corpus      the document(s) this suite assumes are indexed, for the error message when they
 *                    are not. A suite silently scoring zero because its corpus was never uploaded is
 *                    indistinguishable from a pipeline regression, which is the worst way for this to
 *                    fail.
 * @param description what the suite is for
 * @param cases       the cases, run in declaration order
 */
public record GoldenDataset(String suite, String corpus, String description, List<GoldenCase> cases) {

    public GoldenDataset {
        cases = cases != null ? List.copyOf(cases) : List.of();
    }
}
