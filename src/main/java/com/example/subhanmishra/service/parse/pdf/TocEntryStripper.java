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
 * <p>This is the second half of the same defect {@link PageFooterStripper} fixes, in a different place.
 * The footer put a <em>printed</em> page number at the bottom of every page; a contents entry puts one
 * at the end of every line, and the model reads it the same way. Measured on the Spring Boot reference
 * manual after footers were stripped, the evaluation suite still recorded a fabricated citation to page
 * 263 - the model had retrieved PDF page 11, a contents page, read
 * {@code "5.2.4. Configuring Endpoints . . . . . . . 263"} and cited 263. Nothing on the page it cited
 * had been retrieved at all.
 *
 * <p>The entries are also worthless to retrieve. A contents line duplicates a heading that appears
 * verbatim in the body, so it competes with the body for the same query while carrying none of the
 * answer: that same chunk came back at rank 4 for an actuator question and contributed nothing. In this
 * corpus they are 137 of 1,083 chunks - an eighth of everything stored - and all 137 are navigation.
 *
 * <p>Detection is by line shape rather than by page, because page position proves nothing: a contents
 * section can run to any length and a chapter can open with its own summary list. The shape is specific
 * enough to be safe. Requiring the dot leader to be at least five dots and the number to end the line
 * excludes the two things in this corpus that come closest - the Spring Boot banner's
 * {@code "....... . . ."} (no trailing number) and its
 * {@code "........ Started Example in 2.536 seconds (JVM running for 2.864)"} (dots leading, not
 * trailing). Verified against the whole stored corpus: the pattern matches on pages 2 to 19, the contents
 * section, and on no other page of 645.
 *
 * <p>A contents page loses its entries and keeps its heading, which would leave a chunk holding the words
 * "Table of Contents" and nothing else - the junk-chunk problem this is partly meant to remove. So a
 * block whose lines were <em>mostly</em> entries is dropped whole rather than emptied. Two guards keep
 * that from eating real text: the block must hold several entries, not one or two, and entries must be at
 * least half of it.
 *
 * <p>The document-level guard is the mirror of {@link PageFooterStripper}'s consensus rule. A file that
 * is <em>predominantly</em> contents entries is an index or a contents extract, and its entries are its
 * content; stripping it would index an empty document and answer nothing, silently. On the reference
 * manual entries are 705 of 19,715 non-blank lines, 3.6%, so the guard is nowhere near firing on a
 * document that merely has a contents section.
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
        // Removing a line leaves the blank lines that surrounded it back to back, so collapse them -
        // this text is persisted and embedded, and a paragraph boundary is what a blank line means.
        return String.join("\n", kept).replaceAll("\n{3,}", "\n\n").strip();
    }

    private static boolean isEntry(String line) {
        return ENTRY.matcher(line.stripTrailing()).find();
    }

    private static String[] splitLines(@Nullable String text) {
        return text == null ? new String[0] : text.split("\n", -1);
    }
}
