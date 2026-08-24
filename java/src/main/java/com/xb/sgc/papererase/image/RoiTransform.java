package com.xb.sgc.papererase.image;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateWindow;
import com.xb.sgc.papererase.safety.RegionValidator;

/**
 * ROI 坐标变换器：在整图像素坐标、ROI 局部归一化坐标和放大图像素坐标之间双向转换。
 * 局部放大只改变送检分辨率，不改变最终擦除必须使用的整图坐标。
 */
public final class RoiTransform {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int fullWidth;
    private final int fullHeight;

    /**
     * 创建整图中的 ROI。ROI 使用原图像素坐标，左上角为 {@code (x,y)}，覆盖半开矩形
     * {@code [x,x+width) × [y,y+height)}。
     *
     * @param x ROI 左上角像素 X
     * @param y ROI 左上角像素 Y
     * @param width ROI 像素宽度
     * @param height ROI 像素高度
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     */
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

    /**
     * 以已映射的像素候选框为中心生成 ROI，并向四周增加像素边距；正文边界所在方向会被
     * 纳入 ROI，供局部模型同时看到候选与安全带，但该窗口不拥有擦除权限。
     *
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     * @param candidate 原图像素候选框
     * @param bodyBoundary 最近正文边界，可为空
     * @param marginPixels 四周扩展像素数
     * @return 被整图边界裁剪后的 ROI 变换
     */
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

    /**
     * 将 VLM 归一化候选框映射为整图像素 ROI，不绕过归一化坐标合法性校验。
     * 左上角采用 floor、右下角采用 ceil，避免缩放取整漏掉抗锯齿边缘。
     *
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     * @param candidate VLM 返回的 0..1 候选框
     * @param bodyBoundary 最近正文边界，可为空
     * @param marginPixels ROI 额外扩展像素数
     * @return 整图像素 ROI
     */
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

        // VLM 的 (x1,y1)-(x2,y2) 是整图归一化矩形；转换后仍保持左上 floor、右下 ceil。
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
     * 合并 VLM 候选框与 pattern 粗窗口后生成 ROI。候选框可能整体偏移，合并只扩大模型可见范围，
     * 不改变最终擦除框；正文边界仍只用于最终安全门禁。
     *
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     * @param candidate 本页 locate 候选框
     * @param window pattern 给出的归一化粗窗口，可为空
     * @param bodyBoundary 正文边界，可为空
     * @param marginPixels 额外像素边距
     * @return 覆盖候选和粗窗口的整图像素 ROI
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

    /**
     * 创建覆盖页面指定边缘的 ROI，主要用于“locate 无候选”时的边缘复核。
     *
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     * @param edge 要检查的页面边缘
     * @param bodyBoundary 正文边界，可用于收紧 ROI
     * @param marginPixels 正文边界外扩像素数
     * @return 边缘 ROI
     */
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

    /**
     * 将 ROI 内的归一化矩形映射回整图像素矩形。
     *
     * @param x1 ROI 相对左上角 X，范围 0..1
     * @param y1 ROI 相对左上角 Y，范围 0..1
     * @param x2 ROI 相对右下角 X，范围 0..1
     * @param y2 ROI 相对右下角 Y，范围 0..1
     * @return 整图像素矩形，右下边界为 exclusive
     */
    public PixelRect localRectToFullPixels(double x1, double y1, double x2, double y2) {
        validateLocalRect(x1, y1, x2, y2);
        // 局部模型返回 ROI 相对坐标：先乘 ROI 尺寸得到局部像素，再加 ROI 左上角偏移回整图。
        int left = x + (int) Math.floor(x1 * width);
        int top = y + (int) Math.floor(y1 * height);
        int right = x + (int) Math.ceil(x2 * width);
        int bottom = y + (int) Math.ceil(y2 * height);
        return new PixelRect(left, top, right - left, bottom - top);
    }

    /**
     * 将 ROI 内归一化矩形映射为整图归一化矩形，内部先经过像素坐标以保持取整规则一致。
     *
     * @param x1 ROI 相对左上角 X
     * @param y1 ROI 相对左上角 Y
     * @param x2 ROI 相对右下角 X
     * @param y2 ROI 相对右下角 Y
     * @return 整图 0..1 归一化矩形
     */
    public NormalizedRect localRectToFullNormalized(double x1, double y1, double x2, double y2) {
        PixelRect rect = localRectToFullPixels(x1, y1, x2, y2);
        return new NormalizedRect(rect.getX() / (double) fullWidth,
                rect.getY() / (double) fullHeight,
                (rect.getX() + rect.getWidth()) / (double) fullWidth,
                (rect.getY() + rect.getHeight()) / (double) fullHeight);
    }

    /**
     * 将整图归一化点反算为 ROI 内归一化点，用于把正文边界或审计点投影回局部图。
     *
     * @param fullX 整图归一化 X
     * @param fullY 整图归一化 Y
     * @return ROI 内归一化点
     */
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
