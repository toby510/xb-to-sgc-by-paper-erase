package com.xb.sgc.papererase.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.pipeline.ExamOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;

import javax.imageio.ImageIO;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RunWriter {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final WordMergeComponent word = new WordMergeComponent();

    public static Path createRunDir(Path testRoot, String model, String timestamp) throws IOException {
        String safeModel = model == null || model.trim().isEmpty() ? "unknown-model" : model.replaceAll("[/\\\\:]", "_");
        Path runDir = testRoot.resolve("xb-to-sgc-by-paper-erase-output")
                .resolve("exam-page-only")
                .resolve("runs")
                .resolve(safeModel + "@" + timestamp);
        Files.createDirectories(runDir);
        return runDir;
    }

    public void writeExam(ExamInput input, ExamOutcome outcome, Path runDir) throws IOException {
        Path erasedDir = runDir.resolve("erased").resolve(input.getSubject()).resolve(input.getExamId());
        Path consensusDir = runDir.resolve("consensus").resolve(input.getSubject()).resolve(input.getExamId());
        Path wordDir = runDir.resolve("word_output").resolve(input.getSubject()).resolve(input.getExamId());
        Files.createDirectories(erasedDir);
        Files.createDirectories(consensusDir);
        Files.createDirectories(wordDir);

        List<Path> originalWordPages = new ArrayList<Path>();
        List<Path> erasedWordPages = new ArrayList<Path>();
        for (PageInput page : input.getPages()) {
            PageOutcome pageOutcome = outcome.page(page.getPageId());
            String stem = input.getExamId() + "_" + page.getPageOrder();
            Path originalPath = erasedDir.resolve(stem + "_原图.png");
            Path erasedPath = erasedDir.resolve(stem + "_擦除后.png");
            ImageIO.write(pageOutcome.getOriginal(), "png", originalPath.toFile());
            ImageIO.write(pageOutcome.getCandidate(), "png", erasedPath.toFile());
            originalWordPages.add(originalPath);
            if ("manual_review".equals(pageOutcome.getStatus()) || "error".equals(pageOutcome.getStatus())) {
                Path preview = erasedDir.resolve(stem + "_人工审核预览.png");
                ManualReviewWatermarker.writePreview(pageOutcome.getCandidate(), preview);
                erasedWordPages.add(preview);
            } else {
                erasedWordPages.add(erasedPath);
            }
            mapper.writeValue(erasedDir.resolve(stem + "_regions.json").toFile(), pageEvidence(page, pageOutcome));
            appendAudit(runDir.resolve("_audit.ndjson"), input, page, pageOutcome);
        }

        mapper.writeValue(consensusDir.resolve("exam_consensus.json").toFile(), outcome.getConsensus());
        word.merge(originalWordPages, wordDir.resolve(input.getExamId() + "_原图.docx"));
        word.merge(erasedWordPages, wordDir.resolve(input.getExamId() + "_擦除后.docx"));
    }

    public static void writeRunJson(Path runDir, String model, int examCount, int pageCount) throws IOException {
        Map<String, Object> run = new LinkedHashMap<String, Object>();
        run.put("model", model);
        run.put("exam_count", examCount);
        run.put("page_count", pageCount);
        run.put("output_schema", "xb-to-sgc-by-paper-erase/exam-page-only/v1");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("run.json").toFile(), run);
    }

    private Map<String, Object> pageEvidence(PageInput page, PageOutcome outcome) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("page_id", page.getPageId());
        json.put("exam_id", page.getExamId());
        json.put("page_order", page.getPageOrder());
        json.put("status", outcome.getStatus());
        json.put("reason", outcome.getReason());
        json.put("regions", outcome.getRegions());
        json.put("locate", outcome.getLocate());
        json.put("audit", outcome.getAudit());
        Map<String, Object> transforms = new LinkedHashMap<String, Object>();
        transforms.put("original_width", outcome.getTransforms().getOriginalWidth());
        transforms.put("original_height", outcome.getTransforms().getOriginalHeight());
        transforms.put("normalized_width", outcome.getTransforms().getNormalizedWidth());
        transforms.put("normalized_height", outcome.getTransforms().getNormalizedHeight());
        transforms.put("reading_rotation", outcome.getTransforms().getReadingRotation());
        json.put("transforms", transforms);
        return json;
    }

    private void appendAudit(Path auditPath, ExamInput input, PageInput page, PageOutcome outcome) throws IOException {
        Files.createDirectories(auditPath.getParent());
        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        audit.put("subject", input.getSubject());
        audit.put("exam_id", input.getExamId());
        audit.put("page_id", page.getPageId());
        audit.put("page_order", page.getPageOrder());
        audit.put("status", outcome.getStatus());
        audit.put("reason", outcome.getReason());
        try (BufferedWriter writer = Files.newBufferedWriter(auditPath, StandardCharsets.UTF_8,
                Files.exists(auditPath) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
            writer.write(new ObjectMapper().writeValueAsString(audit));
            writer.newLine();
        }
    }
}
