package com.example.subhanmishra.service.parse.pdf;

/**
 * A straight line drawn on the page, normalised so {@code x1 <= x2} and {@code y1 <= y2}.
 * <p>
 * In the same top-down frame as {@link TextRun}, so the two can be compared. Only horizontal and vertical
 * lines are kept; nothing else rules a table.
 */
public record LineSegment(float x1, float y1, float x2, float y2) {

    public static LineSegment of(float x1, float y1, float x2, float y2) {
        return new LineSegment(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }

    public boolean isHorizontal(float tolerance) {
        return Math.abs(y2 - y1) <= tolerance && x2 - x1 > tolerance;
    }

    public boolean isVertical(float tolerance) {
        return Math.abs(x2 - x1) <= tolerance && y2 - y1 > tolerance;
    }

    public float length() {
        return Math.max(x2 - x1, y2 - y1);
    }
}
