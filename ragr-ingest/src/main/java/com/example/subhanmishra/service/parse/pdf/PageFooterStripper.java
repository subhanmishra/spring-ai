package com.example.subhanmishra.service.parse.pdf;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the page number a PDF prints in its footer. The footer is ordinary text on the page, so it
 * arrives at the end of the page's text, and it does two kinds of damage:
 * <ul>
 *   <li><b>Wrong citations.</b> The printed number is rarely the PDF page number - front matter shifts it
 *       - and the model prefers the printed one at the bottom of a passage over the correct one in its
 *       header. Readers following those citations landed 19 pages early.</li>
 *   <li><b>Useless chunks.</b> A page whose last block is only the footer makes a chunk holding just a
 *       number, and those were retrieved in place of real content.</li>
 * </ul>
 *
 * <p><b>Found by agreement across the document</b>, not by pattern alone, since a code block or a table
 * can also end in a number. The gap between PDF page and printed number is the same on every page, so the
 * most common gap identifies the scheme, and only a final number fitting it is removed. With no
 * consistent scheme nothing is removed - the safe failure.
 */
public final class PageFooterStripper {

    private static final Logger log = LoggerFactory.getLogger(PageFooterStripper.class);

    /** A line that is a page number and nothing else. Five digits covers any real document. */
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d{1,5})\\s*$");

    /**
     * Fewer pages ending in a number than this is too few to agree on a scheme; two pages could match by
     * coincidence.
     */
    private static final int MIN_PAGES_FOR_CONSENSUS = 3;

    /**
     * The most common gap must fit at least this share of the pages ending in a number; otherwise the
     * numbers are incidental and nothing is removed.
     */
    private static final double MIN_AGREEMENT = 0.5;

    /** {@code null} when no folio scheme was found, which disables stripping entirely. */
    private final @Nullable Integer offset;

    private PageFooterStripper(@Nullable Integer offset) {
        this.offset = offset;
    }

    /** A stripper that never strips - for readers that cannot supply page numbers. */
    public static PageFooterStripper disabled() {
        return new PageFooterStripper(null);
    }

    /**
     * Works out the document's folio offset from its pages.
     *
     * @param pageTexts page number to that page's extracted text, in any order
     */
    public static PageFooterStripper detect(Map<Integer, String> pageTexts) {
        Map<Integer, Integer> offsetCounts = new HashMap<>();
        int pagesWithTrailingNumber = 0;

        for (Map.Entry<Integer, String> page : pageTexts.entrySet()) {
            Integer printed = trailingNumber(page.getValue());
            if (printed == null) {
                continue;
            }
            pagesWithTrailingNumber++;
            offsetCounts.merge(page.getKey() - printed, 1, Integer::sum);
        }

        if (pagesWithTrailingNumber < MIN_PAGES_FOR_CONSENSUS) {
            return new PageFooterStripper(null);
        }

        Map.Entry<Integer, Integer> modal = offsetCounts.entrySet().stream()
                                                        .max(Map.Entry.comparingByValue())
                                                        .orElse(null);
        if (modal == null || modal.getValue() < pagesWithTrailingNumber * MIN_AGREEMENT) {
            log.debug("No consistent page-footer offset across {} page(s) carrying a trailing number; "
                      + "leaving footers in place", pagesWithTrailingNumber);
            return new PageFooterStripper(null);
        }

        log.info("Detected printed page-number footers offset by {} from the PDF page, on {} of {} page(s)",
                 modal.getKey(), modal.getValue(), pagesWithTrailingNumber);
        return new PageFooterStripper(modal.getKey());
    }

    public boolean active() {
        return offset != null;
    }

    /**
     * The page's text without its footer, or unchanged when this page carries none.
     *
     * <p>Returns an empty string when the footer <em>was</em> the whole text, which is the case that
     * produces a chunk holding only a page number. Callers must drop the resulting block rather than
     * store it.
     */
    public String strip(int pageNumber, @Nullable String text) {
        if (offset == null || text == null || text.isEmpty()) {
            return text != null ? text : "";
        }
        Integer printed = trailingNumber(text);
        if (printed == null || pageNumber - printed != offset) {
            return text;
        }

        String trimmed = text.stripTrailing();
        int lastBreak = trimmed.lastIndexOf('\n');
        // No newline means the footer was the entire text of this block.
        return lastBreak < 0 ? "" : trimmed.substring(0, lastBreak).stripTrailing();
    }

    /** The number on the text's final line, or null when that line is anything else. */
    private static @Nullable Integer trailingNumber(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.stripTrailing();
        int lastBreak = trimmed.lastIndexOf('\n');
        String lastLine = lastBreak < 0 ? trimmed : trimmed.substring(lastBreak + 1);

        Matcher matcher = BARE_NUMBER.matcher(lastLine);
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
