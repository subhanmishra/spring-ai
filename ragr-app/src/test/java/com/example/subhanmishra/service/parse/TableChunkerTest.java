package com.example.subhanmishra.service.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the contract that makes a table survive chunking: it is cut between rows and never within one,
 * and every piece restates the header so the values in it still have column names to bind to. Without the
 * repeated header, rows in the second chunk carry bare values - the most common way a grounded answer
 * confidently misreads a table.
 */
class TableChunkerTest {

    /** Small enough that a handful of short rows already overflows it. */
    private static final int BUDGET_TOKENS = 30;

    private static final int CEILING_TOKENS = 2048;

    private static final List<String> HEADER = List.of("Code", "Meaning");

    @Test
    @DisplayName("a table within budget becomes exactly one chunk")
    void smallTableIsASingleChunk() {
        ContentBlock.Table table = new ContentBlock.Table(HEADER,
                                                          List.of(List.of("E01", "Overheat"),
                                                                  List.of("E02", "Low pressure")),
                                                          null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().markdown()).isEqualTo("""
                | Code | Meaning |
                | --- | --- |
                | E01 | Overheat |
                | E02 | Low pressure |""");
        assertThat(chunks.getFirst().firstRow()).isEqualTo(1);
        assertThat(chunks.getFirst().lastRow()).isEqualTo(2);
    }

    @Test
    @DisplayName("every chunk of a split table restates the header and separator")
    void headerIsRepeatedOnEveryChunk() {
        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table(12), BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.markdown())
                .startsWith("| Code | Meaning |\n| --- | --- |\n"));
    }

    @Test
    @DisplayName("splitting happens between rows, and every row lands in exactly one chunk")
    void everyRowSurvivesExactlyOnce() {
        int rowCount = 12;

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table(rowCount), BUDGET_TOKENS, CEILING_TOKENS);

        List<String> dataLines = new ArrayList<>();
        for (TableChunker.TableChunk chunk : chunks) {
            // Everything after the header and separator lines is data.
            dataLines.addAll(List.of(chunk.markdown().split("\n")).subList(2, chunk.markdown().split("\n").length));
        }

        assertThat(dataLines).hasSize(rowCount);
        for (int row = 1; row <= rowCount; row++) {
            assertThat(dataLines.get(row - 1)).isEqualTo("| E%02d | fault %d |".formatted(row, row));
        }
    }

    @Test
    @DisplayName("the row ranges of the chunks are contiguous and cover the whole table")
    void rowRangesAreContiguous() {
        int rowCount = 12;

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table(rowCount), BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks.getFirst().firstRow()).isEqualTo(1);
        assertThat(chunks.getLast().lastRow()).isEqualTo(rowCount);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).firstRow()).isEqualTo(chunks.get(i - 1).lastRow() + 1);
        }
    }

    @Test
    @DisplayName("a caption is repeated ahead of the header on every chunk")
    void captionIsRepeatedOnEveryChunk() {
        ContentBlock.Table table = new ContentBlock.Table(HEADER, rows(12), "Table 4. Alarm codes");

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.markdown()).startsWith("Table 4. Alarm codes\n\n| Code |"));
    }

    @Test
    @DisplayName("a header-only table is still emitted, with no row range")
    void headerOnlyTableIsKept() {
        ContentBlock.Table table = new ContentBlock.Table(HEADER, List.of(), null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().markdown()).isEqualTo("| Code | Meaning |\n| --- | --- |");
        assertThat(chunks.getFirst().lastRow()).isZero();
    }

    @Test
    @DisplayName("a pipe inside a cell is escaped rather than read as a column break")
    void pipesInCellsAreEscaped() {
        ContentBlock.Table table = new ContentBlock.Table(List.of("Expression"),
                                                          List.of(List.of("a | b")),
                                                          null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks.getFirst().markdown()).endsWith("| a \\| b |");
    }

    @Test
    @DisplayName("newlines inside a cell collapse so one row stays on one line")
    void multilineCellsAreFlattened() {
        ContentBlock.Table table = new ContentBlock.Table(HEADER,
                                                          List.of(List.of("E01", "Overheat\n  in the manifold")),
                                                          null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks.getFirst().markdown().lines()).hasSize(3);
        assertThat(chunks.getFirst().markdown()).endsWith("| E01 | Overheat in the manifold |");
    }

    @Test
    @DisplayName("a row with fewer cells than the header is padded so values stay under the right column")
    void raggedRowsArePadded() {
        ContentBlock.Table table = new ContentBlock.Table(List.of("Code", "Meaning", "Action"),
                                                          List.of(List.of("E01")),
                                                          null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks.getFirst().markdown()).endsWith("| E01 |  |  |");
    }

    @Test
    @DisplayName("a single row larger than the budget is emitted whole rather than cut apart")
    void oversizedRowIsNotSplit() {
        String wide = "word ".repeat(BUDGET_TOKENS * 2).trim();
        ContentBlock.Table table = new ContentBlock.Table(HEADER, List.of(List.of("E01", wide)), null);

        List<TableChunker.TableChunk> chunks = TableChunker.chunk(table, BUDGET_TOKENS, CEILING_TOKENS);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().markdown()).contains(wide);
    }

    private static ContentBlock.Table table(int rowCount) {
        return new ContentBlock.Table(HEADER, rows(rowCount), null);
    }

    private static List<List<String>> rows(int rowCount) {
        List<List<String>> rows = new ArrayList<>(rowCount);
        for (int row = 1; row <= rowCount; row++) {
            rows.add(List.of("E%02d".formatted(row), "fault " + row));
        }
        return rows;
    }
}
