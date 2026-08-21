package com.xb.sgc.papererase.image;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.safety.RegionValidator;

public final class RoiTransform {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int fullWidth;
    private final int fullHeight;

    public RoiTransform(int x, int y, int width, int height, int fullWidth, int fullHeight) {
        if (fullWidth <= 0 || fullHeight <= 0) {
            throw new IllegalArgumentException("full image dimensions must be positive");
        }
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.x = clamp(x, 0, fullWidth);
        this.y = clamp(y, 0, fullHeight);
        this.width = clamp(width, 0, fullWidth - this.x);
        this.height = clamp(height, 0, fullHeight - this.y);
    }

    public static RoiTransform fromCandidate(int fullWidth, int fullHeight, RegionValidator.PixelRegion candidate,
                                             BodyBoundary bodyBoundary, int marginPixels) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        int left = candidate.getX() - marginPixels;
        int top = candidate.getY() - marginPixels;
        int right = candidate.getX() + candidate.getWidth() + marginPixels;
        int bottom = candidate.getY() + candidate.getHeight() + marginPixels;

        if (bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)) {
            int bodyY = (int) Math.ceil(bodyBoundary.y * fullHeight);
            if (bodyY >= candidate.getY() + candidate.getHeight()) {
                bottom = Math.max(bottom, bodyY);
            } else {
                top = Math.min(top, bodyY);
            }
        }
        if (bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)) {
            int bodyX = (int) Math.ceil(bodyBoundary.x * fullWidth);
            if (bodyX >= candidate.getX() + candidate.getWidth()) {
                right = Math.max(right, bodyX);
            } else {
                left = Math.min(left, bodyX);
            }
        }

        left = clamp(left, 0, fullWidth);
        top = clamp(top, 0, fullHeight);
        right = clamp(right, left, fullWidth);
        bottom = clamp(bottom, top, fullHeight);
        return new RoiTransform(left, top, right - left, bottom - top, fullWidth, fullHeight);
    }

    public static RoiTransform fromEdge(int fullWidth, int fullHeight, PageEdge edge, BodyBoundary bodyBoundary,
                                        int marginPixels) {
        if (edge == PageEdge.TOP) {
            int bottom = bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)
                    ? (int) Math.ceil(bodyBoundary.y * fullHeight) + marginPixels : fullHeight / 5;
            return new RoiTransform(0, 0, fullWidth, bottom, fullWidth, fullHeight);
        }
        if (edge == PageEdge.BOTTOM) {
            int top = bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)
                    ? (int) Math.floor(bodyBoundary.y * fullHeight) - marginPixels : fullHeight * 4 / 5;
            return new RoiTransform(0, top, fullWidth, fullHeight - top, fullWidth, fullHeight);
        }
        if (edge == PageEdge.LEFT) {
            int right = bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)
                    ? (int) Math.ceil(bodyBoundary.x * fullWidth) + marginPixels : fullWidth / 5;
            return new RoiTransform(0, 0, right, fullHeight, fullWidth, fullHeight);
        }
        if (edge == PageEdge.RIGHT) {
            int left = bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)
                    ? (int) Math.floor(bodyBoundary.x * fullWidth) - marginPixels : fullWidth * 4 / 5;
            return new RoiTransform(left, 0, fullWidth - left, fullHeight, fullWidth, fullHeight);
        }
        throw new IllegalArgumentException("edge is required");
    }

    public PixelRect localRectToFullPixels(double x1, double y1, double x2, double y2) {
        double clampedX1 = clamp01(x1);
        double clampedY1 = clamp01(y1);
        double clampedX2 = clamp01(x2);
        double clampedY2 = clamp01(y2);
        int left = x + (int) Math.floor(clampedX1 * width);
        int top = y + (int) Math.floor(clampedY1 * height);
        int right = x + (int) Math.ceil(clampedX2 * width);
        int bottom = y + (int) Math.ceil(clampedY2 * height);
        right = clamp(right, left, x + width);
        bottom = clamp(bottom, top, y + height);
        return new PixelRect(left, top, right - left, bottom - top);
    }

    public NormalizedRect localRectToFullNormalized(double x1, double y1, double x2, double y2) {
        PixelRect rect = localRectToFullPixels(x1, y1, x2, y2);
        return new NormalizedRect(rect.getX() / (double) fullWidth,
                rect.getY() / (double) fullHeight,
                (rect.getX() + rect.getWidth()) / (double) fullWidth,
                (rect.getY() + rect.getHeight()) / (double) fullHeight);
    }

    public LocalPoint fullNormalizedToLocalPoint(double fullX, double fullY) {
        double pixelX = fullX * fullWidth;
        double pixelY = fullY * fullHeight;
        return new LocalPoint(clamp01((pixelX - x) / width), clamp01((pixelY - y) / height));
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public enum PageEdge {
        TOP, BOTTOM, LEFT, RIGHT
    }

    public static final class PixelRect {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private PixelRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    public static final class NormalizedRect {
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;

        private NormalizedRect(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public double getX1() {
            return x1;
        }

        public double getY1() {
            return y1;
        }

        public double getX2() {
            return x2;
        }

        public double getY2() {
            return y2;
        }
    }

    public static final class LocalPoint {
        private final double x;
        private final double y;

        private LocalPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }
}
