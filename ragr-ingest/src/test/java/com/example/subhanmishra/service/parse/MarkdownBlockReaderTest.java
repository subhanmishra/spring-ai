package com.example.subhanmishra.service.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures are real pages of the Markdown library, copied unchanged - hand-written fixtures once hid
 * a parser bug that real input exposed at once (see the parsing context document).
 */
class MarkdownBlockReaderTest {

    private static final List<String> DROP = List.of("Kotlin", "Xml");

    private static MarkdownBlockReader.Page read(String fixture) throws IOException {
        try (InputStream in = Objects.requireNonNull(MarkdownBlockReaderTest.class.getResourceAsStream("/markdown/" + fixture))) {
            return MarkdownBlockReader.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), DROP, fixture);
        }
    }

    private static String allText(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            switch (block) {
                case ContentBlock.Prose prose -> text.append(prose.text());
                case ContentBlock.Code code -> text.append(code.text());
                case ContentBlock.Heading heading -> text.append(heading.text());
                case ContentBlock.Table table -> text.append(table.header()).append(table.rows());
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    @DisplayName("front matter becomes metadata and is not part of the text")
    void frontMatterIsMetadata() throws IOException {
        MarkdownBlockReader.Page page = read("028-reference-features-task-execution-and-scheduling.md");

        assertThat(page.frontMatter()).containsEntry("project", "Spring Boot")
                                      .containsEntry("version", "4.1.1")
                                      .containsKey("online_url");
        assertThat(allText(page.blocks())).doesNotContain("online_url").doesNotContain("stack:");
        assertThat(page.blocks().getFirst()).isEqualTo(new ContentBlock.Heading(1, "Task Execution and Scheduling"));
    }

    @Test
    @DisplayName("code keeps its fences, language and indentation exactly")
    void codeIsVerbatim() throws IOException {
        MarkdownBlockReader.Page page = read("028-reference-features-task-execution-and-scheduling.md");

        assertThat(page.blocks()).filteredOn(ContentBlock.Code.class::isInstance)
                                 .map(block -> (ContentBlock.Code) block)
                                 .anySatisfy(code -> {
                                     assertThat(code.language()).isEqualTo("java");
                                     assertThat(code.text()).startsWith("```java\n")
                                                            .endsWith("\n```")
                                                            .contains("\n    @Bean(\"applicationTaskExecutor\")\n"
                                                                      + "    SimpleAsyncTaskExecutor applicationTaskExecutor() {\n"
                                                                      + "        return new SimpleAsyncTaskExecutor(\"app-\");\n");
                                 });
    }

    @Test
    @DisplayName("a link keeps its text and loses its target")
    void linksLoseTheirTargets() throws IOException {
        String text = allText(read("028-reference-features-task-execution-and-scheduling.md").blocks());

        assertThat(text).contains("Bootstrap executor for background initialization of beans")
                        .doesNotContain("](http");
    }

    @Test
    @DisplayName("a link whose text is code still loses its target, and code spans are left alone")
    void linkTextMayBeCode() {
        assertThat(MarkdownBlockReader.stripLinks("Use [`RestClient`](https://docs.spring.io/x) or `[a](b)`."))
                .isEqualTo("Use `RestClient` or `[a](b)`.");
    }

    @Test
    @DisplayName("of a Java and Kotlin pair only the Java stays, with its callout; the label lines go")
    void javaTwinKept() throws IOException {
        List<ContentBlock> blocks = read("183-web-webmvc-mvc-controller-ann-methods-requestattrib.md").blocks();
        String text = allText(blocks);

        assertThat(blocks).filteredOn(ContentBlock.Code.class::isInstance)
                          .extracting(block -> ((ContentBlock.Code) block).language())
                          .containsExactly("java");
        assertThat(text).doesNotContain("**Java**").doesNotContain("**Kotlin**").doesNotContain("fun handle");
        assertThat(text).containsOnlyOnce("**(1)** Using the `@RequestAttribute` annotation.");
    }

    @Test
    @DisplayName("tabs that are not twins - Maven and Gradle - stay whole, labels included")
    void otherTabsStayWhole() throws IOException {
        List<ContentBlock> blocks = read("008-migration-servlet-authorization.md").blocks();

        assertThat(blocks).filteredOn(ContentBlock.Code.class::isInstance)
                          .extracting(block -> ((ContentBlock.Code) block).language())
                          .containsExactly("xml", "groovy");
        assertThat(allText(blocks)).contains("**Maven**").contains("**Gradle**");
    }

    @Test
    @DisplayName("a pipe table becomes a table, its cells' code spans kept")
    void pipeTable() throws IOException {
        MarkdownBlockReader.Page page = read("002-boot-classes-actuate-amqp.md");

        assertThat(page.frontMatter()).containsEntry("project", "Troubleshooting: upgrade breakages")
                                      .doesNotContainKey("version");
        assertThat(page.blocks()).filteredOn(ContentBlock.Table.class::isInstance)
                                 .singleElement()
                                 .satisfies(block -> {
                                     ContentBlock.Table table = (ContentBlock.Table) block;
                                     assertThat(table.header()).containsExactly("Boot 3.5 type", "Boot 4.1 type", "Module / notes");
                                     assertThat(table.rows()).singleElement().satisfies(row -> assertThat(row.get(1))
                                             .isEqualTo("`org.springframework.boot.amqp.health.RabbitHealthIndicator`"));
                                 });
    }

    @Test
    @DisplayName("an HTML table becomes a table")
    void htmlTable() {
        String markdown = """
                Before.

                <table>
                <tr><th>Key</th><th>Default</th></tr>
                <tr><td>server.port</td><td>8080<br>or random</td></tr>
                </table>
                """;

        List<ContentBlock> blocks = MarkdownBlockReader.read(markdown, DROP, "inline.md").blocks();

        assertThat(blocks).first().isEqualTo(new ContentBlock.Prose("Before."));
        assertThat(blocks).filteredOn(ContentBlock.Table.class::isInstance)
                          .singleElement()
                          .satisfies(block -> assertThat(((ContentBlock.Table) block).rows())
                                  .containsExactly(List.of("server.port", "8080 or random")));
    }

    @Test
    @DisplayName("a converter placeholder is dropped rather than stored as a stub")
    void placeholderDropped() {
        List<ContentBlock> blocks = MarkdownBlockReader.read("Intro.\n\n[TABLE]\n\ninclude::starters.adoc[]\n\nOutro.\n",
                                                             DROP, "inline.md").blocks();

        assertThat(blocks).containsExactly(new ContentBlock.Prose("Intro."), new ContentBlock.Prose("Outro."));
    }

    @Test
    @DisplayName("a list holding a code block is one unit, so a blank line inside the code cannot split it")
    void listWithCodeIsWhole() {
        String markdown = """
                1. Add the bean:

                   ```java
                   @Bean
                   Foo foo() {

                       return new Foo();
                   }
                   ```
                2. Restart.
                """;

        List<ContentBlock> blocks = MarkdownBlockReader.read(markdown, DROP, "inline.md").blocks();

        assertThat(blocks).singleElement().isInstanceOf(ContentBlock.Code.class);
        assertThat(((ContentBlock.Code) blocks.getFirst()).text()).contains("Foo foo() {\n\n       return new Foo();");
    }

    @Test
    @DisplayName("a file without front matter is read whole, with no metadata")
    void noFrontMatter() {
        MarkdownBlockReader.Page page = MarkdownBlockReader.read("# Notes\r\n\r\nPlain text.\r\n", DROP, "notes.md");

        assertThat(page.frontMatter()).isEmpty();
        assertThat(page.blocks()).containsExactly(new ContentBlock.Heading(1, "Notes"), new ContentBlock.Prose("Plain text."));
    }
}
