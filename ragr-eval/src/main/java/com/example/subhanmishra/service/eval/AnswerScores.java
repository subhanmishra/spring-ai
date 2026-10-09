package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * Reference-free properties of the answer text itself.
 *
 * @param answerChars    length of the answer. A collapse towards zero means answers are being cut off
 *                       (the {@code think} setting in ragr-app's application-dev.yaml)
 * @param refused        whether the assistant declined. Tracked because restoring Spring AI's stock
 *                       template wording would make it refuse general questions it is meant to answer
 * @param echoedInstruction whether the answer repeats the citation instruction instead of following it -
 *                       which has happened, and looks like a normal answer to every other metric
 * @param matchedPhrases expected phrases found, for a golden case that declared them
 * @param missingPhrases expected phrases absent
 * @param forbiddenFound forbidden phrases present
 */
public record AnswerScores(int answerChars,
                           boolean refused,
                           boolean echoedInstruction,
                           List<String> matchedPhrases,
                           List<String> missingPhrases,
                           List<String> forbiddenFound) {

    /**
     * Fraction of the expected phrases present, or 1.0 when the case declared none - a case that
     * asserts nothing about content has not failed that assertion.
     */
    public double phraseCoverage() {
        int expected = matchedPhrases.size() + missingPhrases.size();
        return expected == 0 ? 1.0 : (double) matchedPhrases.size() / expected;
    }
}
