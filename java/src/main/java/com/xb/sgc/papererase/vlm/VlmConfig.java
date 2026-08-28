package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * VLM 配置中心：读取 provider、三个活跃角色的提示词/参数和预览尺寸。
 * active provider 决定请求通道，角色配置决定同一通道下各阶段的提示词与推理参数。
 */
public final class VlmConfig {
    private static final String[] ROLES = {"locate", "verify", "audit"};
    private final Map<String, RoleConfig> roles;
    private final int maxPreviewLongEdge;
    private final String providerKind;

    private VlmConfig(Map<String, RoleConfig> roles, int maxPreviewLongEdge, String providerKind) {
        this.roles = Collections.unmodifiableMap(new HashMap<String, RoleConfig>(roles));
        this.maxPreviewLongEdge = maxPreviewLongEdge;
        this.providerKind = providerKind;
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
        String providerKind = text(provider.path("kind"));
        if (!"openai-compatible".equals(providerKind) && !"ark-responses".equals(providerKind)) {
            throw new IllegalStateException("active provider kind is unsupported");
        }
        Map<String, RoleConfig> roles = new HashMap<String, RoleConfig>();
        for (String role : ROLES) {
            JsonNode roleRef = root.path("roles").path(role);
            JsonNode roleOverride = provider.path("roles").path(role);
            String prompt = text(roleRef.path("prompt"));
            String endpoint = resolveEndpoint(provider, roleOverride, env);
            String model = resolveText(roleOverride.path("model"), provider.path("model"), env, null);
            String apiKey = resolveApiKey(roleOverride.path("api_key"), provider.path("api_key"), env);
            int retries = roleOverride.path("network_retries").asInt(defaultRetries);
            int maxOutputTokens = roleOverride.path("max_output_tokens").asInt(0);
            String imageDetail = text(roleOverride.path("image_detail"));
            String thinkingType = resolveText(roleOverride.path("thinking").path("type"),
                    provider.path("thinking").path("type"), env, null);
            String reasoningEffort = resolveText(roleOverride.path("reasoning").path("effort"),
                    provider.path("reasoning").path("effort"), env, null);
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
            if ("ark-responses".equals(providerKind)
                    && !("auto".equals(imageDetail) || "high".equals(imageDetail) || "low".equals(imageDetail))) {
                throw new IllegalStateException(role + " Ark image detail is required");
            }
            roles.put(role, new RoleConfig(role, prompt, model, endpoint, apiKey, retries, maxOutputTokens, imageDetail,
                    thinkingType, reasoningEffort));
        }
        return new VlmConfig(roles, previewLongEdge, providerKind);
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

    /** 当前 active provider 的传输协议；客户端工厂据此选择请求/响应编解码方式。 */
    public String getProviderKind() {
        return providerKind;
    }

    /** 单个 VLM 角色配置：提示词、模型参数和请求级开关。 */
    public static final class RoleConfig {
        private final String role;
        private final String promptPath;
        private final String model;
        private final String endpoint;
        private final String apiKey;
        private final int retries;
        private final int maxOutputTokens;
        private final String imageDetail;
        private final String thinkingType;
        private final String reasoningEffort;

        private RoleConfig(String role, String promptPath, String model, String endpoint, String apiKey, int retries,
                           int maxOutputTokens, String imageDetail, String thinkingType, String reasoningEffort) {
            this.role = role;
            this.promptPath = promptPath;
            this.model = model;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.retries = retries;
            this.maxOutputTokens = maxOutputTokens;
            this.imageDetail = imageDetail;
            this.thinkingType = thinkingType;
            this.reasoningEffort = reasoningEffort;
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

        /** Ark Responses 的总输出预算（包含模型推理输出），0 代表当前协议不使用该字段。 */
        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        /** Ark 图片细节级别；pattern 可用 auto，其余安全判断保持 high。 */
        public String getImageDetail() {
            return imageDetail;
        }

        /** Ark 深度思考开关；未配置时不传该字段，交由接入点默认策略决定。 */
        public String getThinkingType() {
            return thinkingType;
        }

        /** Ark 推理工作量（low/medium/high）；仅在模型开启思考时配置。 */
        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public String safeSummary() {
            return role + " model=" + model + " endpoint=" + endpoint + " retries=" + retries + " api_key=<redacted>";
        }
    }
}
