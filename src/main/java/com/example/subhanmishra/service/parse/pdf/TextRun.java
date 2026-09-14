package com.example.subhanmishra.service.parse.pdf;

/**
 * One run of text as PDFBox emits it, with its position on the page.
 * <p>
 * A run is the unit a PDF actually draws text in, and in a laid-out table that is one cell - which is why
 * table structure is recoverable from positions at all. Coordinates are top-down (y grows downwards), the
 * same frame {@code PDFTextStripper} reports through {@code getXDirAdj()} and {@code getYDirAdj()}.
 */
public record TextRun(float x, float endX, float y, float height, String text) {

    public float width() {
        return endX - x;
    }

    /** Whether this run starts at {@code column}, within the given tolerance. */
    public boolean startsAt(float column, float tolerance) {
        return Math.abs(x - column) <= tolerance;
    }
}
