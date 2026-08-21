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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExamPipelineTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void batchesPatternWithOverlapAndRunsStableFastPathWithMandatoryAudit() throws Exception {
        FakeVlm fake = FakeVlm.stable();
        fake.onLinePages.add("p9");
        ExamOutcome outcome = new ExamPipeline(fake).process(exam(18, false), new ExamPipeline.RunContext());

        assertEquals(Arrays.asList("p1,p2,p3,p4,p5,p6,p7,p8", "p8,p9,p10,p11,p12,p13,p14,p15",
                "p15,p16,p17,p18"), fake.patternBatches);
        assertEquals(18, fake.locatePageIds.size());
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
        fake.locateManualPages.add("p7");
        fake.lowDirectionConfidencePages.add("p8");

        ExamOutcome outcome = new ExamPipeline(fake).process(exam(8, false), new ExamPipeline.RunContext());

        assertTrue(fake.verifyCalls.contains("p2:r1"));
        assertTrue("strong consensus with no candidate should verify edge ROI", fake.verifyCalls.contains("p3:edge"));
        assertEquals("no_pagenum", outcome.page("p3").getStatus());
        assertEquals("manual_review", outcome.page("p4").getStatus());
        assertEquals("manual_review", outcome.page("p5").getStatus());
        assertEquals("manual_review", outcome.page("p6").getStatus());
        assertEquals("manual_review", outcome.page("p7").getStatus());
        assertEquals("manual_review", outcome.page("p8").getStatus());
        assertEquals("safe_to_erase", outcome.page("p1").getStatus());
        assertFalse("verify-denied and erase-failed pages are not audited", fake.auditPageIds.contains("p4"));
        assertFalse(fake.auditPageIds.contains("p5"));
        assertTrue(fake.auditPageIds.contains("p6"));
    }

    @Test
    public void wholeExamFallsBackOnProtocolMappingFailureButSinglePageModelFailureIsIsolated() throws Exception {
        FakeVlm badPattern = FakeVlm.stable();
        badPattern.patternProtocolFailure = true;
        ExamOutcome protocolOutcome = new ExamPipeline(badPattern).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("manual_review", protocolOutcome.page("p1").getStatus());
        assertEquals("manual_review", protocolOutcome.page("p2").getStatus());
        assertEquals("pattern_protocol_error", protocolOutcome.getReason());

        FakeVlm singlePageFailure = FakeVlm.stable();
        singlePageFailure.locateThrowsPages.add("p2");
        ExamOutcome isolated = new ExamPipeline(singlePageFailure).process(exam(3, false), new ExamPipeline.RunContext());

        assertEquals("safe_to_erase", isolated.page("p1").getStatus());
        assertEquals("manual_review", isolated.page("p2").getStatus());
        assertEquals("safe_to_erase", isolated.page("p3").getStatus());
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
        if (pageOrder == 2) {
            for (int y = 94; y <= 106; y++) {
                for (int x = 3; x <= 3; x++) {
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
        final List<String> patternBatches = new ArrayList<String>();
        final List<String> locatePageIds = new ArrayList<String>();
        final List<String> verifyCalls = new ArrayList<String>();
        final List<String> auditPageIds = new ArrayList<String>();
        final List<String> lowConfidencePages = new ArrayList<String>();
        final List<String> noCandidatePages = new ArrayList<String>();
        final List<String> verifyNoPageNumRegionIds = new ArrayList<String>();
        final List<String> verifyManualRegionIds = new ArrayList<String>();
        final List<String> eraseFailurePages = new ArrayList<String>();
        final List<String> auditFailPages = new ArrayList<String>();
        final List<String> locateManualPages = new ArrayList<String>();
        final List<String> lowDirectionConfidencePages = new ArrayList<String>();
        final List<String> locateThrowsPages = new ArrayList<String>();
        final List<String> onLinePages = new ArrayList<String>();
        boolean patternProtocolFailure;

        static FakeVlm stable() {
            return new FakeVlm();
        }

        public PatternResponse pattern(List<VlmClient.PageImage> pages) {
            patternBatches.add(joinPageIds(pages));
            if (patternProtocolFailure) {
                throw new ResponseParser.ParseException("batch page ids mismatch", "{}");
            }
            PatternResponse response = new PatternResponse();
            PatternGroup group = new PatternGroup();
            group.group_id = "g-bottom";
            group.edge = "bottom";
            group.alignment = "center";
            group.layout_description = "bottom center";
            group.confidence = 0.99;
            for (VlmClient.PageImage page : pages) {
                PageDirection direction = new PageDirection();
                direction.page_id = page.getPageId();
                direction.reading_rotation = "p2".equals(page.getPageId()) ? 90 : 0;
                direction.confidence = lowDirectionConfidencePages.contains(page.getPageId()) ? 0.60 : 0.99;
                response.page_directions.add(direction);
                group.page_ids.add(page.getPageId());
            }
            response.pattern_groups.add(group);
            return response;
        }

        public LocateResponse locate(VlmClient.PageImage page, PatternGroup group) {
            locatePageIds.add(page.getPageId());
            if (locateThrowsPages.contains(page.getPageId())) {
                throw new RuntimeException("locate failed");
            }
            LocateResponse response = new LocateResponse();
            response.page_id = page.getPageId();
            response.evidence = "fake";
            BodyBoundary boundary = new BodyBoundary();
            boundary.y = "p2".equals(page.getPageId()) ? 0.80 : 0.90;
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
            region.x1 = eraseFailurePages.contains(page.getPageId()) ? 0.10 : 0.45;
            region.y1 = 0.94;
            region.x2 = eraseFailurePages.contains(page.getPageId()) ? 0.20 : 0.55;
            region.y2 = 0.98;
            region.page_number_text = "1";
            region.same_line_metadata = "page only";
            region.on_line = onLinePages.contains(page.getPageId());
            region.confidence = lowConfidencePages.contains(page.getPageId()) ? 0.80 : 0.99;
            region.safety_margin = "blank";
            response.regions.add(region);
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
            if (verifyNoPageNumRegionIds.contains(key)) {
                response.decision = "no_pagenum";
            } else if (verifyManualRegionIds.contains(key)) {
                response.decision = "manual_review";
            } else {
                response.decision = "safe_to_erase";
            }
            return response;
        }

        public AuditResponse audit(VlmClient.PageImage original, VlmClient.PageImage erased, List<EraseRegion> regions,
                                   List<VlmClient.RoiImage> rois) {
            auditPageIds.add(original.getPageId());
            AuditResponse response = new AuditResponse();
            response.page_id = original.getPageId();
            response.decision = auditFailPages.contains(original.getPageId()) ? "manual_review" : "pass";
            response.body_unchanged = !auditFailPages.contains(original.getPageId());
            response.target_removed = !auditFailPages.contains(original.getPageId());
            response.background_acceptable = !auditFailPages.contains(original.getPageId());
            response.evidence = "fake";
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
