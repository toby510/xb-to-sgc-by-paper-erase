package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegionValidatorTest {
    @Test
    public void validateAcceptsEdgeRegionAndReturnsImmutablePixelBounds() {
        BufferedImage image = blankPage();
        drawText(image, 11, 9, 18, 14);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.16)),
                image);

        assertTrue(result.isAccepted());
        assertEquals(1, result.getRegions().size());
        RegionValidator.PixelRegion pixelRegion = result.getRegions().get(0);
        assertEquals("page-1", pixelRegion.getPageId());
        assertEquals("r1", pixelRegion.getRegionId());
        assertEquals(10, pixelRegion.getX());
        assertEquals(8, pixelRegion.getY());
        assertEquals(10, pixelRegion.getWidth());
        assertEquals(8, pixelRegion.getHeight());

        try {
            result.getRegions().add(pixelRegion);
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected validated pixel bounds to be immutable");
    }

    @Test
    public void validateRejectsInvalidCoordinatesAndDuplicateRegionIds() {
        assertRejected(region("r1", Double.NaN, 0.04, 0.20, 0.08), "finite");
        assertRejected(region("r1", 0.10, 0.04, Double.POSITIVE_INFINITY, 0.08), "finite");
        EraseRegion infiniteConfidence = region("r1", 0.10, 0.04, 0.20, 0.08);
        infiniteConfidence.confidence = Double.POSITIVE_INFINITY;
        assertRejected(infiniteConfidence, "confidence must be finite");
        assertRejected(region("r1", -0.01, 0.04, 0.20, 0.08), "0 <= x1 < x2 <= 1");
        assertRejected(region("r1", 0.10, 0.04, 0.10, 0.08), "strictly positive area");

        BufferedImage image = blankPage();
        drawText(image, 11, 9, 18, 14);
        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", Arrays.asList(
                        region("r1", 0.10, 0.04, 0.20, 0.08),
                        region("r1", 0.30, 0.04, 0.40, 0.08)), boundary(null, 0.16)),
                image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains("duplicate region_id"));
    }

    @Test
    public void validateRejectsNonEdgeRegionInsufficientBodyGapAndInkTouchingBox() {
        assertRejected(region("r1", 0.40, 0.40, 0.50, 0.45), "edge band");
        assertRejected(region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.10), "body blank gap");

        BufferedImage image = blankPage();
        drawText(image, 10, 8, 20, 15);
        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.16)),
                image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains("mask touches candidate box"));
    }

    private void assertRejected(EraseRegion region, String reason) {
        assertRejected(region, boundary(null, 0.16), reason);
    }

    private void assertRejected(EraseRegion region, BodyBoundary boundary, String reason) {
        BufferedImage image = blankPage();
        if (Double.isFinite(region.x1) && Double.isFinite(region.x2)
                && Double.isFinite(region.y1) && Double.isFinite(region.y2)
                && region.x1 >= 0 && region.x2 <= 1 && region.x1 < region.x2
                && region.y1 >= 0 && region.y2 <= 1 && region.y1 < region.y2) {
            int left = (int) Math.floor(region.x1 * image.getWidth());
            int top = (int) Math.floor(region.y1 * image.getHeight());
            int right = (int) Math.ceil(region.x2 * image.getWidth()) - 2;
            int bottom = (int) Math.ceil(region.y2 * image.getHeight()) - 2;
            drawText(image, left + 1, top + 1, Math.max(left + 1, right), Math.max(top + 1, bottom));
        }

        RegionValidator.ValidationResult result = RegionValidator.validate(locate("page-1", region, boundary), image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains(reason));
    }

    private RegionValidator.PageLocateResult locate(String pageId, EraseRegion region, BodyBoundary boundary) {
        return locate(pageId, Arrays.asList(region), boundary);
    }

    private RegionValidator.PageLocateResult locate(String pageId, java.util.List<EraseRegion> regions, BodyBoundary boundary) {
        return new RegionValidator.PageLocateResult(pageId, "safe_to_erase", regions, boundary);
    }

    private EraseRegion region(String id, double x1, double y1, double x2, double y2) {
        EraseRegion region = new EraseRegion();
        region.region_id = id;
        region.x1 = x1;
        region.y1 = y1;
        region.x2 = x2;
        region.y2 = y2;
        region.confidence = 0.99;
        region.page_number_text = "1";
        region.same_line_metadata = "";
        region.safety_margin = "clear";
        return region;
    }

    private BodyBoundary boundary(Double x, Double y) {
        BodyBoundary boundary = new BodyBoundary();
        boundary.x = x;
        boundary.y = y;
        boundary.basis = "java";
        return boundary;
    }

    private BufferedImage blankPage() {
        BufferedImage image = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
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
                if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
        }
    }
}
