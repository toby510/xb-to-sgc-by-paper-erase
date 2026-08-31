package com.xb.sgc.papererase.output;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

/** Ensures cross-run comparison is a read-only report written beside runs, not inside a source run. */
public class RunComparisonWriterTest {
    @Test
    public void writesComparisonBesideRunsWithAccuracyCostAndPerformance() throws Exception {
        Path root = Files.createTempDirectory("run-comparison-").resolve("exam-page-only");
        Path first = fixture(root.resolve("runs/qwen@a"), "qwen", "safe_to_erase", 5100, 1200L);
        Path second = fixture(root.resolve("runs/doubao@b"), "doubao", "manual_review", 4200, 900L);

        Path report = new RunComparisonWriter().write(Arrays.asList(first, second));
        String text = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);

        assertTrue(report.toString().contains("多模型比对"));
        assertTrue(text.contains("## 准确度与安全"));
        assertTrue(text.contains("## 成本与性能"));
        assertTrue(text.contains("数学/1001/第1页"));
        assertTrue(text.contains("模型A独立通过"));
    }

    private Path fixture(Path run, String model, String status, int totalTokens, long elapsedMillis) throws Exception {
        Path erased = run.resolve("erased/数学/1001");
        Files.createDirectories(erased);
        Files.write(run.resolve("run.json"), ("{\"model\":\"" + model + "\"}").getBytes(StandardCharsets.UTF_8));
        Files.write(erased.resolve("1001_1_regions.json"), ("{\"page_id\":\"1001:1\",\"exam_id\":\"1001\","
                + "\"page_order\":1,\"status\":\"" + status + "\",\"audit\":{\"body_unchanged\":true}}").getBytes(StandardCharsets.UTF_8));
        Files.write(run.resolve("_vlm_usage.ndjson"), ("{\"usage_available\":true,\"page_ids\":[\"1001:1\"],"
                + "\"input_tokens\":4000,\"output_tokens\":100,\"total_tokens\":" + totalTokens
                + ",\"cached_tokens\":0,\"image_tokens\":0,\"text_tokens\":0,\"reasoning_tokens\":0,\"cost_cny\":0.01}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(run.resolve("_progress.ndjson"), ("{\"stage\":\"page\",\"page_id\":\"1001:1\",\"elapsed_ms\":" + elapsedMillis
                + "}\n{\"stage\":\"exam\",\"exam_id\":\"1001\",\"elapsed_ms\":" + elapsedMillis + "}\n").getBytes(StandardCharsets.UTF_8));
        return run;
    }
}
