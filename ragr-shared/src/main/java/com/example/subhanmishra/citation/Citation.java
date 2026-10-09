package com.example.subhanmishra.citation;

import org.jspecify.annotations.Nullable;

/**
 * One citation, either as the model wrote it in an answer or as a retrieved chunk declared it.
 *
 * <p>Three cases:
 * <ul>
 *   <li><b>A page number</b> - the normal case.</li>
 *   <li><b>No page</b> - correct for DOCX, XLSX, PPTX and HTML, which have no pages.</li>
 *   <li><b>A {@code pageLabel}</b> - the model wrote something else where the page belongs, usually a
 *       section number: "p. 5.3". It is kept as written, with no page number, so it is neither read as
 *       page 5 nor treated as having no page. It can never match a chunk header, whose page is always a
 *       plain number.</li>
 * </ul>
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
     * Whether this citation points at the same place as another: the same filename ignoring case (the
     * model is careless about case) and exactly the same page, or both without one. A page label matches
     * nothing.
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
