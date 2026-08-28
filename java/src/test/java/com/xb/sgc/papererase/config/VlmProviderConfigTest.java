package com.xb.sgc.papererase.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlmProviderConfigTest {
    @Test
    public void dashscopeRolesCanOverrideModelBaseUrlEndpointAndApiKeyIndependently() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Paths.get("../config/vlm-providers.json").toFile());
        JsonNode roles = root.path("providers").path("dashscope").path("roles");

        assertRoleOverride(roles, "locate", "XB_PAPER_ERASE_LOCATE_MODEL");
        assertRoleOverride(roles, "verify", "XB_PAPER_ERASE_VERIFY_MODEL");
        assertRoleOverride(roles, "audit", "XB_PAPER_ERASE_AUDIT_MODEL");
    }

    private void assertRoleOverride(JsonNode roles, String role, String modelEnv) {
        JsonNode roleConfig = roles.path(role);
        assertFalse("missing dashscope role override for " + role, roleConfig.isMissingNode());
        assertEquals("qwen3.8-max", roleConfig.path("model").path("default").asText());
        assertEquals(modelEnv, roleConfig.path("model").path("env").asText());
        assertTrue(roleConfig.path("base_url").path("env").asText().contains(role.toUpperCase()));
        assertTrue(roleConfig.path("endpoint").path("env").asText().contains(role.toUpperCase()));
        assertTrue(roleConfig.path("api_key").isArray());
        assertTrue(roleConfig.path("api_key").get(0).asText().contains(role.toUpperCase()));
    }
}
