package com.xb.sgc.papererase.vlm;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlmConfigTest {
    @Test
    public void loadsFourFailClosedRolesWithoutLeakingApiKey() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("XB_PAPER_ERASE_PATTERN_API_KEY", "pattern-secret");
        env.put("XB_PAPER_ERASE_LOCATE_API_KEY", "locate-secret");
        env.put("XB_PAPER_ERASE_VERIFY_API_KEY", "verify-secret");
        env.put("XB_PAPER_ERASE_AUDIT_API_KEY", "audit-secret");

        VlmConfig config = VlmConfig.load(Paths.get("../config/vlm-providers.json"), env);

        assertEquals("qwen3.8-max", config.role("pattern").getModel());
        assertEquals("qwen3.8-max", config.role("locate").getModel());
        assertEquals("qwen3.8-max", config.role("verify").getModel());
        assertEquals("qwen3.8-max", config.role("audit").getModel());
        assertEquals(2, config.role("pattern").getRetries());
        assertTrue(config.role("audit").getEndpoint().contains("/chat/completions"));
        assertFalse(config.role("pattern").safeSummary().contains("pattern-secret"));
    }

    @Test
    public void failsClosedWhenAnyRoleLacksEndpointOrApiKey() throws Exception {
        try {
            VlmConfig.load(Paths.get("../config/vlm-providers.json"), Collections.<String, String>emptyMap());
            throw new AssertionError("missing API keys must fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("api key"));
            assertFalse(expected.getMessage().contains("MST_QWEN_API_KEY="));
        }

        Map<String, String> env = new HashMap<String, String>();
        env.put("XB_PAPER_ERASE_PATTERN_API_KEY", "pattern-secret");
        env.put("XB_PAPER_ERASE_LOCATE_API_KEY", "locate-secret");
        env.put("XB_PAPER_ERASE_VERIFY_API_KEY", "verify-secret");
        env.put("XB_PAPER_ERASE_AUDIT_API_KEY", "audit-secret");
        env.put("XB_PAPER_ERASE_PATTERN_ENDPOINT", " ");
        try {
            VlmConfig.load(Paths.get("../config/vlm-providers.json"), env);
            throw new AssertionError("blank endpoint override must fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("endpoint"));
            assertFalse(expected.getMessage().contains("pattern-secret"));
        }
    }
}
