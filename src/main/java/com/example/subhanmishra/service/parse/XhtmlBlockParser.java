package com.example.subhanmishra.service.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** Elements whose end marks a paragraph boundary in prose, and a value boundary inside a cell. */
    private static final Set<String> PARAGRAPH_ENDING =
            Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "blockquote", "pre", "tr");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private XhtmlBlockParser() {
    }

    /** The XML parser preserves tag case, unlike the HTML one, so tag names are normalised here. */
    private static String tagOf(Element element) {
        return element.tagName().toLowerCase(Locale.ROOT);
    }

    /**
     * Flattens one element's descendants to a single whitespace-normalised value.
     * <p>
     * {@code Element.text()} cannot be used for this. It decides where to insert separators from jsoup's
     * registry of which HTML tags are block-level, and the XML parser registers no tags at all, so every
     * element looks inline and sibling blocks run together. DOCX and XLSX wrap each line of a cell in its
     * own {@code <p>}, so relying on it turns "Overheat" and "in the manifold" into "Overheatin the
     * manifold". Walking the nodes and separating at the same boundaries the prose walker uses keeps the
     * two consistent. This also folds a nested table into the text of the cell that holds it.
     */
    private static String flatten(Element element) {
        StringBuilder text = new StringBuilder();
        appendText(element, text);
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    private static void appendText(Element element, StringBuilder out) {
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                out.append(textNode.text());
            }
            else if (node instanceof Element child) {
                appendText(child, out);
                if (PARAGRAPH_ENDING.contains(tagOf(child))) {
                    out.append(' ');
                }
            }
        }
    }

    /**
     * Reads Tika's XHTML with jsoup's <b>XML</b> parser. This is not a preference - the HTML parser
     * silently destroys the document. Tika emits a self-closing {@code <title/>} whenever the source has
     * no title metadata, which most DOCX, XLSX and PPTX files do not. In HTML {@code title} is an RCDATA
     * element and cannot self-close, so the HTML parser reads {@code <title/>} as an unclosed
     * {@code <title>} and swallows the rest of the file as its text content, leaving only a trailing
     * fragment reachable under {@code <body>} - on a real 8-page resume, 1,358 characters out of 14,718,
     * with no exception and nothing in the logs. The input comes from an XML serialiser and is always
     * well-formed, so the XML parser is the correct reader for it.
     */
    public static List<ContentBlock> parse(String xhtml) {
        Document document = Jsoup.parse(xhtml, "", Parser.xmlParser());

        // Only the body is content; Tika's <head> carries the title and the document metadata. Falling
        // back to the whole document keeps a body-less fragment readable rather than silently empty.
        Element body = document.selectFirst("body");

        Collector collector = new Collector();
        collector.walk(body != null ? body : document);
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

                if ("table".equals(tagOf(child))) {
                    endParagraph();
                    flushProse();
                    toTable(child).ifPresent(blocks::add);
                    continue;
                }

                walk(child);
                if (PARAGRAPH_ENDING.contains(tagOf(child))) {
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
                                    .filter(cell -> "td".equals(tagOf(cell)) || "th".equals(tagOf(cell)))
                                    .map(XhtmlBlockParser::flatten)
                                    .toList();
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty() || rows.stream().flatMap(List::stream).allMatch(String::isBlank)) {
            return Optional.empty();
        }

        Element caption = table.selectFirst("caption");
        String captionText = caption != null && caption.closest("table") == table ? flatten(caption) : "";

        return Optional.of(new ContentBlock.Table(rows.getFirst(),
                                                  List.copyOf(rows.subList(1, rows.size())),
                                                  captionText.isBlank() ? null : captionText));
    }
}
