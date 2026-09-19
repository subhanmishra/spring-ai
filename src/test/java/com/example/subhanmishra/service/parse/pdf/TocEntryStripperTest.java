package com.example.subhanmishra.service.parse.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TocEntryStripperTest {

    /** The leader as the reference manual prints it: dots, a non-breaking space, then the page. */
    private static String entry(String heading, int page) {
        return heading + " " + ". ".repeat(30) + "  " + page;
    }

    /** A contents page: a heading followed by {@code count} entries, blank-line separated. */
    private static String contentsPage(String heading, int count) {
        return heading + "\n\n" + IntStream.range(0, count)
                                           .mapToObj(i -> entry("%d. Section".formatted(i + 1), 20 + i))
                                           .collect(Collectors.joining("\n\n"));
    }

    private static TocEntryStripper strippingStripper() {
        // One contents page against plenty of body text, so the document-level guard does not fire.
        return TocEntryStripper.detect(List.of(contentsPage("Table of Contents", 20),
                                               "Body text.\n\n".repeat(60)));
    }

    @Nested
    @DisplayName("detection")
    class Detection {

        @Test
        @DisplayName("a document with a contents section is stripped")
        void detectsEntries() {
            assertThat(strippingStripper().active()).isTrue();
        }

        @Test
        @DisplayName("a document with no contents entries is left alone")
        void inactiveWithoutEntries() {
            assertThat(TocEntryStripper.detect(List.of("Just prose.", "More prose.")).active()).isFalse();
        }

        @Test
        @DisplayName("a document that is mostly contents entries IS a contents listing")
        void refusesWhenEntriesAreTheDocument() {
            // An index or a contents extract. Stripping it would index an empty document that answers
            // nothing, with no error anywhere - worse than leaving the page numbers in.
            TocEntryStripper stripper = TocEntryStripper.detect(List.of(contentsPage("Index", 40)));

            assertThat(stripper.active()).isFalse();
            assertThat(stripper.strip(contentsPage("Index", 40))).contains("1. Section");
        }

        @Test
        @DisplayName("a disabled stripper is a no-op")
        void disabledStripsNothing() {
            String page = contentsPage("Table of Contents", 20);

            assertThat(TocEntryStripper.disabled().active()).isFalse();
            assertThat(TocEntryStripper.disabled().strip(page)).isEqualTo(page);
        }
    }

    @Nested
    @DisplayName("line shape")
    class LineShape {

        private final TocEntryStripper stripper = strippingStripper();

        @Test
        @DisplayName("the Spring Boot banner is not a contents entry")
        void ignoresBannerDots() {
            // The closest thing in this corpus to a false positive: dot runs with no trailing page.
            String banner = "Body line.\n\n....... . . .\n\n....... . . . (log output here)";

            assertThat(stripper.strip(banner)).isEqualTo(banner);
        }

        @Test
        @DisplayName("leading dots before a number are not a leader")
        void ignoresLeadingDots() {
            String line = "........ Started Example in 2.536 seconds (JVM running for 2.864)";

            assertThat(stripper.strip("Body.\n\n" + line)).isEqualTo("Body.\n\n" + line);
        }

        @Test
        @DisplayName("a sentence ending in an abbreviation and a number is not a leader")
        void ignoresProseEndingInANumber() {
            String line = "See section 4.30.6 for details on port 8080";

            assertThat(stripper.strip(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("an entry with no trailing page number is not stripped")
        void requiresATrailingNumber() {
            String line = "Further reading . . . . . . . . . . . .";

            assertThat(stripper.strip(line)).isEqualTo(line);
        }
    }

    @Nested
    @DisplayName("stripping")
    class Stripping {

        private final TocEntryStripper stripper = strippingStripper();

        @Test
        @DisplayName("a contents page is dropped whole, heading included")
        void dropsADominatedBlock() {
            // Removing only the entries would leave a chunk reading "Table of Contents" and nothing
            // else, which is the junk chunk this is partly meant to remove.
            assertThat(stripper.strip(contentsPage("Table of Contents", 20))).isEmpty();
        }

        @Test
        @DisplayName("a body block with one stray entry keeps its text")
        void removesOnlyTheEntryFromBodyText() {
            String text = "Auto-configuration is designed to work well with starters.\n\n"
                          + entry("4.30.6. Testing", 253) + "\n\n"
                          + "The final part of our application is the main method.";

            assertThat(stripper.strip(text))
                    .isEqualTo("Auto-configuration is designed to work well with starters.\n\n"
                               + "The final part of our application is the main method.");
        }

        @Test
        @DisplayName("a handful of entries is not enough to drop real content")
        void keepsBlocksWithFewEntries() {
            // A chapter opening with a short summary list. Four entries go; the prose around them stays.
            String text = "This chapter covers the following topics.\n\n"
                          + entry("1. One", 21) + "\n\n" + entry("2. Two", 22) + "\n\n"
                          + entry("3. Three", 23) + "\n\n" + entry("4. Four", 24) + "\n\n"
                          + "Each is described in turn below.";

            assertThat(stripper.strip(text))
                    .isEqualTo("This chapter covers the following topics.\n\n"
                               + "Each is described in turn below.");
        }

        @Test
        @DisplayName("removing an entry does not leave a gap where it was")
        void collapsesBlankLinesLeftBehind() {
            // The text is persisted and embedded, and a blank line is what marks a paragraph boundary,
            // so a run of them would split one paragraph into several chunks.
            String text = "Before.\n\n" + entry("1. One", 21) + "\n\nAfter.";

            assertThat(stripper.strip(text)).isEqualTo("Before.\n\nAfter.");
        }

        @Test
        void handlesNullAndBlank() {
            assertThat(stripper.strip(null)).isEmpty();
            assertThat(stripper.strip("")).isEmpty();
        }
    }
}
