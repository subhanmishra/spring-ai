package com.example.subhanmishra.service.parse.pdf;

/**
 * One run of text as PDFBox emits it, with its position on the page.
 * <p>
 * A PDF draws text in runs, and in a table a run is usually one cell - which is why positions can recover
 * a table at all. y grows downwards, as {@code PDFTextStripper} reports it.
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
