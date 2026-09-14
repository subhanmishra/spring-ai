package com.example.subhanmishra.service.parse.pdf;

import com.example.subhanmishra.service.parse.ContentBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    private PdfBlockReader() {
    }

    public static List<Page> read(InputStream pdf, PdfTableDetector.Mode forcedMode) throws IOException {
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
            return List.copyOf(pages);
        }
    }

    private static PdfTableDetector.Mode modeFor(List<LineSegment> lines) {
        long verticalRules = lines.stream().filter(line -> line.isVertical(1f)).map(LineSegment::x1).distinct().count();
        return verticalRules >= MIN_RULES_FOR_LATTICE ? PdfTableDetector.Mode.LATTICE : PdfTableDetector.Mode.STREAM;
    }
}
