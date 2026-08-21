package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Collections;

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
