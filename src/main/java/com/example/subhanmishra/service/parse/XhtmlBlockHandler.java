package com.example.subhanmishra.service.parse;

import org.jspecify.annotations.Nullable;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a list of {@link ContentBlock}s from the XHTML SAX events Tika emits.
 * <p>
 * Tika's parsers already recover table structure from DOCX, XLSX, PPTX and HTML and emit real
 * {@code <table><tr><td>} markup. Spring AI's {@code TikaDocumentReader} defaults to a
 * {@code BodyContentHandler}, which throws those tags away and hands back flat text - so the structure
 * was available and discarded. This handler consumes the same events and keeps it.
 * <p>
 * Consuming SAX directly rather than serialising to an XHTML string and re-parsing it also avoids
 * materialising the whole document in memory twice.
 * <p>
 * Known limitations, all deliberate: {@code colspan}/{@code rowspan} are not expanded, and a nested table
 * is flattened into the text of the cell that contains it rather than becoming a block of its own.
 */
public final class XhtmlBlockHandler extends DefaultHandler {

    /** Elements whose end marks a paragraph boundary in prose. */
    private static final Set<String> PARAGRAPH_ENDING =
            Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "blockquote", "pre", "tr");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<ContentBlock> blocks = new ArrayList<>();

    /**
     * Tika's XHTML skeleton always carries a {@code <head>} with the document title, and that text is
     * metadata rather than content - without this gate it would be prepended to the first prose block.
     * This is the one thing {@code BodyContentHandler} was doing for us that is still worth keeping.
     */
    private boolean inBody;

    private final List<String> paragraphs = new ArrayList<>();
    private final StringBuilder paragraph = new StringBuilder();

    /**
     * Depth rather than a boolean, so a nested table does not end the outer one early. Only depth 1 builds
     * a grid; deeper content keeps flowing into whichever cell of the outer table is open.
     */
    private int tableDepth;

    private final List<List<String>> rows = new ArrayList<>();
    private @Nullable List<String> currentRow;
    private @Nullable StringBuilder cell;
    private final StringBuilder caption = new StringBuilder();
    private boolean inCaption;

    /**
     * The blocks recovered so far, in reading order. Safe to call once parsing has finished; any text not
     * yet closed by an element boundary is flushed first.
     */
    public List<ContentBlock> blocks() {
        flushProse();
        return List.copyOf(blocks);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        String tag = tagName(localName, qName);

        if ("body".equals(tag)) {
            inBody = true;
            return;
        }
        if (!inBody) {
            return;
        }

        if ("table".equals(tag)) {
            tableDepth++;
            if (tableDepth == 1) {
                // Everything read up to here belongs before the table, so close it first and keep
                // reading order intact.
                flushProse();
                resetTable();
            }
            return;
        }

        if (tableDepth != 1) {
            return;
        }

        switch (tag) {
            case "tr" -> currentRow = new ArrayList<>();
            case "td", "th" -> cell = new StringBuilder();
            case "caption" -> inCaption = true;
            default -> {
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String tag = tagName(localName, qName);

        if ("body".equals(tag)) {
            flushProse();
            inBody = false;
            return;
        }
        if (!inBody) {
            return;
        }

        if ("table".equals(tag)) {
            if (tableDepth == 1) {
                emitTable();
            }
            tableDepth = Math.max(0, tableDepth - 1);
            return;
        }

        if (tableDepth == 1) {
            if (cell != null && PARAGRAPH_ENDING.contains(tag) && !"tr".equals(tag)) {
                // DOCX and XLSX wrap cell text in <p>, and a cell may hold several. Without a separator
                // here they would run together into one word.
                cell.append(' ');
                return;
            }
            switch (tag) {
                case "td", "th" -> {
                    if (currentRow != null && cell != null) {
                        currentRow.add(normalise(cell.toString()));
                    }
                    cell = null;
                }
                case "tr" -> {
                    if (currentRow != null && !currentRow.isEmpty()) {
                        rows.add(List.copyOf(currentRow));
                    }
                    currentRow = null;
                }
                case "caption" -> inCaption = false;
                default -> {
                }
            }
            return;
        }

        if (tableDepth == 0 && PARAGRAPH_ENDING.contains(tag)) {
            endParagraph();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (!inBody) {
            return;
        }
        if (cell != null) {
            cell.append(ch, start, length);
            return;
        }
        if (inCaption) {
            caption.append(ch, start, length);
            return;
        }
        if (tableDepth > 0) {
            // Whitespace and stray text between cells; it belongs to no cell and no paragraph.
            return;
        }
        paragraph.append(ch, start, length);
    }

    @Override
    public void endDocument() {
        flushProse();
    }

    /**
     * The first row becomes the header. Office and HTML authors mark header cells with {@code <th>} only
     * sometimes, so treating row one as the header regardless is the heuristic that behaves the same in
     * both cases - and a table whose first row really is data still reads correctly, because every chunk
     * shows that row above the values it is being used to label.
     */
    private void emitTable() {
        if (rows.isEmpty() || rows.stream().flatMap(List::stream).allMatch(String::isBlank)) {
            resetTable();
            return;
        }

        List<String> header = rows.getFirst();
        List<List<String>> data = List.copyOf(rows.subList(1, rows.size()));
        String tableCaption = normalise(caption.toString());

        blocks.add(new ContentBlock.Table(header, data, tableCaption.isBlank() ? null : tableCaption));
        resetTable();
    }

    private void resetTable() {
        rows.clear();
        currentRow = null;
        cell = null;
        caption.setLength(0);
        inCaption = false;
    }

    private void endParagraph() {
        String text = normalise(paragraph.toString());
        if (!text.isBlank()) {
            paragraphs.add(text);
        }
        paragraph.setLength(0);
    }

    /**
     * Emits everything read since the last block as one prose block, with paragraphs separated by blank
     * lines - the shape the prose chunker already expects.
     */
    private void flushProse() {
        endParagraph();
        if (!paragraphs.isEmpty()) {
            blocks.add(new ContentBlock.Prose(String.join("\n\n", paragraphs)));
            paragraphs.clear();
        }
    }

    /** SAX reports the element name in localName when namespace-aware and in qName otherwise. */
    private static String tagName(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String normalise(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}
