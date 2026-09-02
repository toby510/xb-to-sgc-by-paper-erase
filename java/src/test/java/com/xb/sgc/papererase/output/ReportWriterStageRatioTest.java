package com.xb.sgc.papererase.output;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** 验证报告中的阶段结果率以本阶段进入/触发量为分母，而不是全量图片数。 */
public class ReportWriterStageRatioTest {
    @Test
    public void usesEnteredOrTriggeredPagesAsSuccessAndFailureDenominator() throws Exception {
        Path run = Files.createTempDirectory("report-stage-ratio-");
        Path erased = run.resolve("erased/语文/1001");
        Files.createDirectories(erased);
        write(erased.resolve("1001_1_regions.json"),
                "{\"page_id\":\"1001:1\",\"exam_id\":\"1001\",\"page_order\":1,\"status\":\"safe_to_erase\"}");
        write(erased.resolve("1001_2_regions.json"),
                "{\"page_id\":\"1001:2\",\"exam_id\":\"1001\",\"page_order\":2,\"status\":\"manual_review\"}");
        // 人工审核页会在报告重建后归档到 bad，夹具需包含对应的可复制产物。
        Files.write(erased.resolve("1001_2_原图.png"), new byte[] {0});
        Files.write(erased.resolve("1001_2_擦除后.png"), new byte[] {0});
        write(run.resolve("_vlm_usage.ndjson"),
                usage("locate", "1001:1") + usage("locate", "1001:1")
                        + usage("locate", "1001:2") + usage("verify", "1001:1") + usage("verify", "1001:2"));
        write(run.resolve("_progress.ndjson"),
                event("locate", "1001:1", "safe_to_erase") + event("locate", "1001:2", "safe_to_erase")
                        + event("verify", "1001:1", "safe_to_erase") + event("verify", "1001:2", "manual_review"));

        new ReportWriter().writeFromRunDirectory(run);
        String report = new String(Files.readAllBytes(run.resolve("测试报告/测试报告.md")), StandardCharsets.UTF_8);

        assertTrue(report.contains("| 安全复核（verify） | 2/2=100% | 1/2=50% | 1/2=50% |"));
        assertTrue(report.contains("| locate 重试 | 1/2=50% | 1/1=100% | 0/1=0% |"));
        assertTrue(report.contains("| ROI 安全复核（verify） | 2/2=100% | 1/2=50% | 1/2=50% |"));
    }

    private static String usage(String role, String pageId) {
        return "{\"role\":\"" + role + "\",\"usage_available\":true,\"page_ids\":[\"" + pageId
                + "\"],\"input_tokens\":10,\"output_tokens\":1,\"total_tokens\":11}\n";
    }

    private static String event(String stage, String pageId, String reason) {
        return "{\"stage\":\"" + stage + "\",\"page_id\":\"" + pageId
                + "\",\"status\":\"completed\",\"reason\":\"" + reason + "\"}\n";
    }

    private static void write(Path file, String content) throws Exception {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
