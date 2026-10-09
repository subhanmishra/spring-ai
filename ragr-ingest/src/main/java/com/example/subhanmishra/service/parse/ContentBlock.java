package com.example.subhanmishra.service.parse;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One piece of a document as a reader found it, before chunking: prose, or a table.
 * <p>
 * Plain text cannot say "these lines are one table", so tables used to be merged with the prose around
 * them and cut in half, leaving rows without their header. Marking the kind lets the chunker keep a
 * table together.
 */
public sealed interface ContentBlock {

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
