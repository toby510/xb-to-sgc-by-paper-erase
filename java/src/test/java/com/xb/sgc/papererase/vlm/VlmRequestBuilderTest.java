package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateWindow;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlmRequestBuilderTest {
    @Test
    public void verifyCarriesOnlyCurrentPageSemanticAnchor() {
        EraseRegion region = region("r1", "第2页", "英语试卷");
        String anchor = VlmClient.verifySemanticAnchor(region);
        assertTrue(anchor.contains("第2页"));
        assertTrue(anchor.contains("英语试卷"));
        assertEquals("", VlmClient.verifySemanticAnchor(null));
        assertFalse(anchor.contains("pattern_group"));
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
        assertTrue("局部精修必须重新测量当前 ROI 可见的正文边界，不能沿用整页幻觉边界",
                instruction.contains("ROI-relative nearest_body_boundary"));
        assertFalse(instruction.contains("Do not return or infer a body boundary"));

        String relocation = VlmClient.relocationInstruction(region);
        assertTrue(relocation.contains("nearest_body_boundary inside each matched region"));
    }

    @Test
    public void protocolCorrectionInstructionIsCarriedInOpenAiRequestBody() throws Exception {
        String patternCorrection = VlmClient.patternProtocolCorrectionInstruction(Arrays.asList("exam:1", "exam:2"),
                "page_ids must be classified exactly once");
        String body = VlmClient.OpenAiCompatible.buildRequestBody("qwen", "prompt", patternCorrection,
                Arrays.asList(new VlmClient.PageImage("exam:1", image(Color.WHITE)), new VlmClient.PageImage("exam:2", image(Color.WHITE))),
                java.util.Collections.<VlmClient.RoiImage>emptyList());
        String instruction = new ObjectMapper().readTree(body).path("messages").path(0).path("content").path(0).path("text").asText();

        assertTrue(instruction.contains("every input page_id exactly once"));
        assertTrue(instruction.contains("exam:1"));
        assertTrue(instruction.contains("exam:2"));
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

    @Test
    public void pagePreviewUsesConfiguredLongEdge() throws Exception {
        BufferedImage original = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        String dataUrl = new VlmClient.PageImage("p1", original).previewDataUrl(1280);
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(bytes));

        assertEquals(1280, preview.getWidth());
        assertEquals(640, preview.getHeight());
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
