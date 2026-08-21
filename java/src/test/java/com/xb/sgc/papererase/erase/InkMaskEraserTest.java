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

public class InkMaskEraserTest {
    @Test
    public void erasesOnlyApprovedInkInsideValidatedRegion() {
        BufferedImage source = page(80, 80, new Color(245, 244, 238));
        drawAntiAliasedDigit(source, 30, 8);
        source.setRGB(5, 40, Color.BLACK.getRGB());
        source.setRGB(32, 30, Color.BLACK.getRGB());
        int outsideDot = source.getRGB(5, 40);
        int nearbyBody = source.getRGB(32, 30);
        int originalInk = source.getRGB(33, 12);
        RegionValidator.PixelRegion region = validated(source, 0.35, 0.05, 0.55, 0.20, 0.40);

        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(source, region);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, outcome.getStatus());
        assertNotSame(source, outcome.getCandidate());
        assertEquals(originalInk, source.getRGB(33, 12));
        assertEquals(outsideDot, outcome.getCandidate().getRGB(5, 40));
        assertEquals(nearbyBody, outcome.getCandidate().getRGB(32, 30));
        assertTrue(outcome.getApprovedMask().isApproved(33, 12));
        assertTrue(outcome.getApprovedMask().isApproved(35, 13));
        assertFalse(outcome.getApprovedMask().isApproved(32, 30));
        assertTrue(outcome.getCandidate().getRGB(33, 12) != originalInk);

        boolean[][] exported = outcome.getApprovedMask().toArray();
        exported[12][33] = false;
        assertTrue(outcome.getApprovedMask().isApproved(33, 12));
    }

    @Test
    public void rejectsComplexBackgroundColoredStampInsufficientSamplesAndMaskTouchingRegionEdge() {
        BufferedImage textured = page(80, 80, new Color(245, 244, 238));
        for (int y = 4; y < 20; y++) {
            for (int x = 28; x < 48; x++) {
                int v = ((x + y) % 2 == 0) ? 230 : 252;
                textured.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        drawAntiAliasedDigit(textured, 30, 8);
        assertManual(textured, 0.35, 0.05, 0.60, 0.20, 0.40, "complex background");

        BufferedImage stamped = page(80, 80, new Color(245, 244, 238));
        drawAntiAliasedDigit(stamped, 30, 8);
        for (int y = 13; y <= 16; y++) {
            for (int x = 43; x <= 46; x++) {
                stamped.setRGB(x, y, Color.RED.getRGB());
            }
        }
        assertManual(stamped, 0.35, 0.05, 0.65, 0.20, 0.40, "colored non-target");

        BufferedImage grayRule = page(80, 80, new Color(245, 244, 238));
        drawAntiAliasedDigit(grayRule, 30, 8);
        for (int x = 42; x <= 48; x++) {
            grayRule.setRGB(x, 12, new Color(170, 170, 170).getRGB());
        }
        assertManual(grayRule, 0.35, 0.05, 0.65, 0.20, 0.40, "gray rule");

        BufferedImage tiny = page(80, 80, new Color(245, 244, 238));
        tiny.setRGB(30, 8, Color.BLACK.getRGB());
        assertManual(tiny, 0.35, 0.05, 0.425, 0.125, 0.40, "insufficient background samples");

        BufferedImage touching = page(80, 80, new Color(245, 244, 238));
        touching.setRGB(28, 8, new Color(130, 130, 130).getRGB());
        assertManual(touching, 0.35, 0.05, 0.55, 0.20, 0.40, "mask touches region boundary");
    }

    @Test
    public void preservesArgbTransparencyAndDoesNotConvertWholeImage() {
        BufferedImage source = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        int paper = new Color(245, 244, 238, 210).getRGB();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, paper);
            }
        }
        source.setRGB(31, 9, new Color(20, 20, 20, 210).getRGB());
        source.setRGB(5, 40, new Color(10, 10, 10, 125).getRGB());
        RegionValidator.PixelRegion region = validated(source, 0.35, 0.05, 0.55, 0.20, 0.40);

        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(source, region);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, outcome.getStatus());
        assertEquals(BufferedImage.TYPE_INT_ARGB, outcome.getCandidate().getType());
        assertEquals(new Color(10, 10, 10, 125).getRGB(), outcome.getCandidate().getRGB(5, 40));
        assertEquals(new Color(20, 20, 20, 210).getRGB(), source.getRGB(31, 9));
    }

    private void assertManual(BufferedImage source, double x1, double y1, double x2, double y2, double bodyY, String reason) {
        int before = source.getRGB(30, 8);
        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(source, validated(source, x1, y1, x2, y2, bodyY));

        assertEquals(InkMaskEraser.Status.MANUAL_REVIEW, outcome.getStatus());
        assertTrue(outcome.getReason().contains(reason));
        assertEquals(before, source.getRGB(30, 8));
    }

    private RegionValidator.PixelRegion validated(BufferedImage image, double x1, double y1, double x2, double y2, double bodyY) {
        EraseRegion region = new EraseRegion();
        region.region_id = "r1";
        region.x1 = x1;
        region.y1 = y1;
        region.x2 = x2;
        region.y2 = y2;
        region.confidence = 0.99;
        BodyBoundary boundary = new BodyBoundary();
        boundary.y = bodyY;
        boundary.basis = "java";
        RegionValidator.ValidationResult result = RegionValidator.validate(
                new RegionValidator.PageLocateResult("page-1", "safe_to_erase", Arrays.asList(region), boundary),
                image);
        assertTrue(result.getReasons().toString(), result.isAccepted());
        return result.getRegions().get(0);
    }

    private BufferedImage page(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private void drawAntiAliasedDigit(BufferedImage image, int x, int y) {
        for (int yy = y + 1; yy <= y + 6; yy++) {
            image.setRGB(x + 3, yy, Color.BLACK.getRGB());
            image.setRGB(x + 4, yy, new Color(90, 90, 90).getRGB());
        }
        image.setRGB(x + 2, y + 2, new Color(130, 130, 130).getRGB());
        image.setRGB(x + 5, y + 5, new Color(150, 150, 150).getRGB());
    }
}
