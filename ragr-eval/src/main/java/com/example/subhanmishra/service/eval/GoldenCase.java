package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One case in the curated regression dataset.
 *
 * <p>Every field beyond {@code id} and {@code query} is an optional assertion, because useful cases come
 * in different shapes: a recall case lists {@code expectedPages}; a hallucination case sets
 * {@code expectGrounded: false} and expects no invented citation; a conversational case sets
 * {@code expectRefusal: false}, so the assistant keeps answering general questions.
 *
 * @param id             stable identifier - the metric tag and row key. Renaming one breaks continuity
 *                       with earlier runs; add a case instead
 * @param query          the question, sent through the real chat path
 * @param description    why the case exists - the regression it catches
 * @param expectedFile   the document the answer should come from, matched ignoring case
 * @param expectedPages  pages that hold the answer. Retrieving any is a hit; the first one's rank feeds
 *                       MRR. Empty skips recall scoring for this case
 * @param mustContain    strings the answer must include, ignoring case. Exact tokens - a property, a
 *                       class, a default - not prose, which the model paraphrases
 * @param mustNotContain strings the answer must not include
 * @param expectGrounded whether the corpus can answer this. False marks an out-of-corpus question, where
 *                       any citation is a fabrication
 * @param expectRefusal  whether the assistant should decline. Almost always false: it is meant to answer
 *                       general questions too
 * @param expectedPagesMode {@code ALL} (default) when the answer needs every listed page, {@code ANY} when
 *                       each answers on its own. Only page recall reads it
 * @param category       the {@link TaskType} this case exercises, for the per-task breakdown; optional
 * @param relevantPages  other pages whose chunks hold part of the answer, checked by reading them. Only the
 *                       judge's calibration reads these; recall, hit rate and MRR stay on the expected pages
 * @param mustNotMatch   regular expressions the answer must not match, ignoring case - for a wrong answer
 *                       that uses the right words. Compiled on load, so a bad pattern fails before any
 *                       question is asked
 */
public record GoldenCase(String id,
                         String query,
                         String description,
                         String expectedFile,
                         List<Integer> expectedPages,
                         List<String> mustContain,
                         List<String> mustNotContain,
                         Boolean expectGrounded,
                         Boolean expectRefusal,
                         ExpectedPagesMode expectedPagesMode,
                         @Nullable TaskType category,
                         List<Integer> relevantPages,
                         List<String> mustNotMatch) {

    /** Normalises the optional collections to empty and the optional flags to their defaults. */
    public GoldenCase {
        expectedPages = expectedPages != null ? List.copyOf(expectedPages) : List.of();
        mustContain = mustContain != null ? List.copyOf(mustContain) : List.of();
        mustNotContain = mustNotContain != null ? List.copyOf(mustNotContain) : List.of();
        expectGrounded = expectGrounded == null || expectGrounded;
        expectRefusal = expectRefusal != null && expectRefusal;
        expectedPagesMode = expectedPagesMode != null ? expectedPagesMode : ExpectedPagesMode.ALL;
        relevantPages = relevantPages != null ? List.copyOf(relevantPages) : List.of();
        mustNotMatch = mustNotMatch != null ? List.copyOf(mustNotMatch) : List.of();
        mustNotMatch.forEach(Pattern::compile);
    }

    /** {@link #mustNotMatch}, compiled. */
    public List<Pattern> forbiddenPatterns() {
        return mustNotMatch.stream().map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE)).toList();
    }

    /** Whether this case asserts anything about retrieval. Recall is not scored when it does not. */
    public boolean scoresRecall() {
        return !expectedPages.isEmpty();
    }

    /** Every page a chunk relevant to the question may come from: the expected pages and the relevant ones. */
    public List<Integer> referencePages() {
        return Stream.concat(expectedPages.stream(), relevantPages.stream()).distinct().toList();
    }

    /** Whether an answer needs every expected page, or any one of them. */
    public enum ExpectedPagesMode {
        ALL,
        ANY
    }
}
