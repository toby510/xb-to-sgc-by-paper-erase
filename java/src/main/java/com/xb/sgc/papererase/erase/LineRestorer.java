package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.safety.RegionValidator;
import com.xb.sgc.papererase.safety.PixelDiffGate;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

/**
 * 细线恢复辅助器：处理候选区域内被擦除器覆盖的独立横线，并用 PixelDiffGate 验证越界安全。
 * 它不是语义识别器，只有上游已批准的像素区域才能进入本类。
 */
public final class LineRestorer {
    private LineRestorer() {
    }

    public static LineRestoreResult restoreHorizontal(BufferedImage image, RegionValidator.PixelRegion region, InkMaskEraser.ApprovedMask approvedMask) {
        if (image == null || region == null || approvedMask == null) {
            return LineRestoreResult.rejected("image, region and mask are required");
        }
        String invalid = invalidInputs(image, region, approvedMask);
        if (invalid != null) {
            return LineRestoreResult.rejected(invalid);
        }
        boolean[][] rawMask = approvedMask.toArray();
        Bounds bounds = bounds(rawMask);
        if (bounds == null) {
            return LineRestoreResult.rejected("approved mask is empty");
        }
        int lineY = matchingLineY(image, region, bounds);
        if (lineY < 0) {
            return LineRestoreResult.rejected("line segments are inconsistent");
        }
        if (hasVerticalCrossing(image, bounds, lineY)) {
            return LineRestoreResult.rejected("grid or table line detected");
        }
        int color = lineColor(image, bounds, lineY);
        BufferedImage candidate = copy(image);
        boolean[][] lineMask = new boolean[image.getHeight()][image.getWidth()];
        for (int x = bounds.left; x <= bounds.right; x++) {
            if (rawMask[lineY][x]) {
                candidate.setRGB(x, lineY, color);
                lineMask[lineY][x] = true;
            }
        }
        InkMaskEraser.ApprovedMask approvedLineMask = InkMaskEraser.ApprovedMask.from(region, image.getWidth(), image.getHeight(), lineMask);
        PixelDiffGate.GateResult diff = PixelDiffGate.check(image, candidate, approvedLineMask);
        if (!diff.isPassed()) {
            return LineRestoreResult.rejected(diff.getReason());
        }
        return LineRestoreResult.restored(candidate, approvedLineMask);
    }

    private static String invalidInputs(BufferedImage image, RegionValidator.PixelRegion region, InkMaskEraser.ApprovedMask mask) {
        long right = (long) region.getX() + region.getWidth();
        long bottom = (long) region.getY() + region.getHeight();
        if (region.getX() < 0 || region.getY() < 0 || region.getWidth() <= 0 || region.getHeight() <= 0
                || right > image.getWidth() || bottom > image.getHeight()) {
            return "region outside image";
        }
        if (mask.getImageWidth() != image.getWidth() || mask.getImageHeight() != image.getHeight()) {
            return "mask dimensions differ";
        }
        boolean[][] raw = mask.toArray();
        Bounds bounds = bounds(raw);
        if (bounds != null && (bounds.left < region.getX() || bounds.top < region.getY()
                || bounds.right >= right || bounds.bottom >= bottom)) {
            return "mask outside region";
        }
        return null;
    }

    private static int matchingLineY(BufferedImage image, RegionValidator.PixelRegion region, Bounds bounds) {
        for (int y = Math.max(region.getY(), bounds.top - 2);
             y <= Math.min(region.getY() + region.getHeight() - 1, bounds.bottom + 2); y++) {
            int leftColor = continuousLeftLineColor(image, region.getX(), bounds.left - 1, y);
            int rightColor = continuousRightLineColor(image, bounds.right + 1, region.getX() + region.getWidth() - 1, y);
            if (leftColor != Integer.MIN_VALUE && rightColor != Integer.MIN_VALUE && close(leftColor, rightColor)) {
                return y;
            }
        }
        return -1;
    }

    private static int continuousLeftLineColor(BufferedImage image, int minX, int endX, int y) {
        int count = 0;
        int color = Integer.MIN_VALUE;
        for (int x = endX; x >= minX; x--) {
            if (isDark(image.getRGB(x, y))) {
                color = image.getRGB(x, y);
                count++;
            } else if (count > 0) {
                break;
            }
        }
        return count >= 6 ? color : Integer.MIN_VALUE;
    }

    private static int continuousRightLineColor(BufferedImage image, int startX, int maxX, int y) {
        int count = 0;
        int color = Integer.MIN_VALUE;
        for (int x = startX; x <= maxX; x++) {
            if (isDark(image.getRGB(x, y))) {
                color = image.getRGB(x, y);
                count++;
            } else if (count > 0) {
                break;
            }
        }
        return count >= 6 ? color : Integer.MIN_VALUE;
    }

    private static boolean hasVerticalCrossing(BufferedImage image, Bounds bounds, int lineY) {
        for (int x = bounds.left; x <= bounds.right; x++) {
            int up = 0;
            for (int y = lineY - 1; y >= Math.max(0, lineY - 8); y--) {
                if (isDark(image.getRGB(x, y))) {
                    up++;
                }
            }
            int down = 0;
            for (int y = lineY + 1; y <= Math.min(image.getHeight() - 1, lineY + 8); y++) {
                if (isDark(image.getRGB(x, y))) {
                    down++;
                }
            }
            if (up >= 3 && down >= 3) {
                return true;
            }
        }
        return false;
    }

    private static int lineColor(BufferedImage image, Bounds bounds, int y) {
        int left = image.getRGB(Math.max(0, bounds.left - 1), y);
        int right = image.getRGB(Math.min(image.getWidth() - 1, bounds.right + 1), y);
        return close(left, right) ? left : right;
    }

    private static Bounds bounds(boolean[][] mask) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int y = 0; y < mask.length; y++) {
            for (int x = 0; x < mask[y].length; x++) {
                if (mask[y][x]) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return left == Integer.MAX_VALUE ? null : new Bounds(left, top, right, bottom);
    }

    private static boolean isDark(int argb) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return red < 80 && green < 80 && blue < 80;
    }

    private static boolean close(int a, int b) {
        int ar = (a >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF;
        int bg = (b >>> 8) & 0xFF;
        int bb = b & 0xFF;
        return Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb) <= 18;
    }

    private static BufferedImage copy(BufferedImage source) {
        if (source == null) {
            return null;
        }
        ColorModel colorModel = source.getColorModel();
        WritableRaster raster = source.copyData(null);
        return new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
    }

    private static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static final class LineRestoreResult {
        private final boolean restored;
        private final String reason;
        private final BufferedImage candidate;
        private final InkMaskEraser.ApprovedMask lineMask;

        private LineRestoreResult(boolean restored, String reason, BufferedImage candidate, InkMaskEraser.ApprovedMask lineMask) {
            this.restored = restored;
            this.reason = reason;
            this.candidate = copy(candidate);
            this.lineMask = lineMask;
        }

        static LineRestoreResult restored(BufferedImage candidate, InkMaskEraser.ApprovedMask lineMask) {
            return new LineRestoreResult(true, "restored", candidate, lineMask);
        }

        static LineRestoreResult rejected(String reason) {
            return new LineRestoreResult(false, reason, null, null);
        }

        public boolean isRestored() {
            return restored;
        }

        public String getReason() {
            return reason;
        }

        public BufferedImage getCandidate() {
            return copy(candidate);
        }

        public InkMaskEraser.ApprovedMask getLineMask() {
            return lineMask;
        }
    }
}
