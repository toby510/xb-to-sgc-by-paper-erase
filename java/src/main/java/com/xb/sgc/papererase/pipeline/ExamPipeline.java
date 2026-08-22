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
    private static final int ROI_MAPPING_GUARD_PIXELS = 4;
    private final VlmClient vlm;
    private final int patternSampleMaxPages;

    public ExamPipeline(VlmClient vlm) {
        this(vlm, 0);
    }

    /**
     * @param patternSampleMaxPages 0 表示全页 pattern；正数表示稳定试卷的代表页上限。
     */
    public ExamPipeline(VlmClient vlm, int patternSampleMaxPages) {
        if (vlm == null) {
            throw new IllegalArgumentException("vlm is required");
        }
        if (patternSampleMaxPages < 0) {
            throw new IllegalArgumentException("patternSampleMaxPages must be >= 0");
        }
        this.vlm = vlm;
        this.patternSampleMaxPages = patternSampleMaxPages;
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
        List<PageInput> representative = PageBatcher.representative(exam.getPages(), patternSampleMaxPages);
        if (representative.size() < exam.getPages().size()) {
            PatternResponse sampled = analyzePatternBatch(exam, representative, originals, context, "representative");
            if (canInheritRepresentativePattern(sampled, representative)) {
                PatternGroup inherited = copyPatternGroup(sampled.pattern_groups.get(0));
                inherited.page_ids.clear();
                int rotation = sampled.page_directions.get(0).reading_rotation;
                double directionConfidence = sampled.page_directions.get(0).confidence;
                for (PageInput page : exam.getPages()) {
                    PageDirection direction = new PageDirection();
                    direction.page_id = page.getPageId();
                    direction.reading_rotation = rotation;
                    direction.confidence = directionConfidence;
                    bundle.directions.put(direction.page_id, direction);
                    inherited.page_ids.add(page.getPageId());
                    bundle.groupByPage.put(page.getPageId(), inherited);
                }
                bundle.groups.add(inherited);
                context.event("pattern", exam.getExamId(), null, "inherited",
                        "representative_page_count=" + representative.size(), 0);
                return bundle;
            }
            // 代表页不能证明整卷同质时，成本让位于安全：保留原有全页分析，而不猜测未采样页。
            context.event("pattern", exam.getExamId(), null, "fallback_full", "representative_not_stable", 0);
        }
        int batchIndex = 0;
        int fullPatternBatchSize = patternSampleMaxPages == 0 ? 8 : 6;
        for (List<PageInput> batch : PageBatcher.overlapping(exam.getPages(), fullPatternBatchSize, 1)) {
            batchIndex++;
            PatternResponse response = analyzePatternBatch(exam, batch, originals, context, "batch=" + batchIndex);
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

    private PatternResponse analyzePatternBatch(ExamInput exam, List<PageInput> pages, Map<String, BufferedImage> originals,
                                                RunContext context, String label) {
        long startedAt = System.currentTimeMillis();
        List<VlmClient.PageImage> images = new ArrayList<VlmClient.PageImage>();
        List<String> expected = new ArrayList<String>();
        for (PageInput page : pages) {
            images.add(new VlmClient.PageImage(page.getPageId(), originals.get(page.getPageId())));
            expected.add(page.getPageId());
        }
        context.event("pattern", exam.getExamId(), null, "started", label + " page_count=" + images.size(), 0);
        PatternResponse response;
        try {
            response = vlm.pattern(images);
            context.event("pattern", exam.getExamId(), null, "completed", label + " page_count=" + images.size(),
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            context.event("pattern", exam.getExamId(), null, "failed", e.getClass().getSimpleName(),
                    System.currentTimeMillis() - startedAt);
            throw e;
        }
        if (!pageIds(response).equals(new java.util.HashSet<String>(expected))) {
            throw new ResponseParser.ParseException("batch page ids mismatch", "");
        }
        return response;
    }

    private boolean canInheritRepresentativePattern(PatternResponse response, List<PageInput> representative) {
        if (response.pattern_groups.size() != 1 || !response.heterogeneous_page_ids.isEmpty()
                || !response.no_pagenum_page_ids.isEmpty() || !response.ungrouped_page_ids.isEmpty()) {
            return false;
        }
        PatternGroup group = response.pattern_groups.get(0);
        if (group.confidence < 0.95 || group.locate_window == null || group.page_ids.size() != representative.size()
                || response.page_directions.size() != representative.size()) {
            return false;
        }
        int rotation = response.page_directions.get(0).reading_rotation;
        for (PageDirection direction : response.page_directions) {
            if (direction.reading_rotation != rotation || direction.confidence < MIN_DIRECTION_CONFIDENCE) {
                return false;
            }
        }
        return true;
    }

    private PatternGroup copyPatternGroup(PatternGroup source) {
        PatternGroup copy = new PatternGroup();
        copy.group_id = source.group_id;
        copy.edge = source.edge;
        copy.alignment = source.alignment;
        copy.layout_description = source.layout_description;
        copy.confidence = source.confidence;
        copy.locate_window = source.locate_window;
        return copy;
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
            LocateRoi locateRoi = patternSampleMaxPages > 0 ? locateRoi(page.getPageId(), group, normalizedImage) : null;
            if (locateRoi == null) {
                locate = vlm.locate(pageImage, group);
            } else {
                // locate 只看局部高清图；其响应坐标在此处统一映射回整页坐标，后续安全门禁无需特判。
                locate = mapLocateToFull(vlm.locate(pageImage, group, locateRoi.image), locateRoi.transform,
                        normalizedImage.getWidth(), normalizedImage.getHeight());
            }
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
        // 首次定位已经通过空间门禁、但候选框本身没有任何可擦墨迹时，不能让 Java 沿边缘猜
        // 测页码。改由模型查看同一边缘带的高清图，重新给出局部坐标和正文边界；没有明确
        // 坐标就关闭失败。这针对的是“语义识别对、归一化坐标偏移”的模型已知失效模式。
        boolean refinedByVlm = false;
        if (hasEmptyTargetBox(normalizedImage, validation.getRegions())) {
            Refinement refinement = refineEmptyTargetBox(exam, page, normalizedImage, locate, pageImage, context);
            if (refinement == null) {
                return manual(page, original, normalizedImage, transforms, "coordinate_refine_denied", group, locate);
            }
            locate = refinement.locate;
            validation = refinement.validation;
            refinedByVlm = true;
            context.event("validation", exam.getExamId(), page.getPageId(), "accepted", "coordinate_refined", 0);
        }
        RiskGate.PageContext riskContext = RiskGate.PageContext.stable(page.getPageId())
                .withPatternGroupId(group == null ? null : group.group_id)
                .withConsensusState(bundle.consensusState)
                .withReadingRotation(direction.reading_rotation)
                .withPageSequenceIncomplete(exam.isPageSequenceIncomplete());
        // 只对风险页局部二检，以控制成本；同线候选仍强制二检，防止把正文行尾当页码。
        boolean hasColoredCandidate = InkMaskEraser.hasColoredPixels(normalizedImage, validation.getRegions());
        boolean hasCoordinateRescue = hasCoordinateRescue(validation.getRegions(), normalizedImage);
        boolean requiresVerify = !refinedByVlm && (RiskGate.requiresLocalVerify(riskContext, validation) || hasOnLineRegion(locate.regions)
                || hasColoredCandidate || hasCoordinateRescue);
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

    private boolean hasEmptyTargetBox(BufferedImage image, List<RegionValidator.PixelRegion> regions) {
        for (RegionValidator.PixelRegion region : regions) {
            InkMaskEraser.EraseOutcome probe = InkMaskEraser.erase(image, region, false);
            if ("no target ink found".equals(probe.getReason())) {
                return true;
            }
        }
        return false;
    }

    private Refinement refineEmptyTargetBox(ExamInput exam, PageInput page, BufferedImage image, LocateResponse locate,
                                             VlmClient.PageImage pageImage, RunContext context) {
        // 多框页面没有“唯一失焦目标”的证明，避免将局部复核结果绑定到错误的候选。
        if (locate.regions.size() != 1) {
            return null;
        }
        EraseRegion originalRegion = locate.regions.get(0);
        EdgeRoi edgeRoi = fullEdgeRoi(page.getPageId(), originalRegion, image);
        if (edgeRoi == null) {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        context.event("coordinate_refine", exam.getExamId(), page.getPageId(), "started", originalRegion.region_id, 0);
        // 不污染首次定位结果，只用请求副本把“必须返回 ROI 坐标”这个意图显式传给模型。
        EraseRegion requestRegion = copyRegion(originalRegion);
        requestRegion.safety_margin = "coordinate_refinement_requested";
        VerifyResponse verify = vlm.verify(pageImage, requestRegion, edgeRoi.image);
        context.event("coordinate_refine", exam.getExamId(), page.getPageId(), "completed", verify.decision,
                System.currentTimeMillis() - startedAt);
        if (!"safe_to_erase".equals(verify.decision) || verify.refined_region == null
                || verify.refined_nearest_body_boundary == null) {
            return null;
        }
        EraseRegion refined = copyRegion(originalRegion);
        RoiTransform.NormalizedRect rect = edgeRoi.transform.localRectToFullNormalized(
                verify.refined_region.x1, verify.refined_region.y1,
                verify.refined_region.x2, verify.refined_region.y2);
        refined.x1 = rect.getX1();
        refined.y1 = rect.getY1();
        refined.x2 = rect.getX2();
        refined.y2 = rect.getY2();
        refined.safety_margin = "local_vlm_coordinate_refined";
        LocateResponse refinedLocate = new LocateResponse();
        refinedLocate.page_id = locate.page_id;
        refinedLocate.status = locate.status;
        refinedLocate.regions.add(refined);
        refinedLocate.nearest_body_boundary = mapBoundary(verify.refined_nearest_body_boundary, edgeRoi.transform,
                image.getWidth(), image.getHeight());
        refinedLocate.evidence = locate.evidence + "; coordinate_refined=" + verify.evidence;
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(refinedLocate.page_id, refinedLocate.status, refinedLocate.regions,
                        refinedLocate.nearest_body_boundary), image);
        context.event("coordinate_refine", exam.getExamId(), page.getPageId(),
                validation.isAccepted() ? "mapped" : "rejected",
                "region=" + refined.x1 + "," + refined.y1 + "," + refined.x2 + "," + refined.y2
                        + "; body=" + refinedLocate.nearest_body_boundary.x + ","
                        + refinedLocate.nearest_body_boundary.y + "; reasons=" + validation.getReasons(), 0);
        return validation.isAccepted() ? new Refinement(refinedLocate, validation) : null;
    }

    private EdgeRoi fullEdgeRoi(String pageId, EraseRegion region, BufferedImage image) {
        RoiTransform.PageEdge edge;
        if (region.y2 <= 0.20) {
            edge = RoiTransform.PageEdge.TOP;
        } else if (region.y1 >= 0.80) {
            edge = RoiTransform.PageEdge.BOTTOM;
        } else if (region.x2 <= 0.20) {
            edge = RoiTransform.PageEdge.LEFT;
        } else if (region.x1 >= 0.80) {
            edge = RoiTransform.PageEdge.RIGHT;
        } else {
            return null;
        }
        RoiTransform transform = RoiTransform.fromEdge(image.getWidth(), image.getHeight(), edge, null, 0);
        return new EdgeRoi(transform, new VlmClient.RoiImage(pageId, region.region_id, crop(image, transform)));
    }

    private BodyBoundary mapBoundary(BodyBoundary local, RoiTransform transform, int fullWidth, int fullHeight) {
        BodyBoundary mapped = new BodyBoundary();
        mapped.x = local.x == null ? null : (transform.getX() + local.x * transform.getWidth()) / fullWidth;
        mapped.y = local.y == null ? null : (transform.getY() + local.y * transform.getHeight()) / fullHeight;
        mapped.basis = local.basis;
        return mapped;
    }

    private EraseRegion copyRegion(EraseRegion source) {
        EraseRegion copy = new EraseRegion();
        copy.region_id = source.region_id;
        copy.x1 = source.x1; copy.y1 = source.y1; copy.x2 = source.x2; copy.y2 = source.y2;
        copy.page_number_text = source.page_number_text;
        copy.same_line_metadata = source.same_line_metadata;
        copy.on_line = source.on_line;
        copy.confidence = source.confidence;
        copy.safety_margin = source.safety_margin;
        return copy;
    }

    /**
     * Java 曾为补齐模型过紧/偏移坐标而扩展候选框时，必须增加一次局部视觉复核。坐标救援
     * 只解决“页码笔画没有完全落入模型框”的几何问题，不能替代对该墨迹语义的独立确认。
     */
    private boolean hasCoordinateRescue(List<RegionValidator.PixelRegion> regions, BufferedImage image) {
        for (RegionValidator.PixelRegion region : regions) {
            if (region.isCoordinateRescued()) {
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

    private LocateRoi locateRoi(String pageId, PatternGroup group, BufferedImage image) {
        if (group == null || group.locate_window == null) {
            return null;
        }
        double x1 = Math.max(0D, group.locate_window.x1 - 0.02D);
        double y1 = Math.max(0D, group.locate_window.y1 - 0.02D);
        double x2 = Math.min(1D, group.locate_window.x2 + 0.02D);
        double y2 = Math.min(1D, group.locate_window.y2 + 0.02D);
        // 粗窗口只确定“靠哪一边”。为了让模型能测到真正的正文边界，沿页码基线的轴必须
        // 看完整条页面；朝正文方向固定再扩 15%，但仍远小于整页，不会退化为整图定位。
        if ("bottom".equals(group.edge)) {
            x1 = 0D;
            x2 = 1D;
            y1 = Math.max(0D, y1 - 0.15D);
        } else if ("top".equals(group.edge)) {
            x1 = 0D;
            x2 = 1D;
            y2 = Math.min(1D, y2 + 0.15D);
        } else if ("left".equals(group.edge)) {
            y1 = 0D;
            y2 = 1D;
            x2 = Math.min(1D, x2 + 0.15D);
        } else if ("right".equals(group.edge)) {
            y1 = 0D;
            y2 = 1D;
            x1 = Math.max(0D, x1 - 0.15D);
        }
        if (x1 >= x2 || y1 >= y2) {
            return null;
        }
        int left = (int) Math.floor(x1 * image.getWidth());
        int top = (int) Math.floor(y1 * image.getHeight());
        int right = (int) Math.ceil(x2 * image.getWidth());
        int bottom = (int) Math.ceil(y2 * image.getHeight());
        RoiTransform transform = new RoiTransform(left, top, Math.max(1, right - left), Math.max(1, bottom - top),
                image.getWidth(), image.getHeight());
        return new LocateRoi(transform, new VlmClient.RoiImage(pageId, "locate", crop(image, transform)));
    }

    private LocateResponse mapLocateToFull(LocateResponse local, RoiTransform transform, int width, int height) {
        LocateResponse full = new LocateResponse();
        full.page_id = local.page_id;
        full.status = local.status;
        full.evidence = local.evidence + "; coordinates_mapped_from_locate_roi";
        full.nearest_body_boundary = mapBoundary(local.nearest_body_boundary, transform, width, height);
        for (EraseRegion region : local.regions) {
            EraseRegion mapped = copyRegion(region);
            RoiTransform.PixelRect rect = transform.localRectToFullPixels(region.x1, region.y1, region.x2, region.y2);
            // ROI 相对坐标经模型量化再映射回原图时会损失少量边缘笔画。仅在这条局部定位
            // 主链路统一补 4px，并交由既有正文空白带、掩码边界与审计门禁重新证明安全。
            int left = Math.max(0, rect.getX() - ROI_MAPPING_GUARD_PIXELS);
            int top = Math.max(0, rect.getY() - ROI_MAPPING_GUARD_PIXELS);
            int right = Math.min(width, rect.getX() + rect.getWidth() + ROI_MAPPING_GUARD_PIXELS);
            int bottom = Math.min(height, rect.getY() + rect.getHeight() + ROI_MAPPING_GUARD_PIXELS);
            mapped.x1 = left / (double) width;
            mapped.y1 = top / (double) height;
            mapped.x2 = right / (double) width;
            mapped.y2 = bottom / (double) height;
            full.regions.add(mapped);
        }
        return full;
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

    /** 绑定局部图与其到标准化整页坐标的变换，禁止把 ROI 坐标直接用于原图。 */
    private static final class EdgeRoi {
        final RoiTransform transform;
        final VlmClient.RoiImage image;

        EdgeRoi(RoiTransform transform, VlmClient.RoiImage image) {
            this.transform = transform;
            this.image = image;
        }
    }

    private static final class LocateRoi {
        final RoiTransform transform;
        final VlmClient.RoiImage image;

        LocateRoi(RoiTransform transform, VlmClient.RoiImage image) {
            this.transform = transform;
            this.image = image;
        }
    }

    private static final class Refinement {
        final LocateResponse locate;
        final RegionValidator.ValidationResult validation;

        Refinement(LocateResponse locate, RegionValidator.ValidationResult validation) {
            this.locate = locate;
            this.validation = validation;
        }
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
