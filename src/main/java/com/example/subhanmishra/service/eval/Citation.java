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
 * @param fileName   the source file, compared case-insensitively
 * @param pageNumber the 1-based page, or null when the source has no pages
 */
public record Citation(String fileName, @Nullable Integer pageNumber) {

    /**
     * Whether this citation refers to the same place as another. Filenames are compared ignoring case
     * because the model reproduces them from the prompt and is not reliable about case; page numbers
     * must match exactly, including both being absent.
     */
    public boolean matches(Citation other) {
        if (other == null || !this.fileName.equalsIgnoreCase(other.fileName)) {
            return false;
        }
        return this.pageNumber == null
                ? other.pageNumber == null
                : this.pageNumber.equals(other.pageNumber);
    }

    @Override
    public String toString() {
        return pageNumber != null ? "%s p.%d".formatted(fileName, pageNumber) : fileName;
    }
}
