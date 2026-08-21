package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.safety.RegionValidator;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BackgroundEstimator {
    private static final int MIN_SAMPLES = 40;
    private static final double MAX_LOCAL_STDDEV = 8.0;

    private BackgroundEstimator() {
    }

    public static Estimate estimate(BufferedImage source, RegionValidator.PixelRegion region, boolean[][] mask) {
        List<Integer> reds = new ArrayList<Integer>();
        List<Integer> greens = new ArrayList<Integer>();
        List<Integer> blues = new ArrayList<Integer>();
        List<Integer> alphas = new ArrayList<Integer>();
        List<Integer> luminances = new ArrayList<Integer>();

        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                ColorParts c = parts(argb);
                if (mask[y][x]) {
                    continue;
                }
                if (isColoredNonTarget(c) || isGrayRule(c)) {
                    return Estimate.rejected("colored non-target or gray rule inside region");
                }
                if (c.luminance < 205) {
                    continue;
                }
                reds.add(c.red);
                greens.add(c.green);
                blues.add(c.blue);
                alphas.add(c.alpha);
                luminances.add(c.luminance);
            }
        }

        if (luminances.size() < MIN_SAMPLES) {
            return Estimate.rejected("insufficient background samples");
        }
        double stddev = stddev(luminances);
        if (stddev > MAX_LOCAL_STDDEV) {
            return Estimate.rejected("complex background variance");
        }

        return Estimate.accepted(argb(median(alphas), median(reds), median(greens), median(blues)));
    }

    static boolean isInk(ColorParts c, int backgroundLum) {
        return c.luminance <= backgroundLum - 25 && c.red <= 180 && c.green <= 180 && c.blue <= 180;
    }

    static boolean isColoredNonTarget(ColorParts c) {
        int max = Math.max(c.red, Math.max(c.green, c.blue));
        int min = Math.min(c.red, Math.min(c.green, c.blue));
        return max - min > 60 && c.luminance < 235;
    }

    static boolean isGrayRule(ColorParts c) {
        int max = Math.max(c.red, Math.max(c.green, c.blue));
        int min = Math.min(c.red, Math.min(c.green, c.blue));
        return max - min <= 12 && c.luminance >= 155 && c.luminance < 205;
    }

    static ColorParts parts(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return new ColorParts(alpha, red, green, blue);
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

    private static int median(List<Integer> values) {
        Collections.sort(values);
        return values.get(values.size() / 2).intValue();
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    static final class ColorParts {
        final int alpha;
        final int red;
        final int green;
        final int blue;
        final int luminance;

        ColorParts(int alpha, int red, int green, int blue) {
            this.alpha = alpha;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        }
    }

    public static final class Estimate {
        private final boolean accepted;
        private final int argb;
        private final String reason;

        private Estimate(boolean accepted, int argb, String reason) {
            this.accepted = accepted;
            this.argb = argb;
            this.reason = reason;
        }

        static Estimate accepted(int argb) {
            return new Estimate(true, argb, "stable background");
        }

        static Estimate rejected(String reason) {
            return new Estimate(false, 0, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public int getArgb() {
            return argb;
        }

        public String getReason() {
            return reason;
        }
    }
}
