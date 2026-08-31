package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.safety.RegionValidator;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class LineRestorerTest {
    @Test
    public void restoresOnlyWhenLineSegmentsMatchOnBothSides() {
        BufferedImage image = page();
        for (int x = 12; x <= 67; x++) {
            image.setRGB(x, 12, Color.BLACK.getRGB());
        }
        RegionValidator.PixelRegion region = validated(0.25, 0.05, 0.75, 0.20);
        InkMaskEraser.ApprovedMask mask = mask(image, region, 34, 10, 40, 14);
        int originalGap = image.getRGB(34, 12);

        LineRestorer.LineRestoreResult result = LineRestorer.restoreHorizontal(image, region, mask);

        assertTrue(result.isRestored());
        assertNotSame(image, result.getCandidate());
        assertEquals(originalGap, image.getRGB(34, 12));
        for (int x = 34; x <= 40; x++) {
            assertEquals(Color.BLACK.getRGB(), result.getCandidate().getRGB(x, 12));
            assertTrue(result.getLineMask().isApproved(x, 12));
        }
        BufferedImage exported = result.getCandidate();
        exported.setRGB(34, 12, Color.MAGENTA.getRGB());
        assertEquals(Color.BLACK.getRGB(), result.getCandidate().getRGB(34, 12));
    }

    @Test
    public void rejectsMismatchedCrossingAndGridLikeLines() {
        BufferedImage mismatch = page();
        for (int x = 12; x <= 33; x++) {
            mismatch.setRGB(x, 12, Color.BLACK.getRGB());
        }
        for (int x = 41; x <= 67; x++) {
            mismatch.setRGB(x, 13, Color.BLACK.getRGB());
        }
        assertRejected(mismatch, "line segments are inconsistent");

        BufferedImage crossing = page();
        for (int x = 12; x <= 67; x++) {
            crossing.setRGB(x, 12, Color.BLACK.getRGB());
        }
        for (int y = 4; y <= 24; y++) {
            crossing.setRGB(34, y, Color.BLACK.getRGB());
        }
        assertRejected(crossing, "grid or table line");
    }

    private void assertRejected(BufferedImage image, String reason) {
        RegionValidator.PixelRegion region = validated(0.25, 0.05, 0.75, 0.20);
        LineRestorer.LineRestoreResult result = LineRestorer.restoreHorizontal(image, region,
                mask(image, region, 34, 10, 40, 14));

        assertFalse(result.isRestored());
        assertTrue(result.getReason().contains(reason));
    }

    @Test
    public void rejectsInvalidMaskWithoutMutatingSource() {
        BufferedImage image = page();
        for (int x = 12; x <= 67; x++) {
            image.setRGB(x, 12, Color.BLACK.getRGB());
        }
        RegionValidator.PixelRegion region = validated(0.25, 0.05, 0.75, 0.20);
        boolean[][] outsideRaw = new boolean[image.getHeight()][image.getWidth()];
        outsideRaw[30][30] = true;

        assertIllegalArgument("outside region", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, image.getWidth(), image.getHeight(), outsideRaw);
            }
        });

        InkMaskEraser.ApprovedMask empty = InkMaskEraser.ApprovedMask.from(region, image.getWidth(), image.getHeight(),
                new boolean[image.getHeight()][image.getWidth()]);
        LineRestorer.LineRestoreResult result = LineRestorer.restoreHorizontal(image, region, empty);
        assertFalse(result.isRestored());
        assertTrue(result.getReason().contains("empty"));
        assertEquals(Color.BLACK.getRGB(), image.getRGB(12, 12));
    }

    private InkMaskEraser.ApprovedMask mask(BufferedImage image, RegionValidator.PixelRegion region, int left, int top, int right, int bottom) {
        boolean[][] mask = new boolean[image.getHeight()][image.getWidth()];
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y][x] = true;
                image.setRGB(x, y, new Color(245, 244, 238).getRGB());
            }
        }
        return InkMaskEraser.ApprovedMask.from(region, image.getWidth(), image.getHeight(), mask);
    }

    private RegionValidator.PixelRegion validated(double x1, double y1, double x2, double y2) {
        BufferedImage image = page();
        EraseRegion region = new EraseRegion();
        region.region_id = "r1";
        region.x1 = x1;
        region.y1 = y1;
        region.x2 = x2;
        region.y2 = y2;
        region.confidence = 0.99;
        BodyBoundary boundary = new BodyBoundary();
        boundary.y = 0.40;
        boundary.basis = "java";
        region.nearest_body_boundary = boundary;
        return RegionValidator.validate(new RegionValidator.PageLocateResult("page-1", "safe_to_erase",
                Arrays.asList(region)), image).getRegions().get(0);
    }

    private BufferedImage page() {
        BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color(245, 244, 238).getRGB());
            }
        }
        return image;
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
