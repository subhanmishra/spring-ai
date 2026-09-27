package com.example.subhanmishra.service.parse.pdf;

import com.example.subhanmishra.service.parse.ContentBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Recovers tables from one page's positioned text, using the drawn rules when there are any and the text's
 * own column alignment when there are not.
 * <p>
 * Sampling two real PDFs showed that a single strategy cannot cover both, because they fail in opposite
 * ways:
 * <ul>
 *   <li>An Asciidoctor manual draws <em>no</em> table borders - its 47,000 line operations are code-block
 *       backgrounds - but lays cells out left-aligned on constant x positions.</li>
 *   <li>An SSRS invoice rules every cell, but centres and right-aligns their contents, so a cell's start x
 *       moves with the length of its text and alignment tells you nothing.</li>
 * </ul>
 * Rows always come from banding text by y. Only the columns differ, and in the ruled case they come from
 * the rules <em>crossing that band</em>, never from the page as a whole: an invoice draws several
 * unrelated grids on one page, and pooling their rules invents boundaries that belong to none of them.
 * Binning a run by which column interval contains it is also what makes the ruled path immune to the
 * alignment problem.
 */
public final class PdfTableDetector {

    /** How far apart two runs' baselines may sit and still count as the same row. */
    private static final float ROW_TOLERANCE = 3f;

    /** Rules drawn for adjacent cells land a couple of points apart; they are one boundary. */
    private static final float COLUMN_TOLERANCE = 4f;

    /** Below this, a "column break" is the gap after a bullet glyph rather than a real column. */
    private static final float MIN_COLUMN_GAP = 24f;

    /** Two columns need three boundaries. Fewer rules than this cannot describe a grid. */
    private static final int MIN_RULES = 3;

    /** Fewer rows than this is a coincidence, not a table. */
    private static final int MIN_ROWS = 3;

    private static final int MIN_COLUMNS = 2;

    private PdfTableDetector() {
    }

    public enum Mode {
        /** Columns from the drawn vertical rules. */
        LATTICE,
        /** Columns from where the text runs start. */
        STREAM
    }

    /** One horizontal band of text: everything sharing a baseline, ordered left to right. */
    private record Band(float y, List<TextRun> runs) {
    }

    public static List<ContentBlock> read(List<TextRun> runs, List<LineSegment> lines, Mode mode) {
        List<Band> bands = bandsOf(runs);
        if (bands.isEmpty()) {
            return List.of();
        }

        List<LineSegment> verticals = lines.stream().filter(line -> line.isVertical(1f)).toList();
        List<LineSegment> horizontals = lines.stream().filter(line -> line.isHorizontal(1f)).toList();

        List<ContentBlock> blocks = new ArrayList<>();
        List<String> prose = new ArrayList<>();

        int index = 0;
        while (index < bands.size()) {
            int end = mode == Mode.LATTICE
                    ? latticeTableEnd(bands, index, verticals)
                    : streamTableEnd(bands, index);

            Optional<ContentBlock> table = end > index
                    ? (mode == Mode.LATTICE
                            ? latticeTable(bands.subList(index, end), verticals, horizontals)
                            : streamTable(bands.subList(index, end)))
                    : Optional.empty();

            if (table.isPresent()) {
                flushProse(prose, blocks);
                blocks.add(table.get());
                index = end;
                continue;
            }
            prose.add(lineOf(bands.get(index)));
            index++;
        }
        flushProse(prose, blocks);
        return List.copyOf(blocks);
    }

    /**
     * Groups runs into rows by baseline. Each band is then ordered left to right: cells on one visual row
     * do not share an exact baseline, so the y-ordering the grouping needs would otherwise leave a row's
     * cells out of reading order.
     */
    private static List<Band> bandsOf(List<TextRun> runs) {
        List<TextRun> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparing(TextRun::y).thenComparing(TextRun::x));

        List<List<TextRun>> grouped = new ArrayList<>();
        List<TextRun> current = new ArrayList<>();
        float bandY = Float.NaN;

        for (TextRun run : sorted) {
            if (current.isEmpty()) {
                bandY = run.y();
                current.add(run);
                continue;
            }
            if (Math.abs(run.y() - bandY) <= ROW_TOLERANCE) {
                current.add(run);
                continue;
            }
            grouped.add(current);
            current = new ArrayList<>();
            bandY = run.y();
            current.add(run);
        }
        if (!current.isEmpty()) {
            grouped.add(current);
        }

