package com.example.subhanmishra.service;

import com.example.subhanmishra.citation.AnswerCitations;
import com.example.subhanmishra.service.eval.AnswerScores;
import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationParser;
import com.example.subhanmishra.service.eval.CitationScores;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.ExpectationScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.RetrievalRanking;
import com.example.subhanmishra.service.eval.RetrievalScores;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Computes every metric that needs neither an AI judge nor a known-correct answer.
 *
 * <p>These run on every live turn because they cost nothing: a few regex passes over the answer and a
 * comparison with the retrieved chunks. No network, no model, no database.
 *
 * <p><b>The rule for adding a metric here:</b> it may use only the question, the answer and the retrieved
 * chunks. Anything that needs the <em>right</em> answer belongs on the golden path, where a
 * {@link GoldenCase} supplies it.
 */
@Service
public class EvalScoringService {

    /**
     * Phrases that mean the assistant declined, matched at the start of the answer only. "I don't have
     * enough information" opening an answer is a refusal; the same words mid-answer are usually a caveat.
     */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "i don't have enough information",
            "i do not have enough information",
            "i don't have access",
            "i do not have access",
            "i cannot answer",
            "i can't answer",
            "i'm unable to answer",
            "the provided context does not contain",
            "the context does not contain",
            "there is no information");

    /** How much of the answer counts as "the opening" when looking for a refusal. */
    private static final int REFUSAL_WINDOW_CHARS = 200;

    /**
     * The model repeating its instructions instead of following them - it has happened, and looks like a
     * normal answer to every other metric here.
     */
    private static final Pattern ECHOED_INSTRUCTION = Pattern.compile(
            "(remember to cite|be sure to cite|cite your sources|don't forget to cite)",
            Pattern.CASE_INSENSITIVE);

    /** Scores a live chat turn, where nothing is known about what the answer should have been. */
    public EvalScores score(String answer, @Nullable List<Document> retrieved) {
        return score(answer, retrieved, null);
    }

    /**
     * Scores a turn, applying a golden case's assertions when one is supplied.
     *
     * @param answer     the assistant's reply
     * @param retrieved  the chunks {@code QuestionAnswerAdvisor} actually put in the prompt
     * @param goldenCase the case being replayed, or null for live traffic
     */
    public EvalScores score(String answer, @Nullable List<Document> retrieved, @Nullable GoldenCase goldenCase) {
        List<Document> hits = retrieved != null ? retrieved : List.of();
        String text = answer != null ? answer : "";
        AnswerScores answerScores = scoreAnswer(text, goldenCase);

        return new EvalScores(scoreRetrieval(hits),
                              scoreCitations(text, hits),
                              answerScores,
                              scoreExpectations(hits, answerScores, goldenCase),
                              null,
                              null);
    }

    /**
     * Checks a case's declared expectations. Always {@link ExpectationScores#NONE} for live traffic,
     * which declares none.
     */
    private ExpectationScores scoreExpectations(List<Document> hits,
                                                AnswerScores answerScores,
                                                @Nullable GoldenCase goldenCase) {
        if (goldenCase == null) {
            return ExpectationScores.NONE;
        }
        boolean expectRefusal = Boolean.TRUE.equals(goldenCase.expectRefusal());
        boolean expectGrounded = Boolean.TRUE.equals(goldenCase.expectGrounded());

        return new ExpectationScores(!expectRefusal && answerScores.refused(),
                                     expectRefusal && !answerScores.refused(),
                                     expectGrounded && hits.isEmpty());
    }

    private RetrievalScores scoreRetrieval(List<Document> hits) {
        if (hits.isEmpty()) {
            return RetrievalScores.EMPTY;
        }

        double top = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        int withHeader = 0;
        LinkedHashSet<Integer> distinctPages = new LinkedHashSet<>();

        for (Document hit : hits) {
            Double score = hit.getScore();
            if (score != null) {
                top = Math.max(top, score);
                lowest = Math.min(lowest, score);
            }
            Citation header = CitationParser.parseHeader(hit.getText());
            if (header != null) {
                withHeader++;
                if (header.pageNumber() != null) {
                    distinctPages.add(header.pageNumber());
                }
            }
        }

        // A vector store that returns no scores is a real possibility; report zeros rather than
        // infinities, which would poison every aggregate downstream.
        boolean scored = top != Double.NEGATIVE_INFINITY;
        double topScore = scored ? top : 0;
        double lowestScore = scored ? lowest : 0;

        return new RetrievalScores(hits.size(), topScore, lowestScore, topScore - lowestScore,
                                   List.copyOf(distinctPages), withHeader);
    }

    /**
     * Scores the answer's citations against the ones its context actually offered.
     *
     * <p>{@link AnswerCitations} decides what is a citation and whether it is supported - the same rules
     * chat uses, so the two cannot disagree.
     */
    private CitationScores scoreCitations(String answer, List<Document> hits) {
        List<Citation> available = CitationParser.availableCitations(hits);
        Set<String> availableNames = CitationParser.availableFileNames(hits);

        List<Citation> emitted = CitationParser.parseAnswerCandidates(answer).stream()
                .filter(candidate -> AnswerCitations.isCitation(candidate, availableNames))
                .toList();

        if (emitted.isEmpty()) {
            return new CitationScores(0, 0, 0, available.size(), List.of());
        }

        List<Citation> fabricated = new ArrayList<>();
        int valid = 0;
        for (Citation citation : emitted) {
            if (AnswerCitations.isSupported(citation, available, availableNames)) {
                valid++;
            } else {
                fabricated.add(citation);
            }
        }
        return new CitationScores(emitted.size(), valid, fabricated.size(), available.size(),
                                  List.copyOf(fabricated));
    }

    private AnswerScores scoreAnswer(String answer, @Nullable GoldenCase goldenCase) {
        String lower = answer.toLowerCase(Locale.ROOT);
        String opening = lower.substring(0, Math.min(lower.length(), REFUSAL_WINDOW_CHARS));
        boolean refused = REFUSAL_MARKERS.stream().anyMatch(opening::contains);
        boolean echoed = ECHOED_INSTRUCTION.matcher(answer).find();

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();

        if (goldenCase != null) {
            for (String phrase : goldenCase.mustContain()) {
                if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                    matched.add(phrase);
                } else {
                    missing.add(phrase);
                }
            }
            for (String phrase : goldenCase.mustNotContain()) {
                if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                    forbidden.add(phrase);
                }
            }
            // Reported by the text that matched, not the pattern, so the failure reads like a phrase's.
            for (Pattern pattern : goldenCase.forbiddenPatterns()) {
                Matcher matcher = pattern.matcher(answer);
                if (matcher.find()) {
                    forbidden.add(matcher.group());
                }
            }
        }

        return new AnswerScores(answer.length(), refused, echoed, List.copyOf(matched),
                                List.copyOf(missing), List.copyOf(forbidden));
    }

    /**
     * Whether the retrieved set contains a page the case expected, and at what rank.
     *
     * @return the 1-based rank of the first expected page retrieved, or 0 if none was. Callers take the
     *         reciprocal for MRR.
     */
    public int firstRelevantRank(@Nullable List<Document> retrieved, GoldenCase goldenCase) {
        List<Boolean> relevance = relevance(retrieved, goldenCase);
        for (int rank = 1; rank <= relevance.size(); rank++) {
            if (relevance.get(rank - 1)) {
                return rank;
            }
        }
        return 0;
    }

    /**
     * How well retrieval ordered the chunks, judged against the pages the case declared.
     *
     * <p>RAGAS's non-LLM context precision, except that relevance is by page - every chunk carries its
     * page, and the dataset lists pages. Free: no model call.
     *
     * <p><b>A floor, not a value.</b> The expected pages are the ones with the answer, not every page that
     * could help, so a useful chunk from an unlisted page counts as noise. Compare runs, not levels;
     * {@code ContextPrecisionEvaluator} is the cross-check.
     *
     * @return null when the case lists no expected pages - "not scored", which must not drag the average
     *         down the way 0.0 would
     */
    public @Nullable ContextPrecisionScores contextPrecision(@Nullable List<Document> retrieved,
                                                             GoldenCase goldenCase) {
        if (!goldenCase.scoresRecall()) {
            return null;
        }
        return ContextPrecisionScores.of(relevance(retrieved, goldenCase));
    }

    /**
     * Context precision with a chunk counted as used when the answer cites its page.
     *
     * <p>The third way to decide relevance, beside {@link #contextPrecision} (expected pages) and
     * {@code ContextPrecisionEvaluator} (the judge), and the only one both free and exact. It exists
     * because the judge proved unreliable at this very question. A citation is the model's own statement
     * of where a claim came from, so it answers "was this passage used" with no second opinion.
     *
     * <p>Two limits, both under-counting: a passage used without a citation counts as unused (so an
     * uncited answer scores 0.0), and two chunks from one cited page both count as used. Recorded for
     * live turns and golden runs alike.
     *
     * @return null when nothing was retrieved - an ungrounded answer has no ranked list to score
     */
    public @Nullable ContextPrecisionScores citedPrecision(@Nullable String answer,
                                                           @Nullable List<Document> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) {
            return null;
        }
        Set<String> availableNames = CitationParser.availableFileNames(retrieved);
        // Only what AnswerCitations accepts as a citation, so this agrees with the validity rate and
        // with what the chat API reports - a fabricated citation matches no header and so marks nothing.
        List<Citation> cited = CitationParser.parseAnswerCandidates(answer).stream()
                .filter(candidate -> AnswerCitations.isCitation(candidate, availableNames))
                .toList();

        List<Boolean> relevance = new ArrayList<>(retrieved.size());
        for (Document document : retrieved) {
            Citation header = CitationParser.parseHeader(document.getText());
            relevance.add(header != null && cited.stream().anyMatch(header::matches));
        }
        return ContextPrecisionScores.of(relevance);
    }

    /**
     * The reference-based retrieval metrics over a golden case's whole candidate pool: the same
     * {@link RetrievalRanking} the judge's grades produce on live traffic, with a chunk on an expected
     * page graded 2 and every other chunk 0, plus page-level recall.
     *
     * <p>Page recall answers "did retrieval find everything the answer needs": expected pages in the
     * prompt over expected pages - or, when they are alternatives ({@code expectedPagesMode: ANY}), 1.0
     * once one is. Pooled chunk recall answers something else: whether the threshold kept what the pool
     * found.
     *
     * <p>The stored relevance vector counts chunks on any {@link GoldenCase#referencePages() reference
     * page}, so comparing it with the judge's grades measures the judge, not how narrowly the expected
     * pages were drawn.
     *
     * @param pool      the candidate pool in rank order
     * @param inContext how many leading pool chunks were in the prompt
     * @return null when the case declares no expected pages
     */
    public @Nullable ReferenceScores referenceScores(List<Document> pool, int inContext, GoldenCase goldenCase) {
        if (!goldenCase.scoresRecall()) {
            return null;
        }
        List<Boolean> relevance = relevance(pool, goldenCase, goldenCase.expectedPages());
        RetrievalRanking ranking = RetrievalRanking.of(
                relevance.stream().map(relevant -> relevant ? RetrievalRanking.RELEVANT : 0).toList(), inContext);

        Set<Integer> found = new HashSet<>();
        for (int i = 0; i < inContext; i++) {
            if (relevance.get(i)) {
                Citation header = CitationParser.parseHeader(pool.get(i).getText());
                found.add(header.pageNumber());
            }
        }
        double pageRecall = goldenCase.expectedPagesMode() == GoldenCase.ExpectedPagesMode.ANY
                ? (found.isEmpty() ? 0.0 : 1.0)
                : (double) found.size() / new HashSet<>(goldenCase.expectedPages()).size();
        String vector = relevance(pool, goldenCase, goldenCase.referencePages()).stream()
                                                                             .map(relevant -> relevant ? "1" : "0")
                                                                             .collect(Collectors.joining(","));
        return new ReferenceScores(ranking, pageRecall, vector);
    }

    /**
     * @param ranking    rank metrics with expected-page chunks as the relevant ones
     * @param pageRecall expected pages found in the prompt, honouring the case's mode
     * @param relevance  the pool's reference-page vector, "1,0,0,0,1,0,0,1,0,0" - what the judge's
     *                   chunk grades are calibrated against
     */
    public record ReferenceScores(RetrievalRanking ranking, double pageRecall, String relevance) {
    }

    /**
     * Per-rank relevance against the given pages of the case, in rank order.
     *
     * <p>The one definition of "relevant" by page; rank and precision both use it so they cannot drift.
     */
    private List<Boolean> relevance(@Nullable List<Document> retrieved, GoldenCase goldenCase) {
        return relevance(retrieved, goldenCase, goldenCase.expectedPages());
    }

    private List<Boolean> relevance(@Nullable List<Document> retrieved, GoldenCase goldenCase, List<Integer> pages) {
        if (retrieved == null || !goldenCase.scoresRecall()) {
            return List.of();
        }
        List<Boolean> relevance = new ArrayList<>(retrieved.size());
        for (Document document : retrieved) {
            Citation header = CitationParser.parseHeader(document.getText());
            boolean fileMatches = header != null
                    && (goldenCase.expectedFile() == null
                        || goldenCase.expectedFile().equalsIgnoreCase(header.fileName()));
            relevance.add(fileMatches
                                  && header.pageNumber() != null
                                  && pages.contains(header.pageNumber()));
        }
        return relevance;
    }
}
