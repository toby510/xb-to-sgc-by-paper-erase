package com.xb.sgc.papererase.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.vlm.ModelPricing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多模型 run 的只读对比报告。该工具只读取既有 run 目录，绝不重新调用 VLM、修改图片或重建
 * 单模型报告；每次对比写入与 runs 同级的独立“多模型比对”目录。
 */
public final class RunComparisonWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0");
    private static final DecimalFormat PERCENT = new DecimalFormat("0.00%");
    private static final DecimalFormat MONEY = new DecimalFormat("0.0000");
    private final ModelPricing pricing;

    public RunComparisonWriter() {
        this(null);
    }

    public RunComparisonWriter(Path pricingConfig) {
        this.pricing = pricingConfig == null ? null : ModelPricing.load(pricingConfig);
    }

    public Path write(List<Path> runDirs) throws IOException {
        if (runDirs == null || runDirs.size() < 2 || runDirs.size() > 3) {
            throw new IllegalArgumentException("compare requires 2 or 3 run directories");
        }
        List<ComparedRun> runs = new ArrayList<ComparedRun>();
        Path runsRoot = null;
        for (int i = 0; i < runDirs.size(); i++) {
            Path runDir = runDirs.get(i).toAbsolutePath().normalize();
            if (!Files.isRegularFile(runDir.resolve("run.json"))) {
                throw new IllegalArgumentException("not a run directory: " + runDir);
            }
            Path parent = runDir.getParent();
            if (runsRoot == null) runsRoot = parent;
            else if (!runsRoot.equals(parent)) {
                throw new IllegalArgumentException("all compared runs must share the same runs directory");
            }
            runs.add(new ComparedRun("模型" + (char) ('A' + i), runDir, model(runDir), RunMetrics.read(runDir, pricing)));
        }
        Path output = runsRoot.getParent().resolve("多模型比对")
                .resolve(new SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date()));
        Files.createDirectories(output);
        Path report = output.resolve("模型对比报告.md");
        Files.write(report, render(runs).getBytes(StandardCharsets.UTF_8));
        return report;
    }

    private String render(List<ComparedRun> runs) {
        StringBuilder report = new StringBuilder("# 多模型运行比对报告\n\n");
        report.append("说明：本报告只比较既有运行产物；Token 来自各 run 的 `_vlm_usage.ndjson`，缺少该文件的历史 run 不补造数据。\n\n");
        appendMetadata(report, runs);
        appendAccuracy(report, runs);
        appendCostAndPerformance(report, runs);
        appendDifferences(report, runs);
        return report.toString();
    }

    private void appendMetadata(StringBuilder report, List<ComparedRun> runs) {
        report.append("## 运行元数据\n\n| 对比项 |");
        for (ComparedRun run : runs) report.append(' ').append(run.label).append(" |");
        report.append("\n| --- |");
        for (int i = 0; i < runs.size(); i++) report.append(" --- |");
        report.append("\n| 模型 |");
        for (ComparedRun run : runs) report.append(' ').append(escape(run.model)).append(" |");
        report.append("\n| Run目录 |");
        for (ComparedRun run : runs) report.append(' ').append(escape(run.dir.toString())).append(" |");
        report.append("\n| 图片数 |");
        for (ComparedRun run : runs) report.append(' ').append(run.metrics.pageCount()).append(" |");
        report.append("\n\n");
    }

    private void appendAccuracy(StringBuilder report, List<ComparedRun> runs) {
        report.append("## 准确度与安全\n\n");
        tableHeader(report, runs);
        tableRow(report, "准确率", runs, new Value() { public String value(ComparedRun run) {
            return ratio(run.metrics.passedCount(), run.metrics.pageCount());
        }});
        tableRow(report, "成功或正确无页码", runs, new Value() { public String value(ComparedRun run) {
            return String.valueOf(run.metrics.passedCount());
        }});
        tableRow(report, "人工审核或异常", runs, new Value() { public String value(ComparedRun run) {
            return String.valueOf(run.metrics.pageCount() - run.metrics.passedCount());
        }});
        tableRow(report, "疑似正文损伤", runs, new Value() { public String value(ComparedRun run) {
            return String.valueOf(run.metrics.bodyDamagedCount());
        }});
        report.append("\n");
    }

    private void appendCostAndPerformance(StringBuilder report, List<ComparedRun> runs) {
        report.append("## 成本与性能\n\n");
        tableHeader(report, runs);
        tableRow(report, "VLM 调用次数（含重试）", runs, new Value() { public String value(ComparedRun run) {
            return String.valueOf(run.metrics.usage.callCount);
        }});
        tableRow(report, "单图平均总 Token", runs, new Value() { public String value(ComparedRun run) {
            return tokens(run.metrics.pageTokenDistribution().average, run.metrics.pageTokenDistribution().count);
        }});
        tableRow(report, "单图总 Token P90", runs, new Value() { public String value(ComparedRun run) {
            return tokens(run.metrics.pageTokenDistribution().p90, run.metrics.pageTokenDistribution().count);
        }});
        tableRow(report, "单图平均成本（CNY）", runs, new Value() { public String value(ComparedRun run) {
            RunMetrics.Distribution d = run.metrics.pageCostDistribution();
            return d.count == 0 ? "未配置或无数据" : MONEY.format(d.average);
        }});
        tableRow(report, "单图平均全流程耗时", runs, new Value() { public String value(ComparedRun run) {
            return seconds(run.metrics.pageElapsedDistribution().average, run.metrics.pageElapsedDistribution().count);
        }});
        tableRow(report, "整卷平均耗时", runs, new Value() { public String value(ComparedRun run) {
            return seconds(run.metrics.examElapsedDistribution().average, run.metrics.examElapsedDistribution().count);
        }});
        report.append("\n");
    }

    private void appendDifferences(StringBuilder report, List<ComparedRun> runs) {
        report.append("## 结果差异明细\n\n");
        Map<String, List<RunMetrics.PageMetric>> byPage = new LinkedHashMap<String, List<RunMetrics.PageMetric>>();
        for (ComparedRun run : runs) {
            for (RunMetrics.PageMetric page : run.metrics.pages.values()) {
                List<RunMetrics.PageMetric> values = byPage.get(page.pageId);
                if (values == null) {
                    values = new ArrayList<RunMetrics.PageMetric>();
                    byPage.put(page.pageId, values);
                }
                values.add(page);
            }
        }
        List<String> keys = new ArrayList<String>(byPage.keySet());
        Collections.sort(keys);
        boolean any = false;
        report.append("| 页面 |");
        for (ComparedRun run : runs) report.append(' ').append(run.label).append(" |");
        report.append(" 差异结论 |\n| --- |");
        for (int i = 0; i < runs.size(); i++) report.append(" --- |");
        report.append(" --- |\n");
        for (String key : keys) {
            List<String> statuses = new ArrayList<String>();
            RunMetrics.PageMetric display = null;
            for (ComparedRun run : runs) {
                RunMetrics.PageMetric page = run.metrics.pages.get(key);
                if (page != null && display == null) display = page;
                statuses.add(page == null ? "缺少页面" : page.status);
            }
            if (allEqual(statuses)) continue;
            any = true;
            report.append("| ").append(escape(display == null ? key : display.displayKey())).append(" |");
            for (String status : statuses) report.append(' ').append(escape(status)).append(" |");
            report.append(' ').append(differenceConclusion(statuses, runs)).append(" |\n");
        }
        if (!any) {
            report.append("| 无差异 |");
            for (int i = 0; i < runs.size(); i++) report.append(" — |");
            report.append(" 所有对齐页面状态一致 |\n");
        }
        report.append("\n");
    }

    private String differenceConclusion(List<String> statuses, List<ComparedRun> runs) {
        List<String> passed = new ArrayList<String>();
        for (int i = 0; i < statuses.size(); i++) if (isPassed(statuses.get(i))) passed.add(runs.get(i).label);
        if (passed.size() == 1) return passed.get(0) + "独立通过";
        if (passed.isEmpty()) return "均未通过，失败路径不同";
        return "部分模型通过，需结合擦除图复核";
    }

    private static boolean isPassed(String status) { return "safe_to_erase".equals(status) || "no_pagenum".equals(status); }
    private static boolean allEqual(List<String> values) {
        for (int i = 1; i < values.size(); i++) if (!values.get(0).equals(values.get(i))) return false;
        return true;
    }
    private static String model(Path run) throws IOException { return MAPPER.readTree(run.resolve("run.json").toFile()).path("model").asText("unknown"); }
    private static String ratio(int numerator, int denominator) { return denominator == 0 ? "无数据" : PERCENT.format(numerator / (double) denominator); }
    private static String tokens(double value, int count) { return count == 0 ? "无数据" : NUMBER.format(Math.round(value)); }
    private static String seconds(double millis, int count) { return count == 0 ? "无数据" : new DecimalFormat("0.0s").format(millis / 1000D); }
    private static String escape(String value) { return value == null ? "" : value.replace("|", "\\|"); }

    private void tableHeader(StringBuilder report, List<ComparedRun> runs) {
        report.append("| 指标 |");
        for (ComparedRun run : runs) report.append(' ').append(run.label).append(" |");
        report.append("\n| --- |");
        for (int i = 0; i < runs.size(); i++) report.append(" ---: |");
        report.append('\n');
    }
    private void tableRow(StringBuilder report, String label, List<ComparedRun> runs, Value values) {
        report.append("| ").append(label).append(" |");
        for (ComparedRun run : runs) report.append(' ').append(values.value(run)).append(" |");
        report.append('\n');
    }

    private interface Value { String value(ComparedRun run); }
    private static final class ComparedRun {
        final String label; final Path dir; final String model; final RunMetrics.Snapshot metrics;
        ComparedRun(String label, Path dir, String model, RunMetrics.Snapshot metrics) {
            this.label = label; this.dir = dir; this.model = model; this.metrics = metrics;
        }
    }
}
