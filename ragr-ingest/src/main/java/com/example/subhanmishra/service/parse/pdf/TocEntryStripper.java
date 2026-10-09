package com.example.subhanmishra.service.parse.pdf;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes table-of-contents entries - a heading, a run of dot leaders, and the page it sits on.
 *
 * <p>Two reasons, the same as {@link PageFooterStripper}'s:
 * <ul>
 *   <li><b>Wrong citations.</b> An entry ends with a printed page number, and the model cites it - having
 *       read "5.2.4. Configuring Endpoints . . . . 263" on a contents page, it cited page 263.</li>
 *   <li><b>Useless chunks.</b> An entry repeats a heading from the body, so it competes with the body for
 *       the same questions while holding none of the answer.</li>
 * </ul>
 *
 * <p><b>Found by the shape of a line</b>, not by its page: at least five dot leaders, then a number ending
 * the line. That is specific enough to skip the near misses in the manual, such as the Spring Boot banner.
 *
 * <p><b>Two guards keep it from removing real text:</b>
 * <ul>
 *   <li>A block that is <em>mostly</em> entries is dropped whole, so a contents page does not leave a chunk
 *       saying only "Table of Contents". It must hold several entries, at least half its lines.</li>
 *   <li>A document that is <em>mostly</em> entries - an index - is left alone. Its entries are its content.</li>
 * </ul>
 */
public final class TocEntryStripper {

    private static final Logger log = LoggerFactory.getLogger(TocEntryStripper.class);

    /**
     * A dot leader running into a page number at the end of the line. The separator between them is
     * often a non-breaking space, which is why {@code  } appears alongside the ordinary one.
     */
    private static final Pattern ENTRY =
            Pattern.compile("(?:\\.[ \\u00A0]*){5,}[ \\u00A0]*\\d{1,5}[ \\u00A0]*$");

    /** Below this many entries a block is body text that happens to contain one, not a contents page. */
    private static final int MIN_ENTRIES_TO_DROP_BLOCK = 5;

    /** Share of a block's non-blank lines that must be entries before the whole block goes. */
    private static final double BLOCK_DOMINANCE = 0.5;

    /** Above this share of the document, the entries are the document and nothing is stripped. */
    private static final double MAX_DOCUMENT_SHARE = 0.5;

    private final boolean active;

    private TocEntryStripper(boolean active) {
        this.active = active;
    }

    /** A stripper that never strips. */
    public static TocEntryStripper disabled() {
        return new TocEntryStripper(false);
    }

    /**
     * Decides whether this document's contents entries may be stripped.
     *
     * @param texts every piece of prose in the document, in any order
     */
    public static TocEntryStripper detect(Collection<String> texts) {
        long entries = 0;
        long nonBlank = 0;

        for (String text : texts) {
            for (String line : splitLines(text)) {
                if (line.isBlank()) {
                    continue;
                }
                nonBlank++;
                if (isEntry(line)) {
                    entries++;
                }
            }
        }

        if (entries == 0) {
            return new TocEntryStripper(false);
        }
        if (entries > nonBlank * MAX_DOCUMENT_SHARE) {
            log.info("{} of {} non-blank line(s) are table-of-contents entries, so they are this "
                     + "document's content; leaving them in place", entries, nonBlank);
            return new TocEntryStripper(false);
        }

        log.info("Found {} table-of-contents entr(ies) across {} non-blank line(s)", entries, nonBlank);
        return new TocEntryStripper(true);
    }

    public boolean active() {
        return active;
    }

    /**
     * The text without its contents entries.
     *
     * <p>Returns an empty string when the entries dominated the block, which means the remainder is a
     * contents heading and page furniture. Callers must drop the resulting block rather than store it.
     */
    public String strip(@Nullable String text) {
        if (!active || text == null || text.isEmpty()) {
            return text != null ? text : "";
        }

        List<String> kept = new ArrayList<>();
        int entries = 0;
        int nonBlank = 0;

        for (String line : splitLines(text)) {
            if (line.isBlank()) {
                kept.add(line);
                continue;
            }
            nonBlank++;
            if (isEntry(line)) {
                entries++;
            } else {
                kept.add(line);
            }
        }

        if (entries == 0) {
            return text;
        }
        if (entries >= MIN_ENTRIES_TO_DROP_BLOCK && entries >= nonBlank * BLOCK_DOMINANCE) {
            return "";
        }
        // A removed line leaves its blank lines back to back; collapse them, since a blank line marks a
        // paragraph boundary in stored text.
        return String.join("\n", kept).replaceAll("\n{3,}", "\n\n").strip();
    }

    private static boolean isEntry(String line) {
        return ENTRY.matcher(line.stripTrailing()).find();
    }

    private static String[] splitLines(@Nullable String text) {
        return text == null ? new String[0] : text.split("\n", -1);
    }
}
