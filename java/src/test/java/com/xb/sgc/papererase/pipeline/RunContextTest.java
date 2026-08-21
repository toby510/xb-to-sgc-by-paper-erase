package com.xb.sgc.papererase.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class RunContextTest {
    @Test
    public void persistsStageEventWithoutPromptImageOrCredentialData() throws Exception {
        Path runDir = Files.createTempDirectory("paper-erase-progress");
        try {
            Constructor<ExamPipeline.RunContext> constructor = ExamPipeline.RunContext.class.getConstructor(Path.class);
            ExamPipeline.RunContext context = constructor.newInstance(runDir);
            Method event = ExamPipeline.RunContext.class.getMethod("event", String.class, String.class, String.class,
                    String.class, String.class, long.class);
            event.invoke(context, "locate", "exam-1", "exam-1:2", "completed", "safe", 123L);

            JsonNode line = new ObjectMapper().readTree(new String(Files.readAllBytes(runDir.resolve("_progress.ndjson")),
                    StandardCharsets.UTF_8));
            assertEquals("locate", line.path("stage").asText());
            assertEquals("exam-1", line.path("exam_id").asText());
            assertEquals("exam-1:2", line.path("page_id").asText());
            assertEquals("completed", line.path("status").asText());
            assertEquals(123L, line.path("elapsed_ms").asLong());
        } finally {
            Files.deleteIfExists(runDir.resolve("_progress.ndjson"));
            Files.deleteIfExists(runDir);
        }
    }
}
