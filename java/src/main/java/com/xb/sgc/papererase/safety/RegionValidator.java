package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 擦除前的确定性防火墙。模型可以误判，但这里的规则要求候选页码位于独立边缘带，且与
 * 正文之间存在真实、无墨的像素安全带；任意一项无法证明即拒绝自动擦除。
 */
public final class RegionValidator {
    private static final double EDGE_BAND = 0.20;
    private static final int MIN_BODY_GAP_PIXELS = 8;
    private static final int MODEL_BOX_GUARD_PIXELS = 1;

    private RegionValidator() {
    }

    public static ValidationResult validate(PageLocateResult locateResult, BufferedImage image) {
        if (locateResult == null) {
            return ValidationResult.rejected("locate result is required");
        }
        if (image == null) {
            return ValidationResult.rejected("image is required");
        }

        List<String> reasons = new ArrayList<String>();
        List<PixelRegion> pixelRegions = new ArrayList<PixelRegion>();
        Set<String> regionIds = new HashSet<String>();

        if (locateResult.getPageId() == null || locateResult.getPageId().trim().length() == 0) {
            return ValidationResult.rejected("page_id is required");
        }
        if (!"safe_to_erase".equals(locateResult.getStatus())) {
            return ValidationResult.rejected("status must be safe_to_erase");
        }
        if (locateResult.getRegions() == null) {
            return ValidationResult.rejected("regions are required");
        }
        if (locateResult.getRegions().isEmpty()) {
            return ValidationResult.rejected("regions must not be empty");
        }

        for (EraseRegion region : locateResult.getRegions()) {
            if (region == null) {
                reasons.add("region is required");
                continue;
            }
            if (region.region_id == null || region.region_id.trim().length() == 0) {
                reasons.add("region_id is required");
                continue;
            }
            String canonicalRegionId = region.region_id.trim();
            if (!regionIds.add(canonicalRegionId)) {
                reasons.add("duplicate region_id: " + canonicalRegionId);
                continue;
            }
            if (!finite(region.x1) || !finite(region.y1) || !finite(region.x2) || !finite(region.y2)) {
                reasons.add("coordinates must be finite");
                continue;
            }
            if (!finite(region.confidence)) {
                reasons.add("confidence must be finite");
                continue;
            }
            if (region.confidence < 0 || region.confidence > 1) {
                reasons.add("confidence must be between 0 and 1");
                continue;
            }
            if (!(0 <= region.x1 && region.x1 <= 1 && 0 <= region.x2 && region.x2 <= 1
                    && 0 <= region.y1 && region.y1 <= 1 && 0 <= region.y2 && region.y2 <= 1)) {
                reasons.add("coordinates must satisfy 0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1");
                continue;
            }
            if (region.x1 >= region.x2 || region.y1 >= region.y2
                    || (region.x2 - region.x1) * (region.y2 - region.y1) <= 0) {
                reasons.add("region must have strictly positive area");
                continue;
            }

            // 非边缘候选不具备“页码与正文分离”的可证明条件，不能靠置信度放行。
            Edge edge = edge(region);
            if (edge == Edge.NONE) {
                reasons.add("region must be inside an edge band");
                continue;
            }
            String boundaryReason = invalidBodyBoundaryReason(edge, locateResult.getNearestBodyBoundary());
            if (boundaryReason != null) {
                reasons.add(boundaryReason);
                continue;
            }
            PixelRegion pixelRegion = toPixelRegion(locateResult.getPageId(), canonicalRegionId, region, image);
            if (pixelRegion.getWidth() <= 0 || pixelRegion.getHeight() <= 0
                    || pixelRegion.getX() < 0 || pixelRegion.getY() < 0
                    || pixelRegion.getX() + pixelRegion.getWidth() > image.getWidth()
                    || pixelRegion.getY() + pixelRegion.getHeight() > image.getHeight()) {
                reasons.add("coordinates map outside image bounds");
                continue;
            }
            // 用真实像素空白带而非仅模型给出的归一化距离，抵抗边界坐标幻觉。
            String gapReason = invalidPixelGapReason(edge, pixelRegion, locateResult.getNearestBodyBoundary(), image);
            if ("body blank gap contains ink".equals(gapReason)) {
                /*
                 * VLM 坐标是语义定位，常把页码最外侧一两列抗锯齿笔画排除在框外。不能把
                 * 这类“与框内页码连通”的残笔直接当正文，也不能无条件放宽安全带：只把
                 * 连通分量向正文方向扩一像素白边，再重新验证 8px 的真实无墨安全带。
                 */
                pixelRegion = expandConnectedTargetInk(edge, pixelRegion,
                        locateResult.getNearestBodyBoundary(), image);
                gapReason = invalidPixelGapReason(edge, pixelRegion,
                        locateResult.getNearestBodyBoundary(), image);
            }
            if (gapReason != null) {
                reasons.add(gapReason);
                continue;
            }
            // 模型可能把整个框偏到页码相邻空白处。框内完全无墨时，只能在已证明远离正文的
            // 同侧走廊中找回目标；该分支会标记 coordinateRescued 并强制局部二检。
            if (!hasConservativeInk(image, pixelRegion)) {
                pixelRegion = rescueEmptyModelBox(edge, pixelRegion,
                        bodyLimit(edge, locateResult.getNearestBodyBoundary(), image), image);
                gapReason = invalidPixelGapReason(edge, pixelRegion, locateResult.getNearestBodyBoundary(), image);
                if (gapReason != null) {
                    reasons.add(gapReason);
                    continue;
                }
            }
            // 只有原始模型框确实贴到墨迹时，才补一像素抗锯齿保护边并重新证明安全。
            // 正常模型框不改变，避免扩大擦除区域或给所有页面增加二检成本。
            if (maskTouchesCandidateBox(image, pixelRegion)) {
                PixelRegion guarded = withModelBoxGuard(pixelRegion, image);
                String guardedGapReason = invalidPixelGapReason(edge, guarded,
                        locateResult.getNearestBodyBoundary(), image);
                if (guardedGapReason == null && !maskTouchesCandidateBox(image, guarded)) {
                    pixelRegion = guarded;
                } else {
                    reasons.add(guardedGapReason == null ? "ink mask touches candidate box" : guardedGapReason);
                    continue;
                }
            }
            pixelRegions.add(pixelRegion);
        }

        if (!reasons.isEmpty()) {
            return new ValidationResult(false, Collections.<PixelRegion>emptyList(), reasons);
        }
        return new ValidationResult(true, pixelRegions, Collections.<String>emptyList());
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static PixelRegion toPixelRegion(String pageId, String regionId, EraseRegion region, BufferedImage image) {
        int left = clamp((int) Math.floor(region.x1 * image.getWidth()), 0, image.getWidth());
        int top = clamp((int) Math.floor(region.y1 * image.getHeight()), 0, image.getHeight());
        int rightExclusive = clamp((int) Math.ceil(region.x2 * image.getWidth()), 0, image.getWidth());
        int bottomExclusive = clamp((int) Math.ceil(region.y2 * image.getHeight()), 0, image.getHeight());
        return new PixelRegion(pageId, regionId, left, top, rightExclusive - left, bottomExclusive - top,
                region.x1, region.y1, region.x2, region.y2, region.confidence);
    }

    /**
     * 归一化坐标转换为整数像素时，页码的抗锯齿边缘可能恰好落在框边上一像素。先统一补
     * 一圈极小保护带，再重新执行正文空白带、边缘带和局部 VLM 二检；它不是按样本调参。
     */
    private static PixelRegion withModelBoxGuard(PixelRegion region, BufferedImage image) {
        int left = Math.max(0, region.getX() - MODEL_BOX_GUARD_PIXELS);
        int top = Math.max(0, region.getY() - MODEL_BOX_GUARD_PIXELS);
        int right = Math.min(image.getWidth(), region.getX() + region.getWidth() + MODEL_BOX_GUARD_PIXELS);
        int bottom = Math.min(image.getHeight(), region.getY() + region.getHeight() + MODEL_BOX_GUARD_PIXELS);
        return new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Edge edge(EraseRegion region) {
        if (region.y2 <= EDGE_BAND) {
            return Edge.TOP;
        }
        if (region.y1 >= 1 - EDGE_BAND) {
            return Edge.BOTTOM;
        }
        if (region.x2 <= EDGE_BAND) {
            return Edge.LEFT;
        }
        if (region.x1 >= 1 - EDGE_BAND) {
            return Edge.RIGHT;
        }
        return Edge.NONE;
    }

    private static String invalidBodyBoundaryReason(Edge edge, BodyBoundary boundary) {
        if (boundary == null) {
            return "body blank gap is insufficient";
        }
        if (edge == Edge.TOP || edge == Edge.BOTTOM) {
            if (boundary.y == null) {
                return "body blank gap is insufficient";
            }
            if (!finite(boundary.y)) {
                return "body boundary y must be finite";
            }
            if (boundary.y < 0 || boundary.y > 1) {
                return "body boundary y must be between 0 and 1";
            }
        } else {
            if (boundary.x == null) {
                return "body blank gap is insufficient";
            }
            if (!finite(boundary.x)) {
                return "body boundary x must be finite";
            }
            if (boundary.x < 0 || boundary.x > 1) {
                return "body boundary x must be between 0 and 1";
            }
        }
        return null;
    }

    private static String invalidPixelGapReason(Edge edge, PixelRegion region, BodyBoundary boundary, BufferedImage image) {
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) {
            int bodyY = (int) Math.floor(boundary.y * image.getHeight());
            if (bodyY - bottom < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            // 相邻安全带出现任何保守墨迹都拒绝；宁可漏擦，也不穿透到正文首行。
            return bandHasInk(image, region.getX(), bottom, right, bottom + MIN_BODY_GAP_PIXELS)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.BOTTOM) {
            int bodyY = (int) Math.ceil(boundary.y * image.getHeight());
            if (region.getY() - bodyY < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasInk(image, region.getX(), region.getY() - MIN_BODY_GAP_PIXELS, right, region.getY())
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.LEFT) {
            int bodyX = (int) Math.floor(boundary.x * image.getWidth());
            if (bodyX - right < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasInk(image, right, region.getY(), right + MIN_BODY_GAP_PIXELS, bottom)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.RIGHT) {
            int bodyX = (int) Math.ceil(boundary.x * image.getWidth());
            if (region.getX() - bodyX < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasInk(image, region.getX() - MIN_BODY_GAP_PIXELS, region.getY(), region.getX(), bottom)
                    ? "body blank gap contains ink" : null;
        }
        return "body blank gap is insufficient";
    }

    /**
     * 仅在候选框相邻安全带有墨时，吸收从原候选框连通出来的页码残笔。搜索区域被限制在
     * 正文边界之外，并且扩框后必须还留出一个完整的 {@link #MIN_BODY_GAP_PIXELS} 空白带，
     * 因此孤立正文墨迹、表格线或越过安全带的长笔画均不能被带入擦除区。
     */
    private static PixelRegion expandConnectedTargetInk(Edge edge, PixelRegion region,
                                                          BodyBoundary boundary, BufferedImage image) {
        int bodyLimit = bodyLimit(edge, boundary, image);
        if (bodyLimit < 0) {
            return region;
        }
        Bounds bounds = expansionBounds(edge, region, bodyLimit, image);
        if (bounds == null) {
            return region;
        }

        boolean[][] connected = new boolean[bounds.height()][bounds.width()];
        ArrayList<int[]> queue = new ArrayList<int[]>();
        for (int y = Math.max(region.getY(), bounds.top); y < Math.min(region.getY() + region.getHeight(), bounds.bottom); y++) {
            for (int x = Math.max(region.getX(), bounds.left); x < Math.min(region.getX() + region.getWidth(), bounds.right); x++) {
                if (isConservativeInk(image.getRGB(x, y))) {
                    connected[y - bounds.top][x - bounds.left] = true;
                    queue.add(new int[]{x, y});
                }
            }
        }
        if (queue.isEmpty()) {
            return rescueEmptyModelBox(edge, region, bodyLimit, image);
        }

        int minX = region.getX();
        int maxX = region.getX() + region.getWidth() - 1;
        int minY = region.getY();
        int maxY = region.getY() + region.getHeight() - 1;
        for (int index = 0; index < queue.size(); index++) {
            int[] point = queue.get(index);
            int x = point[0];
            int y = point[1];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nextX = x + dx;
                    int nextY = y + dy;
                    if (nextX < bounds.left || nextX >= bounds.right || nextY < bounds.top || nextY >= bounds.bottom
                            || connected[nextY - bounds.top][nextX - bounds.left]
                            || !isConservativeInk(image.getRGB(nextX, nextY))) {
                        continue;
                    }
                    connected[nextY - bounds.top][nextX - bounds.left] = true;
                    queue.add(new int[]{nextX, nextY});
                }
            }
        }

        // 与候选框不连通的安全带墨迹不是页码残笔，保持拒绝，不允许猜测其语义。
        if (hasUnconnectedInk(bounds, connected, image)) {
            return region;
        }
        return paddedExpandedRegion(edge, region, minX, minY, maxX, maxY, bodyLimit, image);
    }

    /**
     * 处理“模型语义判断正确、但框整体偏到页外”的情形。仅从模型框与正文边界之间的
     * 走廊寻找墨迹；该走廊外的正文永远不可进入。返回的区域还会在后续流程强制局部二检。
     */
    private static PixelRegion rescueEmptyModelBox(Edge edge, PixelRegion region, int bodyLimit, BufferedImage image) {
        Bounds corridor = inwardCorridor(edge, region, bodyLimit);
        if (corridor == null || !corridor.isInside(image)) {
            return region;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = corridor.top; y < corridor.bottom; y++) {
            for (int x = corridor.left; x < corridor.right; x++) {
                if (!isConservativeInk(image.getRGB(x, y))) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return region;
        }
        return paddedExpandedRegion(edge, region, minX, minY, maxX, maxY, bodyLimit, image);
    }

    private static Bounds inwardCorridor(Edge edge, PixelRegion region, int bodyLimit) {
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) {
            return new Bounds(region.getX(), bottom, right, bodyLimit - MIN_BODY_GAP_PIXELS);
        }
        if (edge == Edge.BOTTOM) {
            return new Bounds(region.getX(), bodyLimit + MIN_BODY_GAP_PIXELS, right, region.getY());
        }
        if (edge == Edge.LEFT) {
            return new Bounds(right, region.getY(), bodyLimit - MIN_BODY_GAP_PIXELS, bottom);
        }
        if (edge == Edge.RIGHT) {
            return new Bounds(bodyLimit + MIN_BODY_GAP_PIXELS, region.getY(), region.getX(), bottom);
        }
        return null;
    }

    private static int bodyLimit(Edge edge, BodyBoundary boundary, BufferedImage image) {
        if (edge == Edge.TOP) return (int) Math.floor(boundary.y * image.getHeight());
        if (edge == Edge.BOTTOM) return (int) Math.ceil(boundary.y * image.getHeight());
        if (edge == Edge.LEFT) return (int) Math.floor(boundary.x * image.getWidth());
        if (edge == Edge.RIGHT) return (int) Math.ceil(boundary.x * image.getWidth());
        return -1;
    }

    private static Bounds expansionBounds(Edge edge, PixelRegion region, int bodyLimit, BufferedImage image) {
        if (edge == Edge.TOP) {
            return new Bounds(region.getX(), 0, region.getX() + region.getWidth(), bodyLimit - MIN_BODY_GAP_PIXELS);
        }
        if (edge == Edge.BOTTOM) {
            return new Bounds(region.getX(), bodyLimit + MIN_BODY_GAP_PIXELS,
                    region.getX() + region.getWidth(), image.getHeight());
        }
        if (edge == Edge.LEFT) {
            return new Bounds(0, region.getY(), bodyLimit - MIN_BODY_GAP_PIXELS, region.getY() + region.getHeight());
        }
        if (edge == Edge.RIGHT) {
            return new Bounds(bodyLimit + MIN_BODY_GAP_PIXELS, region.getY(), image.getWidth(), region.getY() + region.getHeight());
        }
        return null;
    }

    private static boolean hasUnconnectedInk(Bounds bounds, boolean[][] connected, BufferedImage image) {
        if (!bounds.isInside(image)) {
            return true;
        }
        for (int y = bounds.top; y < bounds.bottom; y++) {
            for (int x = bounds.left; x < bounds.right; x++) {
                if (isConservativeInk(image.getRGB(x, y)) && !connected[y - bounds.top][x - bounds.left]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PixelRegion paddedExpandedRegion(Edge edge, PixelRegion region, int minX, int minY, int maxX,
                                                      int maxY, int bodyLimit, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) bottom = Math.max(bottom, maxY + 2);
        if (edge == Edge.BOTTOM) top = Math.min(top, minY - 1);
        if (edge == Edge.LEFT) right = Math.max(right, maxX + 2);
        if (edge == Edge.RIGHT) left = Math.min(left, minX - 1);
        if ((edge == Edge.TOP && bodyLimit - bottom < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.BOTTOM && top - bodyLimit < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.LEFT && bodyLimit - right < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.RIGHT && left - bodyLimit < MIN_BODY_GAP_PIXELS)) {
            return region;
        }
        PixelRegion expanded = new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(), true);
        return remainsInEdgeBand(edge, expanded, image) ? expanded : region;
    }

    private static boolean remainsInEdgeBand(Edge edge, PixelRegion region, BufferedImage image) {
        if (edge == Edge.TOP) return region.getY() + region.getHeight() <= Math.ceil(EDGE_BAND * image.getHeight());
        if (edge == Edge.BOTTOM) return region.getY() >= Math.floor((1 - EDGE_BAND) * image.getHeight());
        if (edge == Edge.LEFT) return region.getX() + region.getWidth() <= Math.ceil(EDGE_BAND * image.getWidth());
        if (edge == Edge.RIGHT) return region.getX() >= Math.floor((1 - EDGE_BAND) * image.getWidth());
        return false;
    }

    private static boolean bandHasInk(BufferedImage image, int left, int top, int rightExclusive, int bottomExclusive) {
        if (left < 0 || top < 0 || rightExclusive > image.getWidth() || bottomExclusive > image.getHeight()
                || left >= rightExclusive || top >= bottomExclusive) {
            return true;
        }
        for (int y = top; y < bottomExclusive; y++) {
            for (int x = left; x < rightExclusive; x++) {
                if (isConservativeInk(image.getRGB(x, y))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasConservativeInk(BufferedImage image, PixelRegion region) {
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (isConservativeInk(image.getRGB(x, y))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean maskTouchesCandidateBox(BufferedImage image, PixelRegion region) {
        int right = region.getX() + region.getWidth() - 1;
        int bottom = region.getY() + region.getHeight() - 1;
        for (int x = region.getX(); x <= right; x++) {
            if (isDark(image.getRGB(x, region.getY())) || isDark(image.getRGB(x, bottom))) {
                return true;
            }
        }
        for (int y = region.getY(); y <= bottom; y++) {
            if (isDark(image.getRGB(region.getX(), y)) || isDark(image.getRGB(right, y))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDark(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red < 100 && green < 100 && blue < 100;
    }

    private static boolean isConservativeInk(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        return luminance < 210;
    }

    private enum Edge {
        TOP, BOTTOM, LEFT, RIGHT, NONE
    }

    private static final class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private int width() { return right - left; }
        private int height() { return bottom - top; }
        private boolean isInside(BufferedImage image) {
            return left >= 0 && top >= 0 && right <= image.getWidth() && bottom <= image.getHeight()
                    && left < right && top < bottom;
        }
    }

    public static final class PageLocateResult {
        private final String pageId;
        private final String status;
        private final List<EraseRegion> regions;
        private final BodyBoundary nearestBodyBoundary;

        public PageLocateResult(String pageId, String status, List<EraseRegion> regions, BodyBoundary nearestBodyBoundary) {
            this.pageId = pageId;
            this.status = status;
            this.regions = regions == null ? null : Collections.unmodifiableList(new ArrayList<EraseRegion>(regions));
            this.nearestBodyBoundary = nearestBodyBoundary;
        }

        public String getPageId() {
            return pageId;
        }

        public String getStatus() {
            return status;
        }

        public List<EraseRegion> getRegions() {
            return regions;
        }

        public BodyBoundary getNearestBodyBoundary() {
            return nearestBodyBoundary;
        }
    }

    public static final class ValidationResult {
        private final boolean accepted;
        private final List<PixelRegion> regions;
        private final List<String> reasons;

        private ValidationResult(boolean accepted, List<PixelRegion> regions, List<String> reasons) {
            this.accepted = accepted;
            this.regions = Collections.unmodifiableList(new ArrayList<PixelRegion>(regions));
            this.reasons = Collections.unmodifiableList(new ArrayList<String>(reasons));
        }

        private static ValidationResult rejected(String reason) {
            return new ValidationResult(false, Collections.<PixelRegion>emptyList(), Collections.singletonList(reason));
        }

        public static ValidationResult rejectedResult(String reason) {
            return rejected(reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public List<PixelRegion> getRegions() {
            return regions;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }

    public static final class PixelRegion {
        private final String pageId;
        private final String regionId;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        private final double confidence;
        private final boolean coordinateRescued;

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence) {
            this(pageId, regionId, x, y, width, height, x1, y1, x2, y2, confidence, false);
        }

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence, boolean coordinateRescued) {
            this.pageId = pageId;
            this.regionId = regionId;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.confidence = confidence;
            this.coordinateRescued = coordinateRescued;
        }

        public String getPageId() {
            return pageId;
        }

        public String getRegionId() {
            return regionId;
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

        public double getConfidence() {
            return confidence;
        }

        public boolean isCoordinateRescued() {
            return coordinateRescued;
        }
    }
}
