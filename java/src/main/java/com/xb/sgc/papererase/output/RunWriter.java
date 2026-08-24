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
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行产物写入器：保存原图、擦除图、JSON 证据、Word 和运行元数据，保持页面可追溯。
 * 写入层不重新判断像素安全，只忠实落盘 ExamOutcome。
 */
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

    public void writeExam(ExamInput input, ExamOutcome outcome, Path runDir) throws Exception {
        Path erasedDir = runDir.resolve("erased").resolve(input.getSubject()).resolve(input.getExamId());
        Path consensusDir = runDir.resolve("consensus").resolve(input.getSubject()).resolve(input.getExamId());
        Path wordDir = runDir.resolve("word_output").resolve(input.getSubject()).resolve(input.getExamId());
        Path badExamDir = runDir.resolve("bad").resolve(input.getSubject()).resolve(input.getExamId());
        Files.createDirectories(erasedDir);
        Files.createDirectories(consensusDir);
        Files.createDirectories(wordDir);
        deleteRecursively(badExamDir);

        List<Path> originalWordPages = new ArrayList<Path>();
        List<Path> erasedWordPages = new ArrayList<Path>();
        for (PageInput page : input.getPages()) {
            PageOutcome pageOutcome = outcome.page(page.getPageId());
            String stem = input.getExamId() + "_" + page.getPageOrder();
            Path originalPath = erasedDir.resolve(stem + "_原图.png");
            Path erasedPath = erasedDir.resolve(stem + "_擦除后.png");
            ImageIO.write(pageOutcome.getOriginal(), "png", originalPath.toFile());
            originalWordPages.add(originalPath);
            if ("manual_review".equals(pageOutcome.getStatus()) || "error".equals(pageOutcome.getStatus())) {
                if (!pageOutcome.getRegions().isEmpty()) {
                    // 门禁未过也必须让用户看到按模型坐标擦除的效果；它只是不具备交付资格。
                    ManualReviewWatermarker.writeCoordinateErasePreview(pageOutcome.getNormalized(), pageOutcome.getRegions(), erasedPath);
                } else {
                    ManualReviewWatermarker.writeNotDeliverableCopy(pageOutcome.getCandidate(), erasedPath);
                }
            } else {
                ImageIO.write(pageOutcome.getCandidate(), "png", erasedPath.toFile());
            }
            erasedWordPages.add(erasedPath);
            Path evidencePath = erasedDir.resolve(stem + "_regions.json");
            mapper.writeValue(evidencePath.toFile(), pageEvidence(page, pageOutcome));
            if ("manual_review".equals(pageOutcome.getStatus())) {
                Files.createDirectories(badExamDir);
                Files.copy(originalPath, badExamDir.resolve(originalPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(erasedPath, badExamDir.resolve(erasedPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(evidencePath, badExamDir.resolve(evidencePath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            appendAudit(runDir.resolve("_audit.ndjson"), input, page, pageOutcome);
        }

        mapper.writeValue(consensusDir.resolve("exam_consensus.json").toFile(), outcome.getConsensus());
        word.merge(originalWordPages, wordDir.resolve(input.getExamId() + "_原图.docx"));
        word.merge(erasedWordPages, wordDir.resolve(input.getExamId() + "_擦除后.docx"));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    public static void writeRunJson(Path runDir, String model, int examCount, int pageCount) throws IOException {
        Map<String, Object> run = new LinkedHashMap<String, Object>();
        run.put("model", model);
        run.put("exam_count", examCount);
        run.put("page_count", pageCount);
        run.put("output_schema", "xb-to-sgc-by-paper-erase/exam-page-only/v1");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("run.json").toFile(), run);
    }

    /** 只根据既有 erased 产物重建最终人工页集合；不触发 VLM，也不改变擦除判定。 */
    public static void rebuildBadFromErased(Path runDir) throws IOException {
        Path badRoot = runDir.resolve("bad");
        deleteRecursively(badRoot);
        Path erasedRoot = runDir.resolve("erased");
        if (!Files.isDirectory(erasedRoot)) {
            return;
        }
        List<Path> evidenceFiles = new ArrayList<Path>();
        collectEvidenceFiles(erasedRoot, evidenceFiles);
        ObjectMapper reader = new ObjectMapper();
        for (Path evidence : evidenceFiles) {
            com.fasterxml.jackson.databind.JsonNode node = reader.readTree(evidence.toFile());
            if (node == null || !"manual_review".equals(node.path("status").asText())) {
                continue;
            }
            Path examDir = evidence.getParent();
            Path subjectDir = examDir.getParent();
            String name = evidence.getFileName().toString();
            String stem = name.substring(0, name.length() - "_regions.json".length());
            Path destination = badRoot.resolve(subjectDir.getFileName().toString()).resolve(examDir.getFileName().toString());
            Files.createDirectories(destination);
            Files.copy(examDir.resolve(stem + "_原图.png"), destination.resolve(stem + "_原图.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(examDir.resolve(stem + "_擦除后.png"), destination.resolve(stem + "_擦除后.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(evidence, destination.resolve(evidence.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
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

    private static void collectEvidenceFiles(Path directory, List<Path> evidenceFiles) throws IOException {
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Files.isDirectory(file)) {
                    collectEvidenceFiles(file, evidenceFiles);
                } else if (file.getFileName().toString().endsWith("_regions.json")) {
                    evidenceFiles.add(file);
                }
            }
        }
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
