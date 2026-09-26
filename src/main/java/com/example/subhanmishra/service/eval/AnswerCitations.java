package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which bracketed spans in an answer are citations, whether the retrieved context supports
 * them, and removes them from the text a caller reads.
 *
 * <p>The model is still asked to cite inline, and the evaluation still scores those inline citations -
 * they are the only per-claim attribution there is. What changes is the reader's copy: the chat
 * endpoints return the answer with its citations removed and report them as structured data beside it.
 * Both halves read the rules here, so what the response calls a citation is exactly what the fabrication
 * rate counts.
 */
public final class AnswerCitations {

    /**
     * What may sit between, or after, the citations in a span that is nothing but citations: separators,
     * and the digits of a page list or range ("pp. 12, 14", "pp. 12-14") that {@code CITATION_IN_SPAN}
     * leaves behind. None of it can be prose.
     */
    private static final Pattern FILLER = Pattern.compile("[\\s,;\\d\\-–]*");

    /**
     * How far back from the end of a stream an unclosed bracket can still become a span.
     * {@code BRACKETED_SPAN} caps a span's content at 400 characters; past that, the bracket is prose.
     */
    private static final int MAX_SPAN_LENGTH = 402;

    private AnswerCitations() {
    }

    /**
     * Whether a bracketed candidate is a citation rather than a filename named in prose.
     *
     * <p>A candidate is a citation when it carries a page number, or when its filename is one of the
     * documents actually retrieved. Answers about this corpus are full of parentheses holding
     * filenames - "(application.properties)", "(pom.xml)" - which are the model naming a file in prose,
     * not claiming a source. Counting those as fabricated would swamp the fabrication rate with false
     * positives and make the one metric that matters most here unreadable; stripping them would delete
     * words from the answer.
     *
     * <p>Note what the rule still catches: a page number attached to a filename that was never
     * retrieved ("(application.properties, p. 12)") is a citation, and an unsupported one, because
     * inventing a page for a file the model was not given is exactly the failure being measured.
     */
    public static boolean isCitation(Citation candidate, Set<String> availableFileNames) {
        return candidate.pageNumber() != null
                || candidate.hasMalformedPage()
                || availableFileNames.contains(candidate.fileName().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the retrieved context actually supports this citation.
     *
     * <p>A citation carrying a page number has to match a retrieved chunk exactly - that is the
     * fabrication check, and the whole point of the metric.
     *
     * <p>A citation with <em>no</em> page is judged on its filename alone, and that distinction is
     * deliberate rather than lax. "(spring-boot-reference.pdf)" names a document that really was
     * retrieved; it is less precise than it could be, but nothing about it is invented, and lumping it
     * in with a page number the model made up conflates imprecision with dishonesty. It is also the
     * only correct form for a Tika source - DOCX, XLSX, PPTX and HTML have no page attribution, so
     * {@code citationHeader} omits the page half and a pageless citation is exactly right there.
     *
     * <p>Found by the evaluation suite itself: a run flagged a bare "[spring-boot-reference.pdf]" as
     * fabricated, which was the scorer being wrong rather than the model.
     *
     * <p>A <em>malformed</em> page reference is the case in between, and it is never supported. "(…, p.
     * 5.3)" is a section number written where a page belongs: the model did claim a location, so the
     * leniency above does not apply, and the location it claimed is not one the context offered.
     */
    public static boolean isSupported(Citation citation, List<Citation> available, Set<String> availableFileNames) {
        if (citation.hasMalformedPage()) {
            return false;
        }
        return citation.pageNumber() == null
                ? availableFileNames.contains(citation.fileName().toLowerCase(Locale.ROOT))
                : available.stream().anyMatch(citation::matches);
    }

    /**
     * The answer with every citation-only span removed, along with the horizontal whitespace that led
     * into it - "dependencies (manual.pdf, p. 42)." becomes "dependencies.". A span at the start of a
     * line takes the whitespace after it instead, so the line does not start with a space.
     *
     * <p>A span holding anything other than citations is left whole. "(see the table on manual.pdf,
     * p. 42, for defaults)" reads as prose around a citation, and cutting the citation out of it would
     * leave a sentence fragment; leaving one citation in the text is the smaller defect.
     */
    public static String strip(@Nullable String answer, Set<String> availableFileNames) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(answer.length());
        int copiedTo = 0;
        Matcher spans = CitationParser.BRACKETED_SPAN.matcher(answer);
        while (spans.find()) {
            if (!isCitationOnly(spans.group(1), availableFileNames)) {
                continue;
            }
            int cutFrom = spans.start();
            while (cutFrom > copiedTo && isHorizontalSpace(answer.charAt(cutFrom - 1))) {
                cutFrom--;
            }
            int cutTo = spans.end();
            if (cutFrom == 0 || answer.charAt(cutFrom - 1) == '\n') {
                cutFrom = spans.start();
                while (cutTo < answer.length() && isHorizontalSpace(answer.charAt(cutTo))) {
                    cutTo++;
                }
            }
            stripped.append(answer, copiedTo, cutFrom);
            copiedTo = cutTo;
        }
        stripped.append(answer, copiedTo, answer.length());
        return stripped.toString();
    }

