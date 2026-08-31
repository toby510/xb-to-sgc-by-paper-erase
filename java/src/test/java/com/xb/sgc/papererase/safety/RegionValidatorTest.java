package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RegionValidatorTest {
    @Test
    public void validateAcceptsEdgeRegionAndReturnsImmutablePixelBounds() {
        BufferedImage image = blankPage();
        drawText(image, 11, 9, 18, 14);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.205)),
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
        EraseRegion negativeConfidence = region("r1", 0.10, 0.04, 0.20, 0.08);
        negativeConfidence.confidence = -0.01;
        assertRejected(negativeConfidence, "confidence must be between 0 and 1");
        EraseRegion highConfidence = region("r1", 0.10, 0.04, 0.20, 0.08);
        highConfidence.confidence = 1.01;
        assertRejected(highConfidence, "confidence must be between 0 and 1");
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

        result = RegionValidator.validate(
                locate("page-1", Arrays.asList(
                        region("r1", 0.10, 0.04, 0.20, 0.08),
                        region(" r1 ", 0.30, 0.04, 0.40, 0.08)), boundary(null, 0.16)),
                image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains("duplicate region_id"));
    }

    @Test
    public void validateRejectsNonEdgeRegionAndInsufficientBodyGap() {
        assertRejected(region("r1", 0.40, 0.40, 0.50, 0.45), "edge band");
        assertRejected(region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.10), "body blank gap");
        assertRejected(region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 1.20), "body boundary y must be between 0 and 1");
        assertRejected(region("r1", 0.04, 0.30, 0.08, 0.40), boundary(Double.NaN, null), "body boundary x must be finite");

        BufferedImage bandInk = blankPage();
        drawText(bandInk, 11, 9, 18, 14);
        drawText(bandInk, 12, 18, 14, 21);
        RegionValidator.ValidationResult bandResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.205)),
                bandInk);
        assertFalse(bandResult.isAccepted());
        assertTrue(bandResult.getReasons().get(0).contains("body blank gap contains ink"));

    }

    @Test
    public void validateAllowsSparseScanNoiseButRejectsTextAndLineStructuresInBodyGap() {
        BufferedImage sparseNoise = blankPage();
        drawText(sparseNoise, 45, 8, 54, 14);
        // 宽页码行与正文之间的 8px 安全带内出现多个互不相连的浅灰点；这是扫描噪声，
        // 不能因候选框更宽而被累计为正文。
        sparseNoise.setRGB(14, 18, new Color(220, 220, 220).getRGB());
        sparseNoise.setRGB(48, 20, new Color(220, 220, 220).getRGB());
        sparseNoise.setRGB(84, 22, new Color(220, 220, 220).getRGB());
        // 扫描底纹也可能连成 1~3 行的小斑块；它没有足够的文字高度或答题线长度。
        drawGrayBlock(sparseNoise, 58, 18, 65, 20, 220);
        RegionValidator.ValidationResult sparseResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.90, 0.08), boundary(null, 0.205)), sparseNoise);
        assertTrue(sparseResult.getReasons().toString(), sparseResult.isAccepted());

        BufferedImage faintText = blankPage();
        drawText(faintText, 45, 8, 54, 14);
        drawGrayBlock(faintText, 46, 18, 50, 21, 220);
        RegionValidator.ValidationResult textResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.90, 0.08), boundary(null, 0.205)), faintText);
        assertFalse(textResult.isAccepted());
        assertTrue(textResult.getReasons().get(0).contains("body blank gap contains ink"));

        BufferedImage faintLine = blankPage();
        drawText(faintLine, 45, 8, 54, 14);
        drawGrayBlock(faintLine, 30, 20, 70, 21, 220);
        RegionValidator.ValidationResult lineResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.90, 0.08), boundary(null, 0.205)), faintLine);
        assertFalse(lineResult.isAccepted());
        assertTrue(lineResult.getReasons().get(0).contains("body blank gap contains ink"));
    }

    @Test
    public void validateAcceptsBottomPageNumberOnExtremeHorizontalStripWithProvenBlankGap() {
        BufferedImage image = blankPage(400, 100);
        // 横向短条页：页码在底部 25% 内，正文边界在上半部，二者之间保留远大于 8px 的空白带。
        drawText(image, 190, 74, 209, 79);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.45, 0.73, 0.55, 0.82), boundary(null, 0.52)), image);

        assertTrue(result.isAccepted());
    }

    @Test
    public void expandsWideIndependentFooterBandBeyondThirtyTwoPixelsToBlankBoundary() {
        BufferedImage image = blankPage(300, 200);
        // 候选框已落在独立页脚带中部；同一连续徽标/色块向左右各延伸 60px，超过旧 32px
        // 上限，但在新的有界自适应预算内。正文方向仍保留 40px 的真实空白带。
        drawText(image, 60, 180, 240, 190);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.90, 0.60, 0.955), boundary(null, 0.70)), image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        RegionValidator.PixelRegion expanded = result.getRegions().get(0);
        assertTrue("left outer contour must be covered", expanded.getX() <= 60);
        assertTrue("right outer contour must be covered", expanded.getX() + expanded.getWidth() > 240);
    }

    @Test
    public void refusesWideFooterExpansionWhenItWouldCrossTheBodySafetyBand() {
        BufferedImage image = blankPage(300, 200);
        // 连续墨迹一直向正文方向延伸；即使扫描预算变大，也必须在 8px 正文安全带前失败。
        drawText(image, 60, 145, 240, 190);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.90, 0.60, 0.955), boundary(null, 0.70)), image);

        assertFalse(result.isAccepted());
    }

    @Test
    public void refusesWideFooterExpansionWhenNoBlankBoundaryExistsWithinTheHardCap() {
        BufferedImage image = blankPage(300, 200);
        // 左右两端的空白分别距离候选框超过 96px，不能因“可能仍是同一行”无限扩张。
        drawText(image, 10, 180, 290, 190);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.90, 0.60, 0.955), boundary(null, 0.70)), image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().toString(), result.getReasons().contains("ink mask touches candidate box"));
    }

    @Test
    public void validateKeepsNormalPageAndNonBottomStripCandidatesOutOfRelaxedEdgeBand() {
        BufferedImage normalPage = blankPage();
        drawText(normalPage, 45, 146, 54, 159);
        RegionValidator.ValidationResult normalResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.45, 0.73, 0.55, 0.80), boundary(null, 0.52)), normalPage);
        assertFalse(normalResult.isAccepted());
        assertTrue(normalResult.getReasons().get(0).contains("edge band"));

        BufferedImage strip = blankPage(400, 100);
        drawText(strip, 190, 65, 209, 69);
        RegionValidator.ValidationResult nonBottomResult = RegionValidator.validate(
                locate("page-1", region("r1", 0.45, 0.64, 0.55, 0.71), boundary(null, 0.52)), strip);
        assertFalse(nonBottomResult.isAccepted());
        assertTrue(nonBottomResult.getReasons().get(0).contains("edge band"));
    }

    @Test
    public void validateKeepsExtremeHorizontalStripRejectedWhenBlankGapIsInsufficient() {
        BufferedImage image = blankPage(400, 100);
        drawText(image, 190, 74, 209, 79);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.45, 0.73, 0.55, 0.82), boundary(null, 0.70)), image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains("body blank gap is insufficient"));
    }

    @Test
    public void validateExpandsTightBoxToTwoBlankScanlinesOnEverySide() {
        BufferedImage image = blankPage();
        // 模型框为 x=10..19,y=8..15；实际页码抗锯齿延伸到右/上/左边界。
        // 四侧均在很近处有两条空白线，应交给擦除器一个本身带空白边界的批准框。
        drawText(image, 10, 8, 20, 15);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.16)), image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        RegionValidator.PixelRegion expanded = result.getRegions().get(0);
        assertEquals(8, expanded.getX());
        assertEquals(6, expanded.getY());
        assertEquals(15, expanded.getWidth());
        assertEquals(12, expanded.getHeight());
    }

    @Test
    public void validateRejectsTightBoxWhenAContinuousNeighbouringInkRunHasNoBlankBoundary() {
        BufferedImage image = blankPage();
        // 右侧连续墨迹超过统一安全吸附上限 32px；不能猜它仍属于页码。
        drawText(image, 10, 8, 60, 15);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.10, 0.04, 0.20, 0.08), boundary(null, 0.16)), image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains("mask touches candidate box"));
    }

    @Test
    public void validateAbsorbsOnlyPageNumberInkConnectedToAConservativeRightEdgeBox() {
        BufferedImage image = blankPage();
        // 模型框从 x=90 开始，但页码左侧抗锯齿/蓝色笔画实际延伸到 x=87。
        // 这些像素与框内页码连通，且距正文边界 x=70 仍有足够的真实空白带。
        drawText(image, 87, 86, 94, 108);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.90, 0.40, 0.96, 0.60), boundary(0.70, null)),
                image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        RegionValidator.PixelRegion expanded = result.getRegions().get(0);
        // 四侧均保留两条空白扫描线，供后续擦除器复用相同边界语义。
        assertEquals(85, expanded.getX());
        assertEquals(13, expanded.getWidth());
    }

    @Test
    public void validateRescuesAnEmptyRightEdgeModelBoxOnlyInsideTheProvenBlankGap() {
        BufferedImage image = blankPage();
        // 模型框 x=90..96 完全偏在页码右侧；真实页码位于 x=82..88，正文边界为 x=70。
        // 左侧仍可留出至少 8px 无墨安全带，因此可把“模型空框”内移至该独立墨迹组。
        drawText(image, 82, 86, 88, 108);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.90, 0.40, 0.96, 0.60), boundary(0.70, null)),
                image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        RegionValidator.PixelRegion rescued = result.getRegions().get(0);
        assertEquals(81, rescued.getX());
        assertEquals(15, rescued.getWidth());
    }

    @Test
    public void validateRescuesAnEmptyBottomModelBoxWhenItsGapIsBlank() {
        BufferedImage image = blankPage();
        // 模型框完全落在页码下方空白；真实页码仍位于正文边界 y=100 之外的安全走廊。
        drawText(image, 45, 170, 54, 175);

        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.90, 0.60, 0.95), boundary(null, 0.50)), image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        assertTrue(result.getRegions().get(0).isCoordinateRescued());
        assertEquals(169, result.getRegions().get(0).getY());
    }

    @Test
    public void validateAcceptsMissingBoundaryOnlyWithSixteenPixelBlankBand() {
        BufferedImage image = blankPage();
        drawText(image, 45, 8, 54, 14);
        RegionValidator.ValidationResult accepted = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.04, 0.60, 0.08), null), image);
        assertTrue(accepted.getReasons().toString(), accepted.isAccepted());

        drawText(image, 48, 18, 50, 21);
        RegionValidator.ValidationResult rejected = RegionValidator.validate(
                locate("page-1", region("r1", 0.40, 0.04, 0.60, 0.08), null), image);
        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReasons().get(0).contains("body blank gap"));
    }

    @Test
    public void replacesOnlyAConflictingBoundaryWhenRefinedTargetHasAnEightPixelSafetyBand() {
        BufferedImage image = blankPage();
        drawText(image, 45, 8, 54, 14);
        EraseRegion original = region("r1", 0.40, 0.04, 0.60, 0.08);
        EraseRegion refined = region("r1", 0.42, 0.04, 0.58, 0.08);
        BodyBoundary conflicting = boundary(null, 0.07);

        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(original, refined, conflicting, image);

        assertTrue(effective != null);
        assertEquals(0.12, effective.y, 0.0);
        assertTrue(effective.basis.startsWith("java_8px_blank_band_replaced_conflicting_vlm_boundary"));
        assertTrue(effective.basis.contains("original_y=0.07"));
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1", refined, effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
    }

    @Test
    public void replacesConflictingBoundaryForSameLiteralWithPlausibleRoiProjectionShift() {
        BufferedImage image = blankPage();
        drawText(image, 58, 184, 62, 190);
        EraseRegion original = region("r1", 0.65, 0.90, 0.76, 0.98);
        EraseRegion refined = region("r1", 0.565, 0.90, 0.654, 0.98);
        original.page_number_text = "第6页(共8页)";
        refined.page_number_text = "第6页(共8页)";

        EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                original, trimmed, boundary(null, 0.95), image);

        assertTrue("same literal plus bounded center shift must retain pixel-proven replacement", effective != null);
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1", trimmed, effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
    }

    @Test
    public void refusesConflictingBoundaryForSameLiteralInDistantUnrelatedProjection() {
        BufferedImage image = blankPage();
        drawText(image, 72, 184, 78, 190);
        EraseRegion original = region("r1", 0.10, 0.90, 0.20, 0.98);
        EraseRegion refined = region("r1", 0.70, 0.90, 0.80, 0.98);
        original.page_number_text = "第6页(共8页)";
        refined.page_number_text = "第6页(共8页)";

        assertNull(RegionValidator.replaceConflictingBodyBoundary(
                original, refined, boundary(null, 0.95), image));
    }

    @Test
    public void toleratesOnlyIsolatedScanNoiseInConflictingBoundarySafetyBand() {
        BufferedImage image = blankPage();
        drawText(image, 45, 8, 54, 14);
        image.setRGB(48, 18, Color.BLACK.getRGB());
        image.setRGB(51, 20, Color.BLACK.getRGB());

        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.04, 0.60, 0.08),
                region("r1", 0.42, 0.04, 0.58, 0.08), boundary(null, 0.07), image);

        assertTrue(effective != null);
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1",
                region("r1", 0.42, 0.04, 0.58, 0.08), effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
    }

    @Test
    public void replacesConflictingFooterBoundaryWhenSafetyBandContainsOnlyThinVerticalDivider() {
        BufferedImage image = blankPage();
        // refined box starts at y=182 and already contains two blank rows before target y=184；分栏线
        // 恰好结束在框外 y=181，不能被“补齐页码笔画”逻辑沿线向正文方向吸附。
        drawText(image, 45, 184, 54, 190);
        drawText(image, 50, 170, 50, 181);

        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.90, 0.60, 0.98),
                region("r1", 0.42, 0.91, 0.58, 0.98), boundary(null, 0.95), image);

        assertTrue("a detached single-axis divider is not body text", effective != null);
        assertEquals("the boundary must be measured from the already blank candidate edge",
                0.87, effective.y, 0.0);
        assertTrue(RegionValidator.validate(locate("page-1",
                region("r1", 0.42, 0.91, 0.58, 0.98), effective), image).isAccepted());
    }

    @Test
    public void replacesConflictingFooterBoundaryWhenThinDividerEndsInsideRefinedBoxBeforeTargetText() {
        BufferedImage image = blankPage();
        // 中央分栏线从正文延伸到局部精框最上方两行，随后与页码之间有完整空白。
        // 它不是页码目标，不能把精框沿细线一路拉回正文。
        drawText(image, 50, 160, 50, 181);
        drawText(image, 45, 184, 54, 190);

        EraseRegion refined = region("r1", 0.42, 0.90, 0.58, 0.98);
        EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.90, 0.60, 0.98),
                trimmed, boundary(null, 0.95), image);

        assertTrue("a detached divider entering only the box padding is not target text", effective != null);
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1",
                trimmed, effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
        assertTrue("the approved erase box must exclude the detached divider",
                validation.getRegions().get(0).getY() >= 182);
    }

    @Test
    public void replacesConflictingFooterBoundaryWithExactlyEightProvenBlankPixels() {
        BufferedImage image = blankPage();
        // 页码真实墨迹从 y=184 开始；trim 后批准框从 y=182 开始。正文结束于 y=173，
        // 二者之间恰好有正式门禁要求的 8px 空白（174..181），不能额外要求 10px。
        drawText(image, 45, 184, 54, 190);
        drawText(image, 45, 169, 47, 173);

        EraseRegion refined = region("r1", 0.42, 0.90, 0.58, 0.98);
        EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.90, 0.60, 0.98),
                trimmed, boundary(null, 0.95), image);

        assertTrue("the replacement must use the same 8px hard gate as final validation", effective != null);
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1", trimmed, effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
    }

    @Test
    public void refusesConflictingFooterBoundaryWithOnlySevenBlankPixels() {
        BufferedImage image = blankPage();
        drawText(image, 45, 184, 54, 190);
        drawText(image, 45, 174, 47, 177);

        assertNull(RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.90, 0.60, 0.98),
                region("r1", 0.42, 0.90, 0.58, 0.98), boundary(null, 0.95), image));
    }

    @Test
    public void refusesConflictingFooterBoundaryWhenSafetyBandContainsTextLikeInk() {
        BufferedImage image = blankPage();
        drawText(image, 45, 184, 54, 190);
        drawText(image, 48, 176, 52, 179);

        assertNull(RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.90, 0.60, 0.98),
                region("r1", 0.42, 0.91, 0.58, 0.98), boundary(null, 0.95), image));
    }

    @Test
    public void refusesConflictingBoundaryReplacementForSubstantiveInkInSafetyBand() {
        BufferedImage image = blankPage();
        drawText(image, 45, 8, 54, 14);
        drawText(image, 47, 18, 49, 21);

        assertNull(RegionValidator.replaceConflictingBodyBoundary(
                region("r1", 0.40, 0.04, 0.60, 0.08),
                region("r1", 0.42, 0.04, 0.58, 0.08), boundary(null, 0.07), image));
    }

    @Test
    public void conflictingBoundaryUsesActualOuterTargetInkBeforeComputingSafetyGap() {
        BufferedImage image = blankPage();
        // 局部 VLM 框从 y=180 开始，但页码抗锯齿最外沿还在 y=179。
        drawText(image, 45, 179, 54, 190);
        EraseRegion original = region("r1", 0.40, 0.90, 0.60, 0.98);
        EraseRegion refined = region("r1", 0.42, 0.90, 0.58, 0.98);

        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                original, refined, boundary(null, 0.95), image);

        assertTrue(effective != null);
        // 页码最外沿贴框，最终校验还需向正文侧补 2px 空白边界，因此这里预留 10px。
        assertEquals(0.845, effective.y, 0.0);
        RegionValidator.ValidationResult validation = RegionValidator.validate(locate("page-1", refined, effective), image);
        assertTrue(validation.getReasons().toString(), validation.isAccepted());
    }

    @Test
    public void conflictingBoundaryProofTrimsOnlyBlankModelPaddingTowardBody() {
        BufferedImage image = blankPage();
        // 正文侧横线离模型框上沿很近，但距真实页码墨迹仍有安全空白。
        drawText(image, 40, 174, 60, 174);
        drawText(image, 47, 190, 53, 194);
        EraseRegion original = region("r1", 0.40, 0.90, 0.60, 0.98);
        EraseRegion refined = region("r1", 0.42, 0.90, 0.58, 0.98);

        EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        BodyBoundary effective = RegionValidator.replaceConflictingBodyBoundary(
                original, trimmed, boundary(null, 0.95), image);

        assertTrue("blank padding may be trimmed, target ink may not", effective != null);
        assertTrue(trimmed.y1 > refined.y1);
        assertTrue(RegionValidator.validate(locate("page-1", trimmed, effective), image).isAccepted());
    }

    @Test
    public void trimSkipsDetachedThinDividerBeforeFooterText() {
        BufferedImage image = blankPage();
        drawText(image, 50, 160, 50, 165);
        drawText(image, 44, 174, 56, 181);
        EraseRegion modelBox = region("r1", 0.40, 0.80, 0.60, 0.94);

        EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(modelBox, image);

        assertTrue("only a detached thin divider may be skipped: " + trimmed.y1, trimmed.y1 >= 0.86);
        assertTrue("footer text must remain inside the approved box", trimmed.y1 <= 0.87);
    }

    @Test
    public void validateDoesNotTreatSingleOutsideDividerAsBodyText() {
        BufferedImage image = blankPage();
        drawText(image, 48, 184, 54, 191);
        drawText(image, 50, 170, 50, 177);
        EraseRegion footer = region("r1", 0.40, 0.90, 0.60, 0.96);

        assertFalse(RegionValidator.validate(locate("page-1", footer, boundary(null, 0.70)), image)
                .getReasons().contains("body blank gap contains ink"));
    }

    @Test
    public void validateAcceptsWideIndependentFooterTargetLine() {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        // 页码与学校/试卷标识处在同一独立页脚行；各词之间留白，避免模拟表格或长线。
        drawText(image, 35, 174, 56, 183);
        drawText(image, 90, 174, 123, 183);
        drawText(image, 160, 174, 189, 183);
        drawText(image, 245, 174, 275, 183);
        drawText(image, 315, 174, 344, 183);

        EraseRegion targetLine = region("r1", 0.05, 0.85, 0.95, 0.95);
        targetLine.page_number_text = "第1页";
        targetLine.same_line_metadata = "某校 高一数学试卷";
        RegionValidator.ValidationResult result = RegionValidator.validate(
                locate("page-1", targetLine, boundary(null, 0.75)), image);

        assertTrue(result.getReasons().toString(), result.isAccepted());
        assertEquals(1, result.getRegions().size());
        assertTrue("complete independent footer line must retain its width",
                result.getRegions().get(0).getWidth() >= 360);
    }

    @Test
    public void validateRejectsUnsafeStatusMissingPageIdAndEmptyOrNullRegions() {
        BufferedImage image = blankPage();

        assertRejectedLocate(new RegionValidator.PageLocateResult("page-1", "manual_review",
                Arrays.asList(region("r1", 0.10, 0.04, 0.20, 0.08))),
                image, "status must be safe_to_erase");
        assertRejectedLocate(new RegionValidator.PageLocateResult("page-1", "Safe_To_Erase",
                Arrays.asList(region("r1", 0.10, 0.04, 0.20, 0.08))),
                image, "status must be safe_to_erase");
        assertRejectedLocate(new RegionValidator.PageLocateResult(" ", "safe_to_erase",
                Arrays.asList(region("r1", 0.10, 0.04, 0.20, 0.08))),
                image, "page_id is required");
        assertRejectedLocate(new RegionValidator.PageLocateResult("page-1", "safe_to_erase",
                java.util.Collections.<EraseRegion>emptyList()),
                image, "regions must not be empty");
        assertRejectedLocate(new RegionValidator.PageLocateResult("page-1", "safe_to_erase",
                null), image, "regions are required");
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

    private void assertRejectedLocate(RegionValidator.PageLocateResult locateResult, BufferedImage image, String reason) {
        RegionValidator.ValidationResult result = RegionValidator.validate(locateResult, image);

        assertFalse(result.isAccepted());
        assertTrue(result.getReasons().get(0).contains(reason));
    }

    private RegionValidator.PageLocateResult locate(String pageId, EraseRegion region, BodyBoundary boundary) {
        return locate(pageId, Arrays.asList(region), boundary);
    }

    private RegionValidator.PageLocateResult locate(String pageId, java.util.List<EraseRegion> regions, BodyBoundary boundary) {
        if (regions != null) {
            for (EraseRegion region : regions) {
                region.nearest_body_boundary = boundary;
            }
        }
        return new RegionValidator.PageLocateResult(pageId, "safe_to_erase", regions);
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
        return blankPage(100, 200);
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
                if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
        }
    }

    private void drawGrayBlock(BufferedImage image, int left, int top, int right, int bottom, int gray) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
            }
        }
    }
}
