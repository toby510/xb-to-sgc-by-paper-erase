package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class VlmConfig {
    private static final String[] ROLES = {"pattern", "locate", "verify", "audit"};
    private final Map<String, RoleConfig> roles;
    private final int maxPreviewLongEdge;

    private VlmConfig(Map<String, RoleConfig> roles, int maxPreviewLongEdge) {
        this.roles = Collections.unmodifiableMap(new HashMap<String, RoleConfig>(roles));
        this.maxPreviewLongEdge = maxPreviewLongEdge;
    }

    public static VlmConfig load(Path configPath) throws IOException {
        return load(configPath, System.getenv());
    }

    public static VlmConfig load(Path configPath, Map<String, String> env) throws IOException {
        if (configPath == null) {
            throw new IllegalArgumentException("configPath is required");
        }
        JsonNode root = new ObjectMapper().readTree(configPath.toFile());
        int previewLongEdge = root.path("defaults").path("max_preview_long_edge").asInt(1536);
        int defaultRetries = root.path("defaults").path("network_retries").asInt(2);
        String active = text(root.path("active"));
        JsonNode provider = root.path("providers").path(active);
        if (provider.isMissingNode()) {
            throw new IllegalStateException("active provider is missing");
        }
        Map<String, RoleConfig> roles = new HashMap<String, RoleConfig>();
        for (String role : ROLES) {
            JsonNode roleRef = root.path("roles").path(role);
            JsonNode roleOverride = provider.path("roles").path(role);
            String prompt = text(roleRef.path("prompt"));
            String endpoint = resolveEndpoint(provider, roleOverride, env);
            String model = resolveText(roleOverride.path("model"), provider.path("model"), env, "qwen3.8-max");
            String apiKey = resolveApiKey(roleOverride.path("api_key"), provider.path("api_key"), env);
            int retries = roleOverride.path("network_retries").asInt(defaultRetries);
            if (blank(prompt)) {
                throw new IllegalStateException(role + " prompt is required");
            }
            if (blank(endpoint)) {
                throw new IllegalStateException(role + " endpoint is required");
            }
            if (blank(model)) {
                throw new IllegalStateException(role + " model is required");
            }
            if (blank(apiKey)) {
                throw new IllegalStateException(role + " api key is required");
            }
            roles.put(role, new RoleConfig(role, prompt, model, endpoint, apiKey, retries));
        }
        return new VlmConfig(roles, previewLongEdge);
    }

    private static String resolveEndpoint(JsonNode provider, JsonNode roleOverride, Map<String, String> env) {
        if (hasBlankEnvOverride(roleOverride.path("endpoint"), env) || hasBlankEnvOverride(roleOverride.path("base_url"), env)) {
            return "";
        }
        String value = resolveText(roleOverride.path("endpoint"), roleOverride.path("base_url"), env, null);
        if (!blank(value)) {
            return value;
        }
        return resolveText(provider.path("base_url"), provider.path("endpoint"), env, null);
    }

    private static boolean hasBlankEnvOverride(JsonNode node, Map<String, String> env) {
        String envName = text(node.path("env"));
        return !blank(envName) && env.containsKey(envName) && blank(env.get(envName));
    }

    private static String resolveText(JsonNode primary, JsonNode fallback, Map<String, String> env, String defaultValue) {
        String value = resolveTextNode(primary, env);
        if (!blank(value)) {
            return value;
        }
        value = resolveTextNode(fallback, env);
        if (!blank(value)) {
            return value;
        }
        return defaultValue;
    }

    private static String resolveTextNode(JsonNode node, Map<String, String> env) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        String envName = text(node.path("env"));
        if (!blank(envName) && env.containsKey(envName)) {
            return env.get(envName);
        }
        return text(node.path("default"));
    }

    private static String resolveApiKey(JsonNode primary, JsonNode fallback, Map<String, String> env) {
        String value = resolveApiKeyNode(primary, env);
        return blank(value) ? resolveApiKeyNode(fallback, env) : value;
    }

    private static String resolveApiKeyNode(JsonNode node, Map<String, String> env) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String envName = item.asText();
                if (env.containsKey(envName) && !blank(env.get(envName))) {
                    return env.get(envName);
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().length() == 0;
    }

    public RoleConfig role(String role) {
        RoleConfig config = roles.get(role);
        if (config == null) {
            throw new IllegalArgumentException("unknown role: " + role);
        }
        return config;
    }

    public int getMaxPreviewLongEdge() {
        return maxPreviewLongEdge;
    }

    public static final class RoleConfig {
        private final String role;
        private final String promptPath;
        private final String model;
        private final String endpoint;
        private final String apiKey;
        private final int retries;

        private RoleConfig(String role, String promptPath, String model, String endpoint, String apiKey, int retries) {
            this.role = role;
            this.promptPath = promptPath;
            this.model = model;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.retries = retries;
        }

        public String getRole() {
            return role;
        }

        public String getPromptPath() {
            return promptPath;
        }

        public String getModel() {
            return model;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public int getRetries() {
            return retries;
        }

        public String safeSummary() {
            return role + " model=" + model + " endpoint=" + endpoint + " retries=" + retries + " api_key=<redacted>";
        }
    }
}
