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
 * <p>Strict throughout: a reply that is not one of the expected words is a failed measurement (null),
 * never rounded to the nearer answer - a judge that rambled has said nothing.
 */
public final class JudgeText {

    /**
     * Well above any chunk (ragr-ingest's chunk-size is 400 tokens), so it truncates nothing in practice.
     * It exists so one huge table row cannot overflow the judge's context.
     */
    static final int MAX_PASSAGE_CHARS = 4_000;

    /** The leading word of a verdict. Matched as a whole word so that "not" cannot read as "no". */
    private static final Pattern FIRST_WORD = Pattern.compile("[a-z]+");

    private static final Pattern FIRST_DIGIT = Pattern.compile("\\d");

    private JudgeText() {
    }

    /**
     * The chunk as a judge sees it: without its citation header, which carries no evidence, and cut so it
     * cannot overrun {@code judge-num-ctx} - Ollama would drop the start of the prompt, instruction
     * included.
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
