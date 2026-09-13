package com.example.subhanmishra.service.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recovers {@link ContentBlock}s from the XHTML Tika emits.
 * <p>
 * Tika's parsers already reconstruct table structure from DOCX, XLSX, PPTX and HTML and emit real
 * {@code <table><tr><td>} markup. Spring AI's {@code TikaDocumentReader} defaults to a
 * {@code BodyContentHandler}, which throws those tags away and hands back flat text - so the structure was
 * available and being discarded.
 * <p>
 * Only the {@code <body>} is read: Tika's XHTML skeleton always carries a {@code <head>} with the document
 * title, and that is metadata rather than content.
 * <p>
 * Known, deliberate limitations: {@code colspan} and {@code rowspan} are not expanded, and a nested table
 * is flattened into the text of the cell that contains it rather than becoming a block of its own.
 */
public final class XhtmlBlockParser {

    /** Elements whose end marks a paragraph boundary in prose. */
    private static final Set<String> PARAGRAPH_ENDING =
            Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "blockquote", "pre", "tr");

    private XhtmlBlockParser() {
    }

    public static List<ContentBlock> parse(String xhtml) {
        Element body = Jsoup.parse(xhtml).body();

        Collector collector = new Collector();
        collector.walk(body);
        collector.flushProse();
        return List.copyOf(collector.blocks);
    }

    /**
     * Walks the body in document order so prose and tables come out interleaved as they were written. A
     * table closes whatever prose preceded it, which is what stops the two from sharing a chunk later.
     */
    private static final class Collector {

        private final List<ContentBlock> blocks = new ArrayList<>();
        private final List<String> paragraphs = new ArrayList<>();
        private final StringBuilder paragraph = new StringBuilder();

        private void walk(Element element) {
            for (Node node : element.childNodes()) {
                if (node instanceof TextNode text) {
                    paragraph.append(text.text());
                    continue;
                }
                if (!(node instanceof Element child)) {
                    continue;
                }

                if ("table".equals(child.tagName())) {
                    endParagraph();
                    flushProse();
                    toTable(child).ifPresent(blocks::add);
                    continue;
                }

                walk(child);
                if (PARAGRAPH_ENDING.contains(child.tagName())) {
                    endParagraph();
                }
            }
        }

        private void endParagraph() {
            String text = paragraph.toString().strip();
            if (!text.isBlank()) {
                paragraphs.add(text);
            }
            paragraph.setLength(0);
        }

        /**
         * Emits everything read since the last table as one prose block, with paragraphs separated by
         * blank lines - the shape the prose chunker already expects.
         */
        private void flushProse() {
            endParagraph();
            if (!paragraphs.isEmpty()) {
                blocks.add(new ContentBlock.Prose(String.join("\n\n", paragraphs)));
                paragraphs.clear();
            }
        }
    }

    /**
     * The first row becomes the header. Office and HTML authors mark header cells with {@code <th>} only
     * sometimes, so treating row one as the header regardless behaves the same in both cases - and a table
     * whose first row really is data still reads correctly, because every chunk shows that row above the
     * values it is being used to label.
     */
    private static Optional<ContentBlock> toTable(Element table) {
        List<List<String>> rows = new ArrayList<>();

        for (Element row : table.select("tr")) {
            // select() reaches into nested tables too; those rows belong to the cell that contains them.
            if (row.closest("table") != table) {
                continue;
            }
            List<String> cells = row.children()
                                    .stream()
                                    .filter(cell -> "td".equals(cell.tagName()) || "th".equals(cell.tagName()))
                                    // text() flattens and whitespace-normalises the cell, which also folds
                                    // a nested table and DOCX's per-line <p> elements into one value.
                                    .map(Element::text)
                                    .toList();
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty() || rows.stream().flatMap(List::stream).allMatch(String::isBlank)) {
            return Optional.empty();
        }

        Element caption = table.selectFirst("caption");
        String captionText = caption != null && caption.closest("table") == table ? caption.text().strip() : "";

        return Optional.of(new ContentBlock.Table(rows.getFirst(),
                                                  List.copyOf(rows.subList(1, rows.size())),
                                                  captionText.isBlank() ? null : captionText));
    }
}
