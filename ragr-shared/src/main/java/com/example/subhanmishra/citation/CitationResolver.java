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
 * Turns a section number the model wrote in place of a page number back into a real page.
 *
 * <p>The model sometimes cites the heading it read instead of the page it read it on:
 * "(spring-boot-reference.pdf, p. 5.3)" for the section "5.3. Monitoring and Management over HTTP". The
 * passage is the right one, but a reader following "p. 5.3" lands nowhere. So the answer itself is
 * fixed, before anyone reads it, rather than the scoring being relaxed to forgive it. Chat and
 * evaluation both score the fixed text, so they agree on it.
 *
 * <p>Three forms are recognised, each matched against the numbered headings in the retrieved chunks:
 * <ul>
 *   <li>the section number as written: {@code p. 9.2.6};</li>
 *   <li>every dot dropped: {@code p. 926}, tried only when 926 is not a page the model was shown;</li>
 *   <li>some dots dropped: {@code p. 913.1} for 9.13.1, where the dots that remain must line up.</li>
 * </ul>
 *
 * <p><strong>The rule that keeps it honest:</strong> a citation is rewritten only to a page the model
 * was actually given, and only when exactly one retrieved heading fits. Anything else is left as the
 * model wrote it and still counts as fabricated, so this class cannot hide a model that has started
 * guessing. Swapping one unsupported page for another would be worse than the original mistake.
 *
 * <p><strong>Limitation:</strong> the page found is the one the section's heading sits on. For a long
 * section that can be a few pages before the sentence cited, so the citation is right to the section
 * but not always to the page.
 */
public final class CitationResolver {

    /**
     * A numbered heading at the start of a line: {@code "5.2.5. Hypermedia for Actuator Web Endpoints"}.
     * Two or three number parts, so a plain decimal cannot match, and a capital letter after them, so a
     * version number or a numbered list item cannot either. The manual capitalises every heading.
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
     * @param repaired   how many citations were rewritten, counting each occurrence: "p. 5.3" cited in
     *                   four sentences counts four
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
     * @param retrieved the chunks that were in the prompt - the only pages this will ever cite
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
                    // A page the model was shown is a real citation and is never touched. Otherwise a
                    // plain number is repaired only if it is a retrieved heading with its dots removed.
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
     * Ambiguous ones are left out, as {@link #resolve} leaves them.
     *
     * <p>This is how a bare "(5.3)" in an answer is told apart from a version number: it is a reference
     * only if 5.3 heads a page the model was given.
     */
    public static Map<String, Citation> resolvableSections(@Nullable List<Document> retrieved) {
        Map<String, Citation> sections = new HashMap<>(sectionSources(retrieved));
        sections.values().removeIf(source -> source == AMBIGUOUS);
        return Map.copyOf(sections);
    }

    /**
     * Maps each section number heading the retrieved chunks contain to the file and page it sits on,
     * with {@link #AMBIGUOUS} for any that appears on more than one. An ambiguous number is never
     * resolved: picking one page would turn a visible mistake into a hidden one.
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
