package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.safety.ColorSeamGate;
import com.xb.sgc.papererase.safety.PixelDiffGate;
import com.xb.sgc.papererase.safety.RegionValidator;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 目标行擦除器。候选区域可以是页码本体，也可以是“页码及明确同行非正文元数据”组成的
 * 完整独立页眉/页脚行；它从不根据文字形状猜测语义。随后由 PixelDiffGate 证明批准掩码外
 * 没有任何改动。这是正文零误伤的最后一层本地保障。
 */
public final class InkMaskEraser {
    private InkMaskEraser() {
    }

    public static EraseOutcome erase(BufferedImage source, RegionValidator.PixelRegion region) {
        return erase(source, region, false);
    }

    /**
     * 在 RegionValidator 已批准的像素框内执行擦除：先提取目标墨迹掩码，再做形状/颜色风险检查，
     * 然后用外环估计背景重建整框。整框重建是为了清理灰色抗锯齿，但写入范围仍严格等于
     * approved box；彩色像素默认视为非目标，只有局部视觉复核确认候选框仅含页码时才允许
     * 传入 coloredTargetVerified=true；最后由 PixelDiffGate 证明框外没有任何变化，
     * ColorSeamGate 只负责色差告警。
     */
    public static EraseOutcome erase(BufferedImage source, RegionValidator.PixelRegion region, boolean coloredTargetVerified) {
        // 5.2.1 输入与批准框：只接受上游已通过正文门禁的原图坐标矩形。
        if (source == null || region == null) {
            return EraseOutcome.manual(null, new ApprovedMask(0, 0), "source and region are required");
        }
        String regionReason = invalidRegionReason(source, region);
        if (regionReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(source.getWidth(), source.getHeight()), regionReason);
        }
        String nonTargetReason = coloredTargetVerified ? null : nonTargetReason(source, region);
        if (nonTargetReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(source.getWidth(), source.getHeight()), nonTargetReason);
        }
        // 5.2.2 目标掩码：把页码深色和抗锯齿灰色纳入候选，掩码数组仍与整图同坐标系。
        boolean[][] mask = extractMask(source, region, coloredTargetVerified);
        if (!hasApprovedPixel(mask, region)) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), "no target ink found");
        }
        if (touchesRegionBoundary(mask, region)) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), "mask touches region boundary");
        }
        String geometryReason = invalidInkGeometryReason(mask, region, coloredTargetVerified);
        if (geometryReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), geometryReason);
        }

        // 5.3 几何风险后进入整框重建：区域已经过语义、空白带、边界墨迹和文字几何门禁。
        // 此时区域已经过“页码语义 + 空白带 + 边界墨迹 + 文字几何”全部批准。整框重建比
        // 仅擦深色掩码更能去掉扫描页码的灰色抗锯齿残影；批准框外不改一像素。
        boolean[][] approvedPixels = fullRegionMask(source, region);
        BackgroundEstimator.Estimate estimate = BackgroundEstimator.estimateFromOuterRing(source, region);
        // 外环决定重建颜色；框内原始纸张纹理过复杂时，宁可纯白也不把不可靠的拟合带回框内。
        boolean whiteFallback = !estimate.isAccepted() || !BackgroundEstimator.estimate(source, region, mask).isAccepted();

        BufferedImage candidate = copy(source);
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (approvedPixels[y][x]) {
                    candidate.setRGB(x, y, whiteFallback ? whiteAt(source, x, y) : estimate.argbAt(x, y));
                }
            }
        }
        ApprovedMask approvedMask = ApprovedMask.fromApprovedBox(region, source.getWidth(), source.getHeight(), approvedPixels);
        // 6.1 PixelDiffGate：写回后逐像素复核，防止算法或颜色模型改动越界伤正文。
        PixelDiffGate.GateResult diff = PixelDiffGate.check(source, candidate, approvedMask);
        if (!diff.isPassed()) {
            return EraseOutcome.manual(copy(source), approvedMask, diff.getReason());
        }
        // 8.1 色差收口：背景色只产生告警，不扩大掩码，也不改变正文安全门禁结论。
        ColorSeamGate.GateResult seam = ColorSeamGate.check(source, candidate, approvedMask);
        if (!seam.isPassed()) {
            return new EraseOutcome(Status.SAFE_TO_ERASE, colorReason(whiteFallback, seam.getReason()), candidate, approvedMask);
        }
        return new EraseOutcome(Status.SAFE_TO_ERASE,
                whiteFallback ? "white_fallback; color_warning" : "erased", candidate, approvedMask);
    }

    private static String invalidRegionReason(BufferedImage source, RegionValidator.PixelRegion region) {
        long x = region.getX();
        long y = region.getY();
        long width = region.getWidth();
        long height = region.getHeight();
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            return "region outside source";
        }
        if (x + width > source.getWidth() || y + height > source.getHeight()) {
            return "region outside source";
        }
        return null;
    }

    public static boolean hasColoredPixels(BufferedImage source, List<RegionValidator.PixelRegion> regions) {
        if (source == null || regions == null) {
            return false;
        }
        for (RegionValidator.PixelRegion region : regions) {
            for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
                for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                    if (BackgroundEstimator.isColoredNonTarget(BackgroundEstimator.parts(source.getRGB(x, y)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 色块、边框或装饰线不等于正文，但不能仅凭 Java 像素形状自行放行。
     * 命中后由流水线请求一次局部 VLM 复核；复核确认后才允许 {@link #erase}
     * 擦除整个已批准的独立页脚/页眉区域。
     */
    public static boolean requiresVisualTargetConfirmation(BufferedImage source, List<RegionValidator.PixelRegion> regions) {
        if (source == null || regions == null) {
            return false;
        }
        for (RegionValidator.PixelRegion region : regions) {
            if (nonTargetReason(source, region) != null) {
                return true;
            }
            boolean[][] mask = extractMask(source, region, false);
            String reason = invalidInkGeometryReason(mask, region, false);
            // 空框由流水线先走坐标精定位；其余形状异常都必须先让 VLM 看局部图，
            // 不能让 Java 把页码装饰、色块或同行元数据误判成正文表格。
            if (reason != null && !"no target ink found".equals(reason)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在已批准候选框内提取待擦除墨迹掩码。
     *
     * @param source 旋正后的原图
     * @param region RegionValidator 已批准的像素区域
     * @param coloredTargetVerified 是否经过 verify 授权将彩色目标视为页码
     * @return 与整张 source 同尺寸的掩码，候选框外全部为 {@code false}
     */
    private static boolean[][] extractMask(BufferedImage source, RegionValidator.PixelRegion region,
                                           boolean coloredTargetVerified) {
        /*
         * 只在上游已批准的 region 内提取实际目标墨迹。数组仍按整张图片建立，保证后续
         * ApprovedMask 与 PixelDiffGate 使用同一坐标系；region 外永远保持 false。默认不
         * 接纳彩色非目标，只有 verify 明确确认彩色内容属于页码时才允许授权参数为 true。
         */
        int backgroundLum = BackgroundEstimator.medianLightLuminance(source, region);
        boolean[][] mask = new boolean[source.getHeight()][source.getWidth()];
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (BackgroundEstimator.isErasableInk(source.getRGB(x, y), backgroundLum, coloredTargetVerified)) {
                    mask[y][x] = true;
                }
            }
        }
        return mask;
    }

    /**
     * 检查候选框内是否含未经授权的彩色非目标内容。
     *
     * @param source 旋正后的原图
     * @param region 待擦除候选框
     * @return {@code null} 表示未发现彩色非目标；否则返回人工审核原因
     */
    private static String nonTargetReason(BufferedImage source, RegionValidator.PixelRegion region) {
        /*
         * 擦除前的彩色非目标拦截。候选框内只要出现未经 verify 授权的彩色像素，就转人工
         * 审核；这里不靠文字形状或颜色相似度猜测语义，优先避免清掉彩色题干、图表和正文。
         */
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                BackgroundEstimator.ColorParts c = BackgroundEstimator.parts(source.getRGB(x, y));
                if (BackgroundEstimator.isColoredNonTarget(c)) {
                    return "colored non-target inside region";
                }
            }
        }
        return null;
    }

    private static int whiteAt(BufferedImage source, int x, int y) {
        int alpha = (source.getRGB(x, y) >>> 24) & 0xFF;
        return ((alpha & 0xFF) << 24) | 0x00FFFFFF;
    }

    private static String colorReason(boolean whiteFallback, String seamReason) {
        if (whiteFallback) {
            return "white_fallback; color_warning: " + seamReason;
        }
        return "color_warning: " + seamReason;
    }

    private static boolean hasApprovedPixel(boolean[][] mask, RegionValidator.PixelRegion region) {
        /* 只有批准框内确实检测到目标墨迹，后续才允许生成候选图；空掩码不能被“整框涂白”。 */
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (mask[y][x]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 批准框掩码不是“猜测正文范围”，而是上游 RegionValidator 已证明安全的最终写入范围。
     * 这里把框内全部像素列为可重建，框外保持 false，供 PixelDiffGate 做逐像素兜底。
     */
    /**
     * 创建批准框整框写入掩码，用于重建纸张和清除抗锯齿残边。
     *
     * @param source 原图，用于确定掩码尺寸
     * @param region 已批准的像素候选框
     * @return 整图尺寸掩码，只有 region 内为 {@code true}
     */
    private static boolean[][] fullRegionMask(BufferedImage source, RegionValidator.PixelRegion region) {
        /*
         * 页码整框重建需要覆盖纸张和灰色抗锯齿残边，因此写入掩码是批准框全覆盖，而不是
         * 仅覆盖深色墨迹。它绝不向 region 外扩，并由 PixelDiffGate 最终证明框外零变化。
         */
        boolean[][] approved = new boolean[source.getHeight()][source.getWidth()];
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                approved[y][x] = true;
            }
        }
        return approved;
    }

    /**
     * 判断目标掩码是否触碰批准框边界。
     *
     * @param mask 整图尺寸的目标墨迹掩码
     * @param region 批准候选框
     * @return {@code true} 表示目标可能延伸出候选框，必须拒绝或局部精修
     */
    private static boolean touchesRegionBoundary(boolean[][] mask, RegionValidator.PixelRegion region) {
        /*
         * 掩码贴到批准框边缘，说明模型框可能切穿目标或目标延伸到框外。即使只有一个抗锯
         * 齿像素也关闭整框重建，交给局部精修重新确定完整页码框，避免邻近正文被带入。
         */
        int left = region.getX();
        int top = region.getY();
        int right = region.getX() + region.getWidth() - 1;
        int bottom = region.getY() + region.getHeight() - 1;
        for (int x = left; x <= right; x++) {
            if (mask[top][x] || mask[bottom][x]) {
                return true;
            }
        }
        for (int y = top; y <= bottom; y++) {
            if (mask[y][left] || mask[y][right]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 审计候选框内墨迹的形状风险，拦截答题线、表格线、多行正文和高覆盖率区域。
     *
     * @param mask 目标墨迹掩码
     * @param region 候选框，用于计算相对面积和形状阈值
     * @param visuallyConfirmedIndependentTarget 是否已经由局部视觉复核确认是独立非正文目标
     * @return {@code null} 表示几何形状可接受，否则返回稳定失败原因
     */
    private static String invalidInkGeometryReason(boolean[][] mask, RegionValidator.PixelRegion region,
                                                   boolean visuallyConfirmedIndependentTarget) {
        /*
         * 对候选框内墨迹做几何风险审计，而不是判断“它像不像页码”。长横线/竖线、高覆盖率、
         * 多个文字带和交叉线分别对应答题线、表格、正文块等高风险结构；未经过局部视觉
         * 确认时全部拒绝，只有已确认的独立非正文目标才可放宽这些形状限制。
         */
        List<Component> components = components(mask, region);
        if (components.isEmpty()) {
            return "no target ink found";
        }
        int inkCount = 0;
        for (Component component : components) {
            inkCount += component.count;
            if (!visuallyConfirmedIndependentTarget
                    && component.width() >= Math.max(16, region.getWidth() * 0.45) && component.height() <= 3) {
                return "long line component";
            }
            if (!visuallyConfirmedIndependentTarget
                    && component.height() >= Math.max(10, region.getHeight() * 0.70) && component.width() <= 3) {
                return "long line component";
            }
            if (!visuallyConfirmedIndependentTarget
                    && (component.width() * component.height() >= region.getWidth() * region.getHeight() * 0.35
                    || component.count >= region.getWidth() * region.getHeight() * 0.25)) {
                return "ink coverage too high";
            }
        }
        if (!visuallyConfirmedIndependentTarget && inkCount >= region.getWidth() * region.getHeight() * 0.28) {
            return "ink coverage too high";
        }
        if (!visuallyConfirmedIndependentTarget && hasLongHorizontalRun(mask, region)) {
            return "long line component";
        }
        // 连通组件不能直接代表“文字行”：一个中文字符、括号或抗锯齿笔画可能被拆成多个
        // 上下错开的组件。改以整行横向投影识别独立墨迹带：仅在候选框内存在两个被空白行
        // 分隔的文字带时拒绝，避免把一行页码误当两行正文。
        if (!visuallyConfirmedIndependentTarget && hasMultipleTextLineBands(mask, region)) {
            return "multiple text lines";
        }
        if (!visuallyConfirmedIndependentTarget && hasCrossing(mask, region)) {
            return "table or crossing line";
        }
        return null;
    }

    private static boolean hasMultipleTextLineBands(boolean[][] mask, RegionValidator.PixelRegion region) {
        /*
         * 用横向投影把掩码分成文字带：一行中至少 2 个墨点才算有效，出现一整行空白即
         * 结束当前带。两个及以上带意味着候选框可能包含多行正文，而不是单行页码。
         */
        int bands = 0;
        boolean inBand = false;
        int blankRows = 0;
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            int ink = 0;
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (mask[y][x]) {
                    ink++;
                }
            }
            // 单像素抗锯齿噪点不能单独形成一行；真正的字符行至少有两个墨迹像素。
            if (ink >= 2) {
                if (!inBand) {
                    bands++;
                    inBand = true;
                }
                blankRows = 0;
            } else if (inBand) {
                blankRows++;
                // 横向投影跨整行字符：真正的单行页码不会在所有字符上同时出现空白行；
                // 一行全空白已足以分隔两行，且能保留双行正文的拒绝能力。
                if (blankRows >= 1) {
                    inBand = false;
                    blankRows = 0;
                }
            }
        }
        return bands > 1;
    }

    private static boolean hasLongHorizontalRun(boolean[][] mask, RegionValidator.PixelRegion region) {
        /* 页码通常不是横跨候选框 45% 以上的连续线；命中该阈值时按答题线/表格线阻断。 */
        int threshold = Math.max(16, region.getWidth() * 45 / 100);
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            int run = 0;
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                run = mask[y][x] ? run + 1 : 0;
                if (run >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCrossing(boolean[][] mask, RegionValidator.PixelRegion region) {
        /* 检查同一行和同一列的连续墨迹交叉，防止把表格交点、图形轴线当作可擦页码。 */
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            int run = 0;
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                run = mask[y][x] ? run + 1 : 0;
                if (run >= Math.max(10, region.getWidth() / 3)) {
                    int start = x - run + 1;
                    int end = x;
                    for (int xx = start; xx <= end; xx++) {
                        int vertical = 0;
                        for (int yy = region.getY(); yy < region.getY() + region.getHeight(); yy++) {
                            if (mask[yy][xx]) {
                                vertical++;
                            }
                        }
                        if (vertical >= Math.max(6, region.getHeight() / 2)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<Component> components(boolean[][] mask, RegionValidator.PixelRegion region) {
        boolean[][] visited = new boolean[mask.length][mask[0].length];
        List<Component> components = new ArrayList<Component>();
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (!mask[y][x] || visited[y][x]) {
                    continue;
                }
                Component component = new Component(x, y);
                Queue<int[]> queue = new ArrayDeque<int[]>();
                queue.add(new int[]{x, y});
                visited[y][x] = true;
                while (!queue.isEmpty()) {
                    int[] point = queue.remove();
                    component.add(point[0], point[1]);
                    for (int yy = point[1] - 1; yy <= point[1] + 1; yy++) {
                        for (int xx = point[0] - 1; xx <= point[0] + 1; xx++) {
                            if (xx < region.getX() || yy < region.getY()
                                    || xx >= region.getX() + region.getWidth()
                                    || yy >= region.getY() + region.getHeight()
                                    || visited[yy][xx] || !mask[yy][xx]) {
                                continue;
                            }
                            visited[yy][xx] = true;
                            queue.add(new int[]{xx, yy});
                        }
                    }
                }
                components.add(component);
            }
        }
        return components;
    }

    private static BufferedImage copy(BufferedImage source) {
        if (source == null) {
            return null;
        }
        ColorModel colorModel = source.getColorModel();
        WritableRaster raster = source.copyData(null);
        return new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
    }

    public enum Status {
        SAFE_TO_ERASE, MANUAL_REVIEW
    }

    public static final class EraseOutcome {
        private final Status status;
        private final String reason;
        private final BufferedImage candidate;
        private final ApprovedMask approvedMask;

        private EraseOutcome(Status status, String reason, BufferedImage candidate, ApprovedMask approvedMask) {
            this.status = status;
            this.reason = reason;
            this.candidate = copy(candidate);
            this.approvedMask = approvedMask;
        }

        static EraseOutcome manual(BufferedImage candidate, ApprovedMask approvedMask, String reason) {
            return new EraseOutcome(Status.MANUAL_REVIEW, reason, candidate, approvedMask);
        }

        public Status getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        public BufferedImage getCandidate() {
            return copy(candidate);
        }

        public ApprovedMask getApprovedMask() {
            return approvedMask;
        }
    }

    public static final class ApprovedMask {
        private final boolean[][] mask;
        private final int imageWidth;
        private final int imageHeight;
        private final int regionX;
        private final int regionY;
        private final int regionWidth;
        private final int regionHeight;

        private ApprovedMask(int width, int height) {
            this.mask = new boolean[height][width];
            this.imageWidth = width;
            this.imageHeight = height;
            this.regionX = 0;
            this.regionY = 0;
            this.regionWidth = width;
            this.regionHeight = height;
        }

        private ApprovedMask(boolean[][] mask) {
            this.mask = copyRectangular(mask);
            this.imageHeight = mask.length;
            this.imageWidth = mask.length == 0 ? 0 : mask[0].length;
            this.regionX = 0;
            this.regionY = 0;
            this.regionWidth = imageWidth;
            this.regionHeight = imageHeight;
        }

        private ApprovedMask(RegionValidator.PixelRegion region, int imageWidth, int imageHeight, boolean[][] mask) {
            this.mask = copyRectangular(mask);
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.regionX = region.getX();
            this.regionY = region.getY();
            this.regionWidth = region.getWidth();
            this.regionHeight = region.getHeight();
        }

        public static ApprovedMask from(RegionValidator.PixelRegion region, int imageWidth, int imageHeight, boolean[][] mask) {
            validate(region, imageWidth, imageHeight, mask, false);
            return new ApprovedMask(region, imageWidth, imageHeight, mask);
        }

        /** 仅供已通过全部语义/像素门禁的整框背景重建使用。 */
        static ApprovedMask fromApprovedBox(RegionValidator.PixelRegion region, int imageWidth, int imageHeight, boolean[][] mask) {
            validate(region, imageWidth, imageHeight, mask, true);
            return new ApprovedMask(region, imageWidth, imageHeight, mask);
        }

        public boolean isApproved(int x, int y) {
            return y >= 0 && y < mask.length && x >= 0 && mask.length > 0 && x < mask[0].length && mask[y][x];
        }

        public boolean[][] toArray() {
            return copy(mask);
        }

        public int getImageWidth() {
            return imageWidth;
        }

        public int getImageHeight() {
            return imageHeight;
        }

        public int getRegionX() {
            return regionX;
        }

        public int getRegionY() {
            return regionY;
        }

        public int getRegionWidth() {
            return regionWidth;
        }

        public int getRegionHeight() {
            return regionHeight;
        }

        public int countApproved() {
            int count = 0;
            for (int y = 0; y < mask.length; y++) {
                for (int x = 0; x < mask[y].length; x++) {
                    if (mask[y][x]) {
                        count++;
                    }
                }
            }
            return count;
        }

        public boolean containsRegion(int x, int y) {
            return x >= regionX && y >= regionY && x < regionX + regionWidth && y < regionY + regionHeight;
        }

        private static void validate(RegionValidator.PixelRegion region, int imageWidth, int imageHeight, boolean[][] mask,
                                     boolean wholeApprovedBox) {
            if (region == null || mask == null) {
                throw new IllegalArgumentException("region and mask are required");
            }
            if (imageWidth <= 0 || imageHeight <= 0 || mask.length != imageHeight) {
                throw new IllegalArgumentException("mask dimensions must match image dimensions");
            }
            long right = (long) region.getX() + region.getWidth();
            long bottom = (long) region.getY() + region.getHeight();
            if (region.getX() < 0 || region.getY() < 0 || region.getWidth() <= 0 || region.getHeight() <= 0
                    || right > imageWidth || bottom > imageHeight) {
                throw new IllegalArgumentException("region outside image dimensions");
            }
            int count = 0;
            for (int y = 0; y < mask.length; y++) {
                if (mask[y] == null || mask[y].length != imageWidth) {
                    throw new IllegalArgumentException("mask must be rectangular and match image dimensions");
                }
                for (int x = 0; x < imageWidth; x++) {
                    if (!mask[y][x]) {
                        continue;
                    }
                    if (x < region.getX() || y < region.getY() || x >= right || y >= bottom) {
                        throw new IllegalArgumentException("approved pixel outside region");
                    }
                    if (!wholeApprovedBox && (x == region.getX() || y == region.getY() || x == right - 1 || y == bottom - 1)) {
                        throw new IllegalArgumentException("mask touches region boundary");
                    }
                    count++;
                }
            }
            int regionArea = region.getWidth() * region.getHeight();
            if (!wholeApprovedBox && regionArea > 0 && count > Math.max(6, regionArea * 35 / 100)) {
                throw new IllegalArgumentException("mask coverage is too broad");
            }
        }

        private static boolean[][] copy(boolean[][] source) {
            return copyRectangular(source);
        }

        private static boolean[][] copyRectangular(boolean[][] source) {
            if (source == null) {
                throw new IllegalArgumentException("mask is required");
            }
            boolean[][] copy = new boolean[source.length][];
            int width = source.length == 0 ? 0 : (source[0] == null ? -1 : source[0].length);
            for (int y = 0; y < source.length; y++) {
                if (source[y] == null || source[y].length != width) {
                    throw new IllegalArgumentException("mask must be rectangular");
                }
                copy[y] = new boolean[source[y].length];
                System.arraycopy(source[y], 0, copy[y], 0, source[y].length);
            }
            return copy;
        }
    }

    private static final class Component {
        int left;
        int top;
        int right;
        int bottom;
        int count;

        Component(int x, int y) {
            left = x;
            right = x;
            top = y;
            bottom = y;
        }

        void add(int x, int y) {
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
            count++;
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }
    }
}
