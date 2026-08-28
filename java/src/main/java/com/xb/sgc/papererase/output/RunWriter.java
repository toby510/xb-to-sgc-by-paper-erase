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
import java.security.MessageDigest;
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
        Path originalWord = wordDir.resolve(input.getExamId() + "_原图.docx");
        boolean hasManualReview = hasManualReview(outcome);
        Path erasedWord = wordDir.resolve(input.getExamId() + (hasManualReview
                ? "_擦除后_待人工审核.docx" : "_擦除后.docx"));
        word.merge(originalWordPages, originalWord);
        word.merge(erasedWordPages, erasedWord);
        copySourceDocuments(input, wordDir, originalWord.getFileName().toString(), erasedWord.getFileName().toString());
    }

    private static boolean hasManualReview(ExamOutcome outcome) {
        for (PageOutcome page : outcome.getPages()) {
            if ("manual_review".equals(page.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将试卷输入目录直系的原始文档随 Word 产物交付。图片页目录是 ExamInput 的唯一来源，
     * 所以从任意一页反推父目录即可；只复制 docx/pdf，且绝不让源文件覆盖本次生成的 Word。
     */
    private static void copySourceDocuments(ExamInput input, Path wordDir, String originalWordName, String erasedWordName) {
        if (input.getPages().isEmpty()) {
            return;
        }
        Path sourceDir = input.getPages().get(0).getImagePath().getParent();
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(sourceDir)) {
            for (Path source : files) {
                if (!Files.isRegularFile(source) || !isSourceDocument(source)) {
                    continue;
                }
                try {
                    String sourceName = source.getFileName().toString();
                    String destinationName = isGeneratedWord(sourceName, originalWordName, erasedWordName)
                            ? sourceFileName(sourceName) : sourceName;
                    Files.copy(source, wordDir.resolve(destinationName), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | SecurityException copyFailure) {
                    logSourceDocumentCopySkipped(source.getFileName().toString(), copyFailure);
                }
            }
        } catch (IOException | SecurityException listingFailure) {
            // 源目录只是附带交付物；无法读取时不能影响图片、Word、报告或 run.json 主链路。
            logSourceDocumentCopySkipped(sourceDir.getFileName() == null ? "source_directory"
                    : sourceDir.getFileName().toString(), listingFailure);
        }
    }

    /** 源文档为附属产物，日志仅保留文件名和异常类型，避免泄露绝对路径或异常堆栈。 */
    private static void logSourceDocumentCopySkipped(String filename, Exception failure) {
        String safeName = filename == null ? "unknown" : filename.replace('\n', '_').replace('\r', '_');
        System.err.println("source_document_copy_skipped=" + safeName + "; reason="
                + failure.getClass().getSimpleName());
    }

    private static boolean isSourceDocument(Path source) {
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".docx") || name.endsWith(".pdf");
    }

    private static boolean isGeneratedWord(String sourceName, String originalWordName, String erasedWordName) {
        return sourceName.equalsIgnoreCase(originalWordName)
                || sourceName.equalsIgnoreCase(erasedWordName)
                || sourceName.matches("(?i).*_原图\\.docx")
                || sourceName.matches("(?i).*_擦除后.*\\.docx");
    }

    private static String sourceFileName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename + "_源文件" : filename.substring(0, dot) + "_源文件" + filename.substring(dot);
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

    /** 运行开始即记录可复现实验元数据；提示词快照避免同一 run 被中途改文件污染。 */
    public static void writeRunningRunJson(Path runDir, Map<String, Path> datasetRoots, String mode,
                                           com.xb.sgc.papererase.vlm.VlmConfig config,
                                           int plannedExamCount, int plannedPageCount, Path skillRoot,
                                           Map<String, String> frozenPrompts) throws IOException {
        Map<String, Object> run = new LinkedHashMap<String, Object>();
        run.put("status", "running");
        run.put("started_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new java.util.Date()));
        Map<String, String> roots = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Path> entry : datasetRoots.entrySet()) roots.put(entry.getKey(), entry.getValue().toAbsolutePath().toString());
        run.put("dataset_roots", roots);
        run.put("mode", mode);
        run.put("provider_kind", config.getProviderKind());
        run.put("model", config.role("locate").getModel());
        run.put("planned_exam_count", plannedExamCount);
        run.put("planned_page_count", plannedPageCount);
        run.put("code", gitMetadata(skillRoot));
        Map<String, Object> prompts = new LinkedHashMap<String, Object>();
        for (String role : new String[]{"locate", "verify", "audit"}) {
            com.xb.sgc.papererase.vlm.VlmConfig.RoleConfig roleConfig = config.role(role);
            byte[] content = frozenPrompts.get(role).getBytes(StandardCharsets.UTF_8);
            Path source = skillRoot.resolve(roleConfig.getPromptPath());
            Path snapshot = runDir.resolve("metadata").resolve("prompts").resolve(role)
                    .resolve(source.getFileName().toString());
            Files.createDirectories(snapshot.getParent());
            Files.write(snapshot, content);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("configured_path", roleConfig.getPromptPath());
            item.put("sha256", sha256(content));
            item.put("snapshot_path", runDir.relativize(snapshot).toString());
            prompts.put(role, item);
        }
        run.put("prompts", prompts);
        run.put("output_schema", "xb-to-sgc-by-paper-erase/exam-page-only/v1");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("run.json").toFile(), run);
    }

    /** 仅补充终态字段，保留启动阶段已冻结的环境与提示词证据。 */
    public static void completeRunJson(Path runDir, int actualExamCount, int actualPageCount) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> run = mapper.readValue(runDir.resolve("run.json").toFile(), LinkedHashMap.class);
        run.put("status", "completed");
        run.put("completed_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new java.util.Date()));
        run.put("actual_exam_count", actualExamCount);
        run.put("actual_page_count", actualPageCount);
        mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("run.json").toFile(), run);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, Object> gitMetadata(Path root) {
        Map<String, Object> code = new LinkedHashMap<String, Object>();
        code.put("branch", git(root, "rev-parse", "--abbrev-ref", "HEAD"));
        code.put("commit", git(root, "rev-parse", "HEAD"));
        code.put("dirty", !git(root, "status", "--porcelain").isEmpty());
        return code;
    }

    private static String git(Path root, String... args) {
        try {
            java.util.List<String> command = new ArrayList<String>();
            command.add("git");
            java.util.Collections.addAll(command, args);
            Process process = new ProcessBuilder(command).directory(root.toFile()).start();
            java.io.InputStream input = process.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int read; (read = input.read(buffer)) >= 0;) out.write(buffer, 0, read);
            process.waitFor();
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
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
        json.put("approved_regions", outcome.getApprovedRegions());
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
