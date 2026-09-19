package com.example.subhanmishra.service.eval;


import java.util.List;

/**
 * One case in the curated regression dataset.
 *
 * <p>Every field beyond {@code id} and {@code query} is an assertion, and every assertion is optional.
 * That is deliberate: the useful cases are not all the same shape. A recall case declares
 * {@code expectedPages} and cares only about retrieval; a hallucination case declares
 * {@code expectGrounded: false} and asserts that the model declines rather than inventing a citation;
 * a conversational case declares {@code expectRefusal: false} and guards the system prompt's General
 * Knowledge capability against a change that makes the assistant refuse everything off-corpus.
 *
 * @param id             stable identifier, used as the metric tag and the row key, so renaming one
 *                       breaks continuity with earlier runs. Prefer adding a case to renaming one.
 * @param query          the question, sent through the real chat path exactly as a user would send it
 * @param description    why this case exists - what regression it is meant to catch
 * @param expectedFile   the document the answer should come from, matched case-insensitively
 * @param expectedPages  pages that genuinely contain the answer. Retrieving any one of them counts as a
 *                       hit; the rank of the first is what feeds reciprocal rank. Leave empty to skip
 *                       recall scoring for this case rather than asserting a hit against nothing.
 * @param mustContain    strings the answer must include, compared case-insensitively. Intended for
 *                       exact tokens - a property name, a class name, a default value - not prose,
 *                       which a model will paraphrase.
 * @param mustNotContain strings the answer must not include
 * @param expectGrounded whether the corpus can actually answer this. False marks a deliberate
 *                       out-of-corpus question, where the correct behaviour is to answer without
 *                       citing anything - so any citation at all is a fabrication.
 * @param expectRefusal  whether the assistant should decline. Almost always false: the system prompt
 *                       explicitly promises to answer general knowledge, and the stock
 *                       QuestionAnswerAdvisor template that forbids it is softened for that reason.
 */
public record GoldenCase(String id,
                         String query,
                         String description,
                         String expectedFile,
                         List<Integer> expectedPages,
                         List<String> mustContain,
                         List<String> mustNotContain,
                         Boolean expectGrounded,
                         Boolean expectRefusal) {

    /** Normalises the optional collections to empty and the optional flags to their defaults. */
    public GoldenCase {
        expectedPages = expectedPages != null ? List.copyOf(expectedPages) : List.of();
        mustContain = mustContain != null ? List.copyOf(mustContain) : List.of();
        mustNotContain = mustNotContain != null ? List.copyOf(mustNotContain) : List.of();
        expectGrounded = expectGrounded == null || expectGrounded;
        expectRefusal = expectRefusal != null && expectRefusal;
    }

    /** Whether this case asserts anything about retrieval. Recall is not scored when it does not. */
    public boolean scoresRecall() {
        return !expectedPages.isEmpty();
    }
}
