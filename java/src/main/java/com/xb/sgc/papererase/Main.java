package com.xb.sgc.papererase;

import com.xb.sgc.papererase.input.ExamScanner;
import com.xb.sgc.papererase.input.GateDatasetSelector;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.ScanResult;
import com.xb.sgc.papererase.output.ReportWriter;
import com.xb.sgc.papererase.output.RunWriter;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamPipeline;
import com.xb.sgc.papererase.vlm.VlmClient;
import com.xb.sgc.papererase.vlm.VlmConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 运行入口：负责把 TEST/GATE/REPORT 命令连接到扫描、试卷流水线、产物写入和报告生成。
 * 业务擦除规则不放在这里；这里的职责是参数路由和一次运行的生命周期编排。
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || (!"run".equals(args[0]) && !"gate".equals(args[0]) && !"report".equals(args[0]))) {
            System.err.println("Usage: Main run <test-root> | Main gate <bad-root> <full-root> | Main report <run-dir>");
            System.exit(2);
        }
        if ("report".equals(args[0])) {
            Path runDir = Paths.get(args[1]);
            new ReportWriter().writeFromRunDirectory(runDir);
            System.out.println(runDir.resolve("测试报告").resolve("测试报告.md").toAbsolutePath().toString());
            return;
        }
        Path skillRoot = findSkillRoot();
        VlmConfig config = VlmConfig.load(skillRoot.resolve("config/vlm-providers.json"));
        // active 是唯一提供方开关；工厂按 kind 选择协议客户端，角色本身只决定提示词。
        VlmClient vlm = VlmClient.create(config, skillRoot);
        List<ExamInput> exams;
        Path outputRoot;
        java.util.Map<String, Path> datasetRoots = new java.util.LinkedHashMap<String, Path>();
        if ("run".equals(args[0])) {
            Path testRoot = Paths.get(args[1]);
            ScanResult scan = new ExamScanner().scanWithRejections(testRoot);
            if (!scan.getRejectedExams().isEmpty()) {
                Path runDir = RunWriter.createRunDir(testRoot, config.role("locate").getModel(),
                        new SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date()));
                new ReportWriter().writeRejections(scan.getRejectedExams(), runDir);
                RunWriter.writeRunJson(runDir, config.role("locate").getModel(), 0, 0);
                throw new IllegalStateException("rejected exams present; write report before running erasable pages: "
                        + scan.getRejectedExams().size());
            }
            exams = scan.getExams();
            outputRoot = testRoot;
            datasetRoots.put("test_root", testRoot);
        } else {
            if (args.length != 3) {
                System.err.println("Usage: Main gate <bad-root> <full-root>");
                System.exit(2);
                return;
            }
            Path badRoot = Paths.get(args[1]);
            Path fullRoot = Paths.get(args[2]);
            exams = new GateDatasetSelector().select(badRoot, fullRoot);
            outputRoot = fullRoot;
            datasetRoots.put("bad_root", badRoot);
            datasetRoots.put("full_root", fullRoot);
        }
        Path runDir = RunWriter.createRunDir(outputRoot, config.role("locate").getModel(),
                new SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date()));
        int plannedPages = 0;
        for (ExamInput exam : exams) plannedPages += exam.getPages().size();
        RunWriter.writeRunningRunJson(runDir, datasetRoots, args[0], config, exams.size(), plannedPages, skillRoot,
                vlm.frozenPrompts());
        System.err.println("run_dir=" + runDir.toAbsolutePath());
        System.err.println("progress_file=" + runDir.resolve("_progress.ndjson").toAbsolutePath());
        List<ExamOutcome> outcomes = new ArrayList<ExamOutcome>();
        ExamPipeline pipeline = new ExamPipeline(vlm, config.getPatternSampleMaxPages());
        RunWriter runWriter = new RunWriter();
        int pages = 0;
        for (ExamInput exam : exams) {
            ExamPipeline.RunContext context = new ExamPipeline.RunContext(runDir);
            context.event("output", exam.getExamId(), null, "started", null, 0);
            ExamOutcome outcome = pipeline.process(exam, context);
            runWriter.writeExam(exam, outcome, runDir);
            // 已落盘原图/擦除图/Word 与审计，本卷大图不再被 ReportWriter 引用，立即释放避免 100 卷累积超堆。
            outcome.releaseImages();
            context.event("output", exam.getExamId(), null, "completed", null, 0);
            outcomes.add(outcome);
            pages += exam.getPages().size();
        }
        new ReportWriter().write(exams, outcomes, runDir);
        RunWriter.completeRunJson(runDir, exams.size(), pages);
        System.out.println(runDir.toAbsolutePath().toString());
    }

    private static Path findSkillRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path current = cwd;
        while (current != null) {
            if (Files.isRegularFile(current.resolve("SKILL.md"))
                    && Files.isRegularFile(current.resolve("config/vlm-providers.json"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd;
    }
}
