package com.example.subhanmishra.citation;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads citations out of the two places they appear: the header every stored chunk carries, and the
 * inline references the model writes into an answer.
 *
 * <p>This is what lets citations be checked without an AI judge. Every chunk's text starts with
 * {@code [filename, p. N]}, so a citation in the answer can be compared with the chunks the model was
 * given. A citation that matches none of them is fabricated, and finding that out is a plain string
 * comparison.
 *
 * <p>Answers are read more loosely than the prompt asks the model to write them. The model follows the
 * format only roughly - square brackets for round ones, no comma, "page" for "p." - and rejecting those
 * would miss real citations.
 *
 * <p>The loose reading also picks up filenames that are just prose, like "(pom.xml)", which is why
 * {@link #parseAnswerCandidates} returns <em>candidates</em>. Telling real citations apart needs the
 * list of retrieved documents, so {@link AnswerCitations} makes that call.
 */
public final class CitationParser {

    /**
     * A chunk's citation line: the whole first line, in square brackets. The shape is matched, rather
     * than the text split at the first line break, so a chunk stored without a header never has its real
     * first line mistaken for one. {@code RetrievalDiagnosticsService} uses this
     * constant too.
     */
    public static final Pattern CITATION_LINE = Pattern.compile("^\\[[^\\]\\n]*]$");

    /**
     * Starts the optional second header line, naming the section a chunk begins inside:
     * {@code Section: Customizing the Management Server Port}. {@link #stripHeader} removes it with the
     * citation line. {@code DocumentIngestionService.citationHeader} writes it, and says why.
     */
    public static final String SECTION_LINE_PREFIX = "Section: ";

    /**
     * A bracketed span in the answer, round or square - the container, which may hold several
     * citations: {@code (manual.pdf, p. 283; manual.pdf, p. 299)}. So parsing takes two steps, first the
     * span and then each citation inside it with {@link #CITATION_IN_SPAN}. A single pattern would
     * miss both of those.
     */
    static final Pattern BRACKETED_SPAN = Pattern.compile("[\\[(]([^\\[\\]()\\n]{1,400})[\\])]");

    /**
     * One citation inside a span; several, separated by {@code ;} or {@code ,}, are found in turn. The
     * comma after the filename is optional, and the page may follow {@code p.}, {@code pp.},
     * {@code page} or {@code pages}, or be absent.
     *
     * <ul>
     *   <li>The extension must start with a letter, or "(version 3.14)" would read as a file named 3.14.</li>
     *   <li>It may be up to ten characters long, for {@code application.properties}.</li>
     *   <li>A page range ("pp. 12-14") yields only its first page. Chunks are one page each, so pages in
     *       between would be citations the model never made.</li>
     * </ul>
     */
    static final Pattern CITATION_IN_SPAN = Pattern.compile(
            "([^,;\\n]*?[^,;\\s.\\n]\\.[A-Za-z][A-Za-z0-9]{1,9})"
            + "(?:\\s*,)?"
            // An optional page, then only what can continue one: a range or a list. The page is captured
            // whole, dots included - "p. 5.3" is a section number, and cutting it to 5 would invent a page.
            + "(?:\\s*(?:pp?\\.?|pages?)\\s*(\\d{1,5}(?:\\.\\d{1,3})*)[-–\\s\\d]*)?");

    /** Splits a header's inner text into its filename and page halves, e.g. {@code ", p. 590"}. */
    private static final Pattern HEADER_PAGE_SUFFIX = Pattern.compile(",\\s*p\\.\\s*(\\d{1,5})\\s*$");

    private CitationParser() {
    }

    /**
     * The citation in a chunk's header, or null when it has none. A chunk without one was stored before
     * headers existed and can never be cited, so callers count them rather than skip them.
     */
    public static @Nullable Citation parseHeader(@Nullable String chunkText) {
        String firstLine = firstLineOf(chunkText);
        if (firstLine == null || !CITATION_LINE.matcher(firstLine).matches()) {
            return null;
        }
        String inner = firstLine.substring(1, firstLine.length() - 1).strip();
        if (inner.isEmpty()) {
            return null;
        }
        Matcher pageMatcher = HEADER_PAGE_SUFFIX.matcher(inner);
        if (pageMatcher.find()) {
            return new Citation(inner.substring(0, pageMatcher.start()).strip(),
                                Integer.valueOf(pageMatcher.group(1)));
        }
        return new Citation(inner, null);
    }

    /**
     * The chunk's text without its header - the citation line and any {@link #SECTION_LINE_PREFIX section
     * line} - or the whole text when it has none. Anything comparing stored text with the original
     * document must call this first, since the header is not part of the document.
     */
    public static @Nullable String stripHeader(@Nullable String chunkText) {
        if (chunkText == null) {
            return null;
        }
        int firstBreak = chunkText.indexOf('\n');
        if (firstBreak < 0) {
            return chunkText;
        }
        String firstLine = chunkText.substring(0, firstBreak).stripTrailing();
        if (!CITATION_LINE.matcher(firstLine).matches()) {
            return chunkText;
        }
        String body = chunkText.substring(firstBreak + 1);
        if (body.startsWith(SECTION_LINE_PREFIX)) {
            int sectionEnd = body.indexOf('\n');
            body = sectionEnd < 0 ? "" : body.substring(sectionEnd);
        }
        return body.stripLeading();
    }

    /**
     * Every distinct bracketed filename in the answer, in the order written. These are <em>candidates</em>
     * - see the class javadoc for why a filename in parentheses is not necessarily a citation.
     */
    public static List<Citation> parseAnswerCandidates(@Nullable String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Citation> citations = new ArrayList<>();

        Matcher spans = BRACKETED_SPAN.matcher(answer);
        while (spans.find()) {
            Matcher inner = CITATION_IN_SPAN.matcher(spans.group(1));
            while (inner.find()) {
                String fileName = inner.group(1).strip();
                if (fileName.isEmpty()) {
                    continue;
                }
                Citation citation = citationOf(fileName, inner.group(2));
                // The same page cited in three sentences is one source; counting three would flatter
                // the validity rate.
                if (seen.add(key(citation))) {
                    citations.add(citation);
                }
            }
        }
        return citations;
    }

    /** The distinct citations offered by the retrieved context - what the model was entitled to cite. */
    public static List<Citation> availableCitations(@Nullable List<Document> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Citation> citations = new ArrayList<>();
        for (Document document : retrieved) {
            Citation citation = parseHeader(document.getText());
            if (citation != null && seen.add(key(citation))) {
                citations.add(citation);
            }
        }
        return citations;
    }

    /** The distinct filenames offered by the retrieved context, lower-cased for comparison. */
    public static Set<String> availableFileNames(@Nullable List<Document> retrieved) {
        Set<String> names = new LinkedHashSet<>();
        for (Citation citation : availableCitations(retrieved)) {
            names.add(citation.fileName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * A citation from a filename and whatever the model wrote where a page belongs.
     *
     * <p>A reference with a dot is a section number, not a page, and is kept exactly as written so the
     * mistake can be reported as the model made it.
     */
    static Citation citationOf(String fileName, @Nullable String pageRef) {
        if (pageRef == null) {
            return new Citation(fileName, null);
        }
        return pageRef.indexOf('.') < 0
                ? new Citation(fileName, Integer.valueOf(pageRef))
                : new Citation(fileName, null, pageRef);
    }

    private static String key(Citation citation) {
        String page = citation.pageLabel() != null ? citation.pageLabel() : String.valueOf(citation.pageNumber());
        return citation.fileName().toLowerCase(Locale.ROOT) + "#" + page;
    }

    private static @Nullable String firstLineOf(@Nullable String text) {
        if (text == null) {
            return null;
        }
        int firstBreak = text.indexOf('\n');
        return (firstBreak < 0 ? text : text.substring(0, firstBreak)).stripTrailing();
    }
}
