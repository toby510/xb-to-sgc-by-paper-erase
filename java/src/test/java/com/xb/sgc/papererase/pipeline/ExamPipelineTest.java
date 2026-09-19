package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.image.RoiTransform;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.LocalRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.RelocateResponse;
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
    private final Set<Integer> blankImageOrders = new HashSet<Integer>();
    private final Set<Integer> faintMarkImageOrders = new HashSet<Integer>();

    @Test
    public void acceptsTrulyBlankPageAsNoPageNumberBeforeLowDirectionGate() throws Exception {
        blankImageOrders.add(1);
        FakeVlm fake = FakeVlm.stable();
        fake.lowDirectionConfidencePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("no_pagenum", outcome.page("p1").getStatus());
        assertTrue("blank pages do not need locate", fake.locatePageIds.isEmpty());
        assertTrue("blank pages do not need relocation", fake.relocateCalls.isEmpty());
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
    public void riskRelocateEdgeRoiAuditFailureAndSinglePageIsolation() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.lowConfidencePages.add("p2");
        fake.lowConfidencePages.add("p4");
        fake.noCandidatePages.add("p3");
        fake.relocateDeniedPages.add("p4");
        fake.eraseFailurePages.add("p5");
        fake.auditFailPages.add("p6");
        fake.auditColorWarningPages.add("p10");
        fake.locateManualPages.add("p7");
        fake.lowDirectionConfidencePages.add("p8");
        fake.validationRejectedPages.add("p9");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(10, false), new ExamPipeline.RunContext());

        assertTrue(fake.relocateCalls.toString(), fake.relocateCalls.contains("p2:r1"));
        assertFalse("no_pagenum is accepted page-locally without any ROI relocation probe",
                fake.relocateCalls.contains("p3:r1"));
        assertEquals("no_pagenum", outcome.page("p3").getStatus());
        assertEquals("manual_review", outcome.page("p4").getStatus());
        assertEquals("manual_review", outcome.page("p5").getStatus());
        assertEquals("manual_review", outcome.page("p6").getStatus());
        assertEquals("manual_review", outcome.page("p7").getStatus());
        assertEquals("manual_review", outcome.page("p8").getStatus());
        assertEquals("manual_review", outcome.page("p9").getStatus());
        // 页面结论必须同时带上具体门禁原因，便于事后定位是哪一层拒绝。
        assertTrue(outcome.page("p9").getReason().startsWith("validation_rejected: "));
        assertEquals("safe_to_erase", outcome.page("p10").getStatus());
        assertEquals("audit_pass_with_color_warning", outcome.page("p10").getReason());
        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertFalse("relocate-denied and erase-failed pages are not audited", fake.auditPageIds.contains("p4"));
        assertFalse(fake.auditPageIds.contains("p5"));
        assertTrue(fake.auditPageIds.contains("p6"));
        assertFalse("deterministic validation rejection must not spend relocate calls", fake.relocateCalls.contains("p9:r1"));
        // 门禁拒绝的框只做一次内存内诊断 audit（用于区分“伤正文”与“门禁过敏”），绝不交付擦除图。
        assertEquals(1, Collections.frequency(fake.auditPageIds, "p9"));
        for (ExamOutcome.PageOutcome page : outcome.getPages()) {
            assertTrue("final status leaked internal state: " + page.getStatus(),
                    "safe_to_erase".equals(page.getStatus())
                            || "no_pagenum".equals(page.getStatus())
                            || "manual_review".equals(page.getStatus()));
        }
    }

    @Test
    public void isolatesPageWhenLocateProtocolFails() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateProtocolFailurePages.add("p2");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertEquals("manual_review", outcome.page("p2").getStatus());
        assertEquals("locate_error", outcome.page("p2").getReason());
        // 同一张整页最多补发一次，补发仍失败即关闭该页，不做整页重跑。
        assertEquals(1, fake.locateRepairCalls);
        assertEquals("safe_to_erase", outcome.page("p3").getStatus());
    }

    @Test
    public void locallySnapsTightPageNumberBoxBeforeSpendingRelocateCall() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.tightLocatePages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals(outcome.page("p1").getReason(), "safe_to_erase", outcome.page("p1").getStatus());
        assertTrue("a safe local blank-band snap must not call VLM relocation", fake.relocateCalls.isEmpty());
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
    public void closesLocateManualReviewWithoutAnyOtherModelRole() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.locateManualPages.add("p1");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(1, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", outcome.page("p1").getStatus());
        assertEquals("locate_manual_review", outcome.page("p1").getReason());
        assertTrue("a non-safe locate conclusion cannot be relocated, erased or audited",
                fake.relocateCalls.isEmpty() && fake.auditPageIds.isEmpty());
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
        for (EraseRegion region : locate.regions) {
            region.nearest_body_boundary = boundary;
        }
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult("p1", "safe_to_erase", locate.regions), image);

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
        for (int y = 190; y <= 193; y++) {
            for (int x = 48; x <= 52; x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
        return image;
    }

    private static final class FakeVlm implements VlmClient {
        /** 与 ExamPipeline.RELOCATE_ROI_MARGIN_PIXELS 同口径，用于把候选框投影回 ROI 坐标。 */
        private static final int RELOCATE_MARGIN_PIXELS = 24;

        final List<String> locatePageIds = new ArrayList<String>();
        final List<String> relocateCalls = new ArrayList<String>();
        final List<String> auditPageIds = new ArrayList<String>();
        final List<String> lowConfidencePages = new ArrayList<String>();
        final List<String> noCandidatePages = new ArrayList<String>();
        final List<String> relocateDeniedPages = new ArrayList<String>();
        final List<String> eraseFailurePages = new ArrayList<String>();
        final List<String> auditFailPages = new ArrayList<String>();
        final List<String> auditContradictionOncePages = new ArrayList<String>();
        final List<String> auditTargetIsBodyPages = new ArrayList<String>();
        final List<String> auditColorWarningPages = new ArrayList<String>();
        final List<String> locateManualPages = new ArrayList<String>();
        final List<String> lowDirectionConfidencePages = new ArrayList<String>();
        final List<String> locateProtocolFailurePages = new ArrayList<String>();
        final List<String> validationRejectedPages = new ArrayList<String>();
        final List<String> tightLocatePages = new ArrayList<String>();
        final List<String> duplicateRegionPages = new ArrayList<String>();
        final java.util.Map<String, Integer> auditCalls = new java.util.HashMap<String, Integer>();
        int locateRepairCalls;

        static FakeVlm stable() {
            return new FakeVlm();
        }

        @Override
        public LocateResponse locate(VlmClient.PageImage page) {
            locatePageIds.add(page.getPageId());
            if (locateProtocolFailurePages.contains(page.getPageId())) {
                throw new ResponseParser.ParseException("page_number_text is required", "{}");
            }
            LocateResponse response = new LocateResponse();
            response.page_id = page.getPageId();
            response.reading_rotation = "p2".equals(page.getPageId())
                    && page.getImage().getWidth() < page.getImage().getHeight() ? 90 : 0;
            response.direction_confidence = lowDirectionConfidencePages.contains(page.getPageId()) ? 0.60 : 0.99;
            response.evidence = "fake";
            if (locateManualPages.contains(page.getPageId())) {
                response.status = "manual_review";
                return response;
            }
            if (noCandidatePages.contains(page.getPageId())) {
                response.status = "no_pagenum";
                return response;
            }
            response.status = "safe_to_erase";
            BodyBoundary boundary = new BodyBoundary();
            boundary.y = "p2".equals(page.getPageId()) ? 0.80 : 0.90;
            boundary.basis = "java";
            EraseRegion region = new EraseRegion();
            region.region_id = "r1";
            region.x1 = eraseFailurePages.contains(page.getPageId()) ? 0.10
                    : tightLocatePages.contains(page.getPageId()) ? 0.42 : 0.45;
            region.y1 = validationRejectedPages.contains(page.getPageId()) ? 0.40
                    : tightLocatePages.contains(page.getPageId()) ? 0.95 : 0.94;
            region.x2 = eraseFailurePages.contains(page.getPageId()) ? 0.20
                    : tightLocatePages.contains(page.getPageId()) ? 0.58 : 0.55;
            region.y2 = validationRejectedPages.contains(page.getPageId()) ? 0.44
                    : tightLocatePages.contains(page.getPageId()) ? 0.97 : 0.98;
            region.page_number_text = "1";
            region.same_line_metadata = "page only";
            region.on_line = false;
            region.confidence = lowConfidencePages.contains(page.getPageId()) ? 0.80 : 0.99;
            region.safety_margin = "blank";
            region.nearest_body_boundary = boundary;
            response.regions.add(region);
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
                duplicate.nearest_body_boundary = boundary;
                response.regions.add(duplicate);
            }
            return response;
        }

        /** 协议失败后的同页补发：只统计补发次数，语义仍由 locate 决定。 */
        @Override
        public LocateResponse locate(VlmClient.PageImage page, String repairInstruction) {
            locateRepairCalls++;
            return locate(page);
        }

        /**
         * ROI Relocate 只回测局部几何：这里把候选框自身几何投影回 ROI 坐标，等价于
         * “模型重测后与首轮一致”，映射与门禁复核仍全部由 ExamPipeline 完成。
         */
        @Override
        public RelocateResponse relocateCoordinateRefinement(VlmClient.PageImage page, EraseRegion semanticAnchor,
                                                             VlmClient.RoiImage roi) {
            String regionId = semanticAnchor == null ? "edge" : semanticAnchor.region_id;
            relocateCalls.add(page.getPageId() + ":" + regionId);
            RelocateResponse response = new RelocateResponse();
            response.page_id = page.getPageId();
            response.region_id = regionId;
            response.evidence = "fake";
            if (relocateDeniedPages.contains(page.getPageId())) {
                response.target_found = false;
                return response;
            }
            RoiTransform transform = relocateRoiTransform(page.getImage(), semanticAnchor);
            RoiTransform.LocalPoint topLeft = transform.fullNormalizedToLocalPoint(semanticAnchor.x1, semanticAnchor.y1);
            RoiTransform.LocalPoint bottomRight = transform.fullNormalizedToLocalPoint(semanticAnchor.x2, semanticAnchor.y2);
            response.target_found = true;
            response.refined_region = localRegion(topLeft.getX(), topLeft.getY(), bottomRight.getX(), bottomRight.getY());
            response.nearest_body_boundary = localBodyBoundary(semanticAnchor, transform);
            return response;
        }

        @Override
        public AuditResponse audit(VlmClient.PageImage original, VlmClient.PageImage erased, List<EraseRegion> regions,
                                   List<VlmClient.RoiImage> rois) {
            auditPageIds.add(original.getPageId());
            int call = auditCalls.containsKey(original.getPageId()) ? auditCalls.get(original.getPageId()) + 1 : 1;
            auditCalls.put(original.getPageId(), call);
            AuditResponse response = new AuditResponse();
            response.page_id = original.getPageId();
            response.original_target_is_non_body = !auditTargetIsBodyPages.contains(original.getPageId());
            response.decision = auditFailPages.contains(original.getPageId()) ? "manual_review" : "pass";
            response.body_changed = auditFailPages.contains(original.getPageId());
            response.target_removed = !auditFailPages.contains(original.getPageId());
            response.background_acceptable = !auditFailPages.contains(original.getPageId())
                    && !auditColorWarningPages.contains(original.getPageId());
            response.evidence = "fake";
            if (auditContradictionOncePages.contains(original.getPageId()) && call == 1) {
                response.decision = "manual_review";
                response.body_changed = true;
                response.target_removed = true;
                response.background_acceptable = true;
                response.evidence = "reported body change inside approved target";
            }
            if (auditTargetIsBodyPages.contains(original.getPageId())) {
                response.decision = "manual_review";
                response.body_changed = false;
                response.target_removed = true;
            }
            return response;
        }

        /** 与 ExamPipeline.relocateRoiTransform 同口径：候选框四周留固定边距，并补齐候选所属的页面边缘。 */
        private static RoiTransform relocateRoiTransform(BufferedImage image, EraseRegion region) {
            RoiTransform base = RoiTransform.fromNormalizedCandidate(image.getWidth(), image.getHeight(),
                    region, region.nearest_body_boundary, RELOCATE_MARGIN_PIXELS);
            int x = base.getX();
            int y = base.getY();
            int width = base.getWidth();
            int height = base.getHeight();
            switch (RegionValidator.edgeBand(region)) {
                case TOP:
                    height += y;
                    y = 0;
                    break;
                case BOTTOM:
                    height = image.getHeight() - y;
                    break;
                case LEFT:
                    width += x;
                    x = 0;
                    break;
                case RIGHT:
                    width = image.getWidth() - x;
                    break;
                default:
                    return base;
            }
            return new RoiTransform(x, y, width, height, image.getWidth(), image.getHeight());
        }

        private static LocalRegion localRegion(double x1, double y1, double x2, double y2) {
            LocalRegion region = new LocalRegion();
            region.x1 = x1;
            region.y1 = y1;
            region.x2 = x2;
            region.y2 = y2;
            return region;
        }

        /**
         * 局部正文边界同样按 ROI 坐标回话。锚点边界落在当前 ROI 之外时模型在该 ROI 内看不到它，
         * 按协议回 null，交给整页门禁判定，不伪造一个 ROI 内的边界。
         */
        private static BodyBoundary localBodyBoundary(EraseRegion semanticAnchor, RoiTransform transform) {
            BodyBoundary boundary = semanticAnchor.nearest_body_boundary;
            if (boundary == null || boundary.y == null) {
                return null;
            }
            try {
                RoiTransform.LocalPoint point = transform.fullNormalizedToLocalPoint(
                        (semanticAnchor.x1 + semanticAnchor.x2) / 2, boundary.y);
                BodyBoundary local = new BodyBoundary();
                local.y = point.getY();
                local.basis = boundary.basis;
                return local;
            } catch (IllegalArgumentException outsideRoi) {
                return null;
            }
        }
    }
}
