package com.example.subhanmishra.service.parse;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One piece of a document as a reader found it, before chunking: prose, a table, and - from Markdown
 * only - a heading or a code block.
 * <p>
 * Plain text cannot say "these lines are one table", so tables used to be merged with the prose around
 * them and cut in half, leaving rows without their header. Marking the kind lets the chunker keep a
 * table, or a code block, together.
 */
public sealed interface ContentBlock {

    /**
     * A heading the source marked as one ({@code ## Title} in Markdown). It moves the section path on and
     * may start a new chunk. Headings are never guessed from plain text: the PDF guesses mislabelled
     * thousands of chunks.
     *
     * @param level 1 to 6
     * @param text  the heading's words, without the {@code #} marks
     */
    record Heading(int level, String text) implements ContentBlock {
    }

    /**
     * Text that must reach a chunk whole, never cut at a blank line or by the token splitter: a fenced
     * code block with its fences, or a list or quote that holds one. A cut code block loses its fence,
     * and the half without it reads as prose.
     *
     * @param language the fence's language ({@code java}, {@code yaml}), or empty
     * @param text     the Markdown as it goes into the chunk, fences included
     */
    record Code(String language, String text) implements ContentBlock {
    }

    /**
     * Running text, with blank lines between paragraphs.
     */
    record Prose(String text) implements ContentBlock {
    }

    /**
     * A table kept as a grid rather than as pre-rendered text, so the chunker can split it by rows and
     * repeat the header on each piece.
     *
     * @param header  the column names, repeated on every chunk this table is split into
     * @param rows    the data rows, each normalised to {@code header.size()} cells where possible
     * @param caption the table's caption, when the source had one; prefixed to every chunk
     */
    record Table(List<String> header, List<List<String>> rows, @Nullable String caption) implements ContentBlock {
    }
}
