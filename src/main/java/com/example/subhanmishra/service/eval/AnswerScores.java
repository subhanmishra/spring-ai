package com.example.subhanmishra.service.eval;

import java.util.List;

/**
 * Reference-free properties of the answer text itself.
 *
 * @param answerChars    length of the answer. A collapse towards zero is the signature of the
 *                       {@code num-predict} / thinking-token interaction documented in
 *                       {@code application-dev.yaml}, where answers were truncated mid-sentence.
 * @param refused        whether the assistant declined to answer. Worth tracking continuously because
 *                       the stock QuestionAnswerAdvisor template forbids answering beyond the context,
 *                       and anyone restoring that wording would make the assistant refuse the general
 *                       conversation its system prompt explicitly promises.
 * @param echoedInstruction whether the answer parrots the citation instruction back instead of obeying
 *                       it. Not hypothetical: llama3.2 answered "Remember to cite your sources when
 *                       referencing configuration values from documents" in place of citing anything,
 *                       and that failure reads as a normal answer to every other metric here.
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
