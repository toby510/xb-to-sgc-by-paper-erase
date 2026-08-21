package com.xb.sgc.papererase.safety;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PixelDiffGateTest {
    @Test
    public void rejectsDimensionTypeMaskAndMaskOutsidePixelChanges() {
        BufferedImage original = page(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage candidate = page(10, 10, BufferedImage.TYPE_INT_RGB);
        boolean[][] mask = new boolean[10][10];
        mask[4][4] = true;
        candidate.setRGB(4, 4, Color.WHITE.getRGB());

        assertTrue(PixelDiffGate.check(original, candidate, mask).isPassed());

        assertFalse(PixelDiffGate.check(original, page(11, 10, BufferedImage.TYPE_INT_RGB), mask).isPassed());
        assertFalse(PixelDiffGate.check(original, page(10, 10, BufferedImage.TYPE_INT_ARGB), mask).isPassed());
        assertFalse(PixelDiffGate.check(original, candidate, new boolean[9][10]).isPassed());

        BufferedImage outsideChanged = page(10, 10, BufferedImage.TYPE_INT_RGB);
        outsideChanged.setRGB(1, 1, Color.WHITE.getRGB());
        outsideChanged.setRGB(4, 4, Color.WHITE.getRGB());
        assertFalse(PixelDiffGate.check(original, outsideChanged, mask).isPassed());
    }

    @Test
    public void rejectsWhenApprovedMaskHasNoActualPixelChange() {
        BufferedImage original = page(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage candidate = page(10, 10, BufferedImage.TYPE_INT_RGB);
        boolean[][] mask = new boolean[10][10];
        mask[4][4] = true;

        PixelDiffGate.GateResult result = PixelDiffGate.check(original, candidate, mask);

        assertFalse(result.isPassed());
        assertTrue(result.getReason().contains("no approved pixel changed"));
    }

    private BufferedImage page(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
        return image;
    }
}
