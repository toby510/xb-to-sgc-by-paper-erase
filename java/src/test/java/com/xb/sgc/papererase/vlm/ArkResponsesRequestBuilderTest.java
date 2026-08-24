package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArkResponsesRequestBuilderTest {
    @Test
    public void arkResponsesBodyUsesResponsesInputTextAndInputImage() throws Exception {
        Class<?> arkClient = Class.forName("com.xb.sgc.papererase.vlm.VlmClient$ArkResponses");
        Method builder = arkClient.getMethod("buildRequestBody", String.class, String.class, String.class,
                java.util.List.class, java.util.List.class);
        String body = (String) builder.invoke(null, "ep-ark-vision", "prompt", "instruction",
                Collections.singletonList(new VlmClient.PageImage("p1", image())),
                Collections.<VlmClient.RoiImage>emptyList());

        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode content = root.path("input").path(0).path("content");
        assertEquals("ep-ark-vision", root.path("model").asText());
        assertEquals("user", root.path("input").path(0).path("role").asText());
        assertEquals("input_text", content.path(0).path("type").asText());
        assertTrue(content.path(0).path("text").asText().contains("prompt"));
        assertEquals("input_image", content.path(2).path("type").asText());
        assertTrue(content.path(2).path("image_url").asText().startsWith("data:image/png;base64,"));
    }

    @Test
    public void arkResponsesExtractsOnlyCompletedOutputText() throws Exception {
        JsonNode response = new ObjectMapper().readTree("{\"status\":\"completed\",\"output\":[{\"content\":["
                + "{\"type\":\"reasoning\",\"text\":\"hidden\"},{\"type\":\"output_text\",\"text\":\"{\\\"ok\\\":true}\"}]}]}");
        assertEquals("{\"ok\":true}", VlmClient.ArkResponses.responseText(response));
    }

    @Test
    public void arkResponsesAddsLowReasoningForPatternWithoutOutputCap() throws Exception {
        Class<?> arkClient = Class.forName("com.xb.sgc.papererase.vlm.VlmClient$ArkResponses");
        Method builder = arkClient.getMethod("buildRequestBody", String.class, String.class, String.class,
                java.util.List.class, java.util.List.class, int.class, String.class, String.class, String.class);
        String body = (String) builder.invoke(null, "ep-ark-vision", "prompt", "instruction",
                Collections.singletonList(new VlmClient.PageImage("p1", image())),
                Collections.<VlmClient.RoiImage>emptyList(), 0, "auto", "enabled", "low");

        JsonNode root = new ObjectMapper().readTree(body);
        assertTrue(root.path("max_output_tokens").isMissingNode());
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("low", root.path("reasoning").path("effort").asText());
        assertEquals("auto", root.path("input").path(0).path("content").path(2).path("detail").asText());
    }

    @Test
    public void arkRequestCarriesProtocolCorrectionSemantics() throws Exception {
        Class<?> arkClient = Class.forName("com.xb.sgc.papererase.vlm.VlmClient$ArkResponses");
        Method builder = arkClient.getMethod("buildRequestBody", String.class, String.class, String.class,
                java.util.List.class, java.util.List.class);
        String instruction = VlmClient.patternProtocolCorrectionInstruction(Arrays.asList("p1", "p2"), "classification failed")
                + VlmClient.locateProtocolCorrectionInstruction("p2", "page_number_text is required");
        String body = (String) builder.invoke(null, "ep-ark-vision", "prompt", instruction,
                Collections.singletonList(new VlmClient.PageImage("p2", image())), Collections.<VlmClient.RoiImage>emptyList());
        String text = new ObjectMapper().readTree(body).path("input").path(0).path("content").path(0).path("text").asText();

        assertTrue(text.contains("exactly once"));
        assertTrue(text.contains("non-empty page_number_text"));
        assertTrue(text.contains("p1"));
        assertTrue(text.contains("p2"));
    }

    @Test
    public void arkRequestCarriesAllAuditTargetManifestEntries() throws Exception {
        EraseRegion first = region("r1", "371", "化学'卷");
        EraseRegion second = region("r2", "第5页", "Booklet B");
        Class<?> arkClient = Class.forName("com.xb.sgc.papererase.vlm.VlmClient$ArkResponses");
        Method builder = arkClient.getMethod("buildRequestBody", String.class, String.class, String.class,
                java.util.List.class, java.util.List.class);
        String body = (String) builder.invoke(null, "ep", "prompt", "Audit page_id=p1" + VlmClient.auditTargetManifest(Arrays.asList(first, second)),
                Collections.singletonList(new VlmClient.PageImage("p1", image())), Collections.<VlmClient.RoiImage>emptyList());
        String text = new ObjectMapper().readTree(body).path("input").path(0).path("content").path(0).path("text").asText();

        assertTrue(text.contains("TARGET_MANIFEST"));
        assertTrue(text.contains("region_id=r1"));
        assertTrue(text.contains("page_number_text='371'"));
        assertTrue(text.contains("same_line_metadata='化学’卷'"));
        assertTrue(text.contains("region_id=r2"));
        assertTrue(text.contains("page_number_text='第5页'"));
    }

    private static EraseRegion region(String id, String pageNumber, String metadata) {
        EraseRegion region = new EraseRegion();
        region.region_id = id;
        region.page_number_text = pageNumber;
        region.same_line_metadata = metadata;
        return region;
    }

    private BufferedImage image() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
