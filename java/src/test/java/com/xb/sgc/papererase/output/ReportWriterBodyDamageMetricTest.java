package com.xb.sgc.papererase.output;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * 验证安全指标“擦除正文数量”只认审计的结构化字段 body_changed，与 RunMetrics 同一口径。
 * 审计 evidence 是自由文本，否定表述（“无正文损伤”“未发现有任何正文损伤”）不得被当成正文损伤。
 */
public class ReportWriterBodyDamageMetricTest {
    @Test
    public void countsOnlyStructuredBodyChangedFlag() throws Exception {
        Path run = Files.createTempDirectory("report-body-damage-");
        Path erased = run.resolve("erased/语文/1001");
        Files.createDirectories(erased);
        // 审计通过页：evidence 里“未发现有任何正文损伤”是否定表述，不得计入。
        write(erased.resolve("1001_1_regions.json"),
                "{\"page_id\":\"1001:1\",\"exam_id\":\"1001\",\"page_order\":1,\"status\":\"safe_to_erase\","
                        + "\"reason\":\"audit_pass\","
                        + "\"audit\":{\"body_changed\":false,\"evidence\":\"页码已擦除，未发现有任何正文损伤。\"}}");
        // 审计通过页：不含任何损伤措辞。
        write(erased.resolve("1001_2_regions.json"),
                "{\"page_id\":\"1001:2\",\"exam_id\":\"1001\",\"page_order\":2,\"status\":\"safe_to_erase\","
                        + "\"reason\":\"audit_pass\","
                        + "\"audit\":{\"body_changed\":false,\"evidence\":\"r1 四边安全，页码已擦除。\"}}");
        // 审计确认正文变化：必须计入。
        write(erased.resolve("1001_3_regions.json"),
                "{\"page_id\":\"1001:3\",\"exam_id\":\"1001\",\"page_order\":3,\"status\":\"manual_review\","
                        + "\"reason\":\"audit_failed\","
                        + "\"audit\":{\"body_changed\":true,\"evidence\":\"上边危险，正文行下半部分被切断。\"}}");
        // 人工审核但否定表述：结构化字段为 false，文字也不得反向标记正文变化。
        write(erased.resolve("1001_4_regions.json"),
                "{\"page_id\":\"1001:4\",\"exam_id\":\"1001\",\"page_order\":4,\"status\":\"manual_review\","
                        + "\"reason\":\"erase_failed: ink coverage too high\","
                        + "\"audit\":{\"body_changed\":false,\"evidence\":\"页码已擦除，无正文损伤。\"}}");
        // 没有审计结果的页：reason 里出现损伤字样也不计入，正文损伤一律以审计字段为准。
        write(erased.resolve("1001_5_regions.json"),
                "{\"page_id\":\"1001:5\",\"exam_id\":\"1001\",\"page_order\":5,\"status\":\"manual_review\","
                        + "\"reason\":\"locate_error: 未能排除正文损伤风险\"}");
        for (int order = 1; order <= 5; order++) {
            Files.write(erased.resolve("1001_" + order + "_原图.png"), new byte[] {0});
            Files.write(erased.resolve("1001_" + order + "_擦除后.png"), new byte[] {0});
        }

        new ReportWriter().writeFromRunDirectory(run);
        String report = new String(Files.readAllBytes(run.resolve("测试报告/测试报告.md")), StandardCharsets.UTF_8);

        assertTrue(report.contains("| 擦除正文数量 | 1 | 20.00% |"));
    }

    private static void write(Path file, String content) throws Exception {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
