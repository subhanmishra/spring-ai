package com.example.subhanmishra.service;

import com.example.subhanmishra.service.eval.AnswerScores;
import com.example.subhanmishra.service.eval.Citation;
import com.example.subhanmishra.service.eval.CitationParser;
import com.example.subhanmishra.service.eval.CitationScores;
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
     * <p>The filtering step is what keeps this metric meaningful rather than noisy. A bracketed
     * filename is only treated as a citation when it carries a page number, or when its filename is one
     * of the documents actually retrieved. Answers about this corpus are full of parentheses holding
     * filenames - "(application.properties)", "(pom.xml)" - which are the model naming a file in prose,
     * not claiming a source. Counting those as fabricated would swamp the fabrication rate with false
     * positives and make the one metric that matters most here unreadable.
     *
     * <p>Note what the rule still catches: a page number attached to a filename that was never
     * retrieved ("(application.properties, p. 12)") is counted and marked fabricated, because
     * inventing a page for a file the model was not given is exactly the failure being measured.
     */
    private CitationScores scoreCitations(String answer, List<Document> hits) {
        List<Citation> available = CitationParser.availableCitations(hits);
        Set<String> availableNames = CitationParser.availableFileNames(hits);

        List<Citation> emitted = CitationParser.parseAnswerCandidates(answer).stream()
                .filter(candidate -> candidate.pageNumber() != null
                        || availableNames.contains(candidate.fileName().toLowerCase(Locale.ROOT)))
                .toList();

        if (emitted.isEmpty()) {
            return new CitationScores(0, 0, 0, available.size(), List.of());
        }

        List<Citation> fabricated = new ArrayList<>();
        int valid = 0;
        for (Citation citation : emitted) {
            if (available.stream().anyMatch(citation::matches)) {
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
        if (retrieved == null || !goldenCase.scoresRecall()) {
            return 0;
        }
        for (int rank = 1; rank <= retrieved.size(); rank++) {
            Citation header = CitationParser.parseHeader(retrieved.get(rank - 1).getText());
            if (header == null) {
                continue;
            }
            boolean fileMatches = goldenCase.expectedFile() == null
                    || goldenCase.expectedFile().equalsIgnoreCase(header.fileName());
            if (fileMatches && header.pageNumber() != null
                    && goldenCase.expectedPages().contains(header.pageNumber())) {
                return rank;
            }
        }
        return 0;
    }
}
