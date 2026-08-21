package com.xb.sgc.papererase.safety;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class ColorSeamGate {
    private static final double MAX_NEIGHBOR_STDDEV = 12.0;
    private static final int MAX_SEAM_DISTANCE = 32;

    private ColorSeamGate() {
    }

    public static GateResult check(BufferedImage original, BufferedImage candidate, boolean[][] approvedMask) {
        if (original == null || candidate == null || approvedMask == null) {
            return GateResult.failed("original, candidate and approved mask are required");
        }
        if (original.getWidth() != candidate.getWidth() || original.getHeight() != candidate.getHeight()
                || approvedMask.length != original.getHeight()) {
            return GateResult.failed("dimensions differ");
        }
        List<Integer> neighborLum = new ArrayList<Integer>();
        for (int y = 0; y < original.getHeight(); y++) {
            if (approvedMask[y] == null || approvedMask[y].length != original.getWidth()) {
                return GateResult.failed("dimensions differ");
            }
            for (int x = 0; x < original.getWidth(); x++) {
                if (!approvedMask[y][x] || !touchesUnmaskedNeighbor(approvedMask, x, y)) {
                    continue;
                }
                for (int yy = Math.max(0, y - 2); yy <= Math.min(original.getHeight() - 1, y + 2); yy++) {
                    for (int xx = Math.max(0, x - 2); xx <= Math.min(original.getWidth() - 1, x + 2); xx++) {
                        if (!approvedMask[yy][xx]) {
                            int neighbor = original.getRGB(xx, yy);
                            neighborLum.add(luminance(neighbor));
                            if (distance(candidate.getRGB(x, y), neighbor) > MAX_SEAM_DISTANCE) {
                                return GateResult.failed("visible color seam");
                            }
                        }
                    }
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