        List<Band> bands = new ArrayList<>(grouped.size());
        for (List<TextRun> group : grouped) {
            group.sort(Comparator.comparing(TextRun::x));
            bands.add(new Band(group.getFirst().y(), List.copyOf(group)));
        }
        return bands;
    }

    // ---------------------------------------------------------------- lattice

    /**
     * The rules crossing this band's baseline, clustered into boundaries. Restricting to the band keeps
     * one grid's boundaries out of another's, and it bounds the table vertically: a band the grid does not
     * cross is outside it, and the table ends there.
     */
    private static List<Float> rulesCrossing(Band band, List<LineSegment> verticals) {
        return cluster(verticals.stream()
                                .filter(rule -> rule.y1() <= band.y() + ROW_TOLERANCE
                                                && rule.y2() >= band.y() - ROW_TOLERANCE)
                                .map(LineSegment::x1)
                                .sorted()
                                .toList());
    }

    private static int latticeTableEnd(List<Band> bands, int from, List<LineSegment> verticals) {
        List<Float> rules = rulesCrossing(bands.get(from), verticals);
        if (rules.size() < MIN_RULES) {
            return from;
        }

        int end = from + 1;
        while (end < bands.size() && sameGrid(rulesCrossing(bands.get(end), verticals), rules)) {
            end++;
        }
        return end - from >= MIN_ROWS ? end : from;
    }

    /**
     * A later band belongs to the same grid when its boundaries are ones already seen and it is crossed by
     * nearly as many. The count matters as much as the positions: every band on the page is crossed by the
     * page's own frame, so matching on positions alone would let a table run on through the prose beneath
     * it, which is exactly what happened on the first sample invoice.
     */
    private static boolean sameGrid(List<Float> candidate, List<Float> rules) {
        if (candidate.size() < MIN_RULES || candidate.size() < rules.size() - 1) {
            return false;
        }
        return candidate.stream().allMatch(value -> rules.stream().anyMatch(rule -> near(value, rule)));
    }

    /**
     * Lays the bands out on the grid. Rows come from the <em>horizontal</em> rules, not from guessing which
     * bands look like continuations: a ruled table says outright where one row ends and the next begins, so
     * several bands falling between the same pair of rules are the wrapped lines of one cell. Guessing
     * instead - treating any band that leaves the last column empty as a wrapped line - cannot tell "Road,"
     * continuing an address from "GSTIN: | ... | From:" starting a new row, and folded whole tables into
     * their first row.
     */
    private static Optional<ContentBlock> latticeTable(List<Band> bands,
                                                       List<LineSegment> verticals,
                                                       List<LineSegment> horizontals) {
        List<Float> rules = rulesCrossing(bands.getFirst(), verticals);
        List<Float> rowLines = cluster(horizontals.stream().map(LineSegment::y1).sorted().toList());

        List<List<String>> rows = new ArrayList<>();
        int previousRow = Integer.MIN_VALUE;

        for (Band band : bands) {
            int rowIndex = intervalOf(rowLines, band.y());
            // One column per gap between rules. The space outside the outermost rules is page margin.
            if (rowIndex != previousRow || rows.isEmpty()) {
                rows.add(emptyRow(rules.size() - 1));
                previousRow = rowIndex;
            }
            List<String> cells = rows.getLast();
            for (TextRun run : band.runs()) {
                int column = intervalOf(rules, run.x());
                if (column >= 0 && column < cells.size()) {
                    append(cells, column, run.text());
                }
            }
        }
        return finish(rows, false);
    }

    /** Which gap between rules contains {@code x}; {@code -1} when it lies left of the first rule. */
    private static int intervalOf(List<Float> rules, float x) {
        int index = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i) <= x + COLUMN_TOLERANCE) {
                index = i;
            }
            else {
                break;
            }
        }
        return index;
    }

    // ----------------------------------------------------------------- stream

    /**
     * Without rules, a column is where runs repeatedly start. A gap narrower than {@link #MIN_COLUMN_GAP}
     * is not one - that is the space after a bullet glyph, and a bulleted list would otherwise read as a
     * two-column table.
     */
    private static List<Float> startsOf(Band band) {
        List<Float> starts = new ArrayList<>();
        TextRun previous = null;
        for (TextRun run : band.runs()) {
            if (previous != null && run.x() - previous.endX() < MIN_COLUMN_GAP) {
                continue;
            }
            starts.add(run.x());
            previous = run;
        }
        return starts;
    }

    private static int streamTableEnd(List<Band> bands, int from) {
        List<Float> columns = startsOf(bands.get(from));
        if (columns.size() < MIN_COLUMNS + 1) {
            // With no geometry to bound it, stream mode only claims a table when the alignment is
            // unambiguous: three or more columns. Two-column borderless tables stay prose.
            return from;
        }

        int end = from + 1;
        while (end < bands.size() && continuesStream(bands.get(end), columns)) {
            end++;
        }
        return end - from >= MIN_ROWS ? end : from;
    }

    private static boolean continuesStream(Band band, List<Float> columns) {
        List<Float> starts = startsOf(band);
        if (starts.size() < MIN_COLUMNS) {
            return false;
        }
        return starts.stream().allMatch(start -> columns.stream().anyMatch(column -> near(start, column)));
    }

    private static Optional<ContentBlock> streamTable(List<Band> bands) {
        List<Float> columns = startsOf(bands.getFirst());

        List<List<String>> rows = new ArrayList<>();
        for (Band band : bands) {
            List<String> cells = emptyRow(columns.size());
            for (TextRun run : band.runs()) {
                int column = nearestColumn(columns, run.x());
                if (column >= 0) {
                    append(cells, column, run.text());
                }
            }
            rows.add(cells);
        }
        return finish(rows, true);
    }

    private static int nearestColumn(List<Float> columns, float x) {
        int best = -1;
        float bestDistance = MIN_COLUMN_GAP;
        for (int i = 0; i < columns.size(); i++) {
            float distance = Math.abs(columns.get(i) - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ rows

    /**
     * Turns raw rows into a table: drop the columns nothing ever lands in, fold wrapped lines into the row
     * above, then check there is still a table left.
     * <p>
     * Dropping empty columns has to happen first. In a ruled PDF the outermost rules are the page frame,
     * so the first and last columns are margins that no text occupies - and while they are still present,
     * every row looks like it leaves the last column empty, which is the test for a wrapped line. Left in,
     * they fold the entire table into its first row.
     */
    private static Optional<ContentBlock> finish(List<List<String>> rows, boolean mergeContinuations) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        List<List<String>> trimmed = dropEmptyColumns(rows);
        if (trimmed.isEmpty() || trimmed.getFirst().size() < MIN_COLUMNS) {
            return Optional.empty();
        }

        List<List<String>> merged = new ArrayList<>();
        for (List<String> row : trimmed) {
            if (mergeContinuations) {
                addRowOrContinuation(merged, row);
            }
            else {
                merged.add(new ArrayList<>(row));
            }
        }

        if (merged.size() < MIN_ROWS || merged.stream().flatMap(List::stream).allMatch(String::isBlank)) {
            return Optional.empty();
        }
        return Optional.of(new ContentBlock.Table(merged.getFirst(),
                                                  List.copyOf(merged.subList(1, merged.size())),
                                                  null));
    }

    private static List<List<String>> dropEmptyColumns(List<List<String>> rows) {
        int width = rows.getFirst().size();
        List<Integer> keep = new ArrayList<>();
        for (int column = 0; column < width; column++) {
            int index = column;
            if (rows.stream().anyMatch(row -> !row.get(index).isBlank())) {
                keep.add(column);
            }
        }

        List<List<String>> trimmed = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> kept = new ArrayList<>(keep.size());
            keep.forEach(column -> kept.add(row.get(column)));
            trimmed.add(kept);
        }
        return trimmed;
    }

    /**
     * Adds the row, unless it continues the one above it. A continuation leaves the last column empty: a
     * genuinely short row such as "Sub Total | | 8,33,527" still fills it, which is what separates a
     * wrapped line from a row with gaps.
     */
    private static void addRowOrContinuation(List<List<String>> rows, List<String> cells) {
        boolean partial = cells.stream().anyMatch(String::isEmpty);
        if (!rows.isEmpty() && partial && cells.getLast().isEmpty()) {
            List<String> previous = rows.getLast();
            for (int i = 0; i < cells.size(); i++) {
                if (!cells.get(i).isEmpty()) {
                    previous.set(i, (previous.get(i) + " " + cells.get(i)).strip());
                }
            }
            return;
        }
        rows.add(new ArrayList<>(cells));
    }

    // ----------------------------------------------------------------- shared

    private static List<String> emptyRow(int width) {
        List<String> cells = new ArrayList<>(Math.max(0, width));
        for (int i = 0; i < width; i++) {
            cells.add("");
        }
        return cells;
    }

    private static void append(List<String> cells, int column, String text) {
        cells.set(column, cells.get(column).isEmpty() ? text : cells.get(column) + " " + text);
    }

    private static List<Float> cluster(List<Float> values) {
        List<Float> clustered = new ArrayList<>();
        for (float value : values) {
            if (clustered.isEmpty() || value - clustered.getLast() > COLUMN_TOLERANCE) {
                clustered.add(value);
            }
        }
        return clustered;
    }

    private static boolean near(float left, float right) {
        return Math.abs(left - right) <= COLUMN_TOLERANCE;
    }

    private static String lineOf(Band band) {
        return String.join(" ", band.runs().stream().map(TextRun::text).toList()).strip();
    }

    private static void flushProse(List<String> prose, List<ContentBlock> blocks) {
        if (prose.isEmpty()) {
            return;
        }
        String text = String.join("\n\n", prose).strip();
        if (!text.isBlank()) {
            blocks.add(new ContentBlock.Prose(text));
        }
        prose.clear();
    }
}
