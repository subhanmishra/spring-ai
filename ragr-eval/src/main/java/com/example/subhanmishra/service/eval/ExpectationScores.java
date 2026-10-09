package com.example.subhanmishra.service.eval;

/**
 * Whether a golden case's declared expectations were met.
 *
 * <p>Separate from {@link AnswerScores} because these mean nothing on live traffic: they compare a turn
 * with what a {@link GoldenCase} said should happen. Live turns carry {@link #NONE}.
 *
 * <p>Without these, a dataset could declare {@code expectRefusal} and {@code expectGrounded} with nothing
 * checking them - the suite green, the assertions decoration. That is worse than no assertion: it looks
 * like coverage.
 *
 * @param refusalUnexpected an answer was expected and the assistant declined - catches a change that
 *                          makes it refuse everything outside the corpus
 * @param refusalMissing    a refusal was expected and the assistant answered
 * @param groundingMissing  the corpus should answer it and nothing was retrieved. Not a wrong answer: the
 *                          model never had a chance, so look at embedding, chunking or the threshold
 */
public record ExpectationScores(boolean refusalUnexpected,
                                boolean refusalMissing,
                                boolean groundingMissing) {

    public static final ExpectationScores NONE = new ExpectationScores(false, false, false);

    public boolean anyUnmet() {
        return refusalUnexpected || refusalMissing || groundingMissing;
    }
}
