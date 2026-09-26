package com.example.subhanmishra.service;

import com.example.subhanmishra.service.eval.AnswerCitations;
import com.example.subhanmishra.service.eval.AnswerScores;
import com.example.subhanmishra.service.eval.Citation;
import com.example.subhanmishra.service.eval.CitationParser;
import com.example.subhanmishra.service.eval.CitationScores;
import com.example.subhanmishra.service.eval.ContextPrecisionScores;
import com.example.subhanmishra.service.eval.EvalScores;
import com.example.subhanmishra.service.eval.ExpectationScores;
import com.example.subhanmishra.service.eval.GoldenCase;
import com.example.subhanmishra.service.eval.RetrievalScores;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes every metric that needs neither an LLM nor a known-correct answer.
 *
 * <p>These run on 100% of live chat traffic, synchronously, because they cost nothing worth measuring:
 * a handful of regex passes over an answer and a comparison against at most top-k retrieved chunks.
 * No network, no model, no database. That is what makes continuous evaluation of real traffic viable
 * at all on a host where a single model call takes the better part of a minute.
 *
 * <p>The dividing line worth remembering when adding a metric here: this class may use only the query,
 * the answer, and the documents the advisor retrieved. Anything that needs to know what the
 * <em>right</em> answer was belongs on the golden path, where a {@link GoldenCase} supplies it.
 */
@Service
public class EvalScoringService {

    /**
     * Phrases indicating the assistant declined. Matched against the opening of the answer only - "I
     * don't have enough information" as a leading clause is a refusal, whereas the same words in the
     * middle of a long answer are usually a caveat attached to an answer that was in fact given.
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
     * The model restating its instructions rather than following them. Not hypothetical: llama3.2
     * answered with the instruction itself and cited nothing, a failure that reads as a perfectly
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
     * <p>Which candidates count as citations, and which of those the context supports, are decided by
     * {@link AnswerCitations} - the same rules the chat endpoints use to strip citations from the answer
     * and report them to the caller, so the two cannot disagree about what a citation is.
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
        }

        return new AnswerScores(answer.length(), refused, echoed, List.copyOf(matched),
                                List.copyOf(missing), List.copyOf(forbidden));
    }

    /**
     * Whether the retrieved set contains a page the case expected, and at what rank.
     *
     * @return the 1-based rank of the first expected page retrieved, or 0 if none was. Callers take the
     *         reciprocal for MRR; returning the rank rather than the reciprocal keeps "no hit" as an
     *         exact 0 rather than a division no one can invert.
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
     * <p>This is RAGAS's {@code NonLLMContextPrecisionWithReference} in all but the relevance test:
     * RAGAS compares retrieved context strings against reference context strings, whereas here the
     * comparison is page attribution, which this corpus carries on every chunk and which is what the
     * dataset was curated in terms of. It costs nothing - no LLM call, no embedding, just the citation
     * headers that {@link #firstRelevantRank} already parses.
     *
     * <p><strong>Read it as a floor, not as a value.</strong> {@code expectedPages} was verified as
     * "pages that genuinely contain the answer", not as a complete labelling of every page that could
     * usefully inform an answer, so a chunk that helped from a page the list omits is scored as noise.
     * The number is therefore systematically pessimistic and its absolute level means little; what
     * means something is the same number moving between two runs of the same dataset.
     * {@code ContextPrecisionEvaluator} is the cross-check on exactly this.
     *
     * @return null when the case declares no {@code expectedPages}. That is "not scored", which is not
     *         the same as 0.0 meaning "nothing relevant was retrieved", and folding the two together
     *         would drag the suite average down with every grounding and capability case in the set.
     */
    public @Nullable ContextPrecisionScores contextPrecision(@Nullable List<Document> retrieved,
                                                             GoldenCase goldenCase) {
        if (!goldenCase.scoresRecall()) {
            return null;
        }
        return ContextPrecisionScores.of(relevance(retrieved, goldenCase));
    }

    /**
     * Per-rank relevance against the case's expected pages, in rank order.
     *
     * <p>The single definition of "relevant" on the reference-based path. Rank and precision both read
     * it, and having had two copies of the page-matching rule is how they would drift apart.
     */
    private List<Boolean> relevance(@Nullable List<Document> retrieved, GoldenCase goldenCase) {
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
                                  && goldenCase.expectedPages().contains(header.pageNumber()));
        }
        return relevance;
    }
}
