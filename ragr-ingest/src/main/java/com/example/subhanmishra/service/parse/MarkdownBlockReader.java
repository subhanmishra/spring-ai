package com.example.subhanmishra.service.parse;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads one Markdown file into its front matter and its {@link ContentBlock}s.
 * <p>
 * Tika reads Markdown as plain text and collapses it to one line, so headings, code and tables run
 * together. This reader keeps what Markdown states outright:
 * <ul>
 *   <li><b>Headings</b> become {@link ContentBlock.Heading}, the only source of a chunk's section.</li>
 *   <li><b>Code</b> becomes {@link ContentBlock.Code}, kept whole with its fences and indentation.</li>
 *   <li><b>GFM pipe tables</b> become {@link ContentBlock.Table}, so {@link TableChunker} repeats the
 *       header on every piece. An HTML {@code <table>} goes through {@link XhtmlBlockParser}.</li>
 *   <li><b>Everything else</b> is prose, taken verbatim from the file, with link targets removed.</li>
 * </ul>
 * Block boundaries come from commonmark's parse, but the text is sliced from the file rather than
 * re-rendered, so nothing is reformatted.
 */
public final class MarkdownBlockReader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBlockReader.class);

    /** commonmark's parser is thread-safe, so one serves every upload. */
    private static final Parser PARSER = Parser.builder()
                                               .extensions(List.of(TablesExtension.create()))
                                               .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                                               .build();

    /** YAML front matter: a {@code ---} line at the very start, up to the next {@code ---} line. */
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---[ \\t]*\\n(.*?)\\n---[ \\t]*(?:\\n|\\z)",
                                                                Pattern.DOTALL);

    /**
     * {@code [text](target)} and {@code ![alt](target)}: the text stays, the target goes. Doc URLs add
     * tokens to every embedding and say nothing a reader needs.
     */
    private static final Pattern LINK = Pattern.compile(
            "!?\\[((?:[^\\[\\]\\n]|\\[[^\\]\\n]*])*)]\\((?:[^()\\s]|\\([^()\\s]*\\))*(?:\\s+\"[^\"\\n]*\")?\\)");

    /** An inline code span; a link starting inside one is code, not a link. */
    private static final Pattern CODE_SPAN = Pattern.compile("(`+)[\\s\\S]*?\\1");

    /** The marker of a numbered callout: {@code (1)}. */
    private static final Pattern CALLOUT = Pattern.compile("\\(\\d{1,2}\\)");

    /** A fence line, opening or closing, as it appears inside a list or quote. */
    private static final Pattern FENCE_LINE = Pattern.compile("^[ \\t>]*(```|~~~)");

    /**
     * Converter leftovers that hold no content: a table it could not render, an AsciiDoc include it
     * could not resolve. Dropped and logged, so a gap in the source shows in the logs, not as a stub in a
     * chunk.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\A(?:\\[TABLE]|include::\\S*\\[[^\\]]*])\\s*\\z");

    private MarkdownBlockReader() {
    }

    /**
     * @param frontMatter the front matter's scalar values by key, empty when the file has none
     * @param blocks      the body, in reading order
     */
    public record Page(Map<String, String> frontMatter, List<ContentBlock> blocks) {
    }

    /**
     * @param markdown      the whole file
     * @param dropTabLabels the labels of example tabs to drop when the same group has a {@code Java} tab,
     *                      compared ignoring case; see {@link #readBody}
     * @param source        the file's name, for log lines only
     */
    public static Page read(String markdown, Collection<String> dropTabLabels, String source) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        Map<String, String> frontMatter = Map.of();
        Matcher matter = FRONT_MATTER.matcher(text);
        if (matter.find()) {
            frontMatter = parseFrontMatter(matter.group(1), source);
            text = text.substring(matter.end());
        }

        Set<String> drop = dropTabLabels.stream()
                                        .map(label -> label.strip().toLowerCase(Locale.ROOT))
                                        .collect(Collectors.toUnmodifiableSet());
        return new Page(frontMatter, readBody(text, drop, source));
    }

    private static Map<String, String> parseFrontMatter(String yaml, String source) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (!(loaded instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null && value != null && !(value instanceof Map) && !(value instanceof Collection)) {
                    values.put(key.toString(), value.toString().strip());
                }
            });
            return Map.copyOf(values);
        } catch (YAMLException e) {
            // The file is still worth reading; only its metadata is lost.
            log.warn("Unreadable front matter in {}, ignored: {}", source, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Walks the top-level blocks in order.
     * <p>
     * <b>Example tabs.</b> Reference docs show one example in several languages, each a code block under
     * a bold label line ({@code **Java**}, {@code **Kotlin**}). In a group that has a Java tab, the tabs
     * labelled in {@code drop} are removed, with every label line of the group: the twins say the same
     * thing again and crowd the context. Other groups stay whole, since they are not twins - Maven and
     * Gradle, imperative and reactive.
     */
    private static List<ContentBlock> readBody(String body, Set<String> drop, String source) {
        List<Node> nodes = new ArrayList<>();
        for (Node node = PARSER.parse(body).getFirstChild(); node != null; node = node.getNext()) {
            nodes.add(node);
        }

        List<ContentBlock> blocks = new ArrayList<>();
        int droppedTabs = 0;
        int i = 0;
        while (i < nodes.size()) {
            // A tab is its label, its code block, and the numbered callouts explaining that code.
            List<int[]> tabs = new ArrayList<>();
            int end = i;
            while (end + 1 < nodes.size() && tabLabel(nodes.get(end)) != null
                   && nodes.get(end + 1) instanceof FencedCodeBlock) {
                int tabEnd = end + 2;
                while (tabEnd < nodes.size() && isCallout(nodes.get(tabEnd))) {
                    tabEnd++;
                }
                tabs.add(new int[]{end, tabEnd});
                end = tabEnd;
            }
            if (tabs.isEmpty()) {
                addBlock(nodes.get(i), body, blocks, source);
                i++;
                continue;
            }

            List<String> labels = tabs.stream().map(tab -> tabLabel(nodes.get(tab[0])).toLowerCase(Locale.ROOT)).toList();
            boolean twins = labels.size() > 1 && labels.contains("java") && labels.stream().anyMatch(drop::contains);
            for (int t = 0; t < tabs.size(); t++) {
                int[] tab = tabs.get(t);
                if (twins && drop.contains(labels.get(t))) {
                    droppedTabs++;
                    continue;
                }
                // The label line is kept only where it tells tabs apart.
                for (int n = twins ? tab[0] + 1 : tab[0]; n < tab[1]; n++) {
                    addBlock(nodes.get(n), body, blocks, source);
                }
            }
            i = end;
        }

        if (droppedTabs > 0) {
            log.debug("Dropped {} example tab(s) that repeat a Java example in {}", droppedTabs, source);
        }
        return blocks;
    }

    /** The label of an example tab - a paragraph that is only {@code **Label**} - or null. */
    private static @Nullable String tabLabel(Node node) {
        if (node instanceof Paragraph paragraph
            && paragraph.getFirstChild() instanceof StrongEmphasis strong
            && strong.getNext() == null
            && strong.getFirstChild() instanceof Text label
            && label.getNext() == null) {
            String text = label.getLiteral().strip();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    /** A numbered callout under a code block - a paragraph opening with {@code **(1)**}. */
    private static boolean isCallout(Node node) {
        return node instanceof Paragraph paragraph
               && paragraph.getFirstChild() instanceof StrongEmphasis strong
               && strong.getFirstChild() instanceof Text number
               && CALLOUT.matcher(number.getLiteral()).matches();
    }

    private static void addBlock(Node node, String body, List<ContentBlock> blocks, String source) {
        switch (node) {
            case Heading heading -> {
                String text = inlineText(heading).strip();
                if (!text.isEmpty()) {
                    blocks.add(new ContentBlock.Heading(heading.getLevel(), text));
                }
            }
            case FencedCodeBlock code -> blocks.add(fenced(code));
            case IndentedCodeBlock code -> blocks.add(new ContentBlock.Code("", "```\n" + withNewline(code.getLiteral()) + "```"));
            case TableBlock table -> table(table).ifPresent(blocks::add);
            case HtmlBlock html -> {
                String literal = html.getLiteral();
                if (literal.toLowerCase(Locale.ROOT).contains("<table")) {
                    blocks.addAll(XhtmlBlockParser.parseHtml(literal));
                } else if (!literal.strip().startsWith("<!--")) {
                    blocks.add(new ContentBlock.Prose(literal.strip()));
                }
            }
            case ThematicBreak _, LinkReferenceDefinition _ -> {
            }
            case BulletList _, OrderedList _, BlockQuote _ when containsCode(node) ->
                    // A list item can hold a code block; cutting the list at a blank line inside the code
                    // would leave half a fence.
                    blocks.add(new ContentBlock.Code("", stripLinks(sourceOf(node, body))));
            default -> {
                String text = sourceOf(node, body).strip();
                if (PLACEHOLDER.matcher(text).matches()) {
                    log.warn("Dropped a converter placeholder in {}: {}", source, text);
                } else if (!text.isEmpty()) {
                    blocks.add(new ContentBlock.Prose(stripLinks(text)));
                }
            }
        }
    }

    /**
     * Rebuilt from the parse rather than sliced, so an unclosed fence still gets its closing line. The
     * body is the parser's literal, which keeps every line's indentation.
     */
    private static ContentBlock.Code fenced(FencedCodeBlock code) {
        String info = code.getInfo() == null ? "" : code.getInfo().strip();
        String language = info.isEmpty() ? "" : info.split("\\s+")[0];
        String literal = withNewline(code.getLiteral());

        // A fence longer than any run of its own character inside the body, so the body cannot close it.
        String fenceChar = String.valueOf(code.getFenceCharacter() == null ? "`" : code.getFenceCharacter());
        int longest = 0;
        Matcher run = Pattern.compile(Pattern.quote(fenceChar) + "+").matcher(literal);
        while (run.find()) {
            longest = Math.max(longest, run.group().length());
        }
        String fence = fenceChar.repeat(Math.max(3, longest + 1));
        return new ContentBlock.Code(language, fence + info + "\n" + literal + fence);
    }

    private static String withNewline(@Nullable String literal) {
        if (literal == null || literal.isEmpty()) {
            return "";
        }
        return literal.endsWith("\n") ? literal : literal + "\n";
    }

    private static boolean containsCode(Node node) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock || child instanceof IndentedCodeBlock || containsCode(child)) {
                return true;
            }
        }
        return false;
    }

    /** The node's text exactly as written: from its first source line to the end of its last. */
    private static String sourceOf(Node node, String body) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            return "";
        }
        SourceSpan last = spans.getLast();
        return body.substring(spans.getFirst().getInputIndex(), last.getInputIndex() + last.getLength());
    }

    /**
     * Reads the cells from the parse, so escaped pipes and inline markup are already resolved. The first
     * row is the header, as GFM requires.
     */
    private static Optional<ContentBlock> table(TableBlock table) {
        List<List<String>> rows = new ArrayList<>();
        collectRows(table, rows);
        if (rows.isEmpty() || rows.stream().flatMap(List::stream).allMatch(String::isBlank)) {
            return Optional.empty();
        }
        return Optional.of(new ContentBlock.Table(rows.getFirst(), List.copyOf(rows.subList(1, rows.size())), null));
    }

    private static void collectRows(Node node, List<List<String>> rows) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableRow row) {
                List<String> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) {
                        cells.add(inlineText(cell).strip());
                    }
                }
                rows.add(List.copyOf(cells));
            } else {
                collectRows(child, rows);
            }
        }
    }

    /**
     * The words of a heading or cell: text and code spans (with their backticks), links reduced to their
     * text, emphasis marks dropped, line breaks as spaces.
     */
    private static String inlineText(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                text.append(t.getLiteral());
            }

            @Override
            public void visit(Code code) {
                text.append('`').append(code.getLiteral()).append('`');
            }

            @Override
            public void visit(SoftLineBreak lineBreak) {
                text.append(' ');
            }

            @Override
            public void visit(HardLineBreak lineBreak) {
                text.append(' ');
            }

            @Override
            public void visit(HtmlInline html) {
                // <br> inside a cell separates values; any other inline tag carries no words.
                text.append(' ');
            }
        });
        return text.toString();
    }

    /** Removes link targets everywhere but inside code: fenced lines and inline code spans are left alone. */
    static String stripLinks(String markdown) {
        if (markdown.indexOf("](") < 0) {
            return markdown;
        }
        StringBuilder out = new StringBuilder(markdown.length());
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (FENCE_LINE.matcher(line).find()) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence) {
                out.append(line);
            } else {
                out.append(stripLinksOutsideCodeSpans(line));
            }
        }
        return out.toString();
    }

    /** A link's text may itself hold code ({@code [`RestClient`](url)}), so only where a link starts counts. */
    private static String stripLinksOutsideCodeSpans(String line) {
        List<int[]> spans = new ArrayList<>();
        Matcher span = CODE_SPAN.matcher(line);
        while (span.find()) {
            spans.add(new int[]{span.start(), span.end()});
        }
        StringBuilder out = new StringBuilder(line.length());
        Matcher link = LINK.matcher(line);
        int from = 0;
        while (link.find()) {
            int start = link.start();
            if (spans.stream().anyMatch(s -> start > s[0] && start < s[1])) {
                continue;
            }
            out.append(line, from, start).append(link.group(1));
            from = link.end();
        }
        return out.append(line, from, line.length()).toString();
    }
}
