package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * How faithfully an answer cited the context it was given.
 *
 * <p>This is the metric the pipeline's design is most invested in - the citation header in
 * {@code DocumentIngestionService}, the restated rule in {@code SpringAiConfig.QA_PROMPT_TEMPLATE} and
 * the choice of chat model all exist to move it - and until now it was only ever measured by hand.
 * None of it needs an LLM or a golden answer: the citations the model wrote are checked against the
 * citations its retrieved context actually offered.
 *
 * @param emitted    distinct citations the answer contains
 * @param valid      citations matching a retrieved chunk's header
 * @param fabricated citations matching nothing retrieved - the model invented a page. The single most
 *                   damaging failure this pipeline can produce, because a fabricated page number is
 *                   indistinguishable from a real one to the reader and looks like diligence.
 * @param available  distinct citations the retrieved context offered
 * @param fabricatedCitations the offending citations, kept so a failing run names them rather than
 *                   only counting them
 */
public record CitationScores(int emitted,
                             int valid,
                             int fabricated,
                             int available,
                             List<Citation> fabricatedCitations) {

    public static final CitationScores NONE = new CitationScores(0, 0, 0, 0, List.of());

    /**
     * Fraction of emitted citations that were real, or 1.0 when the answer cited nothing.
     *
     * <p>An answer with no citations is vacuously perfect here, which is why this must always be read
     * next to {@link #emitted} - an assistant that stops citing altogether would show a flawless
     * validity rate. {@code citationCoverage} is the metric that catches that.
     */
    public double validityRate() {
        return emitted == 0 ? 1.0 : (double) valid / emitted;
    }

    /** Fraction of emitted citations that were invented. The number to alert on. */
    public double fabricationRate() {
        return emitted == 0 ? 0.0 : (double) fabricated / emitted;
    }

    /**
     * Fraction of the offered citations the answer actually used. Low coverage with high validity means
     * the model is grounding itself in one passage and ignoring the rest of the context it paid to
     * retrieve.
     */
    public double coverage() {
        return available == 0 ? 0.0 : (double) valid / available;
    }
}
