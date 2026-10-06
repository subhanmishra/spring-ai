package com.example.subhanmishra.citation;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a section number the model wrote where a page number belongs into the page it came from.
 *
 * <p>This exists because of a failure the evaluation suite reproduced on every single run: asked how
 * actuator endpoints are exposed, {@code gemma4:e2b} answers with "(spring-boot-reference.pdf, p. 5.3)"
 * - where 5.3 is not a page at all but the heading "5.3. Monitoring and Management over HTTP", which
 * the model read off page 299 of a passage it was given. The same answer cites pages 277 and 283
 * correctly alongside it, so the model is not inventing a location; it is naming the right passage with
 * the wrong kind of identifier. A reader following "p. 5.3" lands nowhere.
 *
 * <p><strong>Why this is a rewrite rather than a looser scoring rule.</strong> Relaxing
 * {@code EvalScoringService} to accept a section number that resolves would make the suite pass while
 * leaving the answer exactly as unusable as before. The defect is in what the reader receives, so the
 * repair belongs in what the reader receives; scoring then follows for free, because both
 * {@code ChatService} and {@code GoldenEvalService} score the resolved text.
 *
 * <p><strong>Why it is safe to do deterministically.</strong> Measured against the live corpus, the
 * mapping from section number to page is total and unambiguous: of 232 heading occurrences across 212
 * pages, no section number appears as a heading on more than one page. Anchoring on the heading form
 * rather than a bare substring is what buys that - "5.3" occurs in 11 chunks as a substring, mostly as
 * a Spring Framework version, and in exactly one as a heading. Every section number the suite has ever
 * seen the model emit - 5.3, 5.2.5, 5.3.1, 5.5.1, 4.3.2, 4.3.3, 9.2.6 - resolves to exactly one
 * retrieved chunk.
 *
 * <p><strong>What it deliberately will not do.</strong> The map is built from the retrieved chunks
 * only, never from the whole corpus, so a resolution can only ever name a page the model was actually
 * shown - swapping one unsupported citation for a different unsupported citation would be a worse
 * failure than the one being fixed. When a label matches no retrieved chunk, or more than one, it is
 * left exactly as the model wrote it and goes on being counted as fabricated. That is what keeps the
 * metric honest: this class cannot quietly absorb a model that has started guessing, because a guess
 * does not resolve.
 *
 * <p><strong>The same mistake with the dots dropped.</strong> On 2 Oct 2026 the golden suite caught
 * the first invented page since the parser strippers landed: {@code profiles-activation} cited "p. 926"
 * of a 645-page manual. 926 appears in no retrieved chunk; the sentence came from page 372, under the
 * heading "9.2.6. Set the Active Spring Profiles", and the run before cited "(9.2.6)" for the same
 * sentence. So a plain page number is also resolved, under two extra conditions: it must name no page
 * the model was shown - a retrieved page is a real citation and is never touched - and its digits must
 * be exactly one retrieved heading with the dots removed. "926" can also be 92.6 or 9.26; if more than
 * one retrieved heading collapses to it, it is left alone and counted as abstained. The residual risk
 * is a genuinely invented page whose digits happen to match a retrieved heading being repaired rather
 * than counted. It is narrow - the guess must collapse onto a heading among five chunks - and the
 * repair still lands on a page the model was given, which is the property this class exists to keep.
 *
 * <p><strong>And with only some of them dropped.</strong> On 6 Oct 2026 {@code port-in-use-startup-failure}
 * cited "p. 913.1" for a sentence from page 419, headed "9.13.1. Change the HTTP Port or Address of the
 * Actuator Endpoints" - the first dot dropped, the second kept. A dotted label that heads no retrieved
 * chunk exactly is therefore also tried against the retrieved headings it can be made from by deleting
 * dots: the same digits, with every dot the label kept sitting where the heading has one. The kept dots
 * are evidence, and using them matters - digits alone are ambiguous more often than the dotless case
 * suggests: of the 374 section numbers heading chunks in the corpus (counted 6 Oct 2026), 26 pairs
 * collapse to the same digits ("4.13" and "4.1.3", "4.11" and "4.1.1"), and a kept dot tells most of
 * them apart: "41.1" can only be "4.1.1". Two retrieved headings that both fit still abstain.
 *
 * <p>One limitation worth stating, since it affects a citation's precision rather than its
 * correctness. The page a section number resolves to is the page the <em>heading</em> sits on, which
 * for a section spanning several pages is its opening page rather than the page carrying the specific
 * sentence. Observed once, on the bare "5.3": the fact was on page 277 and the heading on 299. The
 * citation becomes correct at section level and navigable, which it was not before, but it is not
 * always pinpoint.
 */
