package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PageDirection;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;
import com.xb.sgc.papererase.vlm.VlmClient;
import com.xb.sgc.papererase.vlm.ResponseParser;
import com.xb.sgc.papererase.safety.RegionValidator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExamPipelineTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private final Set<Integer> coloredImageOrders = new HashSet<Integer>();
    private final Set<Integer> blankImageOrders = new HashSet<Integer>();
    private final Set<Integer> faintMarkImageOrders = new HashSet<Integer>();
    private final Set<Integer> twoFooterTargetImageOrders = new HashSet<Integer>();

    @Test
    public void acceptsTrulyBlankPageAsNoPageNumberBeforeLowDirectionGate() throws Exception {
        blankImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.lowDirectionConfidencePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("no_pagenum", outcome.page("p1").getStatus());
        assertTrue("a Java-confirmed blank page must not be sent to pattern", fake.patternBatches.isEmpty());
        assertTrue("blank pages do not need locate", fake.locatePageIds.isEmpty());
        assertTrue("blank pages do not need audit", fake.auditPageIds.isEmpty());
    }

    @Test
    public void doesNotTreatAVisibleFaintMarkAsBlankPage() throws Exception {
        faintMarkImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.lowDirectionConfidencePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals("low_direction_confidence", outcome.page("p1").getReason());
    }

    @Test
    public void coloredTargetStillUsesExistingAuditGate() throws Exception {
        /*
         * 整页 locate 已确认“独立页脚目标”，并且 RegionValidator 已证明其与正文的像素空白带。
         * 局部 ROI 只能用于坐标精化；若它漏看色块页码而答 no_pagenum，不能推翻整页语义结论。
         */
        coloredImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertTrue(fake.auditPageIds.contains("p1"));
    }

    @Test
    public void skipsPatternAndRunsPageLocalFastPathWithMandatoryAudit() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.onLinePages.add("p9");
        ExamOutcome outcome = new ExamPipeline(fake).process(exam(18, false), new ExamPipeline.RunContext());

        assertTrue(fake.patternBatches.isEmpty());
        assertEquals("rotated p2 requires one extra normalized locate", 19, fake.locatePageIds.size());
        assertEquals("only rotated and on-line pages should require local verify",
                Arrays.asList("p2:r1", "p9:r1"), fake.verifyCalls);
        assertEquals("every modified page must be audited", 18, fake.auditPageIds.size());
        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals(90, outcome.page("p2").getTransforms().getReadingRotation());
        assertEquals(18, outcome.getPages().size());
    }

    @Test
    public void riskVerifyEdgeRoiAuditFailureAndSinglePageIsolation() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.lowConfidencePages.add("p2");
        fake.lowConfidencePages.add("p4");
        fake.noCandidatePages.add("p3");
        fake.verifyNoPageNumRegionIds.add("p3:edge");
        fake.verifyManualRegionIds.add("p4:r1");
        fake.eraseFailurePages.add("p5");
        fake.auditFailPages.add("p6");
        fake.auditColorWarningPages.add("p10");
        fake.locateManualPages.add("p7");
        fake.lowDirectionConfidencePages.add("p8");
        fake.validationRejectedPages.add("p9");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(10, false), new ExamPipeline.RunContext());

        assertTrue(fake.verifyCalls.contains("p2:r1"));
        assertFalse("no_pagenum is accepted page-locally without pattern edge probing", fake.verifyCalls.contains("p3:edge"));
        assertEquals("no_pagenum", outcome.page("p3").getStatus());
        assertEquals("manual_review", outcome.page("p4").getStatus());
        assertEquals("manual_review", outcome.page("p5").getStatus());
        assertEquals("manual_review", outcome.page("p6").getStatus());
        assertEquals("manual_review", outcome.page("p7").getStatus());
        assertEquals("manual_review", outcome.page("p8").getStatus());
        assertEquals("manual_review", outcome.page("p9").getStatus());
        assertEquals("validation_rejected", outcome.page("p9").getReason());
        assertEquals("safe_to_erase", outcome.page("p10").getStatus());
        assertEquals("audit_pass_with_color_warning", outcome.page("p10").getReason());
        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertFalse("verify-denied and erase-failed pages are not audited", fake.auditPageIds.contains("p4"));
        assertFalse(fake.auditPageIds.contains("p5"));
        assertTrue(fake.auditPageIds.contains("p6"));
        assertFalse("deterministic validation rejection must not spend verify calls", fake.verifyCalls.contains("p9:r1"));
        assertFalse("deterministic validation rejection must not erase or audit", fake.auditPageIds.contains("p9"));
        for (ExamOutcome.PageOutcome page : outcome.getPages()) {
            assertTrue("final status leaked internal state: " + page.getStatus(),
                    "safe_to_erase".equals(page.getStatus())
                            || "no_pagenum".equals(page.getStatus())
                            || "manual_review".equals(page.getStatus()));
        }
    }

    @Test
    public void inactivePatternFailureHasNoEffectButSinglePageModelFailureIsIsolated() throws Exception {
        FakeVlm badPattern = FakeVlm.stable();
        badPattern.patternProtocolFailure = true;
        ExamOutcome protocolOutcome = new ExamPipeline(badPattern).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", protocolOutcome.page("p1").getStatus());
        assertEquals("safe_to_erase", protocolOutcome.page("p2").getStatus());
        assertTrue(badPattern.patternBatches.isEmpty());

        FakeVlm singlePageFailure = FakeVlm.stable();
        singlePageFailure.locateThrowsPages.add("p2");
        ExamOutcome isolated = new ExamPipeline(singlePageFailure).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", isolated.page("p1").getStatus());
        assertEquals("manual_review", isolated.page("p2").getStatus());
        assertEquals("safe_to_erase", isolated.page("p3").getStatus());
        assertEquals("transport failures retain the existing same-request retry path", 0, singlePageFailure.locateCorrectionCalls);
    }

    @Test
    public void neverInvokesInactivePatternProtocol() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.patternProtocolFailureOnce = true;

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals(0, fake.patternCorrectionCalls);
        assertTrue(fake.patternBatches.isEmpty());
    }

    @Test
    public void ignoresInactivePatternProtocolFailure() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.patternProtocolFailure = true;

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals(0, fake.patternCorrectionCalls);
    }

    @Test
    public void closesPageWhenLocateProtocolFailsWithoutPromptRewrite() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateProtocolFailureOncePages.add("p2");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals("manual_review", outcome.page("p2").getStatus());
        assertEquals("locate_error", outcome.page("p2").getReason());
        assertEquals(0, fake.locateCorrectionCalls);
        assertEquals("safe_to_erase", outcome.page("p3").getStatus());
    }

    @Test
    public void isolatesPageWhenLocateProtocolFails() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateProtocolFailurePages.add("p2");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals("manual_review", outcome.page("p2").getStatus());
        assertEquals("locate_error", outcome.page("p2").getReason());
        assertEquals(0, fake.locateCorrectionCalls);
        assertEquals("safe_to_erase", outcome.page("p3").getStatus());
    }

    @Test
    public void retriesTheSameLocalVerifyRoiOnceAfterProtocolFailure() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.verifyThrowsOnceRegionIds.add("p2:r1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(2, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p2").getStatus());
        assertEquals(Arrays.asList("p2:r1", "p2:r1"), fake.verifyCalls);
    }

    @Test
    public void locallySnapsTightPageNumberBoxBeforeSpendingCoordinateRefineCall() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.tightLocatePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals(outcome.page("p1").getReason(), "safe_to_erase", outcome.page("p1").getStatus());
        assertTrue("a safe local blank-band snap must not call VLM refinement", fake.verifyCalls.isEmpty());
    }

    @Test
    public void coordinateRefineKeepsWholePageBodyBoundaryWhenLocalResponseHasNoBoundary() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.coordinateRefinePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals(0.88, outcome.page("p1").getLocate().nearest_body_boundary.y, 0.0);
        assertTrue(fake.verifyCalls.contains("p1:r1"));
    }

    @Test
    public void localRefineMayReplaceOnlyAConflictingWholePageBoundaryWithProvenBlankBand() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.boundaryConflictPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals(outcome.page("p1").getReason(), "safe_to_erase", outcome.page("p1").getStatus());
        assertTrue(outcome.page("p1").getLocate().nearest_body_boundary.basis
                .startsWith("java_8px_blank_band_replaced_conflicting_vlm_boundary"));
        assertEquals(Arrays.asList("p1:r1"), fake.verifyCalls);
    }

    @Test
    public void fullyContainedDuplicateRegionIsAnIdempotentErase() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.duplicateRegionPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertTrue(fake.auditPageIds.contains("p1"));
    }

    @Test
    public void auditResidualOnTwoFooterRegionsAcceptsOneRoiResponseWithBothBoxes() throws Exception {
        twoFooterTargetImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.twoRegionAuditResidualPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals("the first residual audit and the refined result must both be audited",
                2, Collections.frequency(fake.auditPageIds, "p1"));
        assertEquals(Arrays.asList("p1:r1", "p1:r2"), fake.coordinateRefineCalls);
    }

    @Test
    public void doesNotLetLocalNoPageNumberOverrideHighConfidenceValidatedFullPageLocate() throws Exception {
        twoFooterTargetImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.twoRegionLocatePages.add("p1");
        fake.verifyNoPageNumRegionIds.add("p1:r2");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, true), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertTrue(fake.auditPageIds.contains("p1"));
        assertEquals(Arrays.asList("p1:r1", "p1:r2"), fake.verifyCalls);
    }

    @Test
    public void doesNotLetLocalManualReviewOverrideHighConfidenceValidatedFullPageLocate() throws Exception {
        twoFooterTargetImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.twoRegionLocatePages.add("p1");
        fake.verifyManualRegionIds.add("p1:r2");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, true), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertTrue(fake.auditPageIds.contains("p1"));
    }

    @Test
    public void doesNotUseRemovedPatternRoiToOverrideManualReview() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateManualPages.add("p1");
        fake.patternRoiSafeLocatePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertTrue(fake.locateStatusCorrectionPageIds.isEmpty());
        assertTrue(fake.patternRoiLocatePageIds.isEmpty());
        assertTrue(fake.auditPageIds.isEmpty());
    }

    @Test
    public void patternRoiRelocateDoesNotOverrideManualReviewWhenStatusCorrectionAndRoiRemainManual() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateManualPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals("locate_manual_review", outcome.page("p1").getReason());
        assertTrue(fake.locateStatusCorrectionPageIds.isEmpty());
        assertTrue(fake.patternRoiLocatePageIds.isEmpty());
        assertTrue("a non-safe ROI response cannot erase or audit", fake.auditPageIds.isEmpty());
    }

    @Test
    public void preservesEmptyLocateManualReviewWithoutStatusRewrite() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateManualPages.add("p1");
        fake.locateStatusCorrectionNoPageNumPages.add("p1");
        fake.verifyNoPageNumRegionIds.add("p1:edge");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertTrue(fake.locateStatusCorrectionPageIds.isEmpty());
        assertTrue(fake.auditPageIds.isEmpty());
    }

    @Test
    public void usesLocateForDirectionWithoutCallingPatternAndRelocatesAfterRotation() throws Exception {
        FakeVlm fake = FakeVlm.stable();

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(2, false), new ExamPipeline.RunContext());

        assertTrue("pattern must be absent from the active path", fake.patternBatches.isEmpty());
        assertEquals("normal page uses one locate; rotated page uses original plus normalized locate",
                Arrays.asList("p1", "p2", "p2"), fake.locatePageIds);
        assertEquals(90, outcome.page("p2").getTransforms().getReadingRotation());
        assertEquals("safe_to_erase", outcome.page("p2").getStatus());
    }

    @Test
    public void closesPageAfterTransientLocateFailureWithoutWholePageRetry() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateThrowsOncePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals(Collections.singletonList("p1"), fake.locatePageIds);
        assertTrue(fake.auditPageIds.isEmpty());
    }

    @Test
    public void closesPageWhenAuditReportsBodyChange() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.auditContradictionOncePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals("audit_failed", outcome.page("p1").getReason());
        assertEquals(Collections.singletonList("p1"), fake.locatePageIds);
        assertEquals(1, Collections.frequency(fake.auditPageIds, "p1"));
    }

    @Test
    public void neverRetriesWhenAuditSaysOriginalTargetIsBody() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.auditTargetIsBodyPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals("audit_original_target_is_body", outcome.page("p1").getReason());
        assertEquals(Collections.singletonList("p1"), fake.locatePageIds);
        assertEquals(1, Collections.frequency(fake.auditPageIds, "p1"));
    }

    @Test
    public void recognizesMultipleBodyGapOnlyConflictsForExistingPixelReplacementPath() {
        BufferedImage image = pageImage(1);
        LocateResponse locate = new LocateResponse();
        locate.page_id = "p1";
        locate.status = "safe_to_erase";
        locate.regions.add(region("r1", 0.20, 0.94, 0.30, 0.98));
        locate.regions.add(region("r2", 0.70, 0.94, 0.80, 0.98));
        BodyBoundary boundary = new BodyBoundary();
        boundary.y = 0.95;
        boundary.basis = "body";
        locate.nearest_body_boundary = boundary;
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult("p1", "safe_to_erase", locate.regions, boundary), image);

        assertFalse(validation.isAccepted());
        assertTrue(new ExamPipeline(FakeVlm.stable()).isOnlyBodyGapConflict(validation));
    }

    @Test
    public void permitsMaskTouchAsInitialEligibilityButKeepsOtherValidationRisksClosed() {
        ExamPipeline pipeline = new ExamPipeline(FakeVlm.stable());

        assertTrue(pipeline.allowsConflictingBoundaryReplacementAfterRefine(
                RegionValidator.ValidationResult.rejectedResult("ink mask touches candidate box")));
        assertTrue(pipeline.allowsConflictingBoundaryReplacementAfterRefine(
                RegionValidator.ValidationResult.rejectedResult("body blank gap is insufficient")));
        assertFalse(pipeline.allowsConflictingBoundaryReplacementAfterRefine(
                RegionValidator.ValidationResult.rejectedResult("coordinates must satisfy x1 < x2 and y1 < y2")));
        assertFalse("mask-touch is only an initial eligibility; it is not a final gap replacement condition",
                pipeline.isOnlyBodyGapConflict(RegionValidator.ValidationResult.rejectedResult("ink mask touches candidate box")));
    }

    private EraseRegion region(String id, double x1, double y1, double x2, double y2) {
        EraseRegion region = new EraseRegion();
        region.region_id = id;
        region.x1 = x1; region.y1 = y1; region.x2 = x2; region.y2 = y2;
        region.page_number_text = id;
        region.same_line_metadata = "";
        region.confidence = 0.99;
        region.safety_margin = "blank";
        return region;
    }

    @Test
    public void refinesAnEmptyRegionEvenWhenAnotherFooterRegionPassesGeometryValidation() throws Exception {
        twoFooterTargetImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.emptyMultiRegionPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals(Collections.singletonList("p1:r1"), fake.coordinateRefineCalls);
        assertTrue(fake.auditPageIds.contains("p1"));
    }

    private ExamInput exam(int pages, boolean incomplete) throws Exception {
        List<PageInput> inputs = new ArrayList<PageInput>();
        File dir = tmp.newFolder("exam-" + System.nanoTime());
        for (int i = 1; i <= pages; i++) {
            File file = new File(dir, "school_exam_" + i + ".png");
            ImageIO.write(pageImage(i), "png", file);
            inputs.add(new PageInput("p" + i, "exam", i, file.toPath()));
        }
        return new ExamInput("语文", "exam", "school", inputs, incomplete, Collections.<String>emptyList());
    }

    private BufferedImage pageImage(int pageOrder) {
        BufferedImage image = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        if (blankImageOrders.contains(pageOrder)) {
            return image;
        }
        if (faintMarkImageOrders.contains(pageOrder)) {
            image.setRGB(50, 100, new Color(244, 244, 244).getRGB());
            return image;
        }
        if (pageOrder == 2) {
            // p2 模拟横置扫描页：竖版原图需顺时针 90° 归正为横版。顺时针映射下源图右边缘
            // (x=96) 对应归正图底边，页码墨迹画在此处才会落入底部候选框。
            for (int y = 94; y <= 106; y++) {
                for (int x = 96; x <= 96; x++) {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
            return image;
        }
        if (twoFooterTargetImageOrders.contains(pageOrder)) {
            for (int y = 190; y <= 193; y++) {
                for (int x = 25; x <= 29; x++) image.setRGB(x, y, Color.BLACK.getRGB());
                for (int x = 70; x <= 74; x++) image.setRGB(x, y, Color.BLACK.getRGB());
            }
            return image;
        }
        for (int y = 190; y <= 193; y++) {
            for (int x = 48; x <= 52; x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
        if (coloredImageOrders.contains(pageOrder)) {
            image.setRGB(50, 191, Color.BLUE.getRGB());
        }
        return image;
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals("pixel " + x + "," + y, expected.getRGB(x, y), actual.getRGB(x, y));
            }
        }
    }

    private static final class FakeVlm implements VlmClient {
        final List<String> patternBatches = new ArrayList<String>();
        final List<String> locatePageIds = new ArrayList<String>();
        final List<String> verifyCalls = new ArrayList<String>();
        final List<String> auditPageIds = new ArrayList<String>();
        final List<String> lowConfidencePages = new ArrayList<String>();
        final List<String> noCandidatePages = new ArrayList<String>();
        final List<String> emptyGroupPages = new ArrayList<String>();
        final List<String> verifyNoPageNumRegionIds = new ArrayList<String>();
        final List<String> verifyManualRegionIds = new ArrayList<String>();
        final List<String> eraseFailurePages = new ArrayList<String>();
        final List<String> auditFailPages = new ArrayList<String>();
        final List<String> auditContradictionOncePages = new ArrayList<String>();
        final List<String> auditTargetIsBodyPages = new ArrayList<String>();
        final List<String> auditColorWarningPages = new ArrayList<String>();
        final List<String> locateManualPages = new ArrayList<String>();
        final List<String> patternRoiSafeLocatePages = new ArrayList<String>();
        final List<String> patternRoiLocatePageIds = new ArrayList<String>();
        final List<String> locateStatusCorrectionNoPageNumPages = new ArrayList<String>();
        final List<String> locateStatusCorrectionPageIds = new ArrayList<String>();
        final List<String> lowDirectionConfidencePages = new ArrayList<String>();
        final List<String> locateThrowsPages = new ArrayList<String>();
        final List<String> locateThrowsOncePages = new ArrayList<String>();
        final List<String> locateProtocolFailurePages = new ArrayList<String>();
        final List<String> locateProtocolFailureOncePages = new ArrayList<String>();
        final List<String> onLinePages = new ArrayList<String>();
        final List<String> validationRejectedPages = new ArrayList<String>();
        final List<String> verifyThrowsOnceRegionIds = new ArrayList<String>();
        final List<String> tightLocatePages = new ArrayList<String>();
        final List<String> coordinateRefinePages = new ArrayList<String>();
        final List<String> boundaryConflictPages = new ArrayList<String>();
        final List<String> duplicateRegionPages = new ArrayList<String>();
        final List<String> twoRegionAuditResidualPages = new ArrayList<String>();
        final List<String> twoRegionLocatePages = new ArrayList<String>();
        final List<String> emptyMultiRegionPages = new ArrayList<String>();
        final List<String> coordinateRefineCalls = new ArrayList<String>();
        final java.util.Map<String, Integer> auditCalls = new java.util.HashMap<String, Integer>();
        boolean patternProtocolFailure;
        boolean patternProtocolFailureOnce;
        int patternCorrectionCalls;
        int locateCorrectionCalls;

        static FakeVlm stable() {
            return new FakeVlm();
        }

        public PatternResponse pattern(List<VlmClient.PageImage> pages) {
            patternBatches.add(joinPageIds(pages));
            if (patternProtocolFailure) {
                throw new ResponseParser.ParseException("batch page ids mismatch", "{}");
            }
            if (patternProtocolFailureOnce) {
                patternProtocolFailureOnce = false;
                throw new ResponseParser.ParseException("page_ids must be classified exactly once", "{}");
            }
            PatternResponse response = new PatternResponse();
            PatternGroup group = new PatternGroup();
            group.group_id = "g-bottom";
            group.edge = "bottom";
            group.alignment = "center";
            group.layout_description = "bottom center";
            group.confidence = 0.99;
            group.locate_window = window(0.20, 0.80, 0.80, 1.00);
            for (VlmClient.PageImage page : pages) {
                PageDirection direction = new PageDirection();
                direction.page_id = page.getPageId();
                direction.reading_rotation = "p2".equals(page.getPageId()) ? 90 : 0;
                direction.confidence = lowDirectionConfidencePages.contains(page.getPageId()) ? 0.60 : 0.99;
                response.page_directions.add(direction);
                if (emptyGroupPages.contains(page.getPageId())) {
                    response.ungrouped_page_ids.add(page.getPageId());
                } else {
                    group.page_ids.add(page.getPageId());
                }
            }
            if (!group.page_ids.isEmpty()) response.pattern_groups.add(group);
            return response;
        }

        private com.xb.sgc.papererase.model.ExamModels.LocateWindow window(double x1, double y1, double x2, double y2) {
            com.xb.sgc.papererase.model.ExamModels.LocateWindow window = new com.xb.sgc.papererase.model.ExamModels.LocateWindow();
            window.x1 = x1;
            window.y1 = y1;
            window.x2 = x2;
            window.y2 = y2;
            return window;
        }

        public LocateResponse locate(VlmClient.PageImage page, PatternGroup group) {
            locatePageIds.add(page.getPageId());
            if (locateProtocolFailurePages.contains(page.getPageId())) {
                throw new ResponseParser.ParseException("page_number_text is required", "{}");
            }
            if (locateProtocolFailureOncePages.remove(page.getPageId())) {
                throw new ResponseParser.ParseException("page_number_text is required", "{}");
            }
            if (locateThrowsPages.contains(page.getPageId())) {
                throw new RuntimeException("locate failed");
            }
            if (locateThrowsOncePages.remove(page.getPageId())) {
                throw new RuntimeException("transient locate failure");
            }
            LocateResponse response = new LocateResponse();
            response.page_id = page.getPageId();
            response.reading_rotation = "p2".equals(page.getPageId())
                    && page.getImage().getWidth() < page.getImage().getHeight() ? 90 : 0;
            response.direction_confidence = lowDirectionConfidencePages.contains(page.getPageId()) ? 0.60 : 0.99;
            response.evidence = "fake";
            BodyBoundary boundary = new BodyBoundary();
            boundary.y = "p2".equals(page.getPageId()) ? 0.80
                    : boundaryConflictPages.contains(page.getPageId()) ? 0.95
                    : coordinateRefinePages.contains(page.getPageId()) ? 0.88 : 0.90;
            boundary.basis = "java";
            response.nearest_body_boundary = boundary;
            if (locateManualPages.contains(page.getPageId())) {
                response.status = "manual_review";
                return response;
            }
            if (noCandidatePages.contains(page.getPageId())) {
                response.status = "no_pagenum";
                return response;
            }
            response.status = "safe_to_erase";
            EraseRegion region = new EraseRegion();
            region.region_id = "r1";
            region.x1 = eraseFailurePages.contains(page.getPageId()) ? 0.10
                    : tightLocatePages.contains(page.getPageId()) ? 0.42
                    : coordinateRefinePages.contains(page.getPageId()) ? 0.40 : 0.45;
            region.y1 = validationRejectedPages.contains(page.getPageId()) ? 0.40
                    : boundaryConflictPages.contains(page.getPageId()) ? 0.94
                    : tightLocatePages.contains(page.getPageId()) ? 0.95 : 0.94;
            region.x2 = eraseFailurePages.contains(page.getPageId()) ? 0.20
                    : tightLocatePages.contains(page.getPageId()) ? 0.58
                    : coordinateRefinePages.contains(page.getPageId()) ? 0.45 : 0.55;
            region.y2 = validationRejectedPages.contains(page.getPageId()) ? 0.44
                    : boundaryConflictPages.contains(page.getPageId()) ? 0.98
                    : tightLocatePages.contains(page.getPageId()) ? 0.97 : 0.98;
            region.page_number_text = "1";
            region.same_line_metadata = "page only";
            region.on_line = onLinePages.contains(page.getPageId());
            region.confidence = lowConfidencePages.contains(page.getPageId()) ? 0.80 : 0.99;
            region.safety_margin = "blank";
            response.regions.add(region);
            if (twoRegionAuditResidualPages.contains(page.getPageId()) || twoRegionLocatePages.contains(page.getPageId())) {
                region.x1 = 0.20;
                region.x2 = 0.35;
                EraseRegion second = new EraseRegion();
                second.region_id = "r2";
                second.x1 = 0.65;
                second.y1 = region.y1;
                second.x2 = 0.80;
                second.y2 = region.y2;
                second.page_number_text = "2";
                second.same_line_metadata = "";
                second.on_line = false;
                second.confidence = region.confidence;
                second.safety_margin = "blank";
                response.regions.add(second);
            }
            if (emptyMultiRegionPages.contains(page.getPageId())) {
                EraseRegion second = new EraseRegion();
                second.region_id = "r2";
                second.x1 = 0.65;
                second.y1 = region.y1;
                second.x2 = 0.80;
                second.y2 = region.y2;
                second.page_number_text = "2";
                second.same_line_metadata = "";
                second.on_line = false;
                second.confidence = region.confidence;
                second.safety_margin = "blank";
                response.regions.add(second);
            }
            if (duplicateRegionPages.contains(page.getPageId())) {
                EraseRegion duplicate = new EraseRegion();
                duplicate.region_id = "r2";
                duplicate.x1 = region.x1;
                duplicate.y1 = region.y1;
                duplicate.x2 = region.x2;
                duplicate.y2 = region.y2;
                duplicate.page_number_text = "I";
                duplicate.same_line_metadata = "";
                duplicate.on_line = false;
                duplicate.confidence = region.confidence;
                duplicate.safety_margin = "blank";
                response.regions.add(duplicate);
            }
            return response;
        }

        @Override
        public LocateResponse locate(VlmClient.PageImage page, PatternGroup group, VlmClient.RoiImage roi) {
            patternRoiLocatePageIds.add(page.getPageId());
            if (!patternRoiSafeLocatePages.contains(page.getPageId())) {
                LocateResponse manual = new LocateResponse();
                manual.page_id = page.getPageId();
                manual.reading_rotation = 0;
                manual.direction_confidence = 0.99;
                manual.status = "manual_review";
                manual.evidence = "fake ROI remains uncertain";
                return manual;
            }
            LocateResponse safe = new LocateResponse();
            safe.page_id = page.getPageId();
            safe.reading_rotation = 0;
            safe.direction_confidence = 0.99;
            safe.status = "safe_to_erase";
            safe.evidence = "fake pattern ROI locate";
            BodyBoundary boundary = new BodyBoundary();
            boundary.y = 0.50;
            boundary.basis = "ROI body boundary";
            safe.nearest_body_boundary = boundary;
            EraseRegion region = new EraseRegion();
            region.region_id = "r1";
            // Fake pattern ROI is x=0..1, y=0.63..1.00; this maps back to the footer target.
            region.x1 = 0.45;
            region.y1 = 0.83;
            region.x2 = 0.55;
            region.y2 = 0.91;
            region.page_number_text = "1";
            region.same_line_metadata = "page only";
            region.confidence = 0.99;
            region.safety_margin = "blank";
            safe.regions.add(region);
            return safe;
        }

        @Override
        public LocateResponse relocateCoordinateRefinement(VlmClient.PageImage page, PatternGroup group,
                                                            EraseRegion semanticAnchor, VlmClient.RoiImage roi) {
            if (emptyMultiRegionPages.contains(page.getPageId())) {
                coordinateRefineCalls.add(page.getPageId() + ":" + semanticAnchor.region_id);
                LocateResponse response = new LocateResponse();
                response.page_id = page.getPageId();
                response.reading_rotation = 0;
                response.direction_confidence = 0.99;
                response.status = "safe_to_erase";
                response.evidence = "empty footer region relocated";
                EraseRegion local = new EraseRegion();
                local.region_id = semanticAnchor.region_id;
                // 空框精修现改为完整底部 20% ROI：该页左侧真实页码位于整图 x=25..29，
                // 因此返回的 ROI 相对框必须落在 x=0.20..0.35 才能映射回同一真实墨迹。
                local.x1 = 0.20;
                local.y1 = 0.72;
                local.x2 = 0.35;
                local.y2 = 0.88;
                local.page_number_text = semanticAnchor.page_number_text;
                local.same_line_metadata = semanticAnchor.same_line_metadata;
                local.confidence = semanticAnchor.confidence;
                local.safety_margin = semanticAnchor.safety_margin;
                response.regions.add(local);
                return response;
            }
            if (!twoRegionAuditResidualPages.contains(page.getPageId())) {
                return VlmClient.super.relocateCoordinateRefinement(page, group, semanticAnchor, roi);
            }
            coordinateRefineCalls.add(page.getPageId() + ":" + semanticAnchor.region_id);
            LocateResponse response = new LocateResponse();
            response.page_id = page.getPageId();
            response.reading_rotation = 0;
            response.direction_confidence = 0.99;
            response.status = "safe_to_erase";
            response.evidence = "two-region coordinate refinement";
            EraseRegion local = new EraseRegion();
            local.region_id = semanticAnchor.region_id;
            // pattern 退出后，每个候选都使用“候选框+正文边界+固定 margin”的独立 ROI。
            local.x1 = 0.44;
            local.x2 = 0.66;
            local.y1 = 0.60;
            local.y2 = 0.82;
            local.page_number_text = semanticAnchor.page_number_text;
            local.same_line_metadata = semanticAnchor.same_line_metadata;
            local.confidence = semanticAnchor.confidence;
            local.safety_margin = semanticAnchor.safety_margin;
            response.regions.add(local);
            if ("r1".equals(semanticAnchor.region_id)) {
                EraseRegion second = new EraseRegion();
                second.region_id = "r2";
                second.x1 = 0.44;
                second.x2 = 0.66;
                second.y1 = 0.60;
                second.y2 = 0.82;
                second.page_number_text = "2";
                second.same_line_metadata = "";
                second.confidence = semanticAnchor.confidence;
                second.safety_margin = semanticAnchor.safety_margin;
                response.regions.add(second);
            }
            return response;
        }

        public PatternResponse correctPatternAfterProtocolError(List<VlmClient.PageImage> pages,
                                                                 List<String> expectedPageIds, String error) {
            patternCorrectionCalls++;
            return pattern(pages);
        }

        public LocateResponse correctLocateAfterProtocolError(VlmClient.PageImage page, PatternGroup group, String error) {
            locateCorrectionCalls++;
            return locate(page, group);
        }

        public LocateResponse correctLocateStatusAfterManualReview(VlmClient.PageImage page, PatternGroup group) {
            locateStatusCorrectionPageIds.add(page.getPageId());
            LocateResponse response = new LocateResponse();
            response.page_id = page.getPageId();
            response.reading_rotation = 0;
            response.direction_confidence = 0.99;
            response.status = locateStatusCorrectionNoPageNumPages.contains(page.getPageId()) ? "no_pagenum" : "manual_review";
            response.evidence = "fake status correction";
            BodyBoundary boundary = new BodyBoundary();
            boundary.y = 0.90;
            boundary.basis = "java";
            response.nearest_body_boundary = boundary;
            return response;
        }

        public VerifyResponse verify(VlmClient.PageImage page, EraseRegion region, VlmClient.RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            verifyCalls.add(page.getPageId() + ":" + regionId);
            VerifyResponse response = new VerifyResponse();
            response.page_id = page.getPageId();
            response.region_id = regionId;
            response.allowed_scope = "page number only";
            response.evidence = "fake";
            String key = page.getPageId() + ":" + regionId;
            if (verifyThrowsOnceRegionIds.remove(key)) {
                throw new ResponseParser.ParseException("strict JSON object required", "not-json");
            }
            if (verifyNoPageNumRegionIds.contains(key)) {
                response.decision = "no_pagenum";
            } else if (verifyManualRegionIds.contains(key)) {
                response.decision = "manual_review";
            } else {
                response.decision = "safe_to_erase";
            }
            if ((coordinateRefinePages.contains(page.getPageId()) || boundaryConflictPages.contains(page.getPageId()))
                    && "coordinate_refinement_requested".equals(region.safety_margin)) {
                response.refined_region = new com.xb.sgc.papererase.model.ExamModels.LocalRegion();
                response.refined_region.x1 = boundaryConflictPages.contains(page.getPageId()) ? 0.42 : 0.45;
                response.refined_region.y1 = boundaryConflictPages.contains(page.getPageId()) ? 0.64 : 0.75;
                response.refined_region.x2 = boundaryConflictPages.contains(page.getPageId()) ? 0.68 : 0.55;
                response.refined_region.y2 = boundaryConflictPages.contains(page.getPageId()) ? 0.79 : 0.85;
            }
            return response;
        }

        public AuditResponse audit(VlmClient.PageImage original, VlmClient.PageImage erased, List<EraseRegion> regions,
                                   List<VlmClient.RoiImage> rois) {
            auditPageIds.add(original.getPageId());
            int call = auditCalls.containsKey(original.getPageId()) ? auditCalls.get(original.getPageId()) + 1 : 1;
            auditCalls.put(original.getPageId(), call);
            AuditResponse response = new AuditResponse();
            response.page_id = original.getPageId();
            response.original_target_is_non_body = !auditTargetIsBodyPages.contains(original.getPageId());
            response.decision = auditFailPages.contains(original.getPageId()) ? "manual_review" : "pass";
            response.body_unchanged = !auditFailPages.contains(original.getPageId());
            response.target_removed = !auditFailPages.contains(original.getPageId());
            response.background_acceptable = !auditFailPages.contains(original.getPageId())
                    && !auditColorWarningPages.contains(original.getPageId());
            response.evidence = "fake";
            if (auditContradictionOncePages.contains(original.getPageId()) && call == 1) {
                response.decision = "manual_review";
                response.body_unchanged = false;
                response.target_removed = true;
                response.background_acceptable = true;
                response.evidence = "reported body change inside approved target";
            }
            if (auditTargetIsBodyPages.contains(original.getPageId())) {
                response.decision = "manual_review";
                response.body_unchanged = true;
                response.target_removed = true;
            }
            if (twoRegionAuditResidualPages.contains(original.getPageId()) && call == 1) {
                response.decision = "manual_review";
                response.body_unchanged = true;
                response.target_removed = false;
                response.background_acceptable = false;
                response.evidence = "both footer targets retain readable glyphs";
            }
            return response;
        }

        private static String joinPageIds(List<VlmClient.PageImage> pages) {
            List<String> ids = new ArrayList<String>();
            for (VlmClient.PageImage page : pages) {
                ids.add(page.getPageId());
            }
            return String.join(",", ids);
        }
    }
}
