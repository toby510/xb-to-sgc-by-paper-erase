package com.xb.sgc.papererase.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.RejectedExam;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;
import com.xb.sgc.papererase.vlm.ModelPricing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * 测试报告生成器：从运行元数据、页面结果和图片产物生成固定格式 Markdown 报告。
 * 报告统计展示状态，不反向影响擦除门禁或页面结果。
 */
public class ReportWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DecimalFormat PERCENT = new DecimalFormat("0.00%");
    private final ModelPricing pricing;

    public ReportWriter() {
        this(null);
    }

    public ReportWriter(Path pricingConfig) {
        this.pricing = pricingConfig == null ? null : ModelPricing.load(pricingConfig);
    }

    public void writeRejections(List<RejectedExam> rejected, Path runDir) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# 测试报告\n\n");
        report.append("## 元数据\n\n");
        report.append("- 测试数据集: ").append(escape(datasetRoot(runDir))).append('\n');
        report.append("- 大模型名称: ").append(escape(modelName(runDir))).append('\n');
        report.append("- 报告时间: ").append(now()).append('\n');
        report.append("- 状态: 输入拒绝，未执行擦除\n");
        report.append("- 拒绝试卷数: ").append(rejected.size()).append('\n');
        report.append("\n## 拒绝明细\n\n");
        for (RejectedExam item : rejected) {
            report.append("- ").append(escape(item.getSubject())).append('/').append(escape(item.getExamId()))
                    .append(": ").append(escape(item.getReason())).append('\n');
        }
        writeReport(runDir, report);
    }

    public void write(List<ExamInput> inputs, List<ExamOutcome> outcomes, Path runDir) throws IOException {
        writeRows(rowsFromOutcomes(inputs, outcomes, runDir), runDir);
    }

    /** 只读取既有 run 目录产物重建报告，不触发 VLM、不改擦除结果。 */
    public void writeFromRunDirectory(Path runDir) throws IOException {
        writeRows(rowsFromRunDirectory(runDir), runDir);
        RunWriter.rebuildBadFromErased(runDir);
    }

    private void writeRows(List<ReportRow> rows, Path runDir) throws IOException {
        Path reportDir = runDir.resolve("测试报告");
        int total = rows.size();
        int bodyDamaged = 0;
        int erased = 0;
        int noPageNum = 0;
        int passed = 0;
        int abnormal = 0;
        for (ReportRow row : rows) {
            if (row.bodyDamaged) {
                bodyDamaged++;
            }
            if ("safe_to_erase".equals(row.status)) {
                erased++;
            }
            if ("no_pagenum".equals(row.status)) {
                noPageNum++;
            }
            if (row.normal()) {
                passed++;
            }
            if (!row.normal()) {
                abnormal++;
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# 测试报告\n\n");
        report.append("说明：每页左侧为原图，右侧为 `_擦除后.png`。如果右图角落标有“不可交付，仅供核对”，")
                .append("表示该图未通过自动交付门禁，但仍可用于人工核对擦除效果。\n\n");

        report.append("## 元数据\n\n");
        report.append("- 测试数据集: ").append(escape(datasetRoot(runDir))).append('\n');
        report.append("- 大模型名称: ").append(escape(modelName(runDir))).append('\n');
        report.append("- 报告时间: ").append(now()).append('\n');
        report.append("- Run目录: ").append(escape(runDir.toAbsolutePath().toString())).append('\n');

        report.append("\n## 数据分布\n\n");
        report.append("| 分类 | 数量 | 占比 |\n");
        report.append("| --- | ---: | ---: |\n");
        report.append("| 有页码成功擦除 | ").append(erased).append(" | ").append(rate(erased, total)).append(" |\n");
        report.append("| 正确无页码 | ").append(noPageNum).append(" | ").append(rate(noPageNum, total)).append(" |\n");
        report.append("| 异常 | ").append(abnormal).append(" | ").append(rate(abnormal, total)).append(" |\n");
        report.append("| 合计 | ").append(total).append(" | ").append(rate(total, total)).append(" |\n\n");
        report.append("| 安全指标 | 数量 | 占比 |\n");
        report.append("| --- | ---: | ---: |\n");
        report.append("| 擦除正文数量 | ").append(bodyDamaged).append(" | ").append(rate(bodyDamaged, total)).append(" |\n\n");
        report.append("| 准确率 | 通过数量/总数据量 | 结果 |\n");
        report.append("| --- | ---: | ---: |\n");
        report.append("| 成功擦除或正确无页码且未伤正文 | ").append(passed).append('/').append(total)
                .append(" | ").append(rate(passed, total)).append(" |\n");

        appendUsageAndPerformance(report, RunMetrics.read(runDir, pricing));

        report.append("\n### 异常原因分布\n\n");
        appendAbnormalReasonDistribution(report, rows, total);

        report.append("\n## 异常明细\n\n");
        List<ReportRow> abnormalRows = filter(rows, false);
        if (abnormalRows.isEmpty()) {
            report.append("无异常。\n");
        } else {
            appendRows(report, abnormalPriority(abnormalRows), reportDir, true);
        }

        report.append("\n## 全量明细\n\n");
        Map<String, List<ReportRow>> bySubject = groupBySubject(rows, subjectOrder(runDir, rows));
        for (Map.Entry<String, List<ReportRow>> entry : bySubject.entrySet()) {
            report.append("### ").append(escape(entry.getKey())).append("\n\n");
            appendRows(report, abnormalFirst(entry.getValue()), reportDir, false);
        }
        writeReport(runDir, report);
    }

    /**
     * 仅展示 run 级汇总和分布；每次 HTTP 尝试的 locate/verify/audit/重试明细保留在
     * _vlm_usage.ndjson，避免把数百页的追溯数据塞进人工阅读的测试报告。
     */
    private void appendUsageAndPerformance(StringBuilder report, RunMetrics.Snapshot metrics) {
        RunMetrics.UsageSummary usage = metrics.usage;
        report.append("\n## Token、成本与性能\n\n");
        report.append("### 模型调用汇总\n\n");
        report.append("| 指标 | 结果 |\n| --- | ---: |\n");
        report.append("| VLM 调用次数（含重试） | ").append(usage.callCount).append(" |\n");
        report.append("| 有真实 usage 的调用 | ").append(usage.usageAvailableCallCount).append(" |\n");
        report.append("| 输入 Token | ").append(number(usage.inputTokens)).append(" |\n");
        report.append("| 输出 Token | ").append(number(usage.outputTokens)).append(" |\n");
        report.append("| 推理 Token | ").append(number(usage.reasoningTokens)).append(" |\n");
        report.append("| 总 Token | ").append(number(usage.totalTokens)).append(" |\n");
        report.append("| 成本（CNY） | ").append(usage.hasCompleteCost() ? money(usage.costCny) : "未配置模型单价或 usage 不完整")
                .append(" |\n");
        report.append("\n明细追溯文件：`_vlm_usage.ndjson`（每次 locate / verify / audit / 重试各一行）。\n\n");

        report.append("### 单张图片 Token 与成本分布\n\n");
        report.append("| 指标 | 最小 | P50 | 平均 | P90 | 最大 |\n| --- | ---: | ---: | ---: | ---: | ---: |\n");
        appendDistribution(report, "总 Token", metrics.pageTokenDistribution(), false);
        appendDistribution(report, "成本（CNY）", metrics.pageCostDistribution(), true);

        report.append("\n### 图片全流程与整卷耗时分布\n\n");
        report.append("| 指标 | 最小 | P50 | 平均 | P90 | 最大 |\n| --- | ---: | ---: | ---: | ---: | ---: |\n");
        appendDistribution(report, "图片全流程耗时", metrics.pageElapsedDistribution(), false, true);
        appendDistribution(report, "整份试卷耗时", metrics.examElapsedDistribution(), false, true);
    }

    private void appendDistribution(StringBuilder report, String label, RunMetrics.Distribution distribution, boolean money) {
        appendDistribution(report, label, distribution, money, false);
    }

    private void appendDistribution(StringBuilder report, String label, RunMetrics.Distribution distribution,
                                    boolean money, boolean millis) {
        if (distribution.count == 0) {
            report.append("| ").append(label).append(" | 无数据 | 无数据 | 无数据 | 无数据 | 无数据 |\n");
            return;
        }
        report.append("| ").append(label).append(" | ")
                .append(metric(distribution.min, money, millis)).append(" | ")
                .append(metric(distribution.p50, money, millis)).append(" | ")
                .append(metric(distribution.average, money, millis)).append(" | ")
                .append(metric(distribution.p90, money, millis)).append(" | ")
                .append(metric(distribution.max, money, millis)).append(" |\n");
    }

    private String metric(double value, boolean money, boolean millis) {
        if (money) return money(value);
        if (millis) return new DecimalFormat("0.0s").format(value / 1000D);
        return number(Math.round(value));
    }

    private String number(long value) { return new DecimalFormat("#,##0").format(value); }
    private String money(double value) { return new DecimalFormat("0.0000").format(value); }

    private void appendAbnormalReasonDistribution(StringBuilder report, List<ReportRow> rows, int total) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (ReportRow row : rows) {
            if (row.normal()) {
                continue;
            }
            String key = row.reason.length() == 0 ? "(empty_reason)" : row.reason;
            Integer count = counts.get(key);
            counts.put(key, count == null ? 1 : count + 1);
        }
        if (counts.isEmpty()) {
            report.append("无异常。\n");
            return;
        }
        report.append("| Reason | 数量 | 占总量 | 占异常 |\n");
        report.append("| --- | ---: | ---: | ---: |\n");
        int abnormalTotal = 0;
        for (Integer count : counts.values()) {
            abnormalTotal += count.intValue();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int byCount = b.getValue().intValue() - a.getValue().intValue();
                if (byCount != 0) {
                    return byCount;
                }
                return a.getKey().compareTo(b.getKey());
            }
        });
        for (Map.Entry<String, Integer> entry : entries) {
            report.append("| ").append(escape(entry.getKey()))
                    .append(" | ").append(entry.getValue())
                    .append(" | ").append(rate(entry.getValue(), total))
                    .append(" | ").append(rate(entry.getValue(), abnormalTotal))
                    .append(" |\n");
        }
    }

    private List<ReportRow> rowsFromOutcomes(List<ExamInput> inputs, List<ExamOutcome> outcomes, Path runDir) {
        Map<String, ExamOutcome> outcomeByExam = new LinkedHashMap<String, ExamOutcome>();
        for (ExamOutcome outcome : outcomes) {
            outcomeByExam.put(outcome.getExamId(), outcome);
        }
        List<ReportRow> rows = new ArrayList<ReportRow>();
        for (ExamInput input : inputs) {
            ExamOutcome outcome = outcomeByExam.get(input.getExamId());
            if (outcome == null) {
                continue;
            }
            for (PageInput page : input.getPages()) {
                PageOutcome pageOutcome = outcome.page(page.getPageId());
                String stem = input.getExamId() + "_" + page.getPageOrder();
                Path base = runDir.resolve("erased").resolve(input.getSubject()).resolve(input.getExamId());
                ReportRow row = new ReportRow();
                row.subject = input.getSubject();
                row.examId = input.getExamId();
                row.pageId = page.getPageId();
                row.pageOrder = page.getPageOrder();
                row.status = nvl(pageOutcome.getStatus());
                row.reason = nvl(pageOutcome.getReason());
                row.original = base.resolve(stem + "_原图.png");
                row.erased = base.resolve(stem + "_擦除后.png");
                if (pageOutcome.getAudit() != null) {
                    row.auditEvidence = nvl(pageOutcome.getAudit().evidence);
                    row.bodyDamaged = auditSaysBodyDamaged(!pageOutcome.getAudit().body_unchanged,
                            row.reason, row.auditEvidence);
                } else {
                    row.bodyDamaged = mentionsBodyDamage(row.reason, "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<ReportRow> rowsFromRunDirectory(Path runDir) throws IOException {
        List<ReportRow> rows = new ArrayList<ReportRow>();
        Path erasedRoot = runDir.resolve("erased");
        if (!Files.isDirectory(erasedRoot)) {
            return rows;
        }
        List<Path> regionFiles = new ArrayList<Path>();
        collectRegionFiles(erasedRoot, regionFiles);
        Collections.sort(regionFiles, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.toString().compareTo(b.toString());
            }
        });
        for (Path regionsJson : regionFiles) {
            JsonNode root = MAPPER.readTree(regionsJson.toFile());
            Path examDir = regionsJson.getParent();
            Path subjectDir = examDir.getParent();
            String fileName = regionsJson.getFileName().toString();
            String stem = fileName.substring(0, fileName.length() - "_regions.json".length());
            ReportRow row = new ReportRow();
            row.subject = subjectDir.getFileName().toString();
            row.examId = text(root, "exam_id", examDir.getFileName().toString());
            row.pageOrder = intValue(root, "page_order", orderFromStem(stem));
            row.pageId = text(root, "page_id", row.examId + ":" + row.pageOrder);
            row.status = text(root, "status", "");
            row.reason = text(root, "reason", "");
            row.original = examDir.resolve(stem + "_原图.png");
            row.erased = examDir.resolve(stem + "_擦除后.png");
            JsonNode audit = root.get("audit");
            if (audit != null && audit.isObject()) {
                row.auditEvidence = text(audit, "evidence", "");
                JsonNode body = audit.get("body_unchanged");
                row.bodyDamaged = auditSaysBodyDamaged(body != null && body.isBoolean() && !body.booleanValue(),
                        row.reason, row.auditEvidence);
            } else {
                row.bodyDamaged = mentionsBodyDamage(row.reason, "");
            }
            if (!row.bodyDamaged) {
                row.bodyDamaged = mentionsBodyDamage(row.reason, row.auditEvidence);
            }
            rows.add(row);
        }
        return orderRows(rows, runDir);
    }

    private void appendRows(StringBuilder report, List<ReportRow> rows, Path reportDir, boolean includeReason) {
        for (ReportRow row : rows) {
            report.append("#### ").append(escape(row.subject)).append('/').append(escape(row.examId))
                    .append(" 第 ").append(row.pageOrder).append(" 页")
                    .append("（").append(escape(row.status)).append(" / ").append(escape(row.reason)).append("）\n\n");
            appendWordLinks(report, row, reportDir);
            if (includeReason || !row.normal()) {
                report.append("- 失败原因: ").append(escape(reasonSummary(row))).append('\n');
                if (!Files.isRegularFile(row.erased)) {
                    report.append("- 产物异常: 缺少 `_擦除后.png`\n");
                }
                report.append('\n');
            }
            report.append("<table><tr><th>原图</th><th>擦除后</th></tr><tr><td>")
                    .append(imageTag(reportDir, row.original))
                    .append("</td><td>")
                    .append(imageTag(reportDir, row.erased))
                    .append("</td></tr></table>\n\n");
        }
    }

    private void appendWordLinks(StringBuilder report, ReportRow row, Path reportDir) {
        Path examWordDir = reportDir.getParent().resolve("word_output").resolve(row.subject).resolve(row.examId);
        if (!Files.isDirectory(examWordDir)) {
            return;
        }
        List<Path> docs = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(examWordDir, "*.docx")) {
            for (Path doc : stream) {
                docs.add(doc);
            }
        } catch (IOException e) {
            return;
        }
        if (docs.isEmpty()) {
            return;
        }
        report.append("- Word产物: ");
        for (int i = 0; i < docs.size(); i++) {
            Path doc = docs.get(i);
            if (i > 0) {
                report.append(" / ");
            }
            report.append('[').append(escape(doc.getFileName().toString())).append("](")
                    .append(rel(reportDir, doc)).append(')');
        }
        report.append("\n\n");
    }

    private String imageTag(Path reportDir, Path image) {
        String href = rel(reportDir, image);
        if (!Files.isRegularFile(image)) {
            return escape("缺少图片: " + image.getFileName());
        }
        return "<img src=\"" + href + "\">";
    }

    private String reasonSummary(ReportRow row) {
        String reason = nvl(row.reason);
        String evidence = nvl(row.auditEvidence);
        if (!Files.isRegularFile(row.erased)) {
            return "产物异常：缺少擦除后图；原始状态=" + row.status + "，原因=" + reason;
        }
        if (row.bodyDamaged) {
            return "审计怀疑正文被改变，需要人工确认；原始原因=" + reason + briefEvidence(evidence);
        }
        if (reason.contains("locate") || reason.contains("ParseException") || reason.contains("protocol")) {
            return "模型定位或响应解析异常；原始原因=" + reason;
        }
        if (reason.contains("no_pagenum")) {
            return "模型判断未发现页码；如肉眼可见页码，需要复核定位提示词或输入清晰度。";
        }
        if (reason.contains("target_not_removed") || evidence.contains("target_removed=false")) {
            return "审计认为页码/目标元数据未擦干净；原始原因=" + reason + briefEvidence(evidence);
        }
        if (reason.contains("audit_failed")) {
            return "擦除后审计未通过；原始原因=" + reason + briefEvidence(evidence);
        }
        if (reason.contains("validation")) {
            return "Java安全门禁拒绝该擦除框；原始原因=" + reason;
        }
        if ("manual_review".equals(row.status)) {
            return "进入人工复核；原始原因=" + reason + briefEvidence(evidence);
        }
        if ("error".equals(row.status)) {
            return "处理异常；原始原因=" + reason;
        }
        return "非成功状态；原始状态=" + row.status + "，原因=" + reason + briefEvidence(evidence);
    }

    private String briefEvidence(String evidence) {
        if (evidence == null || evidence.length() == 0) {
            return "";
        }
        String compact = evidence.replace('\n', ' ').replace('\r', ' ');
        if (compact.length() > 160) {
            compact = compact.substring(0, 160) + "...";
        }
        return "；审计证据=" + compact;
    }

    private Map<String, List<ReportRow>> groupBySubject(List<ReportRow> rows, List<String> subjectOrder) {
        Map<String, List<ReportRow>> result = new LinkedHashMap<String, List<ReportRow>>();
        for (String subject : subjectOrder) {
            result.put(subject, new ArrayList<ReportRow>());
        }
        for (ReportRow row : rows) {
            if (!result.containsKey(row.subject)) {
                result.put(row.subject, new ArrayList<ReportRow>());
            }
            result.get(row.subject).add(row);
        }
        return result;
    }

    private List<ReportRow> abnormalFirst(List<ReportRow> rows) {
        List<ReportRow> ordered = new ArrayList<ReportRow>(rows);
        Collections.sort(ordered, new Comparator<ReportRow>() {
            @Override
            public int compare(ReportRow a, ReportRow b) {
                if (a.normal() != b.normal()) {
                    return a.normal() ? 1 : -1;
                }
                int exam = a.examId.compareTo(b.examId);
                if (exam != 0) {
                    return exam;
                }
                return a.pageOrder - b.pageOrder;
            }
        });
        return ordered;
    }

    private List<ReportRow> abnormalPriority(List<ReportRow> rows) {
        List<ReportRow> ordered = new ArrayList<ReportRow>(rows);
        Collections.sort(ordered, new Comparator<ReportRow>() {
            @Override
            public int compare(ReportRow a, ReportRow b) {
                if (a.bodyDamaged != b.bodyDamaged) {
                    return a.bodyDamaged ? -1 : 1;
                }
                int subject = a.subject.compareTo(b.subject);
                if (subject != 0) {
                    return subject;
                }
                int exam = a.examId.compareTo(b.examId);
                if (exam != 0) {
                    return exam;
                }
                return a.pageOrder - b.pageOrder;
            }
        });
        return ordered;
    }

    private List<ReportRow> orderRows(List<ReportRow> rows, Path runDir) {
        final Map<String, Integer> subjectIndex = index(subjectOrder(runDir, rows));
        final Map<String, Integer> examIndex = index(examOrder(runDir, rows));
        Collections.sort(rows, new Comparator<ReportRow>() {
            @Override
            public int compare(ReportRow a, ReportRow b) {
                int s = indexOf(subjectIndex, a.subject) - indexOf(subjectIndex, b.subject);
                if (s != 0) {
                    return s;
                }
                int e = indexOf(examIndex, a.subject + "/" + a.examId) - indexOf(examIndex, b.subject + "/" + b.examId);
                if (e != 0) {
                    return e;
                }
                return a.pageOrder - b.pageOrder;
            }
        });
        return rows;
    }

    private List<String> subjectOrder(Path runDir, List<ReportRow> rows) {
        LinkedHashSet<String> subjects = new LinkedHashSet<String>();
        Path wordRoot = runDir.resolve("word_output");
        if (Files.isDirectory(wordRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(wordRoot)) {
                for (Path subject : stream) {
                    if (Files.isDirectory(subject)) {
                        subjects.add(subject.getFileName().toString());
                    }
                }
            } catch (IOException ignored) {
                // fallback below
            }
        }
        for (ReportRow row : rows) {
            subjects.add(row.subject);
        }
        return new ArrayList<String>(subjects);
    }

    private List<String> examOrder(Path runDir, List<ReportRow> rows) {
        LinkedHashSet<String> exams = new LinkedHashSet<String>();
        Path wordRoot = runDir.resolve("word_output");
        if (Files.isDirectory(wordRoot)) {
            try (DirectoryStream<Path> subjectStream = Files.newDirectoryStream(wordRoot)) {
                for (Path subject : subjectStream) {
                    if (!Files.isDirectory(subject)) {
                        continue;
                    }
                    try (DirectoryStream<Path> examStream = Files.newDirectoryStream(subject)) {
                        for (Path exam : examStream) {
                            if (Files.isDirectory(exam)) {
                                exams.add(subject.getFileName().toString() + "/" + exam.getFileName().toString());
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // fallback below
            }
        }
        for (ReportRow row : rows) {
            exams.add(row.subject + "/" + row.examId);
        }
        return new ArrayList<String>(exams);
    }

    private List<ReportRow> filter(List<ReportRow> rows, boolean normal) {
        List<ReportRow> result = new ArrayList<ReportRow>();
        for (ReportRow row : rows) {
            if (row.normal() == normal) {
                result.add(row);
            }
        }
        return result;
    }

    private void collectRegionFiles(Path dir, List<Path> result) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    collectRegionFiles(child, result);
                } else if (child.getFileName().toString().endsWith("_regions.json")) {
                    result.add(child);
                }
            }
        }
    }

    private void writeReport(Path runDir, StringBuilder report) throws IOException {
        Path reportPath = runDir.resolve("测试报告").resolve("测试报告.md");
        Files.createDirectories(reportPath.getParent());
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String datasetRoot(Path runDir) {
        Path current = runDir.toAbsolutePath();
        while (current != null) {
            String name = current.getFileName() == null ? "" : current.getFileName().toString();
            if ("xb-to-sgc-by-paper-erase-output".equals(name)) {
                return current.getParent() == null ? runDir.toAbsolutePath().toString() : current.getParent().toString();
            }
            current = current.getParent();
        }
        return runDir.toAbsolutePath().toString();
    }

    private String modelName(Path runDir) {
        Path runJson = runDir.resolve("run.json");
        if (Files.isRegularFile(runJson)) {
            try {
                JsonNode root = MAPPER.readTree(runJson.toFile());
                String model = text(root, "model", "");
                if (model.length() > 0) {
                    return model;
                }
            } catch (IOException ignored) {
                // fallback below
            }
        }
        String name = runDir.getFileName() == null ? "" : runDir.getFileName().toString();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private String rel(Path baseDir, Path target) {
        return baseDir.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String rate(int value, int total) {
        if (total == 0) {
            return "0.00%";
        }
        return PERCENT.format((double) value / (double) total);
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private boolean mentionsBodyDamage(String reason, String evidence) {
        String text = (nvl(reason) + " " + nvl(evidence)).toLowerCase();
        if (text.contains("正文被") || text.contains("正文损") || text.contains("伤正文")
                || text.contains("body damaged")) {
            return true;
        }
        // 朴素子串匹配会把 audit 通过页 evidence 里的 "无正文变化" 误判为 "正文变化"；
        // 先剔除常见否定表述再匹配，避免报告虚报"擦除正文"。
        String stripped = text
                .replace("无任何正文变化", "").replace("无正文变化", "")
                .replace("未见正文变化", "").replace("未发现正文变化", "")
                .replace("没有正文变化", "").replace("未出现正文变化", "");
        return stripped.contains("正文变化");
    }

    private boolean auditSaysBodyDamaged(boolean auditBodyChanged, String reason, String evidence) {
        // 结构化审计字段是最高优先级证据：body_unchanged=false 必须统计为正文变化，不能
        // 被模型 evidence 中可能自相矛盾的“未变”字样反向覆盖。字段为 true 时，仍保留
        // 文字兜底，用于发现 reason/evidence 中额外报告的正文损伤。
        return auditBodyChanged || mentionsBodyDamage(reason, evidence);
    }

    private String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? fallback : node.asText();
    }

    private int intValue(JsonNode root, String field, int fallback) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? fallback : node.asInt(fallback);
    }

    private int orderFromStem(String stem) {
        int idx = stem.lastIndexOf('_');
        if (idx < 0 || idx == stem.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(stem.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Integer> index(List<String> values) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < values.size(); i++) {
            result.put(values.get(i), i);
        }
        return result;
    }

    private int indexOf(Map<String, Integer> index, String key) {
        Integer value = index.get(key);
        return value == null ? Integer.MAX_VALUE / 2 : value.intValue();
    }

    private String escape(String text) {
        return nvl(text).replace("|", "\\|");
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private static final class ReportRow {
        String subject;
        String examId;
        String pageId;
        int pageOrder;
        String status;
        String reason;
        Path original;
        Path erased;
        boolean bodyDamaged;
        String auditEvidence;

        boolean normal() {
            return "safe_to_erase".equals(status) || "no_pagenum".equals(status);
        }
    }
}
