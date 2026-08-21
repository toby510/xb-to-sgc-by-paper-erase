package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
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

    @Test
    public void selectsArkResponsesFromActiveProviderWithoutRoleProviderFields() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("../config/vlm-providers.json")), StandardCharsets.UTF_8);
        Path arkConfig = Files.createTempFile("paper-erase-ark", ".json");
        try {
            Files.write(arkConfig, source.replace("\"active\": \"dashscope\"", "\"active\": \"ark\"")
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, String> env = new HashMap<String, String>();
            env.put("XB_PAPER_ERASE_ARK_API_KEY", "ark-secret");
            env.put("MST_XB_AI_ARK_MODEL_ENDPOINT", "ep-ark-vision");

            VlmConfig config = VlmConfig.load(arkConfig, env);
            assertEquals("ark-responses", config.getProviderKind());
            assertEquals("ep-ark-vision", config.role("audit").getModel());
            assertTrue(config.role("audit").getEndpoint().endsWith("/responses"));
            assertFalse(config.role("audit").safeSummary().contains("ark-secret"));
            assertTrue(VlmClient.create(config, Paths.get("..")).getClass().getName().endsWith("VlmClient$ArkResponses"));
            JsonNode roles = new ObjectMapper().readTree(source).path("roles");
            assertTrue(roles.path("pattern").path("provider").isMissingNode());
            Method maxOutput = VlmConfig.RoleConfig.class.getMethod("getMaxOutputTokens");
            Method detail = VlmConfig.RoleConfig.class.getMethod("getImageDetail");
            assertEquals(0, ((Integer) maxOutput.invoke(config.role("pattern"))).intValue());
            assertEquals("enabled", config.role("pattern").getThinkingType());
            assertEquals("low", config.role("pattern").getReasoningEffort());
            assertEquals("auto", detail.invoke(config.role("pattern")));
            assertEquals(0, ((Integer) maxOutput.invoke(config.role("audit"))).intValue());
            assertEquals("disabled", config.role("audit").getThinkingType());
            assertEquals("high", detail.invoke(config.role("audit")));
        } finally {
            Files.deleteIfExists(arkConfig);
        }
    }
}
