package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.InkMaskEraser;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PixelDiffGateTest {
    @Test
    public void rejectsDimensionTypeMaskScopeAndMaskOutsidePixelChanges() {
        BufferedImage original = page(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage candidate = page(10, 10, BufferedImage.TYPE_INT_RGB);
        boolean[][] mask = new boolean[10][10];
        mask[4][4] = true;
        candidate.setRGB(4, 4, Color.BLACK.getRGB());
        RegionValidator.PixelRegion region = validated(original);
        InkMaskEraser.ApprovedMask approvedMask = InkMaskEraser.ApprovedMask.from(region, 10, 10, mask);

        assertTrue(PixelDiffGate.check(original, candidate, approvedMask).isPassed());

        assertFalse(PixelDiffGate.check(original, page(11, 10, BufferedImage.TYPE_INT_RGB), approvedMask).isPassed());
        assertFalse(PixelDiffGate.check(original, page(10, 10, BufferedImage.TYPE_INT_ARGB), approvedMask).isPassed());

        BufferedImage outsideChanged = page(10, 10, BufferedImage.TYPE_INT_RGB);
        outsideChanged.setRGB(1, 1, Color.WHITE.getRGB());
        outsideChanged.setRGB(4, 4, Color.WHITE.getRGB());
        assertFalse(PixelDiffGate.check(original, outsideChanged, approvedMask).isPassed());

        boolean[][] outsideRegion = new boolean[10][10];
        outsideRegion[8][8] = true;
        assertIllegalArgument("outside region", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 10, 10, outsideRegion);
            }
        });
    }

    @Test
    public void rejectsWhenApprovedMaskHasNoActualPixelChange() {
        BufferedImage original = page(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage candidate = page(10, 10, BufferedImage.TYPE_INT_RGB);
        boolean[][] mask = new boolean[10][10];
        mask[4][4] = true;

        PixelDiffGate.GateResult result = PixelDiffGate.check(original, candidate, InkMaskEraser.ApprovedMask.from(validated(original), 10, 10, mask));

        assertFalse(result.isPassed());
        assertTrue(result.getReason().contains("no approved pixel changed"));
    }

    @Test
    public void rejectsBroadMaskAndDoesNotExposeLegacyBooleanArrayEntrypoint() {
        for (Method method : PixelDiffGate.class.getDeclaredMethods()) {
            if ("check".equals(method.getName())) {
                Class<?>[] parameters = method.getParameterTypes();
                assertFalse(parameters.length == 3 && parameters[2].isArray());
            }
        }

        BufferedImage original = page(10, 10, BufferedImage.TYPE_INT_RGB);
        RegionValidator.PixelRegion region = pixelRegion(1, 1, 8, 8);
        boolean[][] broad = new boolean[10][10];
        for (int y = region.getY() + 1; y < region.getY() + region.getHeight() - 1; y++) {
            for (int x = region.getX() + 1; x < region.getX() + region.getWidth() - 1; x++) {
                broad[y][x] = true;
            }
        }

        assertIllegalArgument("mask coverage", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 10, 10, broad);
            }
        });
    }

    private RegionValidator.PixelRegion validated(BufferedImage image) {
        return pixelRegion(3, 3, 4, 4);
    }

    private RegionValidator.PixelRegion pixelRegion(int x, int y, int width, int height) {
        try {
            Constructor<RegionValidator.PixelRegion> constructor = RegionValidator.PixelRegion.class.getDeclaredConstructor(
                    String.class, String.class, int.class, int.class, int.class, int.class,
                    double.class, double.class, double.class, double.class, double.class);
            constructor.setAccessible(true);
            return constructor.newInstance("page-1", "r1", x, y, width, height, 0.3, 0.3, 0.7, 0.7, 0.99);
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

    private BufferedImage page(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        return image;
    }

    private interface ThrowingRunnable {
        void run();
    }
}
