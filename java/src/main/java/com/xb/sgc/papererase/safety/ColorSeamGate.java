package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.InkMaskEraser;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class ColorSeamGate {
    private static final double MAX_NEIGHBOR_STDDEV = 12.0;
    private static final int MAX_SEAM_DISTANCE = 32;
    private static final int MAX_PIXEL_DISTANCE = 36;

    private ColorSeamGate() {
    }

    public static GateResult check(BufferedImage original, BufferedImage candidate, InkMaskEraser.ApprovedMask approvedMask) {
        if (original == null || candidate == null || approvedMask == null) {
            return GateResult.failed("original, candidate and approved mask are required");
        }
        if (original.getWidth() != candidate.getWidth() || original.getHeight() != candidate.getHeight()
                || approvedMask.getImageWidth() != original.getWidth() || approvedMask.getImageHeight() != original.getHeight()) {
            return GateResult.failed("dimensions differ");
        }
        if (original.getType() != candidate.getType()) {
            return GateResult.failed("image types differ");
        }
        if (approvedMask.countApproved() == 0) {
            return GateResult.failed("approved mask is empty");
        }
        boolean[][] mask = approvedMask.toArray();
        List<Integer> neighborLum = new ArrayList<Integer>();
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                if (!mask[y][x]) {
                    continue;
                }
                if (!approvedMask.containsRegion(x, y)) {
                    return GateResult.failed("approved mask outside region");
                }
                LocalStats stats = localStats(original, mask, x, y);
                if (stats.count == 0) {
                    return GateResult.failed("no local seam samples");
                }
                neighborLum.addAll(stats.luminances);
                if (stats.stddev() > MAX_NEIGHBOR_STDDEV) {
                    return GateResult.failed("complex local variance");
                }
                if (touchesUnmaskedNeighbor(mask, x, y) && distance(candidate.getRGB(x, y), stats.averageArgb()) > MAX_SEAM_DISTANCE) {
                    return GateResult.failed("visible color seam");
                }
                if (distance(candidate.getRGB(x, y), stats.averageArgb()) > MAX_PIXEL_DISTANCE) {
                    return GateResult.failed("interior color artifact");
                }
            }
        }
        if (neighborLum.isEmpty()) {
            return GateResult.failed("no local seam samples");
        }
        if (stddev(neighborLum) > MAX_NEIGHBOR_STDDEV) {
            return GateResult.failed("complex local variance");
        }
        return GateResult.passed();
    }

    private static LocalStats localStats(BufferedImage original, boolean[][] mask, int x, int y) {
        LocalStats stats = new LocalStats();
        for (int radius = 1; radius <= 8 && stats.count < 6; radius++) {
            for (int yy = Math.max(0, y - radius); yy <= Math.min(original.getHeight() - 1, y + radius); yy++) {
                for (int xx = Math.max(0, x - radius); xx <= Math.min(original.getWidth() - 1, x + radius); xx++) {
                    if (!mask[yy][xx]) {
                        stats.add(original.getRGB(xx, yy));
                    }
                }
            }
        }
        return stats;
    }

    private static boolean touchesUnmaskedNeighbor(boolean[][] mask, int x, int y) {
        return x == 0 || y == 0 || y == mask.length - 1 || x == mask[y].length - 1
                || !mask[y][x - 1] || !mask[y][x + 1] || !mask[y - 1][x] || !mask[y + 1][x];
    }

    private static int luminance(int argb) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private static int distance(int a, int b) {
        int ar = (a >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF;
        int bg = (b >>> 8) & 0xFF;
        int bb = b & 0xFF;
        return Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
    }

    private static double stddev(List<Integer> values) {
        double mean = 0;
        for (Integer value : values) {
            mean += value.intValue();
        }
        mean /= values.size();
        double sum = 0;
        for (Integer value : values) {
            double d = value.intValue() - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.size());
    }

    private static final class LocalStats {
        int count;
        int redSum;
        int greenSum;
        int blueSum;
        final List<Integer> luminances = new ArrayList<Integer>();

        void add(int argb) {
            count++;
            redSum += (argb >>> 16) & 0xFF;
            greenSum += (argb >>> 8) & 0xFF;
            blueSum += argb & 0xFF;
            luminances.add(luminance(argb));
        }

        int averageArgb() {
            if (count == 0) {
                return 0;
            }
            return 0xFF000000 | ((redSum / count) << 16) | ((greenSum / count) << 8) | (blueSum / count);
        }

        double stddev() {
            return luminances.isEmpty() ? 0.0 : ColorSeamGate.stddev(luminances);
        }
    }

    public static final class GateResult {
        private final boolean passed;
        private final String reason;

        private GateResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        static GateResult passed() {
            return new GateResult(true, "passed");
        }

        static GateResult failed(String reason) {
            return new GateResult(false, reason);
        }

        public boolean isPassed() {
            return passed;
        }

        public String getReason() {
            return reason;
        }
    }
}
