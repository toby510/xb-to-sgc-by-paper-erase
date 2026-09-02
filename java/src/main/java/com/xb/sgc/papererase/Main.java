package com.xb.sgc.papererase;

import com.xb.sgc.papererase.input.ExamScanner;
import com.xb.sgc.papererase.input.GateDatasetSelector;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.ScanResult;
import com.xb.sgc.papererase.output.ReportWriter;
import com.xb.sgc.papererase.output.RunComparisonWriter;
import com.xb.sgc.papererase.output.RunWriter;
import com.xb.sgc.papererase.output.WordOutputConfig;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamPipeline;
import com.xb.sgc.papererase.vlm.VlmClient;
import com.xb.sgc.papererase.vlm.VlmConfig;
import com.xb.sgc.papererase.vlm.VlmUsageFileSink;

import java.io.IOException;
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
        if (args.length < 2 || (!"run".equals(args[0]) && !"gate".equals(args[0]) && !"report".equals(args[0])
                && !"resume".equals(args[0]) && !"compare".equals(args[0]))) {
            System.err.println("Usage: Main run <test-root> | Main gate <bad-root> <full-root> "
                    + "| Main report <run-dir> | Main resume <test-root> <run-dir> "
                    + "| Main compare <run-dir-a> <run-dir-b> [run-dir-c]; "
                    + "run/gate/resume 可追加 --with-qrcode true|false 和 --qrcode-width-cm 4.0-5.6（默认 true、5.6）");
            System.exit(2);
        }
        Path skillRoot = findSkillRoot();
        Path pricingConfig = skillRoot.resolve("config/model-pricing.json");
        if ("report".equals(args[0])) {
            Path runDir = Paths.get(args[1]);
            new ReportWriter(pricingConfig).writeFromRunDirectory(runDir);
            System.out.println(runDir.resolve("测试报告").resolve("测试报告.md").toAbsolutePath().toString());
            return;
        }
        if ("compare".equals(args[0])) {
            if (args.length < 3 || args.length > 4) {
                throw new IllegalArgumentException("Usage: Main compare <run-dir-a> <run-dir-b> [run-dir-c]");
            }
            List<Path> runs = new ArrayList<Path>();
            for (int i = 1; i < args.length; i++) runs.add(Paths.get(args[i]));
            System.out.println(new RunComparisonWriter(pricingConfig).write(runs).toAbsolutePath().toString());
            return;
        }
        WordOutputConfig wordOutputConfig = WordOutputConfig.load(skillRoot.resolve("config/word-template.json"));
        if ("resume".equals(args[0])) {
            QrcodeOptions qrcode = qrcodeOptions(args, 3, wordOutputConfig);
            resumeRun(Paths.get(args[1]), Paths.get(args[2]), qrcode);
            return;
        }
        VlmConfig config = VlmConfig.load(skillRoot.resolve("config/vlm-providers.json"));
        // 与旧版一致：扫描数据前即冻结本次 run 使用的提示词。usage 路径尚未创建时 sink 为 no-op。
        VlmUsageFileSink usageSink = new VlmUsageFileSink(null, pricingConfig);
        VlmClient vlm = VlmClient.create(config, skillRoot, usageSink);
        List<ExamInput> exams;
        Path outputRoot;
        QrcodeOptions qrcode;
        java.util.Map<String, Path> datasetRoots = new java.util.LinkedHashMap<String, Path>();
        if ("run".equals(args[0])) {
            qrcode = qrcodeOptions(args, 2, wordOutputConfig);
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
            qrcode = qrcodeOptions(args, 3, wordOutputConfig);
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
        // active 是唯一提供方开关；usage 文件是严格旁路，写入失败不会改变任何擦除结果。
        usageSink.bind(runDir.resolve("_vlm_usage.ndjson"));
        RunWriter.writeRunningRunJson(runDir, datasetRoots, args[0], config, exams.size(), plannedPages, skillRoot,
                vlm.frozenPrompts());
        System.err.println("run_dir=" + runDir.toAbsolutePath());
        System.err.println("progress_file=" + runDir.resolve("_progress.ndjson").toAbsolutePath());
        List<ExamOutcome> outcomes = new ArrayList<ExamOutcome>();
        ExamPipeline pipeline = new ExamPipeline(vlm);
        RunWriter runWriter = new RunWriter(qrcode.withQrcode, qrcode.widthEmu, qrcode.shortLink,
                qrcode.textLine1, qrcode.textLine2);
        int pages = 0;
        for (ExamInput exam : exams) {
            ExamPipeline.RunContext context = new ExamPipeline.RunContext(runDir);
            context.event(ExamPipeline.PipelineStage.OUTPUT, exam.getExamId(), null,
                    ExamPipeline.EventStatus.STARTED, null, 0);
            ExamOutcome outcome = pipeline.process(exam, context);
            runWriter.writeExam(exam, outcome, runDir);
            // 已落盘原图/擦除图/Word 与审计，本卷大图不再被 ReportWriter 引用，立即释放避免 100 卷累积超堆。
            outcome.releaseImages();
            context.event(ExamPipeline.PipelineStage.OUTPUT, exam.getExamId(), null,
                    ExamPipeline.EventStatus.COMPLETED, null, 0);
            outcomes.add(outcome);
            pages += exam.getPages().size();
        }
        new ReportWriter(pricingConfig).write(exams, outcomes, runDir);
        RunWriter.completeRunJson(runDir, exams.size(), pages);
        System.out.println(runDir.toAbsolutePath().toString());
    }

    /**
     * 断点续跑：扫描 test-root 全量试卷，跳过本 run 目录已完整落盘的试卷（每页原图/擦除图/regions +
     * consensus + Word 齐备），只处理剩余试卷并写入同一 run 目录，最后从产物重建完整报告。
     * 被系统终止的 run（run.json=completed 前）可通过该命令继续，不重复消耗已完成卷的模型额度。
     */
    private static void resumeRun(Path testRoot, Path runDir, QrcodeOptions qrcode) throws Exception {
        if (!Files.isRegularFile(runDir.resolve("run.json"))) {
            throw new IllegalStateException("not a run dir, missing run.json: " + runDir);
        }
        Path skillRoot = findSkillRoot();
        VlmConfig config = VlmConfig.load(skillRoot.resolve("config/vlm-providers.json"));
        VlmClient vlm = VlmClient.create(config, skillRoot,
                new VlmUsageFileSink(runDir.resolve("_vlm_usage.ndjson"), skillRoot.resolve("config/model-pricing.json")));
        ScanResult scan = new ExamScanner().scanWithRejections(testRoot);
        if (!scan.getRejectedExams().isEmpty()) {
            throw new IllegalStateException("rejected exams present; cannot resume: "
                    + scan.getRejectedExams().size());
        }
        List<ExamInput> exams = scan.getExams();
        ExamPipeline pipeline = new ExamPipeline(vlm);
        RunWriter runWriter = new RunWriter(qrcode.withQrcode, qrcode.widthEmu, qrcode.shortLink,
                qrcode.textLine1, qrcode.textLine2);
        int resumed = 0;
        int skipped = 0;
        for (ExamInput exam : exams) {
            if (examFullyWritten(exam, runDir)) {
                skipped++;
                continue;
            }
            ExamPipeline.RunContext context = new ExamPipeline.RunContext(runDir);
            context.event(ExamPipeline.PipelineStage.OUTPUT, exam.getExamId(), null,
                    ExamPipeline.EventStatus.STARTED, null, 0);
            ExamOutcome outcome = pipeline.process(exam, context);
            runWriter.writeExam(exam, outcome, runDir);
            outcome.releaseImages();
            context.event(ExamPipeline.PipelineStage.OUTPUT, exam.getExamId(), null,
                    ExamPipeline.EventStatus.COMPLETED, null, 0);
            resumed++;
        }
        new ReportWriter(skillRoot.resolve("config/model-pricing.json")).writeFromRunDirectory(runDir);
        int pages = 0;
        for (ExamInput exam : exams) {
            pages += exam.getPages().size();
        }
        RunWriter.completeRunJson(runDir, exams.size(), pages);
        System.err.println("resume skipped=" + skipped + " processed=" + resumed);
        System.out.println(runDir.resolve("测试报告").resolve("测试报告.md").toAbsolutePath().toString());
    }

    /** 判定一份试卷是否已在本 run 目录完整落盘（每页原图/擦除图/regions + consensus + Word 均存在）。 */
    private static boolean examFullyWritten(ExamInput exam, Path runDir) {
        Path erasedDir = runDir.resolve("erased").resolve(exam.getSubject()).resolve(exam.getExamId());
        Path consensusDir = runDir.resolve("consensus").resolve(exam.getSubject()).resolve(exam.getExamId());
        Path wordDir = runDir.resolve("word_output").resolve(exam.getSubject()).resolve(exam.getExamId());
        for (PageInput page : exam.getPages()) {
            String stem = exam.getExamId() + "_" + page.getPageOrder();
            if (!Files.isRegularFile(erasedDir.resolve(stem + "_原图.png"))
                    || !Files.isRegularFile(erasedDir.resolve(stem + "_擦除后.png"))
                    || !Files.isRegularFile(erasedDir.resolve(stem + "_regions.json"))) {
                return false;
            }
        }
        if (!Files.isRegularFile(consensusDir.resolve("exam_consensus.json"))) {
            return false;
        }
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(wordDir, "*.docx")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 解析首页二维码参数。二维码以页面绝对坐标独立锚定；宽度只改变自身宽高，
     * 不读取或影响正文图片的位置与尺寸。
     */
    private static QrcodeOptions qrcodeOptions(String[] args, int requiredArgs, WordOutputConfig defaults) {
        if ((args.length - requiredArgs) % 2 != 0) {
            throw new IllegalArgumentException("二维码参数必须按“名称 值”成对传入");
        }
        boolean withQrcode = defaults.isQrcodeEnabled();
        double widthCm = (double) defaults.getQrcodeWidthEmu() / WordOutputConfig.EMU_PER_CM;
        boolean hasWithQrcode = false;
        boolean hasWidth = false;
        for (int i = requiredArgs; i < args.length; i += 2) {
            String key = args[i];
            String value = args[i + 1];
            if ("--with-qrcode".equals(key) && !hasWithQrcode) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("二维码开关只能是 true 或 false");
                }
                withQrcode = Boolean.parseBoolean(value);
                hasWithQrcode = true;
            } else if ("--qrcode-width-cm".equals(key) && !hasWidth) {
                try {
                    widthCm = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("二维码宽度必须是 4.0 到 5.6 之间的数字", e);
                }
                WordOutputConfig.qrcodeWidthEmu(widthCm);
                hasWidth = true;
            } else {
                throw new IllegalArgumentException("二维码参数仅支持 --with-qrcode true|false、--qrcode-width-cm 4.0-5.6");
            }
        }
        return new QrcodeOptions(withQrcode, WordOutputConfig.qrcodeWidthEmu(widthCm), defaults.getQrcodeShortLink(),
                defaults.getQrcodeTextLine1(), defaults.getQrcodeTextLine2());
    }

    private static final class QrcodeOptions {
        private final boolean withQrcode;
        private final long widthEmu;
        private final String shortLink;
        private final String textLine1;
        private final String textLine2;

        private QrcodeOptions(boolean withQrcode, long widthEmu, String shortLink,
                              String textLine1, String textLine2) {
            this.withQrcode = withQrcode;
            this.widthEmu = widthEmu;
            this.shortLink = shortLink;
            this.textLine1 = textLine1;
            this.textLine2 = textLine2;
        }
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
