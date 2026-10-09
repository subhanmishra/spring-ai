package com.example.subhanmishra.service.parse.pdf;

import com.example.subhanmishra.service.parse.ContentBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a PDF into per-page {@link ContentBlock}s, recovering tables from the page geometry.
 * <p>
 * Used in place of {@code PagePdfDocumentReader} when table detection is on: that reader pads the gaps
 * between cells with spaces, after which the columns cannot be recovered.
 * <p>
 * The strategy is chosen per page, since a document can rule some tables and not others. A page with
 * enough vertical lines to form columns is read as ruled; any other by the text's alignment.
 */
public final class PdfBlockReader {

    private static final Logger log = LoggerFactory.getLogger(PdfBlockReader.class);

    /**
     * Fewer distinct vertical lines than this and the page is read as unruled. Not "are there any lines":
     * the manual draws thousands for code-block backgrounds and rules no table at all.
     */
    private static final int MIN_RULES_FOR_LATTICE = 3;

    /** One page's blocks, plus the page number to carry onto every chunk made from them. */
    public record Page(int pageNumber, List<ContentBlock> blocks) {
    }

    /**
     * The pages that carry content, and how many pages the file actually has.
     *
     * <p>The two differ - pages with no text, or only a footer or contents entries, are dropped - so the
     * count is kept separately. Otherwise the 645-page manual would report 627 pages.
     */
    public record Pdf(int pageCount, List<Page> pages) {
    }

    private PdfBlockReader() {
    }

    public static Pdf read(InputStream pdf, PdfTableDetector.Mode forcedMode) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.readAllBytes())) {
            List<Page> pages = new ArrayList<>(document.getNumberOfPages());

            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                List<TextRun> runs = PdfTextRunExtractor.extract(document, pageNumber);
                if (runs.isEmpty()) {
                    continue;
                }

                List<LineSegment> lines = new PdfLineExtractor(document.getPage(pageNumber - 1)).extract();
                PdfTableDetector.Mode mode = forcedMode != null ? forcedMode : modeFor(lines);

                List<ContentBlock> blocks = PdfTableDetector.read(runs, lines, mode);
                pages.add(new Page(pageNumber, blocks));

                long tables = blocks.stream().filter(ContentBlock.Table.class::isInstance).count();
                if (tables > 0) {
                    log.debug("Page {} read in {} mode: {} block(s), {} table(s)",
                              pageNumber, mode, blocks.size(), tables);
                }
            }
            return new Pdf(document.getNumberOfPages(), stripPageFooters(stripTocEntries(pages)));
        }
    }

    /**
     * Removes table-of-contents entries, and any block left holding nothing but a contents heading.
     *
     * <p>The order against {@link #stripPageFooters} does not matter: a contents line is never
     * <em>only</em> a number. Tables are left alone - a contents section is not a table, and clipping a
     * real table's last cell would be worse than the problem being fixed.
     */
    private static List<Page> stripTocEntries(List<Page> pages) {
        TocEntryStripper stripper = TocEntryStripper.detect(
                pages.stream()
                     .flatMap(page -> page.blocks().stream())
                     .filter(ContentBlock.Prose.class::isInstance)
                     .map(block -> ((ContentBlock.Prose) block).text())
                     .toList());
        if (!stripper.active()) {
            return List.copyOf(pages);
        }

        List<Page> kept = new ArrayList<>(pages.size());
        int removedBlocks = 0;

        for (Page page : pages) {
            List<ContentBlock> blocks = new ArrayList<>(page.blocks().size());
            for (ContentBlock block : page.blocks()) {
                if (!(block instanceof ContentBlock.Prose prose)) {
                    blocks.add(block);
                    continue;
                }
                String text = stripper.strip(prose.text());
                if (text.isBlank()) {
                    removedBlocks++;
                } else {
                    blocks.add(text.equals(prose.text()) ? block : new ContentBlock.Prose(text));
                }
            }
            if (!blocks.isEmpty()) {
                kept.add(new Page(page.pageNumber(), List.copyOf(blocks)));
            }
        }

        log.info("Stripped table-of-contents entries; {} block(s) held nothing else and were dropped, "
                 + "leaving {} of {} page(s) with content", removedBlocks, kept.size(), pages.size());
        return List.copyOf(kept);
    }

    /**
     * Removes the printed page number from the end of each page.
     *
     * <p>Runs once every page is read, because {@link PageFooterStripper} needs the whole document to
     * find the numbering scheme.
     *
     * <p>Only a page's <strong>last</strong> block is checked, and only when it is prose: the footer is
     * last on the page, and is never inside a table (checked on the manual).
     */
    private static List<Page> stripPageFooters(List<Page> pages) {
        Map<Integer, String> tails = new LinkedHashMap<>();
        for (Page page : pages) {
            lastProse(page).ifPresent(prose -> tails.put(page.pageNumber(), prose.text()));
        }

        PageFooterStripper stripper = PageFooterStripper.detect(tails);
        if (!stripper.active()) {
            return List.copyOf(pages);
        }

        List<Page> stripped = new ArrayList<>(pages.size());
        int removedBlocks = 0;
        for (Page page : pages) {
            int last = lastProseIndex(page);
            if (last < 0) {
                stripped.add(page);
                continue;
            }
            ContentBlock.Prose prose = (ContentBlock.Prose) page.blocks().get(last);
            String text = stripper.strip(page.pageNumber(), prose.text());
            if (text.equals(prose.text())) {
                stripped.add(page);
                continue;
            }

            List<ContentBlock> blocks = new ArrayList<>(page.blocks());
            if (text.isBlank()) {
                // The footer was the whole block; keeping it would store a chunk that is just a number.
                blocks.remove(last);
                removedBlocks++;
            } else {
                blocks.set(last, new ContentBlock.Prose(text));
            }
            // A page left with no blocks is dropped; it has nothing to chunk.
            if (!blocks.isEmpty()) {
                stripped.add(new Page(page.pageNumber(), List.copyOf(blocks)));
            }
        }

        log.info("Stripped printed page-number footers; {} block(s) held nothing else and were dropped",
                 removedBlocks);
        return List.copyOf(stripped);
    }

    private static Optional<ContentBlock.Prose> lastProse(Page page) {
        int index = lastProseIndex(page);
        return index < 0 ? Optional.empty() : Optional.of((ContentBlock.Prose) page.blocks().get(index));
    }

    /** Index of the page's final block when it is prose, or -1 when the page ends with a table. */
    private static int lastProseIndex(Page page) {
        int last = page.blocks().size() - 1;
        return last >= 0 && page.blocks().get(last) instanceof ContentBlock.Prose ? last : -1;
    }

    private static PdfTableDetector.Mode modeFor(List<LineSegment> lines) {
        long verticalRules = lines.stream().filter(line -> line.isVertical(1f)).map(LineSegment::x1).distinct().count();
        return verticalRules >= MIN_RULES_FOR_LATTICE ? PdfTableDetector.Mode.LATTICE : PdfTableDetector.Mode.STREAM;
    }
}
