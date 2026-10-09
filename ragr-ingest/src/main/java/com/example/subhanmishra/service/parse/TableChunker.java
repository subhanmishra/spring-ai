package com.example.subhanmishra.service.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a {@link ContentBlock.Table} as one or more Markdown chunks.
 * <p>
 * Two rules make a table retrieve well and read correctly:
 * <ul>
 *   <li><b>Markdown pipe format.</b> The column names sit right above the rows, so the embedding sees them,
 *       and the chat model reads the format without being told how.</li>
 *   <li><b>The header is repeated on every chunk.</b> A large table is cut between rows, never inside one,
 *       and each piece repeats the caption and header. Otherwise later rows are values with no column
 *       names - the most common way a RAG answer misreads a table.</li>
 * </ul>
 */
public final class TableChunker {

    private static final Logger log = LoggerFactory.getLogger(TableChunker.class);

    /** Any run of whitespace, including the newlines a cell may contain, collapses to one space. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TableChunker() {
    }

    /**
     * One rendered piece of a table, carrying the rows it covers so the chunk metadata can say so.
     *
     * @param markdown the chunk text: caption, header, separator, then {@code firstRow..lastRow}
     * @param firstRow 1-based index of the first data row in this chunk, 0 when the table has no data rows
     * @param lastRow  1-based index of the last data row in this chunk, 0 when the table has no data rows
     */
    public record TableChunk(String markdown, int firstRow, int lastRow) {
    }

    /**
     * Splits a table into chunks that each stay within {@code budgetTokens} where possible.
     *
     * @param table         the table to render
     * @param budgetTokens  the per-chunk token budget, i.e. {@code app.ingestion.chunk-size}
     * @param ceilingTokens the hard limit above which the embedding model would silently truncate the
     *                      text, i.e. {@code app.ingestion.max-embed-tokens}
     * @return at least one chunk; never empty for a table that has any content
     */
    public static List<TableChunk> chunk(ContentBlock.Table table, int budgetTokens, int ceilingTokens) {
        String prefix = renderPrefix(table);
        int prefixTokens = TokenCounter.count(prefix);

        if (table.rows().isEmpty()) {
            // A header-only table is kept: its column names often answer "what fields does X have".
            return List.of(new TableChunk(prefix, 0, 0));
        }

        List<TableChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(prefix);
        int currentTokens = prefixTokens;
        int firstRowInChunk = 1;
        int rowNumber = 0;

        for (List<String> row : table.rows()) {
            rowNumber++;
            String rendered = "\n" + renderRow(row, table.header().size());
            int rowTokens = TokenCounter.count(rendered);

            // Close the chunk rather than overshoot, but never emit a chunk that is only a header.
            if (rowNumber > firstRowInChunk && currentTokens + rowTokens > budgetTokens) {
                chunks.add(new TableChunk(current.toString(), firstRowInChunk, rowNumber - 1));
                current = new StringBuilder(prefix);
                currentTokens = prefixTokens;
                firstRowInChunk = rowNumber;
            }

            current.append(rendered);
            currentTokens += rowTokens;

            if (currentTokens > ceilingTokens) {
                // One row and its header are already more than the embedding model reads. Splitting the
                // row would part values from their header, so it is kept whole: the stored text is
                // complete, but its vector comes from a truncated copy.
                log.warn("Table row {} plus its header is {} tokens, above the {} token embedding ceiling; "
                         + "the chunk is stored whole but its embedding will be truncated",
                         rowNumber, currentTokens, ceilingTokens);
            }
        }

        chunks.add(new TableChunk(current.toString(), firstRowInChunk, rowNumber));
        return chunks;
    }

    /** The caption, header row and separator that every chunk of this table starts with. */
    private static String renderPrefix(ContentBlock.Table table) {
        StringBuilder prefix = new StringBuilder();
        if (table.caption() != null && !table.caption().isBlank()) {
            prefix.append(normalise(table.caption())).append("\n\n");
        }
        prefix.append(renderRow(table.header(), table.header().size()))
              .append('\n')
              .append(renderSeparator(table.header().size()));
        return prefix.toString();
    }

    /**
     * Renders one row, padded to {@code width} so a short row still lines up under the right column names.
     * Extra cells beyond the header are kept: losing data is worse than an uneven table.
     */
    private static String renderRow(List<String> cells, int width) {
        StringBuilder row = new StringBuilder("|");
        for (int i = 0; i < Math.max(width, cells.size()); i++) {
            row.append(' ')
               .append(i < cells.size() ? escape(cells.get(i)) : "")
               .append(" |");
        }
        return row.toString();
    }

    private static String renderSeparator(int width) {
        return "|" + " --- |".repeat(Math.max(1, width));
    }

    /** A literal pipe inside a cell would otherwise read as a column break. */
    private static String escape(String cell) {
        return normalise(cell).replace("|", "\\|");
    }

    private static String normalise(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}
