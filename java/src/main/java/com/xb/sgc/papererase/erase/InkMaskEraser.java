package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.safety.ColorSeamGate;
import com.xb.sgc.papererase.safety.PixelDiffGate;
import com.xb.sgc.papererase.safety.RegionValidator;

import java.awt.image.BufferedImage;

public final class InkMaskEraser {
    private InkMaskEraser() {
    }

    public static EraseOutcome erase(BufferedImage source, RegionValidator.PixelRegion region) {
        if (source == null || region == null) {
            return EraseOutcome.manual(null, new ApprovedMask(0, 0), "source and region are required");
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

        BackgroundEstimator.Estimate estimate = BackgroundEstimator.estimate(source, region, mask);
        if (!estimate.isAccepted()) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), estimate.getReason());
        }

        BufferedImage candidate = copy(source);
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (mask[y][x]) {
                    candidate.setRGB(x, y, estimate.getArgb());
                }
            }
        }
        PixelDiffGate.GateResult diff = PixelDiffGate.check(source, candidate, mask);
        if (!diff.isPassed()) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), diff.getReason());
        }
        ColorSeamGate.GateResult seam = ColorSeamGate.check(source, candidate, mask);
        if (!seam.isPassed()) {
            return EraseOutcome.manual(copy(source), new ApprovedMask(mask), seam.getReason());
        }
        return new EraseOutcome(Status.SAFE_TO_ERASE, "erased", candidate, new ApprovedMask(mask));
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

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return copy;
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
            this.candidate = candidate;
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
            return candidate;
        }

        public ApprovedMask getApprovedMask() {
            return approvedMask;
        }
    }

    public static final class ApprovedMask {
        private final boolean[][] mask;

        private ApprovedMask(int width, int height) {
            this.mask = new boolean[height][width];
        }

        public ApprovedMask(boolean[][] mask) {
            this.mask = copy(mask);
        }

        public boolean isApproved(int x, int y) {
            return y >= 0 && y < mask.length && x >= 0 && mask.length > 0 && x < mask[0].length && mask[y][x];
        }

        public boolean[][] toArray() {
            return copy(mask);
        }

        private static boolean[][] copy(boolean[][] source) {
            boolean[][] copy = new boolean[source.length][];
            for (int y = 0; y < source.length; y++) {
                copy[y] = new boolean[source[y].length];
                System.arraycopy(source[y], 0, copy[y], 0, source[y].length);
            }
            return copy;
        }
    }
}
