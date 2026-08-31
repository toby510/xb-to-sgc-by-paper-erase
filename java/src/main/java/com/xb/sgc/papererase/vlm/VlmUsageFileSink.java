package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将每次真实 HTTP 调用的用量旁路落盘为 run 级 NDJSON，供报告和跨模型比较读取。 */
public final class VlmUsageFileSink implements VlmUsageSink {
    private Path path;
    private final ModelPricing pricing;
    private final ObjectMapper mapper = new ObjectMapper();
    private long sequence;

    public VlmUsageFileSink(Path path, Path pricingPath) {
        this.path = path;
        this.pricing = ModelPricing.load(pricingPath);
    }

    /**
     * 先冻结提示词、后创建 run 目录时使用：目录准备好前采集为 no-op，不改变业务调用；绑定后才开始
     * 写本次 run 的 usage 明细。这样保持旧版“扫描前冻结提示词”的时点。
     */
    public synchronized void bind(Path path) {
        this.path = path;
    }

    @Override
    public synchronized void record(String providerKind, String model, String role, int attempt, List<String> pageIds,
                                    List<String> roiRegionIds, long elapsedMillis, VlmUsage usage, String errorType) {
        try {
            if (path == null) return;
            VlmUsage safeUsage = usage == null ? VlmUsage.unavailable() : usage;
            Double cost = pricing.costCny(model, safeUsage);
            Map<String, Object> line = new LinkedHashMap<String, Object>();
            line.put("timestamp_ms", System.currentTimeMillis());
            line.put("call_index", ++sequence);
            line.put("provider_kind", providerKind);
            line.put("model", model);
            line.put("role", role);
            line.put("attempt", attempt);
            line.put("page_ids", pageIds);
            line.put("roi_region_ids", roiRegionIds);
            line.put("elapsed_ms", elapsedMillis);
            line.put("status", errorType == null ? "completed" : "failed");
            line.put("error_type", errorType);
            line.put("usage_available", safeUsage.isAvailable());
            line.put("input_tokens", safeUsage.getInputTokens());
            line.put("output_tokens", safeUsage.getOutputTokens());
            line.put("total_tokens", safeUsage.getTotalTokens());
            line.put("cached_tokens", safeUsage.getCachedTokens());
            line.put("image_tokens", safeUsage.getImageTokens());
            line.put("text_tokens", safeUsage.getTextTokens());
            line.put("reasoning_tokens", safeUsage.getReasoningTokens());
            line.put("cost_cny", cost);
            line.put("cost_status", cost == null ? "pricing_not_configured" : "known");
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
                writer.write(mapper.writeValueAsString(line));
                writer.newLine();
            }
        } catch (IOException ignored) {
            // 观测落盘失败必须完全旁路；业务调用已经完成，不能因此改成失败。
        }
    }
}
