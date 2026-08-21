package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.erase.InkMaskEraser;
import com.xb.sgc.papererase.image.OrientationNormalizer;
import com.xb.sgc.papererase.image.RoiTransform;
import com.xb.sgc.papererase.input.PageBatcher;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PageDirection;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageTransforms;
import com.xb.sgc.papererase.safety.RegionValidator;
import com.xb.sgc.papererase.safety.RiskGate;
import com.xb.sgc.papererase.vlm.ResponseParser;
import com.xb.sgc.papererase.vlm.VlmClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 试卷级编排器：视觉模型只能提出候选，Java 负责把每一步都收紧为“可证明安全”。
 *
 * <p>任何一页出现协议、网络、坐标或像素门禁异常，均只降级该页为人工审核；只有
 * pattern 阶段无法建立可信的整卷页面对应关系时才整卷降级。这样既不把异常页混入自动
 * 结果，也不会因为单页偶发失败影响整份试卷的可用页面。</p>
 */
public final class ExamPipeline {
    private static final double MIN_DIRECTION_CONFIDENCE = 0.90;
    private final VlmClient vlm;

    public ExamPipeline(VlmClient vlm) {
        if (vlm == null) {
            throw new IllegalArgumentException("vlm is required");
        }
        this.vlm = vlm;
    }

