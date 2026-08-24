package com.xb.sgc.papererase.image;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateWindow;
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
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("roi width and height must be positive");
        }
        if (x < 0 || y < 0 || x > fullWidth || y > fullHeight
                || (long) x + width > fullWidth || (long) y + height > fullHeight) {
            throw new IllegalArgumentException("roi must be inside full image bounds");
        }
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static RoiTransform fromCandidate(int fullWidth, int fullHeight, RegionValidator.PixelRegion candidate,
                                             BodyBoundary bodyBoundary, int marginPixels) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        if (marginPixels < 0) {
            throw new IllegalArgumentException("marginPixels must be non-negative");
        }
        validateOptionalBodyBoundary(bodyBoundary);
        long left = (long) candidate.getX() - marginPixels;
        long top = (long) candidate.getY() - marginPixels;
        long right = (long) candidate.getX() + candidate.getWidth() + marginPixels;
        long bottom = (long) candidate.getY() + candidate.getHeight() + marginPixels;
        rejectIntOverflow(left, top, right, bottom);

        if (bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)) {
            long bodyY = (long) Math.ceil(bodyBoundary.y * fullHeight);
            if (bodyY >= candidate.getY() + candidate.getHeight()) {
                bottom = Math.max(bottom, bodyY);
            } else {
                top = Math.min(top, bodyY);
            }
        }
        if (bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)) {
            long bodyX = (long) Math.ceil(bodyBoundary.x * fullWidth);
            if (bodyX >= candidate.getX() + candidate.getWidth()) {
                right = Math.max(right, bodyX);
            } else {
                left = Math.min(left, bodyX);
            }
        }
        rejectIntOverflow(left, top, right, bottom);

        int clampedLeft = clampToInt(left, 0, fullWidth);
        int clampedTop = clampToInt(top, 0, fullHeight);
        int clampedRight = clampToInt(right, clampedLeft, fullWidth);
        int clampedBottom = clampToInt(bottom, clampedTop, fullHeight);
        return new RoiTransform(clampedLeft, clampedTop, clampedRight - clampedLeft,
                clampedBottom - clampedTop, fullWidth, fullHeight);
    }

    /** Builds an ROI directly from a VLM normalized candidate without bypassing coordinate validation. */
    public static RoiTransform fromNormalizedCandidate(int fullWidth, int fullHeight, EraseRegion candidate,
                                                       BodyBoundary bodyBoundary, int marginPixels) {
        if (fullWidth <= 0 || fullHeight <= 0) {
            throw new IllegalArgumentException("full image dimensions must be positive");
        }
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        if (marginPixels < 0) {
            throw new IllegalArgumentException("marginPixels must be non-negative");
        }
        validateOptionalBodyBoundary(bodyBoundary);
        validateLocalRect(candidate.x1, candidate.y1, candidate.x2, candidate.y2);

        long candidateLeft = (long) Math.floor(candidate.x1 * fullWidth);
        long candidateTop = (long) Math.floor(candidate.y1 * fullHeight);
        long candidateRight = (long) Math.ceil(candidate.x2 * fullWidth);
        long candidateBottom = (long) Math.ceil(candidate.y2 * fullHeight);
        long left = candidateLeft - marginPixels;
        long top = candidateTop - marginPixels;
        long right = candidateRight + marginPixels;
        long bottom = candidateBottom + marginPixels;
        rejectIntOverflow(left, top, right, bottom);

        if (bodyBoundary != null && bodyBoundary.y != null) {
            long bodyY = (long) Math.ceil(bodyBoundary.y * fullHeight);
            if (bodyY >= candidateBottom) {
                bottom = Math.max(bottom, bodyY);
            } else {
                top = Math.min(top, bodyY);
            }
        }
        if (bodyBoundary != null && bodyBoundary.x != null) {
            long bodyX = (long) Math.ceil(bodyBoundary.x * fullWidth);
            if (bodyX >= candidateRight) {
                right = Math.max(right, bodyX);
            } else {
                left = Math.min(left, bodyX);
            }
        }
        rejectIntOverflow(left, top, right, bottom);

        int clampedLeft = clampToInt(left, 0, fullWidth);
        int clampedTop = clampToInt(top, 0, fullHeight);
        int clampedRight = clampToInt(right, clampedLeft, fullWidth);
        int clampedBottom = clampToInt(bottom, clampedTop, fullHeight);
        return new RoiTransform(clampedLeft, clampedTop, clampedRight - clampedLeft,
                clampedBottom - clampedTop, fullWidth, fullHeight);
    }

    /**
     * 坐标精定位必须同时保留“本页候选框”和 pattern 给出的宽松窗口。候选框可能整体偏移，
     * 只围绕它裁 ROI 会把真实页码排除在图外，使局部模型只能错误回答 no_pagenum。
     * pattern 窗口只决定本次复核可见范围，不拥有擦除权限；最终坐标仍完全由局部模型给出。
     * 整页正文边界仅属于最终安全门禁，不能反向把已给出窗口的局部图拉回整页中部，否则
     * 放大精定位会被正文/分栏线干扰而失去意义。
     */
    public static RoiTransform fromNormalizedCandidateAndWindow(int fullWidth, int fullHeight, EraseRegion candidate,
                                                                 LocateWindow window, BodyBoundary bodyBoundary,
                                                                 int marginPixels) {
        if (window == null) {
            return fromNormalizedCandidate(fullWidth, fullHeight, candidate, bodyBoundary, marginPixels);
        }
        validateLocalRect(window.x1, window.y1, window.x2, window.y2);
        EraseRegion combined = new EraseRegion();
        combined.x1 = Math.min(candidate.x1, window.x1);
        combined.y1 = Math.min(candidate.y1, window.y1);
        combined.x2 = Math.max(candidate.x2, window.x2);
        combined.y2 = Math.max(candidate.y2, window.y2);
        return fromNormalizedCandidate(fullWidth, fullHeight, combined, null, marginPixels);
    }

    public static RoiTransform fromEdge(int fullWidth, int fullHeight, PageEdge edge, BodyBoundary bodyBoundary,
                                        int marginPixels) {
        if (edge == null) {
            throw new IllegalArgumentException("edge is required");
        }
        if (marginPixels < 0) {
            throw new IllegalArgumentException("marginPixels must be non-negative");
        }
        validateOptionalBodyBoundary(bodyBoundary);
        if (edge == PageEdge.TOP) {
            long bottom = bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)
                    ? (long) Math.ceil(bodyBoundary.y * fullHeight) + marginPixels : fullHeight / 5L;
            rejectIntOverflow(bottom);
            return new RoiTransform(0, 0, fullWidth, clampToInt(bottom, 1, fullHeight), fullWidth, fullHeight);
        }
        if (edge == PageEdge.BOTTOM) {
            long top = bodyBoundary != null && bodyBoundary.y != null && finite(bodyBoundary.y)
                    ? (long) Math.floor(bodyBoundary.y * fullHeight) - marginPixels : fullHeight * 4L / 5L;
            rejectIntOverflow(top);
            int clampedTop = clampToInt(top, 0, fullHeight - 1);
            return new RoiTransform(0, clampedTop, fullWidth, fullHeight - clampedTop, fullWidth, fullHeight);
        }
        if (edge == PageEdge.LEFT) {
            long right = bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)
                    ? (long) Math.ceil(bodyBoundary.x * fullWidth) + marginPixels : fullWidth / 5L;
            rejectIntOverflow(right);
            return new RoiTransform(0, 0, clampToInt(right, 1, fullWidth), fullHeight, fullWidth, fullHeight);
        }
        if (edge == PageEdge.RIGHT) {
            long left = bodyBoundary != null && bodyBoundary.x != null && finite(bodyBoundary.x)
                    ? (long) Math.floor(bodyBoundary.x * fullWidth) - marginPixels : fullWidth * 4L / 5L;
            rejectIntOverflow(left);
            int clampedLeft = clampToInt(left, 0, fullWidth - 1);
            return new RoiTransform(clampedLeft, 0, fullWidth - clampedLeft, fullHeight, fullWidth, fullHeight);
        }
        throw new IllegalArgumentException("edge is required");
    }

    public PixelRect localRectToFullPixels(double x1, double y1, double x2, double y2) {
        validateLocalRect(x1, y1, x2, y2);
        int left = x + (int) Math.floor(x1 * width);
        int top = y + (int) Math.floor(y1 * height);
        int right = x + (int) Math.ceil(x2 * width);
        int bottom = y + (int) Math.ceil(y2 * height);
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
        validateFullNormalizedPoint(fullX, fullY);
        double pixelX = fullX * fullWidth;
        double pixelY = fullY * fullHeight;
        double localX = (pixelX - x) / width;
        double localY = (pixelY - y) / height;
        if (localX < 0 || localX > 1 || localY < 0 || localY > 1) {
            throw new IllegalArgumentException("full normalized point must be inside roi");
        }
        return new LocalPoint(localX, localY);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampToInt(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    private static void rejectIntOverflow(long... values) {
        for (long value : values) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("marginPixels is too large");
            }
        }
    }

    private static void validateLocalRect(double x1, double y1, double x2, double y2) {
        if (!finite(x1) || !finite(y1) || !finite(x2) || !finite(y2)) {
            throw new IllegalArgumentException("local coordinates must be finite");
        }
        if (x1 < 0 || x1 > 1 || y1 < 0 || y1 > 1 || x2 < 0 || x2 > 1 || y2 < 0 || y2 > 1) {
            throw new IllegalArgumentException("local coordinates must be between 0 and 1");
        }
        if (x1 >= x2 || y1 >= y2) {
            throw new IllegalArgumentException("local rect must have strictly positive area");
        }
    }

    private static void validateFullNormalizedPoint(double fullX, double fullY) {
        if (!finite(fullX) || !finite(fullY)) {
            throw new IllegalArgumentException("full normalized point must be finite");
        }
        if (fullX < 0 || fullX > 1 || fullY < 0 || fullY > 1) {
            throw new IllegalArgumentException("full normalized point must be between 0 and 1");
        }
    }

    private static void validateOptionalBodyBoundary(BodyBoundary bodyBoundary) {
        if (bodyBoundary == null) {
            return;
        }
        if (bodyBoundary.x != null) {
            if (!finite(bodyBoundary.x)) {
                throw new IllegalArgumentException("body boundary x must be finite");
            }
            if (bodyBoundary.x < 0 || bodyBoundary.x > 1) {
                throw new IllegalArgumentException("body boundary x must be between 0 and 1");
            }
        }
        if (bodyBoundary.y != null) {
            if (!finite(bodyBoundary.y)) {
                throw new IllegalArgumentException("body boundary y must be finite");
            }
            if (bodyBoundary.y < 0 || bodyBoundary.y > 1) {
                throw new IllegalArgumentException("body boundary y must be between 0 and 1");
            }
        }
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

        public PixelRect(int x, int y, int width, int height) {
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
