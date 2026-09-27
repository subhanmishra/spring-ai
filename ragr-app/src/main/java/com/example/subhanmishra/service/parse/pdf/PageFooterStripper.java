package com.example.subhanmishra.service.parse.pdf;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the printed page number that a PDF prints in its page footer.
 *
 * <p>The footer is part of the page's text layer, so it arrives at the end of the extracted page like
 * any other line, and it does two kinds of damage.
 *
 * <p><strong>It makes the model cite the wrong page.</strong> A document's printed page number rarely
 * equals its PDF page number - front matter offsets them - and the model is offered both: the correct
 * PDF page in the citation header at the top of the passage, and a bare printed number at the bottom
 * of the passage that looks like part of the document. It prefers the latter. Measured on the Spring
 * Boot reference manual, whose offset is exactly 19, the evaluation suite recorded a 0.25 citation
 * fabrication rate and <em>every single instance</em> was this: PDF 299 cited as 280, PDF 404 as 385,
 * PDF 392 as 373. A reader following the citation lands 19 pages early.
 *
 * <p><strong>It manufactures worthless chunks.</strong> When a page's last block holds nothing but the
 * footer, the chunk that results is a page number and nothing else - 192 of that manual's 1,275 chunks,
 * 15% of the corpus, each one embedded, stored and retrievable. They are not inert: the query "what is
 * a Spring Boot starter" returned chunks whose entire text was "46" and "555" at similarity 0.775 and
 * 0.772, taking two of the five slots that should have held real content.
 *
 * <p>Detection is by consensus rather than by pattern alone, because "the last line is a number" is not
 * by itself enough to conclude it is a folio - a code block or a table could end that way. The offset
 * between PDF page and printed number is constant for a document, so the modal offset across its pages
 * identifies the scheme, and only a trailing number matching <em>that</em> offset is removed. A document
 * with no consistent folio yields no consensus and nothing is stripped, which is the safe failure.
 * Roman-numeral front matter simply does not match and is ignored on both sides of the calculation.
 */
public final class PageFooterStripper {

    private static final Logger log = LoggerFactory.getLogger(PageFooterStripper.class);

    /** A line that is a page number and nothing else. Five digits covers any real document. */
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d{1,5})\\s*$");

    /**
     * Below this many pages carrying a trailing number there is no population to take a mode from, and
     * a two-page document that happens to end both pages with a digit would otherwise establish a
     * "scheme" from pure coincidence.
     */
    private static final int MIN_PAGES_FOR_CONSENSUS = 3;

    /**
     * The modal offset has to account for at least this share of the pages that carry a trailing
     * number. Below it the numbers are not a folio sequence but incidental, so nothing is stripped.
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
