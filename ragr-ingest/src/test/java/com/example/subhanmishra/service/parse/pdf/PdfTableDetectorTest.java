package com.example.subhanmishra.service.parse.pdf;

import com.example.subhanmishra.service.parse.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the detector with synthetic geometry, so each rule it relies on can be stated on its own rather
 * than inferred from a whole document. The shapes here are the ones two real PDFs actually produce:
 * borderless left-aligned columns in an Asciidoctor manual, and ruled cells with right-aligned numbers in
 * an SSRS invoice.
 */
class PdfTableDetectorTest {

    // --------------------------------------------------------------- stream

    @Test
    @DisplayName("borderless columns on constant x positions are recovered as a table")
    void streamRecoversAlignedColumns() {
        List<TextRun> runs = new ArrayList<>();
        row(runs, 100, "Group ID", "Artifact ID", "Version");
        row(runs, 120, "com.zaxxer", "HikariCP", "3.4.5");
        row(runs, 140, "com.h2database", "h2", "1.4.200");
        row(runs, 160, "io.r2dbc", "r2dbc-h2", "0.8.4");

        List<ContentBlock> blocks = PdfTableDetector.read(runs, List.of(), PdfTableDetector.Mode.STREAM);

        assertThat(blocks).hasSize(1);
        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.header()).containsExactly("Group ID", "Artifact ID", "Version");
        assertThat(table.rows()).containsExactly(List.of("com.zaxxer", "HikariCP", "3.4.5"),
                                                 List.of("com.h2database", "h2", "1.4.200"),
                                                 List.of("io.r2dbc", "r2dbc-h2", "0.8.4"));
    }

    @Test
    @DisplayName("a bulleted list is not a two-column table")
    void streamIgnoresBulletedLists() {
        // The gap after a bullet glyph is a few points; a column break is tens of points.
        List<TextRun> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            float y = 100 + i * 20;
            runs.add(new TextRun(57, 62, y, 10, "•"));
            runs.add(new TextRun(66, 300, y, 10, "A bulleted line of text number " + i));
        }

        List<ContentBlock> blocks = PdfTableDetector.read(runs, List.of(), PdfTableDetector.Mode.STREAM);

        assertThat(blocks).singleElement().isInstanceOf(ContentBlock.Prose.class);
    }

    @Test
    @DisplayName("a wrapped cell is folded into the row above rather than becoming a row")
    void streamFoldsWrappedCells() {
        List<TextRun> runs = new ArrayList<>();
        row(runs, 100, "Group ID", "Artifact ID", "Version");
        row(runs, 120, "com.github.mxab", "thymeleaf-extras-data-", "2.0.1");
        // The continuation: only the first two columns, and nothing in the last.
        runs.add(new TextRun(51, 90, 140, 10, "as"));
        runs.add(new TextRun(218, 300, 140, 10, "attribute"));
        row(runs, 160, "com.zaxxer", "HikariCP", "3.4.5");

        List<ContentBlock> blocks = PdfTableDetector.read(runs, List.of(), PdfTableDetector.Mode.STREAM);

        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.rows()).containsExactly(List.of("com.github.mxab as", "thymeleaf-extras-data- attribute", "2.0.1"),
                                                 List.of("com.zaxxer", "HikariCP", "3.4.5"));
    }

    @Test
    @DisplayName("two aligned rows are a coincidence, not a table")
    void streamNeedsEnoughRows() {
        List<TextRun> runs = new ArrayList<>();
        row(runs, 100, "Group ID", "Artifact ID", "Version");
        row(runs, 120, "com.zaxxer", "HikariCP", "3.4.5");

        List<ContentBlock> blocks = PdfTableDetector.read(runs, List.of(), PdfTableDetector.Mode.STREAM);

        assertThat(blocks).singleElement().isInstanceOf(ContentBlock.Prose.class);
    }

    @Test
    @DisplayName("prose above and below a table keeps its place")
    void streamKeepsReadingOrder() {
        List<TextRun> runs = new ArrayList<>();
        runs.add(new TextRun(51, 400, 60, 10, "Some introductory prose."));
        row(runs, 100, "Group ID", "Artifact ID", "Version");
        row(runs, 120, "com.zaxxer", "HikariCP", "3.4.5");
        row(runs, 140, "com.h2database", "h2", "1.4.200");
        row(runs, 160, "io.r2dbc", "r2dbc-h2", "0.8.4");
        runs.add(new TextRun(51, 400, 200, 10, "Closing prose."));

        List<ContentBlock> blocks = PdfTableDetector.read(runs, List.of(), PdfTableDetector.Mode.STREAM);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isEqualTo(new ContentBlock.Prose("Some introductory prose."));
        assertThat(blocks.get(1)).isInstanceOf(ContentBlock.Table.class);
        assertThat(blocks.get(2)).isEqualTo(new ContentBlock.Prose("Closing prose."));
    }

    // -------------------------------------------------------------- lattice

    @Test
    @DisplayName("ruled columns survive right-aligned cells, where start positions vary per row")
    void latticeHandlesRightAlignedCells() {
        // Cells drawn between rules at x = 20, 100, 300, 420, 560. The numbers are right-aligned, so
        // their start x moves with their length - exactly what defeats start-position clustering.
        List<LineSegment> lines = grid(List.of(20f, 100f, 300f, 420f, 560f), List.of(90f, 110f, 130f, 150f, 170f));

        List<TextRun> runs = new ArrayList<>();
        runs.add(new TextRun(30, 80, 100, 10, "Sl.No."));
        runs.add(new TextRun(150, 250, 100, 10, "Description"));
        runs.add(new TextRun(330, 400, 100, 10, "SAC Code"));
        runs.add(new TextRun(440, 540, 100, 10, "Amount (Rs)"));

        runs.add(new TextRun(144, 260, 120, 10, "Developer Revenue Share"));
        runs.add(new TextRun(348, 400, 120, 10, "995411"));
        runs.add(new TextRun(500, 555, 120, 10, "5,75,134"));

        runs.add(new TextRun(140, 260, 140, 10, "Land Owner Revenue Share"));
        runs.add(new TextRun(348, 400, 140, 10, "995411"));
        runs.add(new TextRun(500, 555, 140, 10, "2,58,393"));

        // Short row: a different start x again, and it fills the final column.
        runs.add(new TextRun(266, 300, 160, 10, "Sub Total"));
        runs.add(new TextRun(515, 555, 160, 10, "8,33,527"));

        List<ContentBlock> blocks = PdfTableDetector.read(runs, lines, PdfTableDetector.Mode.LATTICE);

        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.header()).containsExactly("Sl.No.", "Description", "SAC Code", "Amount (Rs)");
        assertThat(table.rows()).containsExactly(
                List.of("", "Developer Revenue Share", "995411", "5,75,134"),
                List.of("", "Land Owner Revenue Share", "995411", "2,58,393"),
                List.of("", "Sub Total", "", "8,33,527"));
    }

    @Test
    @DisplayName("bands between the same pair of horizontal rules are one row")
    void latticeGroupsRowsByHorizontalRules() {
        List<LineSegment> lines = grid(List.of(20f, 100f, 300f), List.of(90f, 150f, 190f, 230f));

        List<TextRun> runs = new ArrayList<>();
        runs.add(new TextRun(30, 80, 100, 10, "Address:"));
        runs.add(new TextRun(150, 250, 100, 10, "FLAT NO B6-1002"));
        // Same cell, wrapped onto a second line: no rule separates it from the line above.
        runs.add(new TextRun(150, 250, 120, 10, "KANNAMANGALA"));
        runs.add(new TextRun(30, 80, 160, 10, "Phone:"));
        runs.add(new TextRun(150, 250, 160, 10, "+918598042060"));
        runs.add(new TextRun(30, 80, 200, 10, "E-mail:"));
        runs.add(new TextRun(150, 250, 200, 10, "someone@example.com"));

        List<ContentBlock> blocks = PdfTableDetector.read(runs, lines, PdfTableDetector.Mode.LATTICE);

        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.header()).containsExactly("Address:", "FLAT NO B6-1002 KANNAMANGALA");
        assertThat(table.rows()).containsExactly(List.of("Phone:", "+918598042060"),
                                                 List.of("E-mail:", "someone@example.com"));
    }

    @Test
    @DisplayName("a page frame alone is not a grid")
    void latticeIgnoresAPageBorder() {
        // Two vertical rules - the page's own edges - cannot describe columns.
        List<LineSegment> lines = List.of(LineSegment.of(20, 50, 20, 400),
                                          LineSegment.of(560, 50, 560, 400));

        List<TextRun> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(new TextRun(30, 500, 100 + i * 20, 10, "A line of ordinary prose number " + i));
        }

        List<ContentBlock> blocks = PdfTableDetector.read(runs, lines, PdfTableDetector.Mode.LATTICE);

        assertThat(blocks).singleElement().isInstanceOf(ContentBlock.Prose.class);
    }

    // --------------------------------------------------------------- helpers

    /** Three left-aligned columns at the x positions the sample manual uses. */
    private static void row(List<TextRun> runs, float y, String first, String second, String third) {
        runs.add(new TextRun(51, 150, y, 10, first));
        runs.add(new TextRun(218, 330, y, 10, second));
        runs.add(new TextRun(384, 450, y, 10, third));
    }

    /** A full grid of rules at the given column and row positions. */
    private static List<LineSegment> grid(List<Float> columnXs, List<Float> rowYs) {
        List<LineSegment> lines = new ArrayList<>();
        float top = rowYs.getFirst();
        float bottom = rowYs.getLast();
        for (float x : columnXs) {
            lines.add(LineSegment.of(x, top, x, bottom));
        }
        float left = columnXs.getFirst();
        float right = columnXs.getLast();
        for (float y : rowYs) {
            lines.add(LineSegment.of(left, y, right, y));
        }
        return lines;
    }
}
