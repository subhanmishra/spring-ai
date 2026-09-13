package com.example.subhanmishra.service.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the handler with the XHTML shape Tika emits, without going through Tika itself, so the block
 * recovery can be asserted independently of which parser produced the events.
 */
class XhtmlBlockHandlerTest {

    @Test
    @DisplayName("a table becomes a table block, and the prose around it stays separate")
    void tableIsSeparatedFromSurroundingProse() {
        List<ContentBlock> blocks = parse("""
                <html><head><title>Service manual</title></head><body>
                <p>Alarm codes are listed below.</p>
                <table>
                  <tr><th>Code</th><th>Meaning</th></tr>
                  <tr><td>E01</td><td>Overheat</td></tr>
                </table>
                <p>Contact support if the fault persists.</p>
                </body></html>
                """);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isEqualTo(new ContentBlock.Prose("Alarm codes are listed below."));
        assertThat(blocks.get(1)).isEqualTo(new ContentBlock.Table(List.of("Code", "Meaning"),
                                                                   List.of(List.of("E01", "Overheat")),
                                                                   null));
        assertThat(blocks.get(2)).isEqualTo(new ContentBlock.Prose("Contact support if the fault persists."));
    }

    @Test
    @DisplayName("the document title is not read as content")
    void headIsIgnored() {
        List<ContentBlock> blocks = parse("""
                <html><head><title>Service manual</title></head><body><p>Body text.</p></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("Body text."));
    }

    @Test
    @DisplayName("consecutive paragraphs are kept blank-line separated for the prose chunker")
    void paragraphsAreBlankLineSeparated() {
        List<ContentBlock> blocks = parse("""
                <html><body><p>First.</p><p>Second.</p><h2>A heading</h2><li>An item</li></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("First.\n\nSecond.\n\nA heading\n\nAn item"));
    }

    @Test
    @DisplayName("a caption is attached to its table")
    void captionIsCaptured() {
        List<ContentBlock> blocks = parse("""
                <html><body><table>
                  <caption>Table 4. Alarm codes</caption>
                  <tr><th>Code</th></tr>
                  <tr><td>E01</td></tr>
                </table></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Table(List.of("Code"), List.of(List.of("E01")),
                                                                  "Table 4. Alarm codes"));
    }

    @Test
    @DisplayName("a table without header cells still uses its first row as the header")
    void firstRowBecomesTheHeaderWhenThereAreNoHeaderCells() {
        List<ContentBlock> blocks = parse("""
                <html><body><table>
                  <tr><td>Code</td><td>Meaning</td></tr>
                  <tr><td>E01</td><td>Overheat</td></tr>
                </table></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Table(List.of("Code", "Meaning"),
                                                                  List.of(List.of("E01", "Overheat")),
                                                                  null));
    }

    @Test
    @DisplayName("a nested table is flattened into the cell that contains it, not emitted on its own")
    void nestedTableDoesNotEndTheOuterOne() {
        List<ContentBlock> blocks = parse("""
                <html><body><table>
                  <tr><td>Outer</td><td><table><tr><td>inner</td></tr></table></td></tr>
                  <tr><td>E01</td><td>Overheat</td></tr>
                </table></body></html>
                """);

        assertThat(blocks).hasSize(1);
        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.header()).containsExactly("Outer", "inner");
        assertThat(table.rows()).containsExactly(List.of("E01", "Overheat"));
    }

    @Test
    @DisplayName("an empty table is dropped rather than becoming a chunk of separators")
    void emptyTableIsDropped() {
        List<ContentBlock> blocks = parse("""
                <html><body><p>Text.</p><table><tr><td>  </td></tr></table></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("Text."));
    }

    private static List<ContentBlock> parse(String xhtml) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();

            XhtmlBlockHandler handler = new XhtmlBlockHandler();
            parser.parse(new InputSource(new StringReader(xhtml)), handler);
            return handler.blocks();
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not parse the test fixture", e);
        }
    }
}
