package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RegionValidator {
    private static final double EDGE_BAND = 0.20;
    private static final double MIN_BODY_GAP = 0.03;

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

        for (EraseRegion region : locateResult.getRegions()) {
            if (region == null) {
                reasons.add("region is required");
                continue;
            }
            if (locateResult.getPageId() == null || locateResult.getPageId().trim().length() == 0) {
                reasons.add("page_id is required");
                continue;
            }
            if (region.region_id == null || region.region_id.trim().length() == 0) {
                reasons.add("region_id is required");
                continue;
            }
            if (!regionIds.add(region.region_id)) {
                reasons.add("duplicate region_id: " + region.region_id);
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

            Edge edge = edge(region);
            if (edge == Edge.NONE) {
                reasons.add("region must be inside an edge band");
                continue;
            }
            if (!hasBodyGap(edge, region, locateResult.getNearestBodyBoundary())) {
                reasons.add("body blank gap is insufficient");
                continue;
            }

            PixelRegion pixelRegion = toPixelRegion(locateResult.getPageId(), region, image);
            if (pixelRegion.getWidth() <= 0 || pixelRegion.getHeight() <= 0
                    || pixelRegion.getX() < 0 || pixelRegion.getY() < 0
                    || pixelRegion.getX() + pixelRegion.getWidth() > image.getWidth()
                    || pixelRegion.getY() + pixelRegion.getHeight() > image.getHeight()) {
                reasons.add("coordinates map outside image bounds");
                continue;
            }
            if (maskTouchesCandidateBox(image, pixelRegion)) {
                reasons.add("ink mask touches candidate box");
                continue;
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

    private static PixelRegion toPixelRegion(String pageId, EraseRegion region, BufferedImage image) {
        int left = clamp((int) Math.floor(region.x1 * image.getWidth()), 0, image.getWidth());
        int top = clamp((int) Math.floor(region.y1 * image.getHeight()), 0, image.getHeight());
        int rightExclusive = clamp((int) Math.ceil(region.x2 * image.getWidth()), 0, image.getWidth());
        int bottomExclusive = clamp((int) Math.ceil(region.y2 * image.getHeight()), 0, image.getHeight());
        return new PixelRegion(pageId, region.region_id, left, top, rightExclusive - left, bottomExclusive - top,
                region.x1, region.y1, region.x2, region.y2, region.confidence);
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

    private static boolean hasBodyGap(Edge edge, EraseRegion region, BodyBoundary boundary) {
        if (boundary == null) {
            return false;
        }
        if (edge == Edge.TOP) {
            return boundary.y != null && finite(boundary.y) && boundary.y - region.y2 >= MIN_BODY_GAP;
        }
        if (edge == Edge.BOTTOM) {
            return boundary.y != null && finite(boundary.y) && region.y1 - boundary.y >= MIN_BODY_GAP;
        }
        if (edge == Edge.LEFT) {
            return boundary.x != null && finite(boundary.x) && boundary.x - region.x2 >= MIN_BODY_GAP;
        }
        if (edge == Edge.RIGHT) {
            return boundary.x != null && finite(boundary.x) && region.x1 - boundary.x >= MIN_BODY_GAP;
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

    private enum Edge {
        TOP, BOTTOM, LEFT, RIGHT, NONE
    }

    public static final class PageLocateResult {
        private final String pageId;
        private final String status;
        private final List<EraseRegion> regions;
        private final BodyBoundary nearestBodyBoundary;

        public PageLocateResult(String pageId, String status, List<EraseRegion> regions, BodyBoundary nearestBodyBoundary) {
            this.pageId = pageId;
            this.status = status;
            this.regions = Collections.unmodifiableList(new ArrayList<EraseRegion>(regions));
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

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence) {
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
    }
}
