package com.xb.sgc.papererase.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.vlm.ModelPricing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读汇总一个 run 的页面结果、VLM usage 明细和流水线耗时。它用于报告展示，绝不参与
 * 擦除、审核或重试决策；缺少旧版产物时以“无数据”表达，不推测或补造数值。
 */
final class RunMetrics {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Snapshot read(Path runDir) throws IOException {
        return read(runDir, null);
    }

    static Snapshot read(Path runDir, ModelPricing pricing) throws IOException {
        Snapshot snapshot = new Snapshot();
        collectRegions(runDir.resolve("erased"), snapshot.pages);
        readUsage(runDir.resolve("_vlm_usage.ndjson"), snapshot, pricing);
        readProgress(runDir.resolve("_progress.ndjson"), snapshot);
        return snapshot;
    }

    private static void collectRegions(Path dir, Map<String, PageMetric> pages) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    collectRegions(child, pages);
                } else if (child.getFileName().toString().endsWith("_regions.json")) {
                    JsonNode root = MAPPER.readTree(child.toFile());
                    String pageId = root.path("page_id").asText();
                    if (pageId.length() == 0) continue;
                    PageMetric page = new PageMetric(pageId);
                    page.subject = child.getParent().getParent().getFileName().toString();
                    page.examId = root.path("exam_id").asText(child.getParent().getFileName().toString());
                    page.pageOrder = root.path("page_order").asInt(0);
                    page.status = root.path("status").asText();
                    JsonNode audit = root.path("audit");
                    page.bodyDamaged = audit.isObject() && audit.has("body_unchanged") && !audit.path("body_unchanged").asBoolean(true);
                    pages.put(pageId, page);
                }
            }
        }
    }

    private static void readUsage(Path file, Snapshot snapshot, ModelPricing pricing) throws IOException {
        if (!Files.isRegularFile(file)) return;
        List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().length() == 0) continue;
            JsonNode call;
            try { call = MAPPER.readTree(line); } catch (IOException ignored) { continue; }
            snapshot.usage.callCount++;
            String role = call.path("role").asText("unknown");
            String stageKey = role;
            if ("locate".equals(role) && call.path("roi_region_ids").isArray()
                    && call.path("roi_region_ids").size() > 0) {
                stageKey = "locate-coordinate-refine";
            } else if ("verify".equals(role)) {
                stageKey = "verify-ROI";
            }
            StageMetric stage = snapshot.stageMetrics.get(stageKey);
            if (stage == null) {
                stage = new StageMetric(stageKey);
                snapshot.stageMetrics.put(stageKey, stage);
            }
            stage.callCount++;
            boolean available = call.path("usage_available").asBoolean(false);
            if (available) {
                snapshot.usage.usageAvailableCallCount++;
                stage.usageAvailableCallCount++;
                snapshot.usage.inputTokens += longValue(call, "input_tokens");
                snapshot.usage.outputTokens += longValue(call, "output_tokens");
                snapshot.usage.totalTokens += longValue(call, "total_tokens");
                snapshot.usage.cachedTokens += longValue(call, "cached_tokens");
                snapshot.usage.imageTokens += longValue(call, "image_tokens");
                snapshot.usage.textTokens += longValue(call, "text_tokens");
                snapshot.usage.reasoningTokens += longValue(call, "reasoning_tokens");
                stage.inputTokens += longValue(call, "input_tokens");
                stage.outputTokens += longValue(call, "output_tokens");
                stage.totalTokens += longValue(call, "total_tokens");
            }
            // 没有真实 usage 的失败调用不能按 0 Token/0 元伪装为已知成本。
            Double cost = !available ? null : (pricing == null ? (call.hasNonNull("cost_cny") && call.path("cost_cny").isNumber()
                    ? call.path("cost_cny").doubleValue() : null)
                    : pricing.costCny(call.path("model").asText(null), longValue(call, "input_tokens"),
                    longValue(call, "cached_tokens"), longValue(call, "output_tokens")));
            if (cost != null) {
                snapshot.usage.knownCostCallCount++;
                snapshot.usage.costCny += cost.doubleValue();
                stage.knownCostCallCount++;
                stage.costCny += cost.doubleValue();
            }
            List<String> pageIds = strings(call.path("page_ids"));
            if (pageIds.isEmpty()) continue;
            // 多页同调没有供应商级的逐图 token 拆分能力；均摊可保证总量不重复，并保留原始 call 供追溯。
            double divisor = pageIds.size();
            for (String pageId : pageIds) {
                stage.pageIds.add(pageId);
                PageMetric page = snapshot.pages.get(pageId);
                if (page == null) {
                    page = new PageMetric(pageId);
                    snapshot.pages.put(pageId, page);
                }
                page.callCount++;
                Integer roleCount = page.roleCalls.get(role);
                page.roleCalls.put(role, roleCount == null ? 1 : roleCount + 1);
                if (!stageKey.equals(role)) {
                    Integer stageCount = page.roleCalls.get(stageKey);
                    page.roleCalls.put(stageKey, stageCount == null ? 1 : stageCount + 1);
                }
                page.inputTokens += longValue(call, "input_tokens") / divisor;
                page.outputTokens += longValue(call, "output_tokens") / divisor;
                page.totalTokens += longValue(call, "total_tokens") / divisor;
                page.reasoningTokens += longValue(call, "reasoning_tokens") / divisor;
                if (cost == null) page.costComplete = false;
                else page.costCny += cost.doubleValue() / divisor;
            }
        }
    }

    private static void readProgress(Path file, Snapshot snapshot) throws IOException {
        if (!Files.isRegularFile(file)) return;
        Map<String, List<JsonNode>> eventsByPage = new LinkedHashMap<String, List<JsonNode>>();
        for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.trim().length() == 0) continue;
            JsonNode event;
            try { event = MAPPER.readTree(line); } catch (IOException ignored) { continue; }
            long elapsed = longValue(event, "elapsed_ms");
            String pageId = event.path("page_id").asText();
            if (pageId.length() > 0) {
                List<JsonNode> events = eventsByPage.get(pageId);
                if (events == null) { events = new ArrayList<JsonNode>(); eventsByPage.put(pageId, events); }
                events.add(event);
            }
            if (elapsed <= 0) continue;
            String stage = event.path("stage").asText();
            if ("page".equals(stage) && event.path("page_id").asText().length() > 0) {
                PageMetric page = snapshot.pages.get(event.path("page_id").asText());
                if (page != null) page.elapsedMillis = elapsed;
            } else if ("exam".equals(stage) && event.path("exam_id").asText().length() > 0) {
                snapshot.examElapsedMillis.put(event.path("exam_id").asText(), elapsed);
            }
        }
        snapshot.buildFlowPaths(eventsByPage);
        snapshot.buildStageOutcomes(eventsByPage);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<String>();
        if (array.isArray()) for (JsonNode item : array) if (item.isTextual()) values.add(item.asText());
        return values;
    }

    private static long longValue(JsonNode node, String field) {
        return Math.max(0L, node.path(field).asLong(0L));
    }

    static final class Snapshot {
        final Map<String, PageMetric> pages = new LinkedHashMap<String, PageMetric>();
        final UsageSummary usage = new UsageSummary();
        final Map<String, Long> examElapsedMillis = new LinkedHashMap<String, Long>();
        final Map<String, StageMetric> stageMetrics = new LinkedHashMap<String, StageMetric>();
        final Map<String, StageOutcome> stageOutcomes = new LinkedHashMap<String, StageOutcome>();
        final Map<String, Integer> flowPaths = new LinkedHashMap<String, Integer>();
        final Map<String, Integer> flowTriggers = new LinkedHashMap<String, Integer>();
        final Map<String, Integer> flowOutcomes = new LinkedHashMap<String, Integer>();

        /** 每个已落盘页面都参与；Java 直接判空而未调用 VLM 的页面真实消耗为 0 Token。 */
        Distribution pageTokenDistribution() { return Distribution.of(pageDoubles("totalTokens", false)); }
        /** 未完整配置单价时不展示局部 0 元，避免把未知成本误读为免费。 */
        Distribution pageCostDistribution() {
            return usage.hasCompleteCost() ? Distribution.of(pageDoubles("costCny", false)) : Distribution.of(java.util.Collections.<Double>emptyList());
        }
        Distribution pageElapsedDistribution() { return Distribution.of(pageElapsed()); }
        Distribution examElapsedDistribution() {
            List<Double> values = new ArrayList<Double>();
            for (Long value : examElapsedMillis.values()) values.add(value.doubleValue());
            return Distribution.of(values);
        }

        int pageCount() { return pages.size(); }
        int usagePageCount() {
            int count = 0;
            for (PageMetric page : pages.values()) if (page.callCount > 0) count++;
            return count;
        }
        int usageExamCount() {
            java.util.Set<String> exams = new java.util.HashSet<String>();
            for (PageMetric page : pages.values()) if (page.callCount > 0 && page.examId.length() > 0) exams.add(page.examId);
            return exams.size();
        }
        double averageUsagePagesPerExam() {
            return usageExamCount() == 0 ? 0D : (double) usagePageCount() / (double) usageExamCount();
        }
        Distribution examTokenDistribution() { return Distribution.of(examTotals(false)); }
        Distribution examCostDistribution() {
            return usage.hasCompleteCost() ? Distribution.of(examTotals(true)) : Distribution.of(java.util.Collections.<Double>emptyList());
        }

        private List<Double> examTotals(boolean cost) {
            Map<String, Double> totals = new LinkedHashMap<String, Double>();
            for (PageMetric page : pages.values()) {
                if (page.callCount <= 0 || page.examId.length() == 0) continue;
                Double current = totals.get(page.examId);
                double value = cost ? page.costCny : page.totalTokens;
                totals.put(page.examId, (current == null ? 0D : current.doubleValue()) + value);
            }
            return new ArrayList<Double>(totals.values());
        }
        int examCount() {
            java.util.Set<String> exams = new java.util.HashSet<String>();
            for (PageMetric page : pages.values()) if (page.examId.length() > 0) exams.add(page.examId);
            return exams.size();
        }
        double averagePagesPerExam() { return examCount() == 0 ? 0D : (double) pageCount() / (double) examCount(); }
        int passedCount() {
            int count = 0;
            for (PageMetric page : pages.values()) if (page.isPassed()) count++;
            return count;
        }
        int bodyDamagedCount() {
            int count = 0;
            for (PageMetric page : pages.values()) if (page.bodyDamaged) count++;
            return count;
        }

        private List<Double> pageDoubles(String field, boolean costOnly) {
            List<Double> values = new ArrayList<Double>();
            for (PageMetric page : pages.values()) {
                if (page.callCount <= 0) continue;
                if (costOnly && !page.costComplete) continue;
                values.add("costCny".equals(field) ? page.costCny : page.totalTokens);
            }
            return values;
        }
        private List<Double> pageElapsed() {
            List<Double> values = new ArrayList<Double>();
            for (PageMetric page : pages.values()) if (page.elapsedMillis > 0) values.add((double) page.elapsedMillis);
            return values;
        }

        private void buildFlowPaths(Map<String, List<JsonNode>> eventsByPage) {
            flowPaths.clear();
            flowTriggers.clear();
            flowOutcomes.clear();
            for (PageMetric page : pages.values()) {
                List<JsonNode> events = eventsByPage.get(page.pageId);
                int locateCalls = 0;
                int verifyCalls = 0;
                int auditCalls = 0;
                boolean normalizedLocate = false;
                if (events != null) {
                    for (JsonNode event : events) {
                        String stage = event.path("stage").asText();
                        if ("locate".equals(stage)) {
                            if (!"started".equals(event.path("status").asText())) locateCalls++;
                            if ("normalized".equals(event.path("reason").asText())) normalizedLocate = true;
                        } else if ("verify".equals(stage) && !"started".equals(event.path("status").asText())) {
                            verifyCalls++;
                        } else if ("audit".equals(stage) && !"started".equals(event.path("status").asText())) {
                            auditCalls++;
                        }
                    }
                }
                locateCalls = page.roleCalls.containsKey("locate") ? page.roleCalls.get("locate") : locateCalls;
                verifyCalls = page.roleCalls.containsKey("verify") ? page.roleCalls.get("verify") : verifyCalls;
                auditCalls = page.roleCalls.containsKey("audit") ? page.roleCalls.get("audit") : auditCalls;
                if (normalizedLocate) increment(flowTriggers, "locate-旋转归一化后");
                if (locateCalls > 1 && !normalizedLocate) {
                    increment(flowTriggers, "locate重试");
                    increment(flowOutcomes, page.isPassed() ? "locate重试后成功" : "locate重试后失败");
                }
                if (page.roleCalls.containsKey("locate-coordinate-refine")) increment(flowTriggers, "locate坐标精修");
                if (verifyCalls > 0) {
                    increment(flowTriggers, "ROI verify");
                    increment(flowOutcomes, page.isPassed() ? "ROI verify后成功" : "ROI verify后失败");
                }
                if (auditCalls > 1) {
                    increment(flowTriggers, "audit重试");
                    increment(flowOutcomes, page.isPassed() ? "audit重试后成功" : "audit重试后失败");
                }
                String path;
                if (auditCalls > 1) path = page.isPassed() ? "audit重试后成功" : "audit重试后失败";
                else if (auditCalls == 1) path = page.isPassed() ? "audit首次通过" : "audit首次失败";
                else if (verifyCalls > 0) path = page.isPassed() ? "ROI verify成功" : "ROI verify后失败";
                else if (locateCalls > 1 && !normalizedLocate) path = page.isPassed() ? "locate重试后成功" : "locate重试后失败";
                else if (page.status.indexOf("validation") >= 0) path = "Java门禁拒绝";
                else if (page.isPassed()) path = "首次locate成功";
                else path = "locate失败或无页码判断";
                Integer count = flowPaths.get(path);
                flowPaths.put(path, count == null ? 1 : count + 1);
            }
        }

        private void buildStageOutcomes(Map<String, List<JsonNode>> eventsByPage) {
            stageOutcomes.clear();
            for (Map.Entry<String, List<JsonNode>> entry : eventsByPage.entrySet()) {
                String pageId = entry.getKey();
                List<JsonNode> events = entry.getValue();
                recordLatest(stageOutcomes, "locate", pageId, latestResult(events, "locate", null));
                if (hasReason(events, "locate", "normalized")) {
                    PageMetric page = pages.get(pageId);
                    recordLatest(stageOutcomes, "normalized", pageId, page != null && page.isPassed());
                }
                recordLatest(stageOutcomes, "verify", pageId, latestResult(events, "verify", null));
                recordLatest(stageOutcomes, "audit", pageId, latestResult(events, "audit", null));
            }
            StageMetric refine = stageMetrics.get("locate-coordinate-refine");
            if (refine != null) {
                StageOutcome outcome = stageOutcomes.get("refine");
                if (outcome == null) { outcome = new StageOutcome(); stageOutcomes.put("refine", outcome); }
                for (String pageId : refine.pageIds) {
                    PageMetric page = pages.get(pageId);
                    outcome.entered.add(pageId);
                    if (page != null && page.isPassed()) outcome.success.add(pageId);
                    else outcome.failed.add(pageId);
                }
            }
        }

        private boolean hasReason(List<JsonNode> events, String stage, String reason) {
            for (JsonNode event : events) {
                if (stage.equals(event.path("stage").asText()) && reason.equals(event.path("reason").asText())) return true;
            }
            return false;
        }

        private Boolean latestResult(List<JsonNode> events, String stage, String requiredReason) {
            Boolean result = null;
            for (JsonNode event : events) {
                if (!stage.equals(event.path("stage").asText()) || "started".equals(event.path("status").asText())) continue;
                if (requiredReason != null && !requiredReason.equals(event.path("reason").asText())) continue;
                String reason = event.path("reason").asText();
                result = "safe_to_erase".equals(reason) || "no_pagenum".equals(reason) || "pass".equals(reason);
            }
            return result;
        }

        private void recordLatest(Map<String, StageOutcome> outcomes, String stage, String pageId, Boolean result) {
            if (result == null) return;
            StageOutcome outcome = outcomes.get(stage);
            if (outcome == null) { outcome = new StageOutcome(); outcomes.put(stage, outcome); }
            outcome.entered.add(pageId);
            if (result.booleanValue()) outcome.success.add(pageId);
            else outcome.failed.add(pageId);
        }

        private void increment(Map<String, Integer> counts, String key) {
            Integer count = counts.get(key);
            counts.put(key, count == null ? 1 : count + 1);
        }
    }

    static final class StageMetric {
        final String role;
        int callCount;
        int usageAvailableCallCount;
        int knownCostCallCount;
        final java.util.Set<String> pageIds = new java.util.LinkedHashSet<String>();
        long inputTokens;
        long outputTokens;
        long totalTokens;
        double costCny;
        StageMetric(String role) { this.role = role; }
        boolean hasCompleteCost() { return callCount > 0 && callCount == knownCostCallCount; }
        int pageCount() { return pageIds.size(); }
    }

    static final class StageOutcome {
        final java.util.Set<String> entered = new java.util.LinkedHashSet<String>();
        final java.util.Set<String> success = new java.util.LinkedHashSet<String>();
        final java.util.Set<String> failed = new java.util.LinkedHashSet<String>();
    }

    static final class UsageSummary {
        int callCount;
        int usageAvailableCallCount;
        int knownCostCallCount;
        long inputTokens;
        long outputTokens;
        long totalTokens;
        long cachedTokens;
        long imageTokens;
        long textTokens;
        long reasoningTokens;
        double costCny;

        boolean hasCompleteCost() { return usageAvailableCallCount > 0 && usageAvailableCallCount == knownCostCallCount; }
    }

    static final class PageMetric {
        final String pageId;
        String subject = "";
        String examId = "";
        int pageOrder;
        String status = "";
        boolean bodyDamaged;
        int callCount;
        final Map<String, Integer> roleCalls = new LinkedHashMap<String, Integer>();
        double inputTokens;
        double outputTokens;
        double totalTokens;
        double reasoningTokens;
        double costCny;
        boolean costComplete = true;
        long elapsedMillis;

        PageMetric(String pageId) { this.pageId = pageId; }
        boolean isPassed() { return "safe_to_erase".equals(status) || "no_pagenum".equals(status); }
        String displayKey() { return subject + "/" + examId + "/第" + pageOrder + "页"; }
    }

    static final class Distribution {
        final int count;
        final double min;
        final double p50;
        final double average;
        final double p90;
        final double max;

        private Distribution(int count, double min, double p50, double average, double p90, double max) {
            this.count = count; this.min = min; this.p50 = p50; this.average = average; this.p90 = p90; this.max = max;
        }

        static Distribution of(List<Double> raw) {
            if (raw.isEmpty()) return new Distribution(0, 0, 0, 0, 0, 0);
            List<Double> values = new ArrayList<Double>(raw);
            Collections.sort(values);
            double sum = 0D;
            for (Double value : values) sum += value.doubleValue();
            return new Distribution(values.size(), values.get(0), percentile(values, .5D), sum / values.size(),
                    percentile(values, .9D), values.get(values.size() - 1));
        }

        private static double percentile(List<Double> values, double quantile) {
            double index = (values.size() - 1) * quantile;
            int lower = (int) Math.floor(index);
            int upper = (int) Math.ceil(index);
            if (lower == upper) return values.get(lower);
            double fraction = index - lower;
            return values.get(lower) * (1D - fraction) + values.get(upper) * fraction;
        }
    }
}
