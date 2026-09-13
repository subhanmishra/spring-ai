package com.example.subhanmishra.service.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feeds the parser the XHTML shape Tika emits, without going through Tika itself, so block recovery can be
 * asserted independently of which parser produced the markup.
 */
class XhtmlBlockParserTest {

    @Test
    @DisplayName("a table becomes a table block, and the prose around it stays separate")
    void tableIsSeparatedFromSurroundingProse() {
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
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
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
                <html><head><title>Service manual</title></head><body><p>Body text.</p></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("Body text."));
    }

    @Test
    @DisplayName("consecutive paragraphs are kept blank-line separated for the prose chunker")
    void paragraphsAreBlankLineSeparated() {
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
                <html><body><p>First.</p><p>Second.</p><h2>A heading</h2><li>An item</li></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("First.\n\nSecond.\n\nA heading\n\nAn item"));
    }

    @Test
    @DisplayName("a caption is attached to its table")
    void captionIsCaptured() {
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
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
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
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
    void nestedTableDoesNotBecomeItsOwnBlock() {
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
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
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
                <html><body><p>Text.</p><table><tr><td>  </td></tr></table></body></html>
                """);

        assertThat(blocks).containsExactly(new ContentBlock.Prose("Text."));
    }

    @Test
    @DisplayName("several paragraphs inside one cell are folded into a single value")
    void multipleParagraphsInACellBecomeOneValue() {
        // The shape DOCX and XLSX produce: cell text wrapped in <p>, sometimes more than one per cell.
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
                <html><body><table>
                  <tr><td><p>Code</p></td><td><p>Meaning</p></td></tr>
                  <tr><td><p>E01</p></td><td><p>Overheat</p><p>in the manifold</p></td></tr>
                </table></body></html>
                """);

        ContentBlock.Table table = (ContentBlock.Table) blocks.getFirst();
        assertThat(table.header()).containsExactly("Code", "Meaning");
        assertThat(table.rows()).containsExactly(List.of("E01", "Overheat in the manifold"));
    }

    @Test
    @DisplayName("prose that shares a parent element with a table is still kept in reading order")
    void proseAroundATableInsideOneParentKeepsItsOrder() {
        List<ContentBlock> blocks = XhtmlBlockParser.parse("""
                <html><body><div>
                  <p>Before.</p>
                  <table><tr><td>Code</td></tr><tr><td>E01</td></tr></table>
                  <p>After.</p>
                </div></body></html>
                """);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isEqualTo(new ContentBlock.Prose("Before."));
        assertThat(blocks.get(1)).isInstanceOf(ContentBlock.Table.class);
        assertThat(blocks.get(2)).isEqualTo(new ContentBlock.Prose("After."));
    }
}
