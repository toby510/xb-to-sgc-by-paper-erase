package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 供应商返回的真实 token 用量的统一视图。
 *
 * <p>该对象只承载 HTTP 响应中的 usage 字段，绝不参与提示词、重试、坐标判断或擦除决策；
 * 因而采集缺失时只能显示“未知”，不能影响业务主链路。</p>
 */
public final class VlmUsage {
    private final boolean available;
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final long cachedTokens;
    private final long imageTokens;
    private final long textTokens;
    private final long reasoningTokens;

    private VlmUsage(boolean available, long inputTokens, long outputTokens, long totalTokens,
                     long cachedTokens, long imageTokens, long textTokens, long reasoningTokens) {
        this.available = available;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
        this.imageTokens = imageTokens;
        this.textTokens = textTokens;
        this.reasoningTokens = reasoningTokens;
    }

    public static VlmUsage unavailable() {
        return new VlmUsage(false, 0, 0, 0, 0, 0, 0, 0);
    }

    /** OpenAI 兼容响应：prompt/completion 是输入/输出的供应商原始计量。 */
    public static VlmUsage fromOpenAiCompatible(JsonNode root) {
        JsonNode usage = root == null ? null : root.get("usage");
        if (usage == null || !usage.isObject()) return unavailable();
        JsonNode inputDetails = usage.path("prompt_tokens_details");
        JsonNode outputDetails = usage.path("completion_tokens_details");
        return new VlmUsage(true,
                nonNegative(usage.path("prompt_tokens")),
                nonNegative(usage.path("completion_tokens")),
                nonNegative(usage.path("total_tokens")),
                nonNegative(inputDetails.path("cached_tokens")),
                nonNegative(inputDetails.path("image_tokens")),
                nonNegative(inputDetails.path("text_tokens")),
                nonNegative(outputDetails.path("reasoning_tokens")));
    }

    /** Ark Responses 响应：input/output 字段与 OpenAI 兼容协议不同，统一映射到本对象。 */
    public static VlmUsage fromArkResponses(JsonNode root) {
        JsonNode usage = root == null ? null : root.get("usage");
        if (usage == null || !usage.isObject()) return unavailable();
        return new VlmUsage(true,
                nonNegative(usage.path("input_tokens")),
                nonNegative(usage.path("output_tokens")),
                nonNegative(usage.path("total_tokens")),
                nonNegative(usage.path("input_tokens_details").path("cached_tokens")),
                nonNegative(usage.path("input_tokens_details").path("image_tokens")),
                nonNegative(usage.path("input_tokens_details").path("text_tokens")),
                nonNegative(usage.path("output_tokens_details").path("reasoning_tokens")));
    }

    private static long nonNegative(JsonNode node) {
        return node != null && node.canConvertToLong() ? Math.max(0L, node.asLong()) : 0L;
    }

    public boolean isAvailable() { return available; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getCachedTokens() { return cachedTokens; }
    public long getImageTokens() { return imageTokens; }
    public long getTextTokens() { return textTokens; }
    public long getReasoningTokens() { return reasoningTokens; }
}
