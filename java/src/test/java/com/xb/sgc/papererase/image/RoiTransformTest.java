package com.xb.sgc.papererase.image;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.safety.RegionValidator;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class RoiTransformTest {
    @Test
    public void fromCandidateIncludesCandidateBlankAndNearestBodyLine() {
        BufferedImage image = blankPage(1000, 2000);
        drawText(image, 110, 90, 190, 150);
        BodyBoundary boundary = boundary(null, 0.20);
        RegionValidator.PixelRegion candidate = validatedCandidate(image, boundary);

        RoiTransform roi = RoiTransform.fromCandidate(image.getWidth(), image.getHeight(), candidate, boundary, 20);

        assertEquals(80, roi.getX());
        assertEquals(60, roi.getY());
        assertEquals(140, roi.getWidth());
        assertEquals(340, roi.getHeight());
        assertEquals(100.0 / 1000.0, roi.localRectToFullNormalized(1.0 / 7.0, 1.0 / 17.0, 6.0 / 7.0, 5.0 / 17.0).getX1(), 0.000001);
        assertEquals(80.0 / 2000.0, roi.localRectToFullNormalized(1.0 / 7.0, 1.0 / 17.0, 6.0 / 7.0, 5.0 / 17.0).getY1(), 0.000001);

        RoiTransform.LocalPoint local = roi.fullNormalizedToLocalPoint(0.10, 0.04);
        assertEquals(1.0 / 7.0, local.getX(), 0.000001);
        assertEquals(1.0 / 17.0, local.getY(), 0.000001);
    }

    @Test
    public void localRectToFullPixelsUsesFloorCeilClampWithoutOverflow() {
        RoiTransform roi = new RoiTransform(80, 60, 140, 340, 1000, 2000);

        RoiTransform.PixelRect rect = roi.localRectToFullPixels(0.25, 0.50, 1.20, 1.20);

        assertEquals(115, rect.getX());
        assertEquals(230, rect.getY());
        assertEquals(105, rect.getWidth());
        assertEquals(170, rect.getHeight());
    }

    @Test
    public void fromEdgeBuildsSafeRoiWhenCandidateIsAbsent() {
        BodyBoundary boundary = boundary(null, 0.18);

        RoiTransform roi = RoiTransform.fromEdge(1000, 2000, RoiTransform.PageEdge.TOP, boundary, 20);

        assertEquals(0, roi.getX());
        assertEquals(0, roi.getY());
        assertEquals(1000, roi.getWidth());
        assertEquals(380, roi.getHeight());
    }

    private RegionValidator.PixelRegion validatedCandidate(BufferedImage image, BodyBoundary boundary) {
        EraseRegion region = new EraseRegion();
        region.region_id = "r1";
        region.x1 = 0.10;
        region.y1 = 0.04;
        region.x2 = 0.20;
        region.y2 = 0.08;
        region.confidence = 0.99;
        return RegionValidator.validate(
                new RegionValidator.PageLocateResult("page-1", "safe_to_erase", Arrays.asList(region), boundary),
                image).getRegions().get(0);
    }

    private BodyBoundary boundary(Double x, Double y) {
        BodyBoundary boundary = new BodyBoundary();
        boundary.x = x;
        boundary.y = y;
        boundary.basis = "java";
        return boundary;
    }

    private BufferedImage blankPage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        return image;
    }

    private void drawText(BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
    }
}
