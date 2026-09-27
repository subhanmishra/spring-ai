package com.example.subhanmishra.service.parse.pdf;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the horizontal and vertical lines drawn on one page, which is what a ruled table is made of.
 * <p>
 * Two conversions matter here. PDFBox hands path coordinates to this engine in bottom-up user space, while
 * {@code PDFTextStripper} reports text in a top-down frame relative to the crop box; the two layers are
 * useless together unless they agree, so every point is flipped and offset into the text frame as it
 * arrives. And a table border is often drawn as a thin filled <em>rectangle</em> rather than a stroked
 * line, so {@code appendRectangle} contributes its edges too.
 * <p>
 * Rotated pages are not handled: the flip assumes the page is upright. Every PDF checked so far is.
 */
public final class PdfLineExtractor extends PDFGraphicsStreamEngine {

    /** A rectangle thinner than this in one axis is a rule, not a box, and contributes one line. */
    private static final float RULE_THICKNESS = 3f;

    private final List<LineSegment> segments = new ArrayList<>();
    private final List<Point2D> currentPath = new ArrayList<>();
    private final float pageTop;
    private final float pageLeft;

    private Point2D currentPoint;

    public PdfLineExtractor(PDPage page) {
        super(page);
        PDRectangle box = page.getCropBox();
        this.pageTop = box.getUpperRightY();
        this.pageLeft = box.getLowerLeftX();
    }

    /** Runs the page's content stream and returns the lines it drew, in the text layer's coordinates. */
    public List<LineSegment> extract() throws IOException {
        processPage(getPage());
        return List.copyOf(segments);
    }

    private float toTextY(double userY) {
        return (float) (pageTop - userY);
    }

    private float toTextX(double userX) {
        return (float) (userX - pageLeft);
    }

    private void addIfStraight(Point2D from, Point2D to) {
        LineSegment segment = LineSegment.of(toTextX(from.getX()), toTextY(from.getY()),
                                             toTextX(to.getX()), toTextY(to.getY()));
        if (segment.isHorizontal(1f) || segment.isVertical(1f)) {
            segments.add(segment);
        }
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        float left = toTextX(Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX())));
        float right = toTextX(Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX())));
        float top = toTextY(Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY())));
        float bottom = toTextY(Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY())));

        float width = right - left;
        float height = bottom - top;

        // A hairline rectangle is how many generators draw a single rule. Collapse it to that one line
        // rather than to four, so a rule does not masquerade as a one-cell grid.
        if (height <= RULE_THICKNESS && width > RULE_THICKNESS) {
            segments.add(LineSegment.of(left, (top + bottom) / 2, right, (top + bottom) / 2));
            return;
        }
        if (width <= RULE_THICKNESS && height > RULE_THICKNESS) {
            segments.add(LineSegment.of((left + right) / 2, top, (left + right) / 2, bottom));
            return;
        }
        if (width <= RULE_THICKNESS || height <= RULE_THICKNESS) {
            return;
        }

        segments.add(LineSegment.of(left, top, right, top));
        segments.add(LineSegment.of(left, bottom, right, bottom));
        segments.add(LineSegment.of(left, top, left, bottom));
        segments.add(LineSegment.of(right, top, right, bottom));
    }

    @Override
    public void moveTo(float x, float y) {
        currentPoint = new Point2D.Float(x, y);
        currentPath.clear();
        currentPath.add(currentPoint);
    }

    @Override
    public void lineTo(float x, float y) {
        Point2D next = new Point2D.Float(x, y);
        if (currentPoint != null) {
            addIfStraight(currentPoint, next);
        }
        currentPoint = next;
        currentPath.add(next);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        // A curve never rules a table; just keep the pen position honest.
        currentPoint = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return currentPoint;
    }

    @Override
    public void closePath() {
        if (currentPath.size() > 1 && currentPoint != null) {
            addIfStraight(currentPoint, currentPath.getFirst());
        }
    }

    @Override
    public void endPath() {
        currentPath.clear();
    }

    @Override
    public void strokePath() {
        currentPath.clear();
    }

    @Override
    public void fillPath(int windingRule) {
        currentPath.clear();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        currentPath.clear();
    }

    @Override
    public void drawImage(PDImage pdImage) {
        // Images carry no rules.
    }

    @Override
    public void clip(int windingRule) {
        // Clipping does not draw.
    }

    @Override
    public void shadingFill(COSName shadingName) {
        // Gradients do not rule tables.
    }
}