    private static boolean isCitationOnly(String spanContent, Set<String> availableFileNames) {
        Matcher inner = CitationParser.CITATION_IN_SPAN.matcher(spanContent);
        int consumedTo = 0;
        boolean found = false;
        while (inner.find()) {
            String fileName = inner.group(1).strip();
            if (fileName.isEmpty()
                    || !FILLER.matcher(spanContent.substring(consumedTo, inner.start())).matches()
                    || !isCitation(CitationParser.citationOf(fileName, inner.group(2)), availableFileNames)) {
                return false;
            }
            found = true;
            consumedTo = inner.end();
        }
        return found && FILLER.matcher(spanContent.substring(consumedTo)).matches();
    }

    private static boolean isHorizontalSpace(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * {@link #strip} applied to an answer as it streams in.
     *
     * <p>A citation arrives split across tokens - "(manual" then ".pdf, p." then " 42)" - so nothing
     * from an opening bracket onwards can be released until its closing bracket shows whether the span
     * was a citation. That hold is the only one: text outside a bracket is released as soon as it
     * arrives, except for trailing spaces, which wait in case a citation follows and takes them with it.
     * The delay is a handful of tokens, never the whole answer.
     *
     * <p>Released text is always a prefix of {@code strip} over everything received so far, cut at a
     * point no later token can change, so what a client has already rendered is never contradicted.
     */
    public static final class StreamingStripper {

        private final StringBuilder received = new StringBuilder();
        private int released;

        /** Takes the next token and returns whatever can now be released, possibly nothing. */
        public String accept(String token, Set<String> availableFileNames) {
            received.append(token);
            return release(settledLength(), availableFileNames);
        }

        /** Returns everything still held once the stream has ended. */
        public String finish(Set<String> availableFileNames) {
            return release(received.length(), availableFileNames);
        }

        private String release(int upTo, Set<String> availableFileNames) {
            String settled = strip(received.substring(0, upTo), availableFileNames);
            if (settled.length() <= released) {
                return "";
            }
            String next = settled.substring(released);
            released = settled.length();
            return next;
        }

        /** How much of what has arrived no later token can change the stripping of. */
        private int settledLength() {
            int length = received.length();
            int settled = length;
            int open = Math.max(received.lastIndexOf("("), received.lastIndexOf("["));
            if (open >= 0 && length - open <= MAX_SPAN_LENGTH && !closedOrBroken(open)) {
                settled = open;
            }
            while (settled > 0 && isHorizontalSpace(received.charAt(settled - 1))) {
                settled--;
            }
            return settled;
        }

        private boolean closedOrBroken(int open) {
            for (int i = open + 1; i < received.length(); i++) {
                char c = received.charAt(i);
                if (c == ')' || c == ']' || c == '\n') {
                    return true;
                }
            }
            return false;
        }
    }
}
