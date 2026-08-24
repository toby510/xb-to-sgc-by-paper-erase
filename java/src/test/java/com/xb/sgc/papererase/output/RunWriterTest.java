package com.xb.sgc.papererase.output;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageTransforms;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class RunWriterTest {
    @Test
    public void writesStrictOutputTreeWithEvidenceAuditReportAndRunJson() throws Exception {
        Path root = Files.createTempDirectory("run-writer-");
        ExamInput input = new ExamInput("语文", "1001", "15065", Arrays.asList(
                new PageInput("1001:1", "1001", 1, root.resolve("15065_1001_1.png")),
                new PageInput("1001:2", "1001", 2, root.resolve("15065_1001_2.png"))),
                false, Collections.<String>emptyList());
        BufferedImage white = solid(Color.WHITE);
        BufferedImage blue = solid(Color.BLUE);
        BufferedImage green = solid(Color.GREEN);
        PageTransforms tx = new PageTransforms(12, 12, 12, 12, 0);
        ExamOutcome outcome = new ExamOutcome("1001", "processed", "ok", Arrays.asList(
                new PageOutcome("1001:1", "safe_to_erase", "audit_pass", white, white, green, tx,
                        null, Collections.emptyList(), null, null),
                new PageOutcome("1001:2", "manual_review", "risk", white, blue, blue, tx,
                        null, Collections.emptyList(), null, null)),
                Collections.emptyList());

        Path runDir = RunWriter.createRunDir(root, "qwen3.8-max", "20260821T120000");
        new RunWriter().writeExam(input, outcome, runDir);
        new ReportWriter().write(Collections.singletonList(input), Collections.singletonList(outcome), runDir);
        RunWriter.writeRunJson(runDir, "qwen3.8-max", 1, 2);

        Path base = runDir.resolve("erased/语文/1001");
        assertTrue(Files.isRegularFile(base.resolve("1001_1_原图.png")));
        assertTrue(Files.isRegularFile(base.resolve("1001_1_擦除后.png")));
        assertTrue(Files.isRegularFile(base.resolve("1001_2_regions.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("consensus/语文/1001/exam_consensus.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("word_output/语文/1001/1001_原图.docx")));
        assertTrue(Files.isRegularFile(runDir.resolve("word_output/语文/1001/1001_擦除后.docx")));
        Path bad = runDir.resolve("bad/语文/1001");
        assertTrue(Files.isRegularFile(bad.resolve("1001_2_原图.png")));
        assertTrue(Files.isRegularFile(bad.resolve("1001_2_擦除后.png")));
        assertTrue(Files.isRegularFile(bad.resolve("1001_2_regions.json")));
        assertTrue("only final manual-review pages belong in bad", !Files.exists(bad.resolve("1001_1_原图.png")));
        assertTrue(Files.isRegularFile(runDir.resolve("_audit.ndjson")));
        assertTrue(Files.isRegularFile(runDir.resolve("测试报告/测试报告.md")));
        assertTrue(Files.isRegularFile(runDir.resolve("run.json")));
        String report = new String(Files.readAllBytes(runDir.resolve("测试报告/测试报告.md")), "UTF-8");
        assertTrue(report.contains("## 元数据"));
        assertTrue(report.contains("## 数据分布"));
        assertTrue(report.contains("## 异常明细"));
        assertTrue(report.contains("## 全量明细"));
        assertTrue(report.contains("准确率"));
        assertTrue(report.contains("成功擦除或正确无页码且未伤正文"));
        assertTrue(report.contains("### 异常原因分布"));
        assertTrue(report.contains("| risk | 1 |"));
        assertTrue(report.contains("1001_2_擦除后.png"));
        assertTrue(report.contains("<img src=\"../erased/语文/1001/1001_2_擦除后.png\">"));

        deleteRecursively(runDir.resolve("bad"));
        new ReportWriter().writeFromRunDirectory(runDir);
        String regenerated = new String(Files.readAllBytes(runDir.resolve("测试报告/测试报告.md")), "UTF-8");
        assertTrue(regenerated.contains("qwen3.8-max"));
        assertTrue(regenerated.contains("语文/1001 第 2 页"));
        assertTrue("report-only rebuilds the final manual-review inspection set",
                Files.isRegularFile(runDir.resolve("bad/语文/1001/1001_2_擦除后.png")));
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
                for (Path file : files) deleteRecursively(file);
            }
        }
        Files.delete(path);
    }

}
