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

public final class InkMaskEraser {
    private InkMaskEraser() {
    }

    public static EraseOutcome erase(BufferedImage source, RegionValidator.PixelRegion region) {
        if (source == null || region == null) {
            return EraseOutcome.manual(null, new ApprovedMask(0, 0), "source and region are required");
        }
        String regionReason = invalidRegionReason(source, region);
        if (regionReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(source.getWidth(), source.getHeight()), regionReason);
        }
        String nonTargetReason = nonTargetReason(source, region);
        if (nonTargetReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(source.getWidth(), source.getHeight()), nonTargetReason);
        }
        boolean[][] mask = extractMask(source, region);
        if (!hasApprovedPixel(mask, region)) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), "no target ink found");
        }
        if (touchesRegionBoundary(mask, region)) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), "mask touches region boundary");
        }
        String geometryReason = invalidInkGeometryReason(mask, region);
        if (geometryReason != null) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), geometryReason);
        }

        BackgroundEstimator.Estimate estimate = BackgroundEstimator.estimate(source, region, mask);
        if (!estimate.isAccepted()) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), estimate.getReason());
        }

        BufferedImage candidate = copy(source);
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (mask[y][x]) {
                    candidate.setRGB(x, y, estimate.argbAt(x, y));
                }
            }
        }
        ApprovedMask approvedMask = ApprovedMask.from(region, source.getWidth(), source.getHeight(), mask);
        PixelDiffGate.GateResult diff = PixelDiffGate.check(source, candidate, approvedMask);
        if (!diff.isPassed()) {
            return EraseOutcome.manual(copy(source), approvedMask, diff.getReason());
        }
        ColorSeamGate.GateResult seam = ColorSeamGate.check(source, candidate, approvedMask);
        if (!seam.isPassed()) {
            return EraseOutcome.manual(copy(source), approvedMask, seam.getReason());
        }
        return new EraseOutcome(Status.SAFE_TO_ERASE, "erased", candidate, approvedMask);
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

    private static boolean[][] extractMask(BufferedImage source, RegionValidator.PixelRegion region) {
        int backgroundLum = medianLightLuminance(source, region);
        boolean[][] mask = new boolean[source.getHeight()][source.getWidth()];
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                BackgroundEstimator.ColorParts c = BackgroundEstimator.parts(source.getRGB(x, y));
                if (BackgroundEstimator.isInk(c, backgroundLum)) {
                    mask[y][x] = true;
                }
            }
        }
        return mask;
    }

    private static String nonTargetReason(BufferedImage source, RegionValidator.PixelRegion region) {
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                BackgroundEstimator.ColorParts c = BackgroundEstimator.parts(source.getRGB(x, y));
                if (BackgroundEstimator.isColoredNonTarget(c)) {
                    return "colored non-target inside region";
                }
                if (BackgroundEstimator.isGrayRule(c)) {
                    return "gray rule inside region";
                }
            }
        }
        return null;
    }

    private static int medianLightLuminance(BufferedImage source, RegionValidator.PixelRegion region) {
        int[] counts = new int[256];
        int total = 0;
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                BackgroundEstimator.ColorParts c = BackgroundEstimator.parts(source.getRGB(x, y));
                if (c.luminance >= 180 && !BackgroundEstimator.isColoredNonTarget(c)) {
                    counts[c.luminance]++;
                    total++;
                }
            }
        }
        if (total == 0) {
            return 255;
        }
        int seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen > total / 2) {
                return i;
            }
        }
        return 255;
    }

    private static boolean hasApprovedPixel(boolean[][] mask, RegionValidator.PixelRegion region) {
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (mask[y][x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean touchesRegionBoundary(boolean[][] mask, RegionValidator.PixelRegion region) {
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

    private static String invalidInkGeometryReason(boolean[][] mask, RegionValidator.PixelRegion region) {
        List<Component> components = components(mask, region);
        if (components.isEmpty()) {
            return "no target ink found";
        }
        int inkCount = 0;
        for (Component component : components) {
            inkCount += component.count;
            if (component.width() >= Math.max(16, region.getWidth() * 0.45) && component.height() <= 3) {
                return "long line component";
            }
            if (component.height() >= Math.max(10, region.getHeight() * 0.70) && component.width() <= 3) {
                return "long line component";
            }
            if (component.width() * component.height() >= region.getWidth() * region.getHeight() * 0.35
                    || component.count >= region.getWidth() * region.getHeight() * 0.25) {
                return "ink coverage too high";
            }
        }
        if (inkCount >= region.getWidth() * region.getHeight() * 0.28) {
            return "ink coverage too high";
        }
        if (hasLongHorizontalRun(mask, region)) {
            return "long line component";
        }
        int minCenter = Integer.MAX_VALUE;
        int maxCenter = Integer.MIN_VALUE;
        for (Component component : components) {
            int center = (component.top + component.bottom) / 2;
            minCenter = Math.min(minCenter, center);
            maxCenter = Math.max(maxCenter, center);
        }
        if (maxCenter - minCenter > Math.max(5, region.getHeight() / 3)) {
            return "multiple text lines";
        }
        if (hasCrossing(mask, region)) {
            return "table or crossing line";
        }
        return null;
    }

    private static boolean hasLongHorizontalRun(boolean[][] mask, RegionValidator.PixelRegion region) {
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
            validate(region, imageWidth, imageHeight, mask);
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

        private static void validate(RegionValidator.PixelRegion region, int imageWidth, int imageHeight, boolean[][] mask) {
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
                    if (x == region.getX() || y == region.getY() || x == right - 1 || y == bottom - 1) {
                        throw new IllegalArgumentException("mask touches region boundary");
                    }
                    count++;
                }
            }
            int regionArea = region.getWidth() * region.getHeight();
            if (regionArea > 0 && count > Math.max(6, regionArea * 35 / 100)) {
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
