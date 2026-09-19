package com.example.subhanmishra.service.parse.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageFooterStripperTest {

    /** Pages whose printed number trails the PDF page by a constant, as a real document's does. */
    private static Map<Integer, String> pagesWithFooters(int firstPdfPage, int count, int offset) {
        Map<Integer, String> pages = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int pdfPage = firstPdfPage + i;
            pages.put(pdfPage, "Some body text on this page.\n\n" + (pdfPage - offset));
        }
        return pages;
    }

    @Nested
    @DisplayName("offset detection")
    class Detection {

        @Test
        @DisplayName("a consistent folio sequence is detected")
        void detectsConstantOffset() {
            PageFooterStripper stripper = PageFooterStripper.detect(pagesWithFooters(275, 10, 19));

            assertThat(stripper.active()).isTrue();
            assertThat(stripper.strip(299, "Body.\n\n280")).isEqualTo("Body.");
        }

        @Test
        @DisplayName("an offset of zero is a folio scheme too")
        void detectsZeroOffset() {
            PageFooterStripper stripper = PageFooterStripper.detect(pagesWithFooters(1, 8, 0));

            assertThat(stripper.active()).isTrue();
            assertThat(stripper.strip(5, "Body.\n\n5")).isEqualTo("Body.");
        }

        @Test
        @DisplayName("too few pages carrying a number is not a scheme")
        void refusesBelowMinimumPages() {
            // Two pages that happen to end in a digit must not establish a rule from coincidence.
            PageFooterStripper stripper = PageFooterStripper.detect(pagesWithFooters(1, 2, 19));

            assertThat(stripper.active()).isFalse();
        }

        @Test
        @DisplayName("inconsistent trailing numbers strip nothing")
        void refusesWithoutConsensus() {
            // Trailing digits with no common offset - a document whose pages happen to end in numbers
            // (code listings, tables) rather than one that prints folios.
            Map<Integer, String> pages = new LinkedHashMap<>();
            pages.put(1, "retries = 3\n\n3");
            pages.put(2, "timeout = 900\n\n900");
            pages.put(3, "port = 8080\n\n8080");
            pages.put(4, "threads = 16\n\n16");

            PageFooterStripper stripper = PageFooterStripper.detect(pages);

            assertThat(stripper.active()).isFalse();
            assertThat(stripper.strip(1, "retries = 3\n\n3")).isEqualTo("retries = 3\n\n3");
        }

        @Test
        @DisplayName("a document with no trailing numbers at all is left alone")
        void refusesWithNoFooters() {
            Map<Integer, String> pages = Map.of(1, "First page.", 2, "Second page.", 3, "Third page.");

            assertThat(PageFooterStripper.detect(pages).active()).isFalse();
        }

        @Test
        @DisplayName("a minority of odd pages does not break detection")
        void toleratesOutliers() {
            // Most pages follow the folio; two end in an unrelated number. The mode still wins, and the
            // outliers are left untouched because they do not match the offset.
            Map<Integer, String> pages = new LinkedHashMap<>(pagesWithFooters(20, 10, 19));
            pages.put(40, "max connections = 500\n\n500");
            pages.put(41, "buffer = 4096\n\n4096");

            PageFooterStripper stripper = PageFooterStripper.detect(pages);

            assertThat(stripper.active()).isTrue();
            assertThat(stripper.strip(40, "max connections = 500\n\n500"))
                    .as("a trailing number that is not the folio must survive")
                    .isEqualTo("max connections = 500\n\n500");
        }

        @Test
        @DisplayName("roman-numeral front matter is ignored rather than confusing the mode")
        void ignoresNonNumericFooters() {
            Map<Integer, String> pages = new LinkedHashMap<>();
            pages.put(1, "Title page.\n\ni");
            pages.put(2, "Contents.\n\nii");
            pages.putAll(pagesWithFooters(21, 6, 20));

            PageFooterStripper stripper = PageFooterStripper.detect(pages);

            assertThat(stripper.active()).isTrue();
            assertThat(stripper.strip(21, "Body.\n\n1")).isEqualTo("Body.");
            assertThat(stripper.strip(1, "Title page.\n\ni")).isEqualTo("Title page.\n\ni");
        }
    }

    @Nested
    @DisplayName("stripping")
    class Stripping {

        private final PageFooterStripper stripper = PageFooterStripper.detect(pagesWithFooters(275, 10, 19));

        @Test
        @DisplayName("a block that is nothing but the footer becomes empty")
        void emptiesAFooterOnlyBlock() {
            // This is the case that produced 192 chunks holding only a page number, two of which were
            // returned in the top five for a real query.
            assertThat(stripper.strip(299, "280")).isEmpty();
        }

        @Test
        void leavesTextWithoutATrailingNumberAlone() {
            assertThat(stripper.strip(299, "Body text with no footer.")).isEqualTo("Body text with no footer.");
        }

        @Test
        @DisplayName("a trailing number that is not this page's folio survives")
        void leavesAMismatchedNumberAlone() {
            assertThat(stripper.strip(299, "The default is\n\n8080")).isEqualTo("The default is\n\n8080");
        }

        @Test
        @DisplayName("only the final line goes, not the paragraph before it")
        void preservesPrecedingContent() {
            String text = "First paragraph.\n\nSecond paragraph ends here.\n\n280";
            assertThat(stripper.strip(299, text)).isEqualTo("First paragraph.\n\nSecond paragraph ends here.");
        }

        @Test
        @DisplayName("a number embedded mid-text is untouched")
        void ignoresNumbersThatAreNotTheLastLine() {
            String text = "280\n\nis the printed number, but this page continues.";
            assertThat(stripper.strip(299, text)).isEqualTo(text);
        }

        @Test
        void handlesNullAndBlank() {
            assertThat(stripper.strip(299, null)).isEmpty();
            assertThat(stripper.strip(299, "")).isEmpty();
        }

        @Test
        @DisplayName("a disabled stripper is a no-op")
        void disabledStripsNothing() {
            assertThat(PageFooterStripper.disabled().active()).isFalse();
            assertThat(PageFooterStripper.disabled().strip(299, "Body.\n\n280")).isEqualTo("Body.\n\n280");
        }
    }
}
