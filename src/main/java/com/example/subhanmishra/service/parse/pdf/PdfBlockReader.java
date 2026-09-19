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
 * This replaces {@code PagePdfDocumentReader} only when table detection is switched on. That reader
 * returns each page as flat text, with {@code PDFTextStripper} having already padded the gaps between
 * cells with spaces - by which point a table's columns are unrecoverable.
 * <p>
 * Which strategy a page uses is decided per page, not per document: a manual can rule its appendix tables
 * and leave the rest borderless. A page is treated as ruled when it carries enough vertical rules to form
 * columns; otherwise the text's own alignment is used.
 */
public final class PdfBlockReader {

    private static final Logger log = LoggerFactory.getLogger(PdfBlockReader.class);

    /**
     * Below this many distinct vertical rules there is nothing to build columns from, and the page is read
     * as unruled. Deliberately not "are there any line operations": the 645-page manual draws tens of
     * thousands of them for code-block backgrounds and rules no table at all.
     */
    private static final int MIN_RULES_FOR_LATTICE = 3;

    /** One page's blocks, plus the page number to carry onto every chunk made from them. */
    public record Page(int pageNumber, List<ContentBlock> blocks) {
    }

    /**
     * The pages that carry content, and how many pages the file actually has.
     *
     * <p>The two differ, which is why the count is carried rather than taken from the list. A page with
     * no text runs is skipped, and a page whose every block turns out to be a page-number footer or a
     * table-of-contents entry is dropped - 18 pages of the reference manual are contents. Reporting the
     * surviving pages as the document's page count would tell a caller a 645-page PDF has 627.
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
     * <p>Runs before {@link #stripPageFooters}, though the order does not in fact matter here: a
     * contents line ends in a page number but is not <em>only</em> a page number, so the footer
     * stripper never sees one as a footer. Tables are passed through untouched - a contents section is
     * not ruled, and clipping a trailing cell out of a real table would be a worse bug than the one
     * being fixed.
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
     * <p>Runs after every page has been read, because {@link PageFooterStripper} identifies the footer
     * by the offset that holds across the whole document rather than by the shape of one line - see its
     * javadoc for why a trailing number alone is not evidence enough.
     *
     * <p>Only the page's <strong>last</strong> block is considered, and only when it is prose. The
     * footer is spatially last, so nothing earlier can be it; and it is never inside a table, which was
     * verified rather than assumed - of 305 table chunks in the reference manual, zero ended with a bare
     * number, while 625 of 970 prose chunks did. Clipping a real trailing cell out of a table would be a
     * worse bug than the one being fixed.
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
                // The footer was the whole block. Keeping it would store a chunk holding nothing but a
                // page number - 15% of this manual's chunks were exactly that.
                blocks.remove(last);
                removedBlocks++;
            } else {
                blocks.set(last, new ContentBlock.Prose(text));
            }
            // A page left with no blocks at all is dropped; it contributes nothing to chunk or to page
            // attribution, and an empty page would otherwise floor the chunk count at one per page.
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
