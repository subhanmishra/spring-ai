package com.example.subhanmishra.service.eval;

/**
 * Whether a golden case's declared expectations were met.
 *
 * <p>Separate from {@link AnswerScores} because these are the only scores that have no meaning on live
 * traffic: they compare what happened against what a {@link GoldenCase} said should happen, and a real
 * user's turn declares nothing. {@link #NONE} is what the online path carries.
 *
 * <p>These exist because the alternative is worse than not having them. A dataset can declare
 * {@code expectRefusal} and {@code expectGrounded} and have nothing check them, in which case the
 * cases still pass, the suite still reports green, and the assertions are decoration - which is a more
 * dangerous state than having no assertion at all, because it looks like coverage.
 *
 * @param refusalUnexpected the case expected an answer and the assistant declined. The regression this
 *                          catches is a change that makes the assistant refuse anything outside the
 *                          corpus, contradicting its system prompt's General Knowledge capability.
 * @param refusalMissing    the case expected the assistant to decline and it answered anyway
 * @param groundingMissing  the case expected the corpus to answer it and retrieval returned nothing.
 *                          Distinct from a wrong answer: the generation step never had a chance, so
 *                          this points at embedding, chunking or the similarity threshold rather than
 *                          at the model or the prompt.
 */
public record ExpectationScores(boolean refusalUnexpected,
                                boolean refusalMissing,
                                boolean groundingMissing) {

    public static final ExpectationScores NONE = new ExpectationScores(false, false, false);

    public boolean anyUnmet() {
        return refusalUnexpected || refusalMissing || groundingMissing;
    }
}
