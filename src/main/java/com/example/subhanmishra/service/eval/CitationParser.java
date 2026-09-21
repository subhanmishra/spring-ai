package com.example.subhanmishra.service.eval;

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
 * <p>This is what makes citation fidelity measurable without an LLM judge or a golden answer. Because
 * {@code DocumentIngestionService.citationHeader} prepends {@code [filename, p. N]} to every chunk's
 * text, and {@code QuestionAnswerAdvisor} exposes the chunks it retrieved, a citation the model wrote
 * can be checked against the citations that were actually available to it. A citation with no
 * corresponding header is fabricated - the model invented a page number - and establishing that is a
 * pure string comparison over data already in hand.
 *
 * <p>The answer grammar is deliberately more liberal than the one the prompts ask for. Both
 * {@code SpringAiConfig.QA_PROMPT_TEMPLATE} and the system prompt describe a single convention, but a
 * model complies approximately: it swaps brackets for parentheses, drops the comma, and writes "page"
 * where it was asked for "p.". Rejecting those would count a perfectly good citation as absent and
 * understate the very metric this class exists to report.
 *
 * <p>Being liberal creates the opposite hazard, which is why {@link #parseAnswerCandidates} is named
 * for what it returns. An answer about this corpus is full of parentheses containing filenames -
 * "(application.properties)", "(pom.xml)" - that are prose, not citations, and scoring them as
 * fabricated would make the fabrication rate mostly noise. Deciding which candidates are real
 * citations needs to know which documents were retrieved, so that policy lives in
 * {@code EvalScoringService} rather than here.
 */
public final class CitationParser {

    /**
     * A chunk's citation header occupies the whole of its first line. Matching the shape rather than
     * splitting on the first blank line matters: chunks ingested before the header existed start
     * straight into their content, and an unconditional split would silently promote a real first line
     * to a citation.
     *
     * <p>{@code RetrievalDiagnosticsService} applies the same rule for its DTO and reads this constant,
     * so the two cannot drift apart.
     */
    public static final Pattern CITATION_LINE = Pattern.compile("^\\[[^\\]\\n]*]$");

    /**
     * A candidate inline citation. Accepts either bracket style, an optional comma, and any of
     * {@code p.} / {@code pp.} / {@code page} / {@code pages} before the number, or no page at all.
     *
     * <p>A page <em>range</em> ("pp. 12-14") yields only its first number. Chunks are one page each, so
     * a range is the model summarising rather than citing, and inventing the intermediate pages here
     * would manufacture fabrications the model never actually claimed.
     */
    /**
     * A bracketed span in the answer - the candidate container, not the citation itself.
     *
     * <p>Parsing happens in two steps rather than one, because a model routinely puts several
     * citations inside a single pair of brackets: {@code (manual.pdf, p. 283; manual.pdf, p. 299)}.
     * A single pattern anchored on a closing bracket immediately after the page number matches
     * <em>neither</em> of those - the first is followed by a semicolon rather than a bracket, and the
     * second has no opening bracket of its own. This was not hypothetical; it was the very first real
     * answer this evaluator scored, and it silently under-counted four citations as two.
     */
    static final Pattern BRACKETED_SPAN = Pattern.compile("[\\[(]([^\\[\\]()\\n]{1,400})[\\])]");

    /**
     * One citation inside a bracketed span, so several separated by {@code ;} or {@code ,} are each
     * found in turn.
     *
     * <p>The extension must begin with a letter. Without that, "(version 3.14)" parses as a file named
     * {@code 3.14}, and requiring at least two extension characters alone does not exclude it.
     *
     * <p>Ten characters of extension because {@code properties} is one, and this corpus is largely
     * about files named {@code application.properties}.
     */
    static final Pattern CITATION_IN_SPAN = Pattern.compile(
            "([^,;\\n]*?[^,;\\s.\\n]\\.[A-Za-z][A-Za-z0-9]{1,9})"
            + "(?:\\s*,)?"
            // An optional page marker and reference, then only what can continue a page reference - a
            // range ("pp. 12-14") or a list ("pp. 12, 14"). The reference is captured whole, dots and
            // all: "p. 5.3" is a section number written where a page goes, and truncating it to 5
            // would report a page the model never claimed. Citation decides which it is.
            + "(?:\\s*(?:pp?\\.?|pages?)\\s*(\\d{1,5}(?:\\.\\d{1,3})*)[-–\\s\\d]*)?");

    /** Splits a header's inner text into its filename and page halves, e.g. {@code ", p. 590"}. */
    private static final Pattern HEADER_PAGE_SUFFIX = Pattern.compile(",\\s*p\\.\\s*(\\d{1,5})\\s*$");

    private CitationParser() {
    }

    /**
     * The citation declared by a retrieved chunk's header, or null when it carries none - which means
     * the chunk predates the header and can never be cited. Those are worth counting rather than
     * ignoring, because a corpus holding them answers some questions with citations and some without,
     * with nothing externally distinguishing the two.
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
     * The chunk's text with its citation header removed, or the whole text when it has none. Anything
     * comparing stored text against what a document actually said has to strip this first - the header
     * is prepended before embedding, so the stored content is not verbatim.
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
        return CITATION_LINE.matcher(firstLine).matches()
                ? chunkText.substring(firstBreak).stripLeading()
                : chunkText;
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
                // De-duplicated: an answer citing the same page in three sentences has cited one
                // source, and counting it three times would flatter the validity rate.
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
     * <p>A reference containing a dot is a section number, not a page - "5.3" is the heading
     * "5.3. Endpoints". It is kept verbatim as a label so the failure reads as what the model wrote.
     */
    private static Citation citationOf(String fileName, @Nullable String pageRef) {
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
