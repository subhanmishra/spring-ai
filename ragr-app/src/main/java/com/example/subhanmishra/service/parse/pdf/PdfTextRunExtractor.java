package com.example.subhanmishra.service.parse.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the positioned text runs of one page.
 * <p>
 * {@code PDFTextStripper} normally joins runs into lines and pads the gaps with spaces, which is what
 * destroys a table's column structure before anything can read it. Overriding {@code writeString} keeps
 * each run and where it sits instead.
 */
public final class PdfTextRunExtractor extends PDFTextStripper {

    private final List<TextRun> runs = new ArrayList<>();

    private PdfTextRunExtractor() throws IOException {
    }

    public static List<TextRun> extract(PDDocument document, int pageNumber) throws IOException {
        PdfTextRunExtractor extractor = new PdfTextRunExtractor();
        extractor.setStartPage(pageNumber);
        extractor.setEndPage(pageNumber);
        extractor.getText(document);
        return List.copyOf(extractor.runs);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        if (positions.isEmpty() || text.isBlank()) {
            return;
        }
        TextPosition first = positions.getFirst();
        TextPosition last = positions.getLast();

        runs.add(new TextRun(first.getXDirAdj(),
                             last.getXDirAdj() + last.getWidthDirAdj(),
                             first.getYDirAdj(),
                             first.getHeightDir(),
                             text.strip()));
    }
}
