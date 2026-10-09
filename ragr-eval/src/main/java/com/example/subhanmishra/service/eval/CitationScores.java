package com.example.subhanmishra.service.eval;

import com.example.subhanmishra.citation.Citation;

import java.util.List;

/**
 * How faithfully an answer cited the context it was given.
 *
 * <p>The metric the design is built around - the citation header, the prompt's citation rule and the
 * choice of model all exist to move it. It needs no judge: the answer's citations are checked against the
 * chunks it was given.
 *
 * @param emitted    distinct citations the answer contains
 * @param valid      citations matching a retrieved chunk's header
 * @param fabricated citations matching nothing retrieved - an invented page, the most damaging failure
 *                   there is, because to a reader it looks exactly like a real one
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
     * <p>So always read it with {@link #emitted}: an assistant that stopped citing would look flawless.
     * {@code citationCoverage} catches that.
     */
    public double validityRate() {
        return emitted == 0 ? 1.0 : (double) valid / emitted;
    }

    /** Fraction of emitted citations that were invented. The number to alert on. */
    public double fabricationRate() {
        return emitted == 0 ? 0.0 : (double) fabricated / emitted;
    }

    /**
     * Share of the offered sources the answer cited. Low coverage with high validity means the model leans
     * on one passage and ignores the rest.
     */
    public double coverage() {
        return available == 0 ? 0.0 : (double) valid / available;
    }
}
