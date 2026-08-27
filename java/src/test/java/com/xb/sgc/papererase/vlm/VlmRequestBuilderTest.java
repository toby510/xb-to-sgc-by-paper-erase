package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateWindow;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlmRequestBuilderTest {
    @Test
    public void initialLocateCarriesCompletePatternStructureRatherThanOnlyGroupId() {
        PatternGroup group = new PatternGroup();
        group.group_id = "footer-spread-double-page";
        group.edge = "bottom";
        group.alignment = "spread";
        group.layout_description = "双页扫描，左右各有一个页码，中间可能有版权符号";
        group.locate_window = new LocateWindow();
        group.locate_window.x1 = 0.0;
        group.locate_window.y1 = 0.9;
        group.locate_window.x2 = 1.0;
        group.locate_window.y2 = 1.0;

        String evidence = VlmClient.locatePatternEvidence(group);

        assertTrue(evidence.contains("footer-spread-double-page"));
        assertTrue(evidence.contains("alignment=spread"));
        assertTrue(evidence.contains("左右各有一个页码"));
        assertTrue(evidence.contains("0.0,0.9,1.0,1.0"));
    }

    @Test
    public void spreadVerifyCarriesPatternButOrdinaryVerifyKeepsItsOriginalInput() {
        PatternGroup spread = new PatternGroup();
        spread.group_id = "footer-spread-double-page";
        spread.edge = "bottom";
        spread.alignment = "spread";
        spread.layout_description = "双页扫描，左右各有一个页码";
        spread.locate_window = new LocateWindow();
        spread.locate_window.x1 = 0.0;
        spread.locate_window.y1 = 0.9;
        spread.locate_window.x2 = 1.0;
        spread.locate_window.y2 = 1.0;

        PatternGroup ordinary = new PatternGroup();
        ordinary.alignment = "center";

        assertTrue(VlmClient.verifyPatternEvidence(spread).contains("alignment=spread"));
        assertTrue(VlmClient.verifyPatternEvidence(spread).contains("左右各有一个页码"));
        assertEquals("", VlmClient.verifyPatternEvidence(ordinary));
    }

    @Test
    public void coordinateRefinementCarriesFirstPassTextAsRoiSearchAnchor() {
        EraseRegion region = new EraseRegion();
        region.safety_margin = "coordinate_refinement_requested";
        region.page_number_text = "第2页(共8页)";
        region.same_line_metadata = "【英语(八)】";

        String instruction = VlmClient.refinementInstruction(region);

        assertTrue(instruction.contains("第2页(共8页)"));
        assertTrue(instruction.contains("【英语(八)】"));
        assertTrue(instruction.contains("Search the entire ROI"));
    }

    @Test
    public void protocolCorrectionInstructionIsCarriedInOpenAiRequestBody() throws Exception {
        String patternCorrection = VlmClient.patternProtocolCorrectionInstruction(Arrays.asList("exam:1", "exam:2"),
                "page_ids must be classified exactly once");
        String locateCorrection = VlmClient.locateProtocolCorrectionInstruction("exam:2", "page_number_text is required");
        String body = VlmClient.OpenAiCompatible.buildRequestBody("qwen", "prompt", patternCorrection + locateCorrection,
                Arrays.asList(new VlmClient.PageImage("exam:1", image(Color.WHITE)), new VlmClient.PageImage("exam:2", image(Color.WHITE))),
                java.util.Collections.<VlmClient.RoiImage>emptyList());
        String instruction = new ObjectMapper().readTree(body).path("messages").path(0).path("content").path(0).path("text").asText();

        assertTrue(instruction.contains("every input page_id exactly once"));
        assertTrue(instruction.contains("exam:1"));
        assertTrue(instruction.contains("exam:2"));
        assertTrue(instruction.contains("non-empty page_number_text"));
        assertTrue(instruction.contains("manual_review with empty regions"));
    }

    @Test
    public void auditManifestCarriesEveryApprovedRegionWithSafelyEscapedSemanticAnchors() throws Exception {
        EraseRegion first = region("r1", "第5页", "英语'试卷\nA");
        EraseRegion second = region("r2", "Page 5 of 8", "Booklet B");
        String manifest = VlmClient.auditTargetManifest(Arrays.asList(first, second));
        String body = VlmClient.OpenAiCompatible.buildRequestBody("qwen", "prompt", "Audit page_id=p1" + manifest,
                Arrays.asList(new VlmClient.PageImage("p1", image(Color.WHITE))), java.util.Collections.<VlmClient.RoiImage>emptyList());
        String instruction = new ObjectMapper().readTree(body).path("messages").path(0).path("content").path(0).path("text").asText();

        assertTrue(instruction.contains("TARGET_MANIFEST"));
        assertTrue(instruction.contains("region_id=r1"));
        assertTrue(instruction.contains("page_number_text='第5页'"));
        assertTrue(instruction.contains("same_line_metadata='英语’试卷 A'"));
        assertTrue(instruction.contains("region_id=r2"));
        assertTrue(instruction.contains("page_number_text='Page 5 of 8'"));
    }

    @Test
    public void openAiCompatibleBodyUsesOrderedTextAndImageUrlPartsForPagesAndRoi() throws Exception {
        VlmClient.PageImage p1 = new VlmClient.PageImage("p1", image(Color.WHITE), "ORIGINAL");
        VlmClient.PageImage p2 = new VlmClient.PageImage("p2", image(Color.LIGHT_GRAY), "ERASED");
        VlmClient.RoiImage roi = new VlmClient.RoiImage("p2", "r1", image(Color.GRAY));

        String body = VlmClient.OpenAiCompatible.buildRequestBody(
                "qwen3.8-max", "prompt text", "instruction text", Arrays.asList(p1, p2), Arrays.asList(roi));
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode content = root.path("messages").path(0).path("content");

        assertEquals("qwen3.8-max", root.path("model").asText());
        // 关掉 qwen3.8-max 的结构化 reasoning：开则单次 130~470s、26~47KB 思考，
        // 关则 ~10s 且坐标像素级一致、答案更完整。
        assertEquals(false, root.path("enable_thinking").asBoolean());
        assertTrue(content.isArray());
        assertEquals("text", content.path(0).path("type").asText());
        assertTrue(content.path(0).path("text").asText().contains("prompt text"));
        assertTrue(content.path(0).path("text").asText().contains("instruction text"));

        assertTextPart(content, 1, "PAGE_ID: p1\nIMAGE_ROLE: ORIGINAL");
        assertImagePart(content, 2);
        assertTextPart(content, 3, "PAGE_ID: p2\nIMAGE_ROLE: ERASED");
        assertImagePart(content, 4);
        assertTextPart(content, 5, "ROI_PAGE_ID: p2\nROI_REGION_ID: r1");
        assertImagePart(content, 6);

        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText())) {
                assertFalse(part.path("text").asText().contains("base64,"));
            }
        }
    }

    private void assertTextPart(JsonNode content, int index, String expected) {
        assertEquals("text", content.path(index).path("type").asText());
        assertEquals(expected, content.path(index).path("text").asText());
    }

    private void assertImagePart(JsonNode content, int index) {
        assertEquals("image_url", content.path(index).path("type").asText());
        String dataUrl = content.path(index).path("image_url").path("url").asText();
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
    }

    private BufferedImage image(Color color) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private static EraseRegion region(String id, String pageNumber, String metadata) {
        EraseRegion region = new EraseRegion();
        region.region_id = id;
        region.page_number_text = pageNumber;
        region.same_line_metadata = metadata;
        return region;
    }
}
