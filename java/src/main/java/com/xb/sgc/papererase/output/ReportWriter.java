package com.xb.sgc.papererase.output;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.RejectedExam;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReportWriter {
    public void writeRejections(List<RejectedExam> rejected, Path runDir) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# 测试报告\n\n");
        report.append("- 状态: 输入拒绝，未执行擦除\n");
        report.append("- 拒绝试卷数: ").append(rejected.size()).append('\n');
        report.append("\n## 拒绝明细\n\n");
        for (RejectedExam item : rejected) {
            report.append("- ").append(item.getSubject()).append('/').append(item.getExamId())
                    .append(": ").append(item.getReason()).append('\n');
        }
        Path reportPath = runDir.resolve("测试报告").resolve("测试报告.md");
        Files.createDirectories(reportPath.getParent());
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void write(List<ExamInput> inputs, List<ExamOutcome> outcomes, Path runDir) throws IOException {
        int pages = 0;
        int safe = 0;
        int manual = 0;
        for (ExamOutcome outcome : outcomes) {
            for (PageOutcome page : outcome.getPages()) {
                pages++;
                if ("safe_to_erase".equals(page.getStatus())) {
                    safe++;
                } else if ("manual_review".equals(page.getStatus()) || "error".equals(page.getStatus())) {
                    manual++;
                }
            }
        }
        StringBuilder report = new StringBuilder();
        report.append("# 测试报告\n\n");
        report.append("- 试卷数: ").append(inputs.size()).append('\n');
        report.append("- 页数: ").append(pages).append('\n');
        report.append("- 自动擦除页: ").append(safe).append('\n');
        report.append("- 人工复核页: ").append(manual).append('\n');
        report.append("\n## 试卷\n\n");
        for (ExamInput input : inputs) {
            report.append("- ").append(input.getSubject()).append('/').append(input.getExamId())
                    .append(": ").append(input.getPages().size()).append(" 页\n");
        }
        Path reportPath = runDir.resolve("测试报告").resolve("测试报告.md");
        Files.createDirectories(reportPath.getParent());
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
    }
}
