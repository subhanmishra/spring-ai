package com.example.subhanmishra.service.provenance;

import java.util.List;

/**
 * Which version of the ingestion pipeline produced a document's chunks.
 *
 * <p>Two halves, and both are needed. {@link #CURRENT_VERSION} catches <em>code</em> changes, which
 * is the load-bearing half: every pipeline change that has actually invalidated this corpus - the
 * citation header, paragraph coalescing, the jsoup XML-parser fix - was a code change with no
 * configuration footprint, so a settings snapshot alone would have missed all three.
 * {@link PipelineSettings} says what differed when the difference <em>is</em> configuration.
 */
public record PipelineProvenance(Integer version, PipelineSettings settings) {

    /**
     * Bump this whenever the pipeline changes in a way that makes already-stored chunks differ from
     * what the same source file would produce now, and add a line to the log below saying what
     * changed. Chunks written under an older version cannot be brought up to date in place - the
     * document has to be ingested again - so the number is what tells you which ones to re-upload.
     *
     * <p>History:
     * <ul>
     *   <li><b>1</b> - first version to record provenance at all. Documents ingested before this
     *       carry no version and are reported stale, because what produced them is unknown.</li>
     * </ul>
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * Why a document's chunks are out of date, or {@code null} when they are current.
     *
     * <p>The reason matters as much as the flag: "stale" on its own leaves the reader to guess
     * whether a re-upload is worth it, while naming the differing setting or version makes it a
     * decision.
     */
    public String stalenessAgainstCurrent(PipelineSettings current) {
        if (version == null || settings == null) {
            return "ingested before pipeline provenance was recorded, so what produced its chunks is unknown";
        }
        if (version < CURRENT_VERSION) {
            return "ingested by pipeline version %d, current is %d".formatted(version, CURRENT_VERSION);
        }
        List<String> differences = settings.differencesFrom(current);
        if (!differences.isEmpty()) {
            return "ingested with different settings: " + String.join(", ", differences);
        }
        return null;
    }
}
