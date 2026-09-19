package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

/**
 * One citation, either as the model wrote it in an answer or as a retrieved chunk declared it.
 *
 * <p>{@code pageNumber} is null for a source with no page attribution - Tika reads DOCX, XLSX, PPTX
 * and HTML without any, so {@code DocumentIngestionService.citationHeader} omits the page half rather
 * than guessing one. A citation with no page is therefore legitimate and must not be scored as
 * malformed.
 *
 * <p>{@code pageLabel} is the third case, and it exists because reporting it as either of the other two
 * was wrong. Asked for a page, {@code gemma4:e2b} sometimes writes the <em>section</em> number it read
 * in the passage - "(spring-boot-reference.pdf, p. 5.3)", where 5.3 is the heading "5.3. Endpoints" and
 * the passage came from page 277. Reading that as page 5 reports a fabrication the model never claimed;
 * reading it as no page at all scores it valid, since the file really was retrieved. So the text is kept
 * verbatim, {@code pageNumber} stays null, and the citation can never match a chunk header - headers are
 * generated and always carry a plain integer.
 *
 * @param fileName   the source file, compared case-insensitively
 * @param pageNumber the 1-based page, or null when the source has no pages or the reference was not one
 * @param pageLabel  what the model wrote where a page belongs, when that was not a plain page number
 */
public record Citation(String fileName, @Nullable Integer pageNumber, @Nullable String pageLabel) {

    /** A well-formed citation: a plain page number, or none at all. */
    public Citation(String fileName, @Nullable Integer pageNumber) {
        this(fileName, pageNumber, null);
    }

    /** Whether the page reference is present but not a page number, such as a section number. */
    public boolean hasMalformedPage() {
        return pageLabel != null;
    }

    /**
     * Whether this citation refers to the same place as another. Filenames are compared ignoring case
     * because the model reproduces them from the prompt and is not reliable about case; page numbers
     * must match exactly, including both being absent.
     *
     * <p>A malformed page reference matches nothing. It is not "no page" - the model did claim a
     * location, and the one it claimed is not a page.
     */
    public boolean matches(Citation other) {
        if (other == null || !this.fileName.equalsIgnoreCase(other.fileName)) {
            return false;
        }
        if (this.hasMalformedPage() || other.hasMalformedPage()) {
            return false;
        }
        return this.pageNumber == null
                ? other.pageNumber == null
                : this.pageNumber.equals(other.pageNumber);
    }

    @Override
    public String toString() {
        if (pageLabel != null) {
            return "%s p.%s".formatted(fileName, pageLabel);
        }
        return pageNumber != null ? "%s p.%d".formatted(fileName, pageNumber) : fileName;
    }
}
