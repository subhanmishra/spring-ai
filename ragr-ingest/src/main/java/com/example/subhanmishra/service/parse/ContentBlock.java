package com.example.subhanmishra.service.parse;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One structural unit of a source document, as recovered by a reader before any chunking happens.
 * <p>
 * The pipeline used to hand raw {@code String} text from the reader straight to the chunker, which then
 * split it on blank lines. That representation cannot express "these lines belong to one table", so a
 * table was free to be merged with the prose around it and, once over budget, cut mid-table by
 * {@code TokenTextSplitter} - leaving rows in a chunk with no header row to bind their values to.
 * Making the block kind explicit is what lets the chunker treat a table as an atomic unit.
 */
public sealed interface ContentBlock {

    /**
     * Free-flowing text. Paragraph boundaries within it are still expressed as blank lines, so prose
     * blocks are coalesced exactly as before this type existed.
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
