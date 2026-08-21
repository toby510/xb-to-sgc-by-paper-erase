package com.xb.sgc.papererase.safety;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ColorSeamGateTest {
    @Test
    public void acceptsStableAndSlightMonotonicGradientRepairs() {
        BufferedImage original = gradientPage(40, 30, 240, 244);
        BufferedImage candidate = copy(original);
        boolean[][] mask = mask(40, 30, 16, 10, 23, 15);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                candidate.setRGB(x, y, new Color(242 + (x - 16) / 3, 242 + (x - 16) / 3, 238).getRGB());
            }
        }

        assertTrue(ColorSeamGate.check(original, candidate, mask).isPassed());
    }

    @Test
    public void rejectsVisibleSeamAndComplexLocalVariance() {
        BufferedImage original = gradientPage(40, 30, 240, 240);
        BufferedImage seam = copy(original);
        boolean[][] mask = mask(40, 30, 16, 10, 23, 15);
        for (int y = 10; y <= 15; y++) {
            for (int x = 16; x <= 23; x++) {
                seam.setRGB(x, y, Color.WHITE.getRGB());
            }
        }

        assertFalse(ColorSeamGate.check(original, seam, mask).isPassed());

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

        assertFalse(ColorSeamGate.check(complex, complexCandidate, mask).isPassed());
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
}
