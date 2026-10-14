package com.example.subhanmishra.citation;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which bracketed spans in an answer are citations, whether the retrieved chunks support them,
 * and removes them from the text a caller reads.
 *
 * <p>The model still cites inline, because those citations are the only per-claim attribution there is
 * and evaluation scores them. The caller gets the answer without them, and the citations as data beside
 * it. Chat and evaluation both use the rules here, so what the response calls a citation is exactly what
 * the fabrication rate counts.
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

    /** What one turn's retrieved context offers to cite: the files, lower-cased. */
    public record Context(Set<String> fileNames) {

        public static final Context EMPTY = new Context(Set.of());

        public Context {
            fileNames = Set.copyOf(fileNames);
        }

        public static Context of(@Nullable List<Document> retrieved) {
            return new Context(CitationParser.availableFileNames(retrieved));
        }
    }

    /**
     * Whether a bracketed candidate is a citation rather than a filename named in prose.
     *
     * <p>A candidate is a citation when it has a page number, or when its filename is a retrieved
     * document. Answers about Spring Boot are full of filenames in brackets - "(application.properties)",
     * "(pom.xml)" - that name a file, not a source. Counting them would flood the fabrication rate with
     * false alarms, and stripping them would delete words from the answer.
     *
     * <p>A page number on a file that was never retrieved - "(application.properties, p. 12)" - is
     * still a citation, and an unsupported one: inventing a location is exactly what is being measured.
     */
    public static boolean isCitation(Citation candidate, Set<String> availableFileNames) {
        return candidate.pageNumber() != null
                || candidate.hasMalformedPage()
                || availableFileNames.contains(candidate.fileName().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the retrieved context actually supports this citation.
     *
     * <ul>
     *   <li><b>With a page:</b> it must match a retrieved chunk exactly. This is the fabrication check.</li>
     *   <li><b>Without a page:</b> the filename alone must be retrieved. "(spring-boot-reference.pdf)" is
     *       imprecise but invents nothing - and for DOCX, HTML and the other formats without pages it is
     *       the only correct form.</li>
     *   <li><b>With a malformed page</b>, such as "p. 5.3": never supported. The model claimed a location,
     *       and it is not one it was given.</li>
     * </ul>
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
     * <p>A span with anything else in it is left whole. Cutting the citation out of "(see the table on
     * manual.pdf, p. 42, for defaults)" would leave a broken sentence; one citation left in the text is
     * the smaller problem.
     */
    public static String strip(@Nullable String answer, Context context) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(answer.length());
        int copiedTo = 0;
        Matcher spans = CitationParser.BRACKETED_SPAN.matcher(answer);
        while (spans.find()) {
            if (!isCitationOnly(spans.group(1), context.fileNames())) {
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
     * <p>A citation arrives split across tokens - "(manual", ".pdf, p.", " 42)" - so text from an opening
     * bracket is held until its closing bracket shows whether it was a citation. Everything else is
     * released at once, apart from trailing spaces, which wait in case a citation follows and takes them.
     * The delay is a few tokens, never the whole answer.
     *
     * <p>What is released is always the start of what {@code strip} would return for the whole answer,
     * so nothing a client has already shown is ever taken back.
     */
    public static final class StreamingStripper {

        private final StringBuilder received = new StringBuilder();
        private int released;

        /** Takes the next token and returns whatever can now be released, possibly nothing. */
        public String accept(String token, Context context) {
            received.append(token);
            return release(settledLength(), context);
        }

        /** Returns everything still held once the stream has ended. */
        public String finish(Context context) {
            return release(received.length(), context);
        }

        private String release(int upTo, Context context) {
            String settled = strip(received.substring(0, upTo), context);
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