public final class CitationResolver {

    /**
     * A numbered heading at the start of a line: {@code "5.2.5. Hypermedia for Actuator Web Endpoints"}.
     *
     * <p>The trailing capital is load-bearing rather than decorative. Without it the pattern also
     * matches a version number or a numeric list item, and the whole value of this class is that a
     * match means a heading. Verified across the corpus: 232 occurrences match this form and zero
     * headings are missed by requiring the capital, because the manual title-cases every one of them.
     *
     * <p>At least two components, so an ordinary decimal cannot match. At most three, which is as deep
     * as the manual numbers its sections.
     */
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "(?m)^[ \\t]*(\\d{1,2}(?:\\.\\d{1,2}){1,2})\\.[ \\t]+\\p{Lu}");

    /**
     * Every numbered heading in a text, in order, each as its whole line - {@code "9.2.6. Set the Active
     * Spring Profiles"}. Matched by the same pattern resolution uses, so anything named here is something
     * a section citation could resolve to. Empty for null or headingless text.
     */
    public static List<String> sectionHeadings(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> headings = new ArrayList<>();
        Matcher matcher = SECTION_HEADING.matcher(text);
        while (matcher.find()) {
            int lineEnd = text.indexOf('\n', matcher.start(1));
            headings.add(text.substring(matcher.start(1), lineEnd < 0 ? text.length() : lineEnd).strip());
        }
        return List.copyOf(headings);
    }

    /**
     * Whether {@code text} opens with a numbered section heading - the same test resolution uses, so
     * ingestion's chunk boundaries and a resolvable section number agree on what a heading is.
     */
    public static boolean opensWithSectionHeading(@Nullable String text) {
        return text != null && SECTION_HEADING.matcher(text).lookingAt();
    }

    /** Marks a section number that resolved to more than one retrieved page, so it must be left alone. */
    private static final Citation AMBIGUOUS = new Citation("", null);

    private CitationResolver() {
    }

    /**
     * The outcome of resolving one answer.
     *
     * @param answer     the answer with every resolvable section number rewritten to its page, or the
     *                   original text unchanged when nothing resolved
     * @param repaired   how many citation occurrences were rewritten. Occurrences rather than distinct
     *                   citations: an answer citing "p. 5.3" in four sentences has four places a reader
     *                   could follow, and all four are rewritten.
     * @param abstained  how many occurrences carried a section number that did not resolve and were
     *                   left as written
     * @param unresolved the distinct citations behind {@code abstained}, so a log line can name them
     * @param repairs    what each rewrite changed, one entry per distinct citation, so a caller can be
     *                   told that the page it is shown is not the one the model wrote
     */
    public record Resolution(String answer, int repaired, int abstained, List<Citation> unresolved,
                             List<Repair> repairs) {

        public Resolution {
            unresolved = List.copyOf(unresolved);
            repairs = List.copyOf(repairs);
        }

        /** An answer that needed nothing done to it. */
        static Resolution unchanged(String answer) {
            return new Resolution(answer, 0, 0, List.of(), List.of());
        }

        /** The repair that produced this citation, or null when the model wrote it as it now stands. */
        public @Nullable Repair repairOf(Citation citation) {
            if (citation.pageNumber() == null) {
                return null;
            }
            return repairs.stream()
                          .filter(repair -> repair.fileName().equalsIgnoreCase(citation.fileName())
                                  && repair.page() == citation.pageNumber())
                          .findFirst()
                          .orElse(null);
        }

        public boolean changed() {
            return repaired > 0;
        }
    }

    /** One rewrite: the section number the model wrote for a file, and the page it was resolved to. */
    public record Repair(String fileName, String section, int page) {
    }

    /**
     * Rewrites resolvable section numbers in {@code answer} to the page of the retrieved chunk whose
     * heading they name.
     *
     * @param answer    the model's answer, exactly as generated
     * @param retrieved the chunks the advisor put in the prompt - the only source of pages this will
     *                  cite, for the reason in the class javadoc
     */
    public static Resolution resolve(@Nullable String answer, @Nullable List<Document> retrieved) {
        if (answer == null || answer.isBlank()) {
            return Resolution.unchanged(answer == null ? "" : answer);
        }
        Map<String, Citation> sectionSources = sectionSources(retrieved);
        Set<Integer> retrievedPages = retrievedPages(retrieved);

        StringBuilder rewritten = new StringBuilder(answer.length());
        Map<String, Citation> unresolved = new LinkedHashMap<>();
        Map<String, Repair> repairs = new LinkedHashMap<>();
        int repaired = 0;
        int abstained = 0;
        int copiedTo = 0;

        Matcher spans = CitationParser.BRACKETED_SPAN.matcher(answer);
        while (spans.find()) {
            Matcher inner = CitationParser.CITATION_IN_SPAN.matcher(spans.group(1));
            while (inner.find()) {
                String pageRef = inner.group(2);
                if (pageRef == null) {
                    continue;
                }
                Citation source;
                if (pageRef.indexOf('.') >= 0) {
                    source = sectionSources.get(pageRef);
                    if (source == null) {
                        source = partlyDottedSection(pageRef, sectionSources);
                    }
                } else {
                    // A plain page number is a candidate only when it names no retrieved page - a page
                    // the model was shown is a real citation and is never touched - and its digits are
                    // a retrieved heading with the dots taken out. Anything else is either right or
                    // fabricated, and neither is this class's business.
                    if (retrievedPages.contains(Integer.valueOf(pageRef))) {
                        continue;
                    }
                    source = collapsedSection(pageRef, sectionSources);
                    if (source == null) {
                        continue;
                    }
                }
                if (source == null || source == AMBIGUOUS) {
                    abstained++;
                    String fileName = inner.group(1).strip();
                    unresolved.putIfAbsent(fileName + "#" + pageRef, new Citation(fileName, null, pageRef));
                    continue;
                }
                // group(1) is matched as one contiguous run inside the answer, so an offset within it
                // is an offset within the answer once shifted by where the span's content begins.
                int page = source.pageNumber();
                int start = spans.start(1) + inner.start(2);
                rewritten.append(answer, copiedTo, start).append(page);
                copiedTo = spans.start(1) + inner.end(2);
                repaired++;
                String fileName = inner.group(1).strip();
                repairs.putIfAbsent(fileName + "#" + pageRef, new Repair(fileName, pageRef, page));
            }
        }
        if (repaired == 0) {
            return new Resolution(answer, 0, abstained, List.copyOf(unresolved.values()), List.of());
        }
        rewritten.append(answer, copiedTo, answer.length());
        return new Resolution(rewritten.toString(), repaired, abstained, List.copyOf(unresolved.values()),
                              List.copyOf(repairs.values()));
    }

    /**
     * The retrieved heading whose number, dots removed, is {@code digits}: "926" for "9.2.6". Null when
     * no heading collapses to it; {@link #AMBIGUOUS} when more than one does - "926" is also 92.6 and
     * 9.26 - or the one that does is itself ambiguous.
     */
    private static @Nullable Citation collapsedSection(String digits, Map<String, Citation> sectionSources) {
        Citation found = null;
        for (Map.Entry<String, Citation> section : sectionSources.entrySet()) {
            if (section.getKey().replace(".", "").equals(digits)) {
                if (found != null) {
                    return AMBIGUOUS;
                }
                found = section.getValue();
            }
        }
        return found;
    }

    /**
     * The retrieved heading {@code label} is with some of its dots deleted: "9.13.1" for "913.1". Null
     * when there is none; {@link #AMBIGUOUS} when more than one fits, or the one that fits is itself
     * ambiguous.
     */
    private static @Nullable Citation partlyDottedSection(String label, Map<String, Citation> sectionSources) {
        Citation found = null;
        for (Map.Entry<String, Citation> section : sectionSources.entrySet()) {
            if (dropsDotsOf(label, section.getKey())) {
                if (found != null) {
                    return AMBIGUOUS;
                }
                found = section.getValue();
            }
        }
        return found;
    }

    /**
     * Whether {@code label} is {@code section} with one or more of its dots deleted and nothing else
     * changed: the same digits, and every dot the label kept after a digit where the section has one.
     */
    static boolean dropsDotsOf(String label, String section) {
        if (label.equals(section) || !label.replace(".", "").equals(section.replace(".", ""))) {
            return false;
        }
        return dotPositions(section).containsAll(dotPositions(label));
    }

    /** Each dot's position, counted in digits before it: {1, 3} for "9.13.1". */
    private static Set<Integer> dotPositions(String label) {
        Set<Integer> positions = new HashSet<>();
        int digits = 0;
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) == '.') {
                positions.add(digits);
            } else {
                digits++;
            }
        }
        return positions;
    }

    /** Every page a retrieved chunk's citation header names, whichever file it is from. */
    private static Set<Integer> retrievedPages(@Nullable List<Document> retrieved) {
        Set<Integer> pages = new HashSet<>();
        if (retrieved != null) {
            for (Document document : retrieved) {
                Citation header = CitationParser.parseHeader(document.getText());
                if (header != null && header.pageNumber() != null) {
                    pages.add(header.pageNumber());
                }
            }
        }
        return pages;
    }

    /**
     * The section numbers the retrieved chunks head, each with the file and page its heading sits on.
     * Ambiguous ones are left out - the same rule {@link #resolve} applies, so anything this names is
     * something {@code resolve} would also rewrite.
     *
     * <p>This is what makes a bare "(5.3)" in an answer recognisable as a reference rather than a
     * version number or a decimal: it is one only if 5.3 heads a page the model was actually given.
     */
    public static Map<String, Citation> resolvableSections(@Nullable List<Document> retrieved) {
        Map<String, Citation> sections = new HashMap<>(sectionSources(retrieved));
        sections.values().removeIf(source -> source == AMBIGUOUS);
        return Map.copyOf(sections);
    }

    /**
     * Maps each section number heading the retrieved chunks contain to the file and page it sits on,
     * with {@link #AMBIGUOUS} for any that appears on more than one.
     *
     * <p>Ambiguity has never been observed - no section number heads two pages anywhere in the corpus -
     * but it is cheap to detect and the alternative is picking one at random, which would turn a
     * visible failure into an invisible one.
     */
    private static Map<String, Citation> sectionSources(@Nullable List<Document> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) {
            return Map.of();
        }
        Map<String, Citation> pages = new HashMap<>();
        for (Document document : retrieved) {
            String text = document.getText();
            Citation header = CitationParser.parseHeader(text);
            // A chunk with no header, or one from a source with no pages, offers nothing to resolve to.
            if (text == null || header == null || header.pageNumber() == null) {
                continue;
            }
            String body = CitationParser.stripHeader(text);
            if (body == null) {
                continue;
            }
            Citation source = new Citation(header.fileName(), header.pageNumber());
            Set<String> inThisChunk = new HashSet<>();
            Matcher headings = SECTION_HEADING.matcher(body);
            while (headings.find()) {
                String section = headings.group(1);
                // The same heading twice in one chunk is still one page, not an ambiguity.
                if (!inThisChunk.add(section)) {
                    continue;
                }
                pages.merge(section, source, (existing, found) -> existing.equals(found) ? existing : AMBIGUOUS);
            }
        }
        return pages;
    }

    /** The distinct section numbers a set of chunks offers. Exposed for diagnostics and tests. */
    static List<String> sectionsOffered(@Nullable List<Document> retrieved) {
        return new ArrayList<>(sectionSources(retrieved).keySet());
    }
}
