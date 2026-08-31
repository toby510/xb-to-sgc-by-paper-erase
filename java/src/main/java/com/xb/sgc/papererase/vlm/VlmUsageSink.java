package com.xb.sgc.papererase.vlm;

import java.util.List;

/** 旁路接收每次 VLM HTTP 调用的可观测数据；实现失败不得反向影响业务调用。 */
public interface VlmUsageSink {
    VlmUsageSink NOOP = new VlmUsageSink() {
        @Override
        public void record(String providerKind, String model, String role, int attempt, List<String> pageIds,
                           List<String> roiRegionIds, long elapsedMillis, VlmUsage usage, String errorType) {
            // 兼容不落盘的旧调用与单元测试。
        }
    };

    void record(String providerKind, String model, String role, int attempt, List<String> pageIds,
                List<String> roiRegionIds, long elapsedMillis, VlmUsage usage, String errorType);
}
