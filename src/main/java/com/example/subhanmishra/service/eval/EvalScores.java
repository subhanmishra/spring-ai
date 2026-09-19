package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything scored for one query, whether it came from live traffic or from the golden suite.
 *
 * <p>The judged fields are boxed and null until the LLM judges have run - which on the live path may
 * be never, because judging is sampled and may be dropped under load. Null therefore means "not
 * judged", distinct from {@code FALSE} meaning "judged and failed", and anything aggregating these
 * must keep the two apart or a dropped judgement will read as a failure.
 *
 * @param relevancy    whether the answer addressed the question given the context, per the LLM judge
 * @param groundedness whether the answer's claims are supported by the context, per the LLM judge
 */
public record EvalScores(RetrievalScores retrieval,
                         CitationScores citations,
                         AnswerScores answer,
                         ExpectationScores expectations,
                         @Nullable Boolean relevancy,
                         @Nullable Boolean groundedness) {

    public EvalScores withJudgements(@Nullable Boolean relevancy, @Nullable Boolean groundedness) {
        return new EvalScores(retrieval, citations, answer, expectations, relevancy, groundedness);
    }

    public boolean judged() {
        return relevancy != null || groundedness != null;
    }

    /**
     * Why this would be considered a failure, or empty when nothing is wrong. Only assertions that were
     * actually made are reported, so an unjudged answer is not failed for lacking a verdict.
     */
    public List<String> failureReasons() {
        List<String> reasons = new ArrayList<>();
        if (citations.fabricated() > 0) {
            reasons.add("fabricated citations: " + citations.fabricatedCitations());
        }
        if (!answer.missingPhrases().isEmpty()) {
            reasons.add("missing expected text: " + answer.missingPhrases());
        }
        if (!answer.forbiddenFound().isEmpty()) {
            reasons.add("contains forbidden text: " + answer.forbiddenFound());
        }
        if (answer.echoedInstruction()) {
            reasons.add("echoed the citation instruction instead of citing");
        }
        if (expectations.refusalUnexpected()) {
            reasons.add("declined to answer a question it was expected to answer");
        }
        if (expectations.refusalMissing()) {
            reasons.add("answered a question it was expected to decline");
        }
        if (expectations.groundingMissing()) {
            reasons.add("retrieved nothing for a question the corpus was expected to answer");
        }
        if (Boolean.FALSE.equals(relevancy)) {
            reasons.add("judged not relevant to the question");
        }
        if (Boolean.FALSE.equals(groundedness)) {
            reasons.add("judged not grounded in the retrieved context");
        }
        return reasons;
    }

    public boolean passed() {
        return failureReasons().isEmpty();
    }
}
