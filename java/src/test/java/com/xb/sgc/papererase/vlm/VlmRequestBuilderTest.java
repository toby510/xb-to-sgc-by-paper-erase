package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlmRequestBuilderTest {
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
}