    public ExamOutcome process(ExamInput exam, RunContext context) {
        context = context == null ? new RunContext() : context;
        long examStartedAt = System.currentTimeMillis();
        context.event("exam", exam.getExamId(), null, "started", "page_count=" + exam.getPages().size(), 0);
        Map<String, BufferedImage> originals = readOriginals(exam);
        context.event("image_load", exam.getExamId(), null, "completed", "page_count=" + originals.size(), 0);
        PatternBundle bundle;
        try {
            bundle = buildPattern(exam, originals, context);
        } catch (ResponseParser.ParseException e) {
            ExamOutcome outcome = wholeExamFallback(exam, originals, "pattern_protocol_error", e.getMessage());
            context.event("exam", exam.getExamId(), null, outcome.getStatus(), outcome.getReason(), System.currentTimeMillis() - examStartedAt);
            return outcome;
        } catch (RuntimeException e) {
            ExamOutcome outcome = wholeExamFallback(exam, originals, "pattern_protocol_error", e.getMessage());
            context.event("exam", exam.getExamId(), null, outcome.getStatus(), outcome.getReason(), System.currentTimeMillis() - examStartedAt);
            return outcome;
        }

        List<PageOutcome> outcomes = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            BufferedImage original = originals.get(page.getPageId());
            long pageStartedAt = System.currentTimeMillis();
            context.event("page", exam.getExamId(), page.getPageId(), "started", null, 0);
            PageOutcome pageOutcome;
            try {
                // 页面异常必须隔离：不能因为一张异常图让后续页绕过审计或丢失产物。
                pageOutcome = published(page, original, bundle, processPage(exam, page, original, bundle, context));
            } catch (RuntimeException e) {
                pageOutcome = manual(page, original, original, transform(original, original, 0),
                        "page_processing_error", bundle.groupByPage.get(page.getPageId()), null);
            }
            outcomes.add(pageOutcome);
            context.event("page", exam.getExamId(), page.getPageId(), pageOutcome.getStatus(), pageOutcome.getReason(),
                    System.currentTimeMillis() - pageStartedAt);
        }
        ExamOutcome outcome = new ExamOutcome(exam.getExamId(), "processed", "ok", outcomes, bundle.groups);
        context.event("exam", exam.getExamId(), null, outcome.getStatus(), outcome.getReason(), System.currentTimeMillis() - examStartedAt);
        return outcome;
    }

    private PageOutcome published(PageInput page, BufferedImage original, PatternBundle bundle, PageOutcome outcome) {
        if ("safe_to_erase".equals(outcome.getStatus()) || "no_pagenum".equals(outcome.getStatus())
                || "manual_review".equals(outcome.getStatus())) {
            return outcome;
        }
        return manual(page, original, outcome.getNormalized(), outcome.getTransforms(),
                "internal_state_" + outcome.getStatus(), bundle.groupByPage.get(page.getPageId()), outcome.getLocate());
    }

    private PatternBundle buildPattern(ExamInput exam, Map<String, BufferedImage> originals, RunContext context) {
        PatternBundle bundle = new PatternBundle();
        int batchIndex = 0;
        for (List<PageInput> batch : PageBatcher.overlapping(exam.getPages(), 8, 1)) {
            long startedAt = System.currentTimeMillis();
            batchIndex++;
            List<VlmClient.PageImage> images = new ArrayList<VlmClient.PageImage>();
            List<String> expected = new ArrayList<String>();
            for (PageInput page : batch) {
                images.add(new VlmClient.PageImage(page.getPageId(), originals.get(page.getPageId())));
                expected.add(page.getPageId());
            }
            context.event("pattern", exam.getExamId(), null, "started", "batch=" + batchIndex + " page_count=" + images.size(), 0);
            PatternResponse response;
            try {
                response = vlm.pattern(images);
                context.event("pattern", exam.getExamId(), null, "completed", "batch=" + batchIndex + " page_count=" + images.size(),
                        System.currentTimeMillis() - startedAt);
            } catch (RuntimeException e) {
                context.event("pattern", exam.getExamId(), null, "failed", e.getClass().getSimpleName(),
                        System.currentTimeMillis() - startedAt);
                throw e;
            }
            // 绝不用响应数组位置对齐；错页坐标是“擦到正文”的高风险来源。
            if (!pageIds(response).equals(new java.util.HashSet<String>(expected))) {
                throw new ResponseParser.ParseException("batch page ids mismatch", "");
            }
            for (PageDirection direction : response.page_directions) {
                PageDirection existing = bundle.directions.get(direction.page_id);
                if (existing != null && (existing.reading_rotation != direction.reading_rotation
                        || Math.abs(existing.confidence - direction.confidence) > 0.20)) {
                    bundle.consensusState = "mixed";
                } else {
                    bundle.directions.put(direction.page_id, direction);
                }
            }
            for (PatternGroup group : response.pattern_groups) {
                bundle.groups.add(group);
                for (String pageId : group.page_ids) {
                    PatternGroup existing = bundle.groupByPage.get(pageId);
                    if (existing != null && !existing.group_id.equals(group.group_id)) {
                        bundle.consensusState = "mixed";
                    }
                    bundle.groupByPage.put(pageId, group);
                }
            }
        }
        return bundle;
    }

    private java.util.Set<String> pageIds(PatternResponse response) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (PageDirection direction : response.page_directions) {
            if (!ids.add(direction.page_id)) {
                throw new ResponseParser.ParseException("duplicate page_id", "");
            }
        }
        return ids;
    }

    private PageOutcome processPage(ExamInput exam, PageInput page, BufferedImage original, PatternBundle bundle, RunContext context) {
        PageDirection direction = bundle.directions.get(page.getPageId());
        if (direction == null || direction.confidence < MIN_DIRECTION_CONFIDENCE) {
            return manual(page, original, original, transform(original, original, direction == null ? 0 : direction.reading_rotation),
                    "low_direction_confidence", bundle.groupByPage.get(page.getPageId()), null);
        }

        // 坐标、边缘带和正文间隔均在统一阅读方向中判定，避免横竖页混用坐标系。
        OrientationNormalizer.NormalizedImage normalized = OrientationNormalizer.normalize(original, direction.reading_rotation);
        BufferedImage normalizedImage = normalized.getImage();
        context.event("normalize", exam.getExamId(), page.getPageId(), "completed", "rotation=" + direction.reading_rotation, 0);
        PageTransforms transforms = new PageTransforms(normalized.getOriginalWidth(), normalized.getOriginalHeight(),
                normalized.getNormalizedWidth(), normalized.getNormalizedHeight(), normalized.getReadingRotation());
        PatternGroup group = bundle.groupByPage.get(page.getPageId());
        VlmClient.PageImage pageImage = new VlmClient.PageImage(page.getPageId(), normalizedImage);
        LocateResponse locate;
        try {
            long startedAt = System.currentTimeMillis();
            context.event("locate", exam.getExamId(), page.getPageId(), "started", null, 0);
            locate = vlm.locate(pageImage, group);
            context.event("locate", exam.getExamId(), page.getPageId(), "completed", locate.status,
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            context.event("locate", exam.getExamId(), page.getPageId(), "failed", e.getClass().getSimpleName(), 0);
            return manual(page, original, normalizedImage, transforms, "locate_error", group, null);
        }
        if ("manual_review".equals(locate.status)) {
            return manual(page, original, normalizedImage, transforms, "locate_manual_review", group, locate);
        }
        if ("no_pagenum".equals(locate.status) || locate.regions.isEmpty()) {
            return handleNoCandidate(exam, page, original, normalizedImage, transforms, bundle, group, locate, pageImage, context);
        }

        // VLM 的坐标只是候选；通过 Java 的确定性硬门禁前绝不触发像素写入。
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(locate.page_id, locate.status, locate.regions, locate.nearest_body_boundary),
                normalizedImage);
        if (!validation.isAccepted()) {
            context.event("validation", exam.getExamId(), page.getPageId(), "rejected", validation.getReasons().toString(), 0);
            return manual(page, original, normalizedImage, transforms, "validation_rejected", group, locate);
        }
        context.event("validation", exam.getExamId(), page.getPageId(), "accepted", "region_count=" + validation.getRegions().size(), 0);
        RiskGate.PageContext riskContext = RiskGate.PageContext.stable(page.getPageId())
                .withPatternGroupId(group == null ? null : group.group_id)
                .withConsensusState(bundle.consensusState)
                .withReadingRotation(direction.reading_rotation)
                .withPageSequenceIncomplete(exam.isPageSequenceIncomplete());
        // 只对风险页局部二检，以控制成本；同线候选仍强制二检，防止把正文行尾当页码。
        boolean hasColoredCandidate = InkMaskEraser.hasColoredPixels(normalizedImage, validation.getRegions());
        boolean requiresVerify = RiskGate.requiresLocalVerify(riskContext, validation) || hasOnLineRegion(locate.regions)
                || hasColoredCandidate;
        if (requiresVerify) {
            PageOutcome denied = verifyThenMaybeManual(exam, page, original, normalizedImage, transforms, group, locate, pageImage,
                    "verify_denied", context);
            if (!"needs_recheck".equals(denied.getStatus())) {
                return denied;
            }
        }
        return eraseAndAudit(exam, page, original, normalizedImage, transforms, group, locate, pageImage,
                validation.getRegions(), requiresVerify, context);
    }

    private boolean hasOnLineRegion(List<EraseRegion> regions) {
        for (EraseRegion region : regions) {
            if (region.on_line) {
                return true;
            }
        }
        return false;
    }

    private PageOutcome handleNoCandidate(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized,
                                          PageTransforms transforms, PatternBundle bundle, PatternGroup group,
                                          LocateResponse locate, VlmClient.PageImage pageImage, RunContext context) {
        if (group == null || "mixed".equals(bundle.consensusState)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "locate_no_pagenum", original, normalized,
                    normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
        }
        long startedAt = System.currentTimeMillis();
        context.event("verify", exam.getExamId(), page.getPageId(), "started", "edge", 0);
        VerifyResponse verify = vlm.verify(pageImage, null,
                edgeRoi(page.getPageId(), group, locate.nearest_body_boundary, normalized));
        context.event("verify", exam.getExamId(), page.getPageId(), "completed", verify.decision,
                System.currentTimeMillis() - startedAt);
        if ("no_pagenum".equals(verify.decision)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "edge_verify_no_pagenum", original, normalized,
                    normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
        }
        return manual(page, original, normalized, transforms, "edge_verify_" + verify.decision, group, locate);
    }

    private PageOutcome verifyThenMaybeManual(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized,
                                              PageTransforms transforms, PatternGroup group, LocateResponse locate,
                                              VlmClient.PageImage pageImage, String deniedReason, RunContext context) {
        for (EraseRegion region : locate.regions) {
            long startedAt = System.currentTimeMillis();
            context.event("verify", exam.getExamId(), page.getPageId(), "started", region.region_id, 0);
            VerifyResponse verify = vlm.verify(pageImage, region,
                    roi(page.getPageId(), region, locate.nearest_body_boundary, normalized));
            context.event("verify", exam.getExamId(), page.getPageId(), "completed", verify.decision,
                    System.currentTimeMillis() - startedAt);
            if (!"safe_to_erase".equals(verify.decision)) {
                if ("no_pagenum".equals(verify.decision)) {
                    return new PageOutcome(page.getPageId(), "no_pagenum", "verify_no_pagenum", original, normalized,
                            normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
                }
                return manual(page, original, normalized, transforms, deniedReason, group, locate);
            }
        }
        return new PageOutcome(page.getPageId(), "needs_recheck", "verify_safe", original, normalized, normalized,
                transforms, group, locate.regions, locate, null);
    }

    private PageOutcome eraseAndAudit(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                                      PatternGroup group, LocateResponse locate, VlmClient.PageImage pageImage,
                                      List<RegionValidator.PixelRegion> pixelRegions, boolean coloredTargetVerified,
                                      RunContext context) {
        BufferedImage candidate = normalized;
        for (RegionValidator.PixelRegion pixelRegion : pixelRegions) {
            // 擦除器执行掩码级修改，并由像素差分门禁保证候选框外零改动。
            InkMaskEraser.EraseOutcome erase = InkMaskEraser.erase(candidate, pixelRegion, coloredTargetVerified);
            if (erase.getStatus() != InkMaskEraser.Status.SAFE_TO_ERASE) {
                context.event("erase", exam.getExamId(), page.getPageId(), "rejected", erase.getReason(), 0);
                return manual(page, original, normalized, transforms, "erase_failed: " + erase.getReason(), group, locate);
            }
            candidate = erase.getCandidate();
        }
        context.event("erase", exam.getExamId(), page.getPageId(), "completed", "region_count=" + pixelRegions.size(), 0);
        long auditStartedAt = System.currentTimeMillis();
        context.event("audit", exam.getExamId(), page.getPageId(), "started", null, 0);
        AuditResponse audit = vlm.audit(pageImage, new VlmClient.PageImage(page.getPageId(), candidate),
                locate.regions, rois(page.getPageId(), locate.regions, locate.nearest_body_boundary, normalized));
        context.event("audit", exam.getExamId(), page.getPageId(), "completed", audit.decision,
                System.currentTimeMillis() - auditStartedAt);
        // 正文不变、目标确实消失是交付的双硬条件；背景色仅是质量告警，不扩大擦除范围。
        if (!audit.body_unchanged || !audit.target_removed) {
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized,
                    normalized, transforms, group, locate.regions, locate, audit);
        }
        if (!audit.background_acceptable) {
            return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass_with_color_warning", original, normalized,
                    candidate, transforms, group, locate.regions, locate, audit);
        }
        if (!"pass".equals(audit.decision)) {
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized,
                    normalized, transforms, group, locate.regions, locate, audit);
        }
        return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass", original, normalized,
                candidate, transforms, group, locate.regions, locate, audit);
    }

    private VlmClient.RoiImage roi(String pageId, EraseRegion region, BodyBoundary boundary, BufferedImage image) {
        RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                image.getWidth(), image.getHeight(), region, boundary, 24);
        return new VlmClient.RoiImage(pageId, region.region_id, crop(image, transform));
    }

    private VlmClient.RoiImage edgeRoi(String pageId, PatternGroup group, BodyBoundary boundary, BufferedImage image) {
        RoiTransform.PageEdge edge = RoiTransform.PageEdge.BOTTOM;
        if ("top".equals(group.edge)) {
            edge = RoiTransform.PageEdge.TOP;
        } else if ("left".equals(group.edge)) {
            edge = RoiTransform.PageEdge.LEFT;
        } else if ("right".equals(group.edge)) {
            edge = RoiTransform.PageEdge.RIGHT;
        }
        return new VlmClient.RoiImage(pageId, "edge",
                crop(image, RoiTransform.fromEdge(image.getWidth(), image.getHeight(), edge, boundary, 24)));
    }

    private List<VlmClient.RoiImage> rois(String pageId, List<EraseRegion> regions,
                                          BodyBoundary boundary, BufferedImage image) {
        List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
        for (EraseRegion region : regions) {
            rois.add(roi(pageId, region, boundary, image));
        }
        return rois;
    }

    private BufferedImage crop(BufferedImage image, RoiTransform transform) {
        return image.getSubimage(transform.getX(), transform.getY(), transform.getWidth(), transform.getHeight());
    }

    private PageOutcome manual(PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                               String reason, PatternGroup group, LocateResponse locate) {
        if (transforms == null) {
            transforms = transform(original, normalized, 0);
        }
        return new PageOutcome(page.getPageId(), "manual_review", reason, original, normalized, normalized,
                transforms, group, locate == null ? Collections.<EraseRegion>emptyList() : locate.regions, locate, null);
    }

    private PageTransforms transform(BufferedImage original, BufferedImage normalized, int readingRotation) {
        return new PageTransforms(original.getWidth(), original.getHeight(), normalized.getWidth(), normalized.getHeight(), readingRotation);
    }

    private ExamOutcome wholeExamFallback(ExamInput exam, Map<String, BufferedImage> originals, String reason, String detail) {
        List<PageOutcome> pages = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            BufferedImage image = originals.get(page.getPageId());
            pages.add(new PageOutcome(page.getPageId(), "manual_review", detail, image, image, image,
                    transform(image, image, 0), null, Collections.<EraseRegion>emptyList(), null, null));
        }
        return new ExamOutcome(exam.getExamId(), "manual_review", reason, pages, Collections.<PatternGroup>emptyList());
    }

    private Map<String, BufferedImage> readOriginals(ExamInput exam) {
        Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();
        for (PageInput page : exam.getPages()) {
            try {
                images.put(page.getPageId(), ImageIO.read(page.getImagePath().toFile()));
            } catch (IOException e) {
                throw new RuntimeException("cannot read page image: " + page.getPageId(), e);
            }
        }
        return images;
    }

    private static final class PatternBundle {
        final Map<String, PageDirection> directions = new HashMap<String, PageDirection>();
        final Map<String, PatternGroup> groupByPage = new HashMap<String, PatternGroup>();
        final List<PatternGroup> groups = new ArrayList<PatternGroup>();
        String consensusState = "stable";
    }

    public static final class RunContext {
        private final Path progressPath;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        /** 无正式产物目录时静默，避免单元测试输出大量阶段日志。 */
        public RunContext() {
            this.progressPath = null;
        }

        /** 将阶段事件持续写入 runDir/_progress.ndjson，运行中即可通过 tail 观察。 */
        public RunContext(Path runDir) {
            if (runDir == null) {
                throw new IllegalArgumentException("runDir is required");
            }
            this.progressPath = runDir.resolve("_progress.ndjson");
        }

        public synchronized void event(String stage, String examId, String pageId, String status, String reason, long elapsedMillis) {
            Map<String, Object> event = new java.util.LinkedHashMap<String, Object>();
            event.put("timestamp_ms", System.currentTimeMillis());
            event.put("stage", stage);
            event.put("exam_id", examId);
            event.put("page_id", pageId);
            event.put("status", status);
            event.put("reason", reason);
            event.put("elapsed_ms", elapsedMillis);
            try {
                String line = mapper.writeValueAsString(event);
                if (progressPath == null) {
                    return;
                }
                Files.createDirectories(progressPath.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(progressPath, StandardCharsets.UTF_8,
                        Files.exists(progressPath) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                throw new RuntimeException("cannot write pipeline progress event", e);
            }
        }
    }
}
