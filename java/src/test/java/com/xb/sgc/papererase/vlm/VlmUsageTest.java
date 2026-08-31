package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies provider-specific usage is normalized without affecting business response parsing. */
public class VlmUsageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void normalizesOpenAiCompatibleUsageIncludingImageAndCachedTokens() throws Exception {
        VlmUsage usage = VlmUsage.fromOpenAiCompatible(mapper.readTree("{\"usage\":{"
                + "\"prompt_tokens\":4765,\"completion_tokens\":319,\"total_tokens\":5084,"
                + "\"prompt_tokens_details\":{\"image_tokens\":2147,\"text_tokens\":2618,\"cached_tokens\":2048}}}"));

        assertEquals(4765, usage.getInputTokens());
        assertEquals(319, usage.getOutputTokens());
        assertEquals(5084, usage.getTotalTokens());
        assertEquals(2147, usage.getImageTokens());
        assertEquals(2618, usage.getTextTokens());
        assertEquals(2048, usage.getCachedTokens());
        assertEquals(0, usage.getReasoningTokens());
    }

    @Test
    public void normalizesArkUsageIncludingReasoningTokens() throws Exception {
        VlmUsage usage = VlmUsage.fromArkResponses(mapper.readTree("{\"usage\":{"
                + "\"input_tokens\":4002,\"output_tokens\":242,\"total_tokens\":4244,"
                + "\"input_tokens_details\":{\"cached_tokens\":300},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":88}}}"));

        assertEquals(4002, usage.getInputTokens());
        assertEquals(242, usage.getOutputTokens());
        assertEquals(4244, usage.getTotalTokens());
        assertEquals(300, usage.getCachedTokens());
        assertEquals(88, usage.getReasoningTokens());
        assertEquals(0, usage.getImageTokens());
    }
}
