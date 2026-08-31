package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模型单价配置读取器。公司接入点可能使用私有折扣，因此金额只信任本地显式配置，绝不把
 * 网络页面或模型名称猜测成价格；单价缺失时仍保留真实 token，金额返回未知。
 */
public final class ModelPricing {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonNode models;

    private ModelPricing(JsonNode models) {
        this.models = models == null ? MAPPER.createObjectNode() : models;
    }

    public static ModelPricing load(Path path) {
        try {
            if (path != null && Files.isRegularFile(path)) {
                return new ModelPricing(MAPPER.readTree(path.toFile()).path("models"));
            }
        } catch (IOException ignored) {
            // 单价是可选观测配置，读取失败不能影响业务处理。
        }
        return new ModelPricing(null);
    }

    /** 返回 CNY 金额；任一必需单价未配置时返回 null，防止把未知成本伪装为 0。 */
    public Double costCny(String model, VlmUsage usage) {
        if (model == null || usage == null || !usage.isAvailable()) return null;
        return costCny(model, usage.getInputTokens(), usage.getCachedTokens(), usage.getOutputTokens());
    }

    /**
     * 按已落盘的真实 usage 重算费用。报告重建时使用它，使历史 usage 能应用后来补齐的本地单价；
     * 仅改变观测展示，绝不回写流水线结果或触发任何 VLM 调用。
     */
    public Double costCny(String model, long inputTokens, long cachedTokens, long outputTokens) {
        if (model == null) return null;
        JsonNode price = models.path(model);
        if (!price.isObject()) return null;
        Double input = decimal(price, "input_cny_per_million_tokens");
        Double cached = decimal(price, "cached_input_cny_per_million_tokens");
        Double output = decimal(price, "output_cny_per_million_tokens");
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cachedTokens = Math.min(inputTokens, Math.max(0L, cachedTokens));
        if (input == null || output == null || (cachedTokens > 0 && cached == null)) return null;
        double value = (inputTokens - cachedTokens) * input.doubleValue() / 1000000D
                + outputTokens * output.doubleValue() / 1000000D;
        if (cachedTokens > 0) value += cachedTokens * cached.doubleValue() / 1000000D;
        return value;
    }

    private static Double decimal(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isNumber() && value.doubleValue() >= 0D ? value.doubleValue() : null;
    }
}
