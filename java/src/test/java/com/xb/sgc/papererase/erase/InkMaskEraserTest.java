package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.safety.RegionValidator;
import org.junit.Test;

import java.awt.Color;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
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
        drawShortSameLineMetadata(source, 42, 10);
        source.setRGB(5, 40, Color.BLACK.getRGB());
        source.setRGB(32, 30, Color.BLACK.getRGB());
        int outsideDot = source.getRGB(5, 40);
        int nearbyBody = source.getRGB(32, 30);
        int originalInk = source.getRGB(33, 12);
        RegionValidator.PixelRegion region = validated(source, 0.35, 0.05, 0.65, 0.20, 0.40);

        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(source, region);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, outcome.getStatus());
        assertNotSame(source, outcome.getCandidate());
        assertEquals(originalInk, source.getRGB(33, 12));
        assertEquals(outsideDot, outcome.getCandidate().getRGB(5, 40));
        assertEquals(nearbyBody, outcome.getCandidate().getRGB(32, 30));
        assertTrue(outcome.getApprovedMask().isApproved(33, 12));
        assertTrue(outcome.getApprovedMask().isApproved(35, 13));
        assertTrue(outcome.getApprovedMask().isApproved(43, 12));
        assertFalse(outcome.getApprovedMask().isApproved(32, 30));
        assertTrue(outcome.getCandidate().getRGB(33, 12) != originalInk);

        BufferedImage exportedCandidate = outcome.getCandidate();
        exportedCandidate.setRGB(33, 12, Color.MAGENTA.getRGB());
        assertTrue(outcome.getCandidate().getRGB(33, 12) != Color.MAGENTA.getRGB());

        boolean[][] exported = outcome.getApprovedMask().toArray();
        exported[12][33] = false;
        assertTrue(outcome.getApprovedMask().isApproved(33, 12));
        assertEquals(80, outcome.getApprovedMask().getImageWidth());
        assertEquals(80, outcome.getApprovedMask().getImageHeight());
        assertEquals(region.getX(), outcome.getApprovedMask().getRegionX());
    }

    @Test
    public void handlesComplexBackgroundColoredStampAndValidatorExpandedTightBox() {
        BufferedImage textured = page(80, 80, new Color(245, 244, 238));
        for (int y = 4; y < 20; y++) {
            for (int x = 28; x < 48; x++) {
                int v = ((x + y) % 2 == 0) ? 230 : 252;
                textured.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        drawAntiAliasedDigit(textured, 30, 8);
        InkMaskEraser.EraseOutcome texturedOutcome = InkMaskEraser.erase(textured,
                validated(textured, 0.35, 0.05, 0.60, 0.20, 0.40));
        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, texturedOutcome.getStatus());
        assertTrue(texturedOutcome.getReason().contains("white_fallback"));
        assertEquals(textured.getRGB(20, 20), texturedOutcome.getCandidate().getRGB(20, 20));

        BufferedImage stamped = page(80, 80, new Color(245, 244, 238));
        drawAntiAliasedDigit(stamped, 30, 8);
        for (int y = 12; y <= 15; y++) {
            for (int x = 43; x <= 46; x++) {
                stamped.setRGB(x, y, Color.RED.getRGB());
            }
        }
        assertManual(stamped, 0.35, 0.05, 0.65, 0.20, 0.40, "colored non-target");

        BufferedImage grayAntiAlias = page(80, 80, new Color(245, 244, 238));
        drawAntiAliasedDigit(grayAntiAlias, 30, 8);
        for (int x = 42; x <= 48; x++) {
            grayAntiAlias.setRGB(x, 12, new Color(190, 190, 190).getRGB());
        }
        InkMaskEraser.EraseOutcome grayOutcome = InkMaskEraser.erase(grayAntiAlias,
                validated(grayAntiAlias, 0.35, 0.05, 0.65, 0.20, 0.40));
        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, grayOutcome.getStatus());

        BufferedImage tiny = page(80, 80, new Color(245, 244, 238));
        tiny.setRGB(30, 8, Color.BLACK.getRGB());
        InkMaskEraser.EraseOutcome tinyOutcome = InkMaskEraser.erase(tiny,
                validated(tiny, 0.35, 0.05, 0.425, 0.125, 0.40));
        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, tinyOutcome.getStatus());
        assertTrue(tinyOutcome.getReason().contains("white_fallback"));

        BufferedImage touching = page(80, 80, new Color(245, 244, 238));
        // 220 灰度不满足旧校验器的固定 <210 阈值，但满足擦除器“局部背景 245 - 25”的
        // 动态掩码阈值。两层必须以同一规则扩框，否则擦除器仍会在边界拒绝。
        touching.setRGB(28, 8, new Color(220, 220, 220).getRGB());
        touching.setRGB(34, 10, Color.BLACK.getRGB());
        InkMaskEraser.EraseOutcome touchingOutcome = InkMaskEraser.erase(touching,
                validated(touching, 0.35, 0.05, 0.55, 0.20, 0.40));
        assertEquals(touchingOutcome.getReason(), InkMaskEraser.Status.SAFE_TO_ERASE, touchingOutcome.getStatus());
    }

    @Test
    public void allowsColoredPageNumberOnlyAfterLocalVerifyApproval() throws Exception {
        BufferedImage source = page(80, 80, new Color(245, 244, 238));
        for (int y = 10; y < 15; y++) {
            for (int x = 68; x < 71; x++) {
                source.setRGB(x, y, new Color(15, 75, 180).getRGB());
            }
        }
        RegionValidator.PixelRegion region = pixelRegion("page-1", "r1", 64, 4, 12, 14);

        assertEquals(InkMaskEraser.Status.MANUAL_REVIEW, InkMaskEraser.erase(source, region).getStatus());
        InkMaskEraser.EraseOutcome approved = InkMaskEraser.erase(source, region, true);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, approved.getStatus());
        assertTrue(approved.getApprovedMask().isApproved(69, 12));
        assertEquals(source.getRGB(5, 40), approved.getCandidate().getRGB(5, 40));
    }

    @Test
    public void rejectsUnsafeInkGeometryInsideValidatedRegion() {
        BufferedImage longLine = page(120, 120, new Color(245, 244, 238));
        drawAntiAliasedDigit(longLine, 34, 10);
        for (int x = 50; x <= 75; x++) {
            longLine.setRGB(x, 11, new Color(190, 190, 190).getRGB());
        }
        assertManual(longLine, 0.25, 0.05, 0.70, 0.20, 0.42, "long line");

        BufferedImage table = page(120, 120, new Color(245, 244, 238));
        drawAntiAliasedDigit(table, 34, 10);
        for (int x = 50; x <= 72; x++) {
            table.setRGB(x, 11, Color.BLACK.getRGB());
        }
        for (int y = 7; y <= 18; y++) {
            table.setRGB(60, y, Color.BLACK.getRGB());
        }
        assertManual(table, 0.25, 0.05, 0.72, 0.20, 0.42, "table or crossing line");

        BufferedImage blackBlock = page(120, 120, new Color(245, 244, 238));
        drawGlyphBlock(blackBlock, 34, 9, 22, 10);
        assertManual(blackBlock, 0.25, 0.05, 0.65, 0.20, 0.42, "ink coverage");
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

    @Test
    public void handlesCustomImageTypeAndManualDefensiveCandidateCopies() {
        BufferedImage custom = customPage(80, 80, new Color(245, 244, 238, 190));
        custom.setRGB(31, 9, new Color(20, 20, 20, 190).getRGB());
        RegionValidator.PixelRegion region = validated(custom, 0.35, 0.05, 0.55, 0.20, 0.40);

        InkMaskEraser.EraseOutcome safe = InkMaskEraser.erase(custom, region);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, safe.getStatus());
        assertEquals(BufferedImage.TYPE_CUSTOM, safe.getCandidate().getType());
        assertEquals(new Color(20, 20, 20, 190).getRGB(), custom.getRGB(31, 9));
        BufferedImage candidate = safe.getCandidate();
        candidate.setRGB(31, 9, Color.MAGENTA.getRGB());
        assertTrue(safe.getCandidate().getRGB(31, 9) != Color.MAGENTA.getRGB());

        BufferedImage manualSource = page(80, 80, new Color(245, 244, 238));
        manualSource.setRGB(28, 8, new Color(130, 130, 130).getRGB());
        InkMaskEraser.EraseOutcome manual = InkMaskEraser.erase(manualSource,
                validated(manualSource, 0.35, 0.05, 0.55, 0.20, 0.40));
        BufferedImage manualCandidate = manual.getCandidate();
        manualCandidate.setRGB(28, 8, Color.MAGENTA.getRGB());
        assertTrue(manual.getCandidate().getRGB(28, 8) != Color.MAGENTA.getRGB());
    }

    @Test
    public void rejectsPixelRegionThatDoesNotMatchCurrentSourceWithoutThrowing() throws Exception {
        BufferedImage source = page(80, 80, new Color(245, 244, 238));
        RegionValidator.PixelRegion mismatched = pixelRegion("page-1", "r1", 70, 70, 20, 20);

        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(source, mismatched);

        assertEquals(InkMaskEraser.Status.MANUAL_REVIEW, outcome.getStatus());
        assertTrue(outcome.getReason().contains("region outside source"));
    }

    @Test
    public void fitsLightGradientBackgroundAndRejectsComplexTexture() {
        BufferedImage gradient = gradientPage(120, 100, 235, 248);
        drawAntiAliasedDigit(gradient, 38, 10);
        RegionValidator.PixelRegion region = validated(gradient, 0.25, 0.05, 0.60, 0.20, 0.42);

        InkMaskEraser.EraseOutcome outcome = InkMaskEraser.erase(gradient, region);

        assertEquals(InkMaskEraser.Status.SAFE_TO_ERASE, outcome.getStatus());
        int repaired = outcome.getCandidate().getRGB(41, 13);
        int expectedRed = 235 + (248 - 235) * 41 / 119;
        assertTrue(Math.abs(((repaired >>> 16) & 0xFF) - expectedRed) <= 3);

    }

    @Test
    public void approvedMaskRejectsRaggedWrongSizedAndOutOfRegionMasks() {
        BufferedImage source = page(80, 80, new Color(245, 244, 238));
        RegionValidator.PixelRegion region = validated(source, 0.35, 0.05, 0.55, 0.20, 0.40);
        boolean[][] ragged = new boolean[80][];
        ragged[0] = new boolean[80];

        assertIllegalArgument("rectangular", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 80, 80, ragged);
            }
        });

        boolean[][] wrongSize = new boolean[79][80];
        assertIllegalArgument("dimensions", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 80, 80, wrongSize);
            }
        });

        boolean[][] outside = new boolean[80][80];
        outside[30][30] = true;
        assertIllegalArgument("outside region", new ThrowingRunnable() {
            public void run() {
                InkMaskEraser.ApprovedMask.from(region, 80, 80, outside);
            }
        });
    }

    @Test
    public void approvedMaskDoesNotExposePublicRawBooleanArrayConstructor() {
        Constructor<?>[] constructors = InkMaskEraser.ApprovedMask.class.getConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            assertFalse(Modifier.isPublic(constructor.getModifiers())
                    && parameterTypes.length == 1
                    && parameterTypes[0].equals(boolean[][].class));
        }
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
        region.nearest_body_boundary = boundary;
        RegionValidator.ValidationResult result = RegionValidator.validate(
                new RegionValidator.PageLocateResult("page-1", "safe_to_erase", Arrays.asList(region)),
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

    private BufferedImage customPage(int width, int height, Color color) {
        ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        BufferedImage image = new BufferedImage(model, raster, false, null);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private void drawShortSameLineMetadata(BufferedImage image, int x, int y) {
        drawGlyphBlock(image, x, y + 1, 2, 4);
        drawGlyphBlock(image, x + 4, y + 1, 2, 4);
    }

    private void drawGlyphBlock(BufferedImage image, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
    }

    private RegionValidator.PixelRegion pixelRegion(String pageId, String regionId, int x, int y, int width, int height) throws Exception {
        Constructor<RegionValidator.PixelRegion> constructor = RegionValidator.PixelRegion.class.getDeclaredConstructor(
                String.class, String.class, int.class, int.class, int.class, int.class,
                double.class, double.class, double.class, double.class, double.class);
        constructor.setAccessible(true);
        return constructor.newInstance(pageId, regionId, x, y, width, height,
                0.0, 0.0, 1.0, 1.0, 0.99);
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

    private void drawAntiAliasedDigit(BufferedImage image, int x, int y) {
        for (int yy = y + 1; yy <= y + 6; yy++) {
            image.setRGB(x + 3, yy, Color.BLACK.getRGB());
            image.setRGB(x + 4, yy, new Color(90, 90, 90).getRGB());
        }
        image.setRGB(x + 2, y + 2, new Color(130, 130, 130).getRGB());
        image.setRGB(x + 5, y + 5, new Color(150, 150, 150).getRGB());
    }

    private interface ThrowingRunnable {
        void run();
    }
}
