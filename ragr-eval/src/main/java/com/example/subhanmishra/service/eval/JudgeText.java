package com.example.subhanmishra.service.eval;

import com.example.subhanmishra.citation.CitationParser;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What every judge does to its input and output: passages shown without their citation header and
 * bounded in length, and one-word or one-digit verdicts read strictly.
 *
 * <p>Strict reading is the rule throughout. A reply that is neither of the expected words is a failed
 * measurement, recorded as null - never coerced into the nearer answer, because a judge that rambled has
 * not said anything about the answer.
 */
public final class JudgeText {

    /**
     * Comfortably above the chunk-size budget in {@code application-dev.yaml} - chunks average 279
     * tokens and are capped below 445 - so this truncates nothing in practice and exists only so that
     * an unsplittable oversized table row cannot blow the judge's context window.
     */
    static final int MAX_PASSAGE_CHARS = 4_000;

    /** The leading word of a verdict. Matched as a whole word so that "not" cannot read as "no". */
    private static final Pattern FIRST_WORD = Pattern.compile("[a-z]+");

    private static final Pattern FIRST_DIGIT = Pattern.compile("\\d");

    private JudgeText() {
    }

    /**
     * The chunk as a judge should see it: citation header removed, since it is identical in shape on
     * every chunk and carries no evidence, and truncated so a passage cannot overrun
     * {@code judge-num-ctx}. Ollama truncates an over-long prompt from the left, which would silently
     * drop the instruction and leave the judge answering a question it was never asked.
     */
    public static String passage(Document document) {
        String stripped = CitationParser.stripHeader(document.getText());
        String text = stripped != null ? stripped : "";
        return text.length() <= MAX_PASSAGE_CHARS ? text : text.substring(0, MAX_PASSAGE_CHARS);
    }

    /** The passages of several chunks, numbered from 1, for the judges that read a whole context. */
    public static String passages(List<Document> documents) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            text.append("[Passage ").append(i + 1).append("]\n").append(passage(documents.get(i))).append("\n\n");
        }
        return text.toString().strip();
    }

    /** True for YES, false for NO, null for anything else. */
    public static @Nullable Boolean yesNo(@Nullable String response) {
        if (response == null) {
            return null;
        }
        Matcher matcher = FIRST_WORD.matcher(response.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group()) {
            case "yes" -> Boolean.TRUE;
            case "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** The first digit of the reply if it lies in {@code [min, max]}, else null. */
    public static @Nullable Integer digit(@Nullable String response, int min, int max) {
        if (response == null) {
            return null;
        }
        Matcher matcher = FIRST_DIGIT.matcher(response);
        if (!matcher.find()) {
            return null;
        }
        int value = Integer.parseInt(matcher.group());
        return value >= min && value <= max ? value : null;
    }

    /** The lines of a list reply, bullets and numbering removed, blanks dropped. */
    static List<String> lines(@Nullable String response) {
        if (response == null) {
            return List.of();
        }
        return response.lines()
                       .map(line -> line.replaceFirst("^\\s*(?:[-*\\u2022]|\\d+[.)])\\s*", "").strip())
                       .filter(line -> !line.isEmpty())
                       .collect(Collectors.toList());
    }
}
