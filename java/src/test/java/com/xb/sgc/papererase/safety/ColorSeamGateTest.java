package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.InkMaskEraser;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ColorSeamGateTest {
    @Test
    public void acceptsStableAndSlightMonotonicGradientRepairs() {
        BufferedImage original = gradientPage(40, 30, 240, 244);
        BufferedImage candidate = copy(original);
        RegionValidator.PixelRegion region = validated(original);
        boolean[][] mask = mask(40, 30, 16, 10, 23, 15);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                candidate.setRGB(x, y, new Color(242 + (x - 16) / 3, 242 + (x - 16) / 3, 238).getRGB());
            }
        }

        assertTrue(ColorSeamGate.check(original, candidate, InkMaskEraser.ApprovedMask.from(region, 40, 30, mask)).isPassed());
    }

    @Test
    public void rejectsVisibleSeamAndComplexLocalVariance() {
        BufferedImage original = gradientPage(40, 30, 240, 240);
        BufferedImage seam = copy(original);
        RegionValidator.PixelRegion region = validated(original);
        boolean[][] mask = mask(40, 30, 16, 10, 23, 15);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                seam.setRGB(x, y, Color.WHITE.getRGB());
            }
        }

        assertFalse(ColorSeamGate.check(original, seam, InkMaskEraser.ApprovedMask.from(region, 40, 30, mask)).isPassed());

        BufferedImage complex = gradientPage(40, 30, 240, 240);
        for (int y = 8; y <= 17; y++) {
            for (int x = 14; x <= 25; x++) {
                int v = ((x + y) % 2 == 0) ? 210 : 252;
                complex.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        BufferedImage complexCandidate = copy(complex);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                complexCandidate.setRGB(x, y, new Color(240, 240, 240).getRGB());
            }
        }

        assertFalse(ColorSeamGate.check(complex, complexCandidate, InkMaskEraser.ApprovedMask.from(region, 40, 30, mask)).isPassed());
    }

    @Test
    public void rejectsInteriorArtifactsEmptyFullEdgeAndImageMismatches() {
        BufferedImage original = gradientPage(40, 30, 240, 244);
        RegionValidator.PixelRegion region = validated(original);
        boolean[][] mask = mask(40, 30, 16, 10, 23, 15);
        InkMaskEraser.ApprovedMask approvedMask = InkMaskEraser.ApprovedMask.from(region, 40, 30, mask);

        BufferedImage interiorArtifact = copy(original);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                interiorArtifact.setRGB(x, y, new Color(242, 242, 238).getRGB());
            }
        }
        interiorArtifact.setRGB(19, 12, Color.WHITE.getRGB());
        assertFalse(ColorSeamGate.check(original, interiorArtifact, approvedMask).isPassed());

        assertFalse(ColorSeamGate.check(original, new BufferedImage(41, 30, BufferedImage.TYPE_INT_RGB), approvedMask).isPassed());
        assertFalse(ColorSeamGate.check(original, new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB), approvedMask).isPassed());

        boolean[][] empty = new boolean[30][40];
        assertFalse(ColorSeamGate.check(original, copy(original), InkMaskEraser.ApprovedMask.from(region, 40, 30, empty)).isPassed());

        boolean[][] edge = new boolean[30][40];
        edge[6][12] = true;
        assertIllegalArgument("mask touches region boundary", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 40, 30, edge);
            }
        });
    }

    private boolean[][] mask(int width, int height, int left, int top, int right, int bottom) {
        boolean[][] mask = new boolean[height][width];
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y][x] = true;
            }
        }
        return mask;
    }

    private BufferedImage gradientPage(int width, int height, int leftValue, int rightValue) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = leftValue + (rightValue - leftValue) * x / Math.max(1, width - 1);
                image.setRGB(x, y, new Color(v, v, 238).getRGB());
            }
        }
        return image;
    }

    private BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return copy;
    }

    private RegionValidator.PixelRegion validated(BufferedImage image) {
        try {
            Constructor<RegionValidator.PixelRegion> constructor = RegionValidator.PixelRegion.class.getDeclaredConstructor(
                    String.class, String.class, int.class, int.class, int.class, int.class,
                    double.class, double.class, double.class, double.class, double.class);
            constructor.setAccessible(true);
            return constructor.newInstance("page-1", "r1", 12, 6, 16, 14, 0.3, 0.2, 0.7, 0.6, 0.99);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void assertIllegalArgument(String messagePart, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(messagePart));
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException containing: " + messagePart);
    }

    private interface ThrowingRunnable {
        void run();
    }
}
