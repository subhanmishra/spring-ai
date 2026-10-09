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
 * Tika already turns the tables in DOCX, XLSX, PPTX and HTML into {@code <table>} markup; Spring AI's
 * default handler just throws the markup away. This reads it instead. Only the {@code <body>} is read -
 * the {@code <head>} is metadata.
 * <p>
 * Deliberate limits: {@code colspan} and {@code rowspan} are not expanded, and a table inside a cell
 * becomes part of that cell's text.
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
     * Not {@code Element.text()}: under the XML parser jsoup treats every tag as inline, so the lines of a
     * cell run together - "Overheat" and "in the manifold" became "Overheatin the manifold". This walk
     * separates at the same tags the prose walker does.
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
     * Reads Tika's XHTML with jsoup's <b>XML</b> parser - the HTML parser silently loses most of the
     * document. Tika writes {@code <title/>} when a file has no title, which most Office files do not. HTML
     * does not allow a self-closing title, so the HTML parser treats everything after it as the title's
     * text, and only a fragment of the document is left. The input is always well-formed XML, so the XML
     * parser is the right one.
     */
    public static List<ContentBlock> parse(String xhtml) {
        Document document = Jsoup.parse(xhtml, "", Parser.xmlParser());

        // Only the body is content. A fragment without one is read whole, rather than coming out empty.
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
     * The first row is always the header, since authors use {@code <th>} only sometimes. If row one is
     * really data, the table still reads correctly: every chunk shows it above the rows below.
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
