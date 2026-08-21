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
import static org.junit.Assert.assertTrue;

public class LineRestorerTest {
    @Test
    public void restoresOnlyWhenLineSegmentsMatchOnBothSides() {
        BufferedImage image = page();
        for (int x = 12; x <= 67; x++) {
            image.setRGB(x, 12, Color.BLACK.getRGB());
        }
        boolean[][] mask = mask(image, 34, 10, 40, 15);
        RegionValidator.PixelRegion region = validated(0.25, 0.05, 0.75, 0.20);

        LineRestorer.LineRestoreResult result = LineRestorer.restoreHorizontal(image, region, mask);

        assertTrue(result.isRestored());
        for (int x = 34; x <= 40; x++) {
            assertEquals(Color.BLACK.getRGB(), image.getRGB(x, 12));
        }
        assertFalse(mask[12][33]);
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
        LineRestorer.LineRestoreResult result = LineRestorer.restoreHorizontal(image, validated(0.25, 0.05, 0.75, 0.20),
                mask(image, 34, 10, 40, 15));

        assertFalse(result.isRestored());
        assertTrue(result.getReason().contains(reason));
    }

    private boolean[][] mask(BufferedImage image, int left, int top, int right, int bottom) {
        boolean[][] mask = new boolean[image.getHeight()][image.getWidth()];
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                mask[y][x] = true;
                image.setRGB(x, y, new Color(245, 244, 238).getRGB());
            }
        }
        return mask;
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
        return RegionValidator.validate(new RegionValidator.PageLocateResult("page-1", "safe_to_erase",
                Arrays.asList(region), boundary), image).getRegions().get(0);
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
}
