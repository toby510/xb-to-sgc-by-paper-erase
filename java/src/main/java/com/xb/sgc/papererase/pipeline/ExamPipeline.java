package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.erase.InkMaskEraser;
import com.xb.sgc.papererase.image.OrientationNormalizer;
import com.xb.sgc.papererase.image.RoiTransform;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.LocalRegion;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.RelocateResponse;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageTransforms;
import com.xb.sgc.papererase.safety.RegionValidator;
import com.xb.sgc.papererase.safety.RiskGate;
import com.xb.sgc.papererase.vlm.ResponseParser;
import com.xb.sgc.papererase.vlm.VlmClient;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * <p>任何一页出现协议、网络、坐标或像素门禁异常，均只降级该页为人工审核。正文风险
 * 始终失败关闭，流水线不以整页重跑改变模型已经给出的保守结论。</p>
 */
public final class ExamPipeline {
    /**
     * audit 局部图的放大倍率。原始 ROI 尺寸下，约 5 像素的框边切口低于模型分辨力；
     * 3 倍放大后同一批样本的识别率由 0/5 提升到 5/5，且阴性样本无误报。
     */
    private static final int AUDIT_ROI_SCALE = 3;

    private static final double MIN_DIRECTION_CONFIDENCE = 0.90;
    private static final int ROI_MAPPING_GUARD_PIXELS = 4;
    /** ROI Relocate 在候选框四周保留的上下文边距（像素）；只提供上下文，不做放大。 */
    private static final int RELOCATE_ROI_MARGIN_PIXELS = 24;
    private final VlmClient vlm;

    public ExamPipeline(VlmClient vlm) {
        if (vlm == null) {
            throw new IllegalArgumentException("vlm is required");
        }
        this.vlm = vlm;
    }

    /**
     * 试卷级总入口：逐页执行“方向定位→像素门禁→风险复核→擦除→审计”。
     *
     * <p>这里故意把页面循环包在单页异常隔离边界内：某页模型超时、坐标非法或审计失败时，
     * 只把该页交给人工，不让异常页影响同卷其他页的原图和产物。</p>
     *
     * @see #processPage(ExamInput, PageInput, BufferedImage, RunContext)
     */
    public ExamOutcome process(ExamInput exam, RunContext context) {
        // 0. 输入与原图装载：所有后续坐标、像素门禁和产物都以这份原图为唯一基准。
        context = context == null ? new RunContext() : context;
        long examStartedAt = System.currentTimeMillis();
        context.event(PipelineStage.EXAM, exam.getExamId(), null, EventStatus.STARTED, "page_count=" + exam.getPages().size(), 0);
        Map<String, BufferedImage> originals = readOriginals(exam);
        context.event(PipelineStage.IMAGE_LOAD, exam.getExamId(), null, EventStatus.COMPLETED, "page_count=" + originals.size(), 0);
        List<PageOutcome> outcomes = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            // 2. locate 及单页安全流水线：每页独立处理，任何一页失败关闭都不影响其他页。
            BufferedImage original = originals.get(page.getPageId());
            long pageStartedAt = System.currentTimeMillis();
            context.event(PipelineStage.PAGE, exam.getExamId(), page.getPageId(), EventStatus.STARTED, null, 0);
            PageOutcome pageOutcome;
            try {
                // 页面异常必须隔离：不能因为一张异常图让后续页绕过审计或丢失产物。
                pageOutcome = published(page, original, processPage(exam, page, original, context));
            } catch (RuntimeException e) {
                // 记录可行动的短错误，避免吞掉协议失配后反复调用模型猜根因。
                context.event(PipelineStage.PAGE_ERROR, exam.getExamId(), page.getPageId(), EventStatus.FAILED, shortError(e), 0);
                pageOutcome = manual(page, original, original, transform(original, original, 0),
                        "page_processing_error", null);
            }
            outcomes.add(pageOutcome);
            context.event(PipelineStage.PAGE, exam.getExamId(), page.getPageId(), pageOutcome.getStatus(), pageOutcome.getReason(),
                    System.currentTimeMillis() - pageStartedAt);
        }
        ExamOutcome outcome = new ExamOutcome(exam.getExamId(), "processed", "ok", outcomes);
        context.event(PipelineStage.EXAM, exam.getExamId(), null, outcome.getStatus(), outcome.getReason(), System.currentTimeMillis() - examStartedAt);
        return outcome;
    }

    /**
     * 对同一张整页 locate 请求最多补发一次，仅处理模型响应的瞬态协议/传输失败。
     *
     * <p>首个可解析响应无论其业务结论为何都直接使用，绝不通过整页重试改变
     * {@code safe_to_erase}/{@code no_pagenum}/{@code manual_review} 的语义决定；因此该重试
     * 不会放宽正文保护，也不会把保守结论改为擦除结论。</p>
     */
    private LocateResponse locateWithProtocolRetry(ExamInput exam, PageInput page, VlmClient.PageImage pageImage,
                                                    RunContext context, String requestPhase) {
        try {
            return vlm.locate(pageImage);
        } catch (RuntimeException firstFailure) {
            context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), EventStatus.RETRY.wireValue(),
                    "same_page_after_transport_or_protocol_failure; phase=" + requestPhase, 0);
            return vlm.locate(pageImage, VlmClient.locateProtocolRepairInstruction());
        }
    }

    private static String shortError(RuntimeException error) {
        String message = error.getMessage();
        if (error instanceof ResponseParser.ParseException) {
            String rawSummary = ((ResponseParser.ParseException) error).getRawSummary();
            if (rawSummary != null && !rawSummary.trim().isEmpty()) {
                message = (message == null ? "" : message + "; ") + "raw=" + rawSummary;
            }
        }
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        String compact = message.replace('\n', ' ').replace('\r', ' ').trim();
        return error.getClass().getSimpleName() + ": " + compact.substring(0, Math.min(300, compact.length()));
    }

    private PageOutcome published(PageInput page, BufferedImage original, PageOutcome outcome) {
        if ("safe_to_erase".equals(outcome.getStatus()) || "no_pagenum".equals(outcome.getStatus())
                || "manual_review".equals(outcome.getStatus())) {
            return outcome;
        }
        return manual(page, original, outcome.getNormalized(), outcome.getTransforms(),
                "internal_state_" + outcome.getStatus(), outcome.getLocate());
    }

    /**
     * 单页安全流水线。核心原则是“模型负责语义，Java 负责几何和像素证据”：模型说这是页码
     * 以后，仍必须由 RegionValidator 证明它处在边缘、远离正文且框边不粘正文，之后才允许
     * InkMaskEraser 写图；任何证明链断裂都返回 manual_review 并保留原图。
     */
    private PageOutcome processPage(ExamInput exam, PageInput page, BufferedImage original, RunContext context) {
        // 2.1 空白页短路：没有任何可见墨迹时直接判定无页码，不调用后续定位和擦除。
        // 纯白占位页没有可擦页码，也没有正文；在方向门禁前用保守像素证据直接归类，
        // 避免模型对无内容页面的方向置信度不足造成无意义的人工审核。
        if (isVisiblyBlankPage(original)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "blank_page", original, original, original, transform(original, original, 0), Collections.<EraseRegion>emptyList(), null, null);
        }
        VlmClient.PageImage originalPageImage = new VlmClient.PageImage(page.getPageId(), original);
        LocateResponse firstLocate;
        long firstLocateStartedAt = System.currentTimeMillis();
        context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), EventStatus.STARTED, null, 0);
        try {
            // 2.2 首次 locate 同时判断阅读方向。正常语义结论绝不整页重采样；仅当响应无法
            // 解析为既定 JSON 协议时，原样重发一次相同请求，消除瞬态的模型格式波动。
            firstLocate = locateWithProtocolRetry(exam, page, originalPageImage, context, "initial");
            context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), EventStatus.COMPLETED,
                    firstLocate.status, System.currentTimeMillis() - firstLocateStartedAt);
        } catch (RuntimeException failure) {
            context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), "failed", shortError(failure), 0);
            return manual(page, original, original, transform(original, original, 0), "locate_error", null);
        }
        if (firstLocate.direction_confidence < MIN_DIRECTION_CONFIDENCE) {
            return manual(page, original, original, transform(original, original, firstLocate.reading_rotation),
                    "low_direction_confidence", firstLocate);
        }

        int readingRotation = firstLocate.reading_rotation;
        OrientationNormalizer.NormalizedImage normalized = OrientationNormalizer.normalize(original, readingRotation);
        BufferedImage normalizedImage = normalized.getImage();
        context.event(PipelineStage.NORMALIZE, exam.getExamId(), page.getPageId(), "completed", "rotation=" + readingRotation, 0);
        PageTransforms transforms = new PageTransforms(normalized.getOriginalWidth(), normalized.getOriginalHeight(),
                normalized.getNormalizedWidth(), normalized.getNormalizedHeight(), normalized.getReadingRotation());
        VlmClient.PageImage pageImage = new VlmClient.PageImage(page.getPageId(), normalizedImage);
        LocateResponse locate = firstLocate;
        if (readingRotation != 0) {
            long normalizedLocateStartedAt = System.currentTimeMillis();
            context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), EventStatus.STARTED, "normalized", 0);
            try {
                // 未旋正图上的坐标一律废弃；旋正后重新定位，第二次响应必须确认当前图方向为 0。
                locate = locateWithProtocolRetry(exam, page, pageImage, context, "normalized");
                context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), EventStatus.COMPLETED,
                        locate.status, System.currentTimeMillis() - normalizedLocateStartedAt);
            } catch (RuntimeException failure) {
                context.event(PipelineStage.LOCATE, exam.getExamId(), page.getPageId(), "failed", shortError(failure), 0);
                return manual(page, original, normalizedImage, transforms, "locate_error", null);
            }
            if (locate.direction_confidence < MIN_DIRECTION_CONFIDENCE) {
                return manual(page, original, normalizedImage, transforms, "low_direction_confidence", locate);
            }
            if (locate.reading_rotation != 0) {
                return manual(page, original, normalizedImage, transforms, "orientation_normalization_failed", locate);
            }
        }
        if ("manual_review".equals(locate.status)) {
            return manual(page, original, normalizedImage, transforms, "locate_manual_review", locate);
        }
        if ("no_pagenum".equals(locate.status) || locate.regions.isEmpty()) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "locate_no_pagenum", original, normalizedImage, normalizedImage, transforms, Collections.<EraseRegion>emptyList(), locate, null);
        }
        // 首次整页 locate 的原始语义框是证据基线；trim/refine 后不得覆盖它。
        List<EraseRegion> initialLocateRegions = copyRegions(locate.regions);

        // 3. Java 正文保护门禁：VLM 坐标只是候选，通过确定性像素证据前绝不写图。
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(locate.page_id, locate.status, locate.regions),
                normalizedImage);
        boolean refinedByVlm = false;
        if (!validation.isAccepted()) {
            // 3.1 首次校验失败：只允许在原候选框内裁掉已证明为空白的 padding。
            context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "rejected", validation.getReasons().toString(), 0);
            // 模型已完成“这是哪一条非正文页码行”的语义判断。若它只是把候选框朝正文侧
            // 多含了一段空白/背透，先在原框内裁掉经像素证明为空白的 padding；不找新文字、
            // 不扩框、不改变语义。这样不会让局部模型的错误重测覆盖正确的整页识别。
            Refinement trimmed = trimOriginalBodyPadding(locate, normalizedImage);
            if (trimmed != null) {
                locate = trimmed.locate;
                validation = trimmed.validation;
                context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "accepted", "original_candidate_blank_padding_trimmed", 0);
            }
        }
        if (!validation.isAccepted()) {
            // 3.2 局部坐标精修：仅对可由高清 ROI 重新定位的轻微几何风险发起二检。
            // 模型已给出明确页码语义但像素框/正文边界未过门禁时，先让模型在完整边缘高清图
            // 中重测；绝不由 Java 放宽规则或自行移动候选框。
            Refinement refinement = shouldRefineRejected(validation) ?
                    relocateRejectedRegions(exam, page, normalizedImage, locate, pageImage, context,
                            allowsConflictingBoundaryReplacementAfterRefine(validation)) : null;
            if (refinement == null) {
                // 诊断 audit：门禁拦下的框先白填试擦（仅内存），让 VLM 复核该框是否真伤正文。
                // 人工审核由此可区分"伤正文"（业务保持原图）与"门禁过敏"（框本身可用）。
                AuditResponse gateAudit = diagnosticAudit(exam, page, normalizedImage, locate, pageImage, context);
                if (gateAudit != null && gateAudit.original_target_is_non_body
                        && !gateAudit.body_changed && gateAudit.target_removed) {
                    // 诊断 audit 确认框不伤正文：给一次坐标精修重试，仍由像素门禁决定最终是否放行。
                    refinement = relocateRejectedRegions(exam, page, normalizedImage, locate, pageImage, context,
                            allowsConflictingBoundaryReplacementAfterRefine(validation));
                    if (refinement != null) {
                        context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "accepted",
                                "coordinate_refined_after_gate_audit", 0);
                    }
                }
                if (refinement == null) {
                    // 拒绝原因必须跟随页面结论一起落盘：只有“validation_rejected”无法区分
                    // 是空白带不足、安全带有墨还是墨迹贴框，事后无法定位到具体门禁。
                    return new PageOutcome(page.getPageId(), "manual_review",
                            "validation_rejected: " + validation.getReasons() + gateAuditSuffix(gateAudit),
                            original, normalizedImage, normalizedImage, transforms, locate.regions, locate, gateAudit);
                }
            }
            locate = refinement.locate;
            validation = refinement.validation;
            refinedByVlm = true;
            context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "accepted", "coordinate_refined_after_rejection", 0);
        }
        context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "accepted", "region_count=" + validation.getRegions().size(), 0);
        // 首次定位已经通过空间门禁、但候选框本身没有任何可擦墨迹时，不能让 Java 沿边缘猜
        // 测页码。改由模型查看同一边缘带的高清图，重新给出局部坐标和正文边界；没有明确
        // 坐标就关闭失败。这针对的是“语义识别对、归一化坐标偏移”的模型已知失效模式。
        if (!refinedByVlm && hasEmptyTargetBox(normalizedImage, validation.getRegions())) {
            // 3.3 空框救援：整页语义正确但框内无墨时，按同一边缘逐框局部重定位。
            /*
             * 局部模型把“空框”校回真实页码后，整页模型的全局正文边界可能恰好落在别的栏位，
             * 进而与精框产生表面冲突。这里允许局部重定位内既有的 16px 投影空白带规则
             * 处理该冲突；它仍要求同边、投影重叠、框内有墨和完整空白带，不能放宽普通定位。
             */
            Refinement refinement = relocateRejectedRegions(exam, page, normalizedImage, locate, pageImage, context, true);
            if (refinement == null) {
                return manual(page, original, normalizedImage, transforms, "coordinate_refine_denied", locate);
            }
            locate = refinement.locate;
            validation = refinement.validation;
            refinedByVlm = true;
            context.event(PipelineStage.VALIDATION, exam.getExamId(), page.getPageId(), "accepted", "coordinate_refined", 0);
        }
        RiskGate.PageContext riskContext = RiskGate.PageContext.stable(page.getPageId())
                .withReadingRotation(readingRotation)
                .withPageSequenceIncomplete(exam.isPageSequenceIncomplete());
        boolean hasCoordinateRescue = hasCoordinateRescue(validation.getRegions(), normalizedImage);
        boolean requiresRelocation = RiskGate.requiresRelocation(riskContext, validation) || hasOnLineRegion(locate.regions)
                || hasCoordinateRescue;
        boolean localRelocationConfirmed = false;
        if (requiresRelocation) {
            RelocationResult relocated = relocateAndMap(exam, page, original, normalizedImage, transforms, locate, pageImage,
                    context);
            if (relocated.denied != null) {
                return relocated.denied;
            }
            locate = relocated.locate;
            validation = relocated.validation;
            localRelocationConfirmed = true;
        }
        return eraseAndAudit(exam, page, original, normalizedImage, transforms, locate, pageImage,
                validation.getRegions(), false, initialLocateRegions,
                localRelocationConfirmed, refinedByVlm, context);
    }

    /** 只接受真正无可见墨迹的页面；任何 RGB 通道低于 245 的像素都会保持原有人工门禁。 */
    private static boolean isVisiblyBlankPage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >>> 16) & 0xff) < 245 || ((rgb >>> 8) & 0xff) < 245 || (rgb & 0xff) < 245) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 判断候选区域中是否存在“页码与正文/答题线同一行”的区域。
     *
     * <p>{@code on_line} 不是网络在线状态，而是 VLM 对版式关系的判断：
     * 页码或同行非正文元数据与正文文字、答题横线、表格线等处在同一条视觉基线/行带内。
     * 这种情况下，矩形擦除框更容易沿同一行侵入正文，即使首次像素校验通过，也应追加局部
     * ROI Relocate 对坐标和局部正文边界进行高清重测。</p>
     *
     * <p>方法只要发现一个区域为 {@code on_line=true} 就返回 true；空列表或所有区域均为
     * false 时返回 false。它只负责触发风险复核，不直接判定擦除是否安全。</p>
     *
     * @param regions locate 返回并通过初步校验的候选擦除区域
     * @return 是否存在与正文或版式线条同一行的候选区域
     */
    private boolean hasOnLineRegion(List<EraseRegion> regions) {
        // 逐个检查候选框，因为同一页面可能同时存在多个页码/同行元数据区域。
        for (EraseRegion region : regions) {
            // on_line=true 表示该区域与正文或答题线共用同一视觉行带，属于坐标贴近正文风险。
            if (region.on_line) {
                return true;
            }
        }
        // 所有候选框都不与正文/答题线同一行，可以不因该项单独触发局部重定位。
        return false;
    }

    private Refinement trimOriginalBodyPadding(LocateResponse source, BufferedImage image) {
        LocateResponse trimmed = copyLocateWithoutRegions(source);
        for (EraseRegion region : source.regions) {
            trimmed.regions.add(RegionValidator.trimBodyFacingBlankPadding(copyRegion(region), image));
        }
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(trimmed.page_id, trimmed.status, trimmed.regions), image);
        return validation.isAccepted() ? new Refinement(trimmed, validation) : null;
    }

    /** 只有页边语义候选的细小几何偏差值得模型二检；明显非法坐标不额外消耗调用。 */
    private boolean shouldRefineRejected(RegionValidator.ValidationResult validation) {
        for (String reason : validation.getReasons()) {
            if ("ink mask touches candidate box".equals(reason)
                    || "body blank gap is insufficient".equals(reason)
                    || "body blank gap contains ink".equals(reason)) {
                return true;
            }
        }
        return false;
    }

    /** 多区域全部仅正文空白带冲突时，允许沿用原有像素替换路径；任何其他原因仍失败关闭。 */
    boolean isOnlyBodyGapConflict(RegionValidator.ValidationResult validation) {
        if (validation == null || validation.getReasons().isEmpty()) {
            return false;
        }
        for (String reason : validation.getReasons()) {
            if (!"body blank gap is insufficient".equals(reason)
                    && !"body blank gap contains ink".equals(reason)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 局部精修后的正文边界替换资格：初始框可因笔画贴边而触发 mask-touch，但不能夹带
     * 坐标非法、非边缘或其它正文风险。实际替换仍要求精修后只剩正文空白带冲突，并由
     * RegionValidator 继续证明同边、文字锚点、候选墨迹和 8px 安全带。
     */
    boolean allowsConflictingBoundaryReplacementAfterRefine(RegionValidator.ValidationResult validation) {
        if (validation == null || validation.getReasons().isEmpty()) {
            return false;
        }
        for (String reason : validation.getReasons()) {
            if (!"body blank gap is insufficient".equals(reason)
                    && !"body blank gap contains ink".equals(reason)
                    && !"ink mask touches candidate box".equals(reason)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEmptyTargetBox(BufferedImage image, List<RegionValidator.PixelRegion> regions) {
        for (RegionValidator.PixelRegion region : regions) {
            InkMaskEraser.EraseOutcome probe = InkMaskEraser.erase(image, region);
            if ("no target ink found".equals(probe.getReason())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断已通过几何门禁的像素框内是否真的存在可擦目标墨迹。
     *
     * <p>用于决定 ROI Relocate 返回的精框能否替换原候选框：精框只通过几何校验还不够，若它落在
     * 空白处，替换后会在擦除阶段以 {@code no target ink found} 失败，把一个原本可擦的页面
     * 升级成人工审核。这里用最宽松的“彩色也算目标”口径探测，只有连彩色像素都算目标时仍然
     * 为空，才认定该框没有可擦墨迹，因此不会误伤彩色页码。</p>
     *
     * @param image 旋正后的整图
     * @param regions 已通过几何门禁的像素框
     * @return 任一框内存在可擦墨迹时为 true
     */
    private boolean hasErasableTargetInk(BufferedImage image,
                                         List<RegionValidator.PixelRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return false;
        }
        for (RegionValidator.PixelRegion region : regions) {
            String reason = InkMaskEraser.erase(image, region).getReason();
            if (!"no target ink found".equals(reason)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重新locate以实现几何坐标精修
     *
     * @param exam
     * @param page
     * @param image
     * @param group
     * @param locate
     * @param pageImage
     * @param context
     * @param allowConflictingBoundaryReplacement
     * @return
     */
    private Refinement relocateRejectedRegions(ExamInput exam, PageInput page, BufferedImage image, LocateResponse locate,
                                         VlmClient.PageImage pageImage, RunContext context,
                                         boolean allowConflictingBoundaryReplacement) {
        if (locate.regions.size() == 1) {
            //todo me:单region走20%边缘带精修（20%底部+高对比+放大3倍）
            EraseRegion originalRegion = locate.regions.get(0);
            // 单 region 需要坐标精修时，原候选既可能为空框，也可能只是几何位置不安全。
            // 为避免继续受原候选坐标偏差影响，直接使用同一物理边缘的完整 ROI 重新定位。
            // VLM只负责重新测量坐标，映射守卫和 RegionValidator 仍决定最终是否采用。
            EdgeRoi edgeRoi = fullEdgeRoi(page.getPageId(), originalRegion, image);
            if (edgeRoi == null) {
                return null;
            }
            Refinement refined = relocateOneRegion(exam, page, image, locate, pageImage, originalRegion, edgeRoi, context,
                    "coordinate_refine", allowConflictingBoundaryReplacement);
            return refined != null && !hasEmptyTargetBox(image, refined.validation.getRegions()) ? refined : null;
        }

        /*
         * 双页/双栏扫描会在同一张物理图中出现多个独立页码。它们不能共用一个局部 ROI，
         * 但每个框都可以独立经过同一模型精定位与像素门禁。只要其中任意一框无法证明安全，
         * 整页仍失败关闭；绝不因另一个框通过而擦除它。
         */
        LocateResponse combined = copyLocateWithoutRegions(locate);
        List<RegionValidator.PixelRegion> approved = new ArrayList<RegionValidator.PixelRegion>();
        for (EraseRegion originalRegion : locate.regions) {
            LocateResponse single = copyLocateWithoutRegions(locate);
            single.regions.add(originalRegion);
            RegionValidator.ValidationResult singleValidation = RegionValidator.validate(
                    new RegionValidator.PageLocateResult(single.page_id, single.status, single.regions), image);
            // 多框页面不能只因为“几何安全”就跳过精修：若该框内没有任何可擦墨迹，说明
            // 模型把文字语义识别对了、坐标却落到了相邻空白/分隔符。必须让高清边缘 ROI
            // 按页码字面量重测，不能把空框原样交给擦除器并在最后才失败。
            boolean emptyTargetBox = singleValidation.isAccepted()
                    && hasEmptyTargetBox(image, singleValidation.getRegions());
            if (singleValidation.isAccepted() && !emptyTargetBox) {
                combined.regions.add(originalRegion);
                approved.addAll(singleValidation.getRegions());
                continue;
            }
            if (!singleValidation.isAccepted() && !shouldRefineRejected(singleValidation)) {
                return null;
            }

            //todo me:以后看代码时就记：
            //candidateCenteredROI 的前提是：“我大致相信第一次坐标位置，只想高清重新量一下”。
            //fullEdgeROI 的前提是：“我已经不能相信第一次坐标中心了，但仍相信它属于这个物理边缘和这个语义目标”。

            // 每个 region 独立决定精修视野：仅已证明为空框的候选使用完整边缘带；其它
            // 验证拒绝情形仍保留原候选中心 ROI，避免扩大既有精修的可见范围。
            BodyBoundary regionBoundary = originalRegion.nearest_body_boundary;
            EdgeRoi edgeRoi = emptyTargetBox
                    //todo me:已通过校验但无墨，即空框则20%边缘带精修+高对比+放大3倍）
                    ? fullEdgeRoi(page.getPageId(), originalRegion, image)
                    //todo me:【候选框为中心四周扩2*min(region宽/高)】同时包含正文坐标+高对比度/扩大3倍
                    : candidateCenteredRoi(page.getPageId(), originalRegion, regionBoundary, image);
            if (edgeRoi == null) {
                return null;
            }
            Refinement refined = relocateOneRegion(exam, page, image, single, pageImage, originalRegion, edgeRoi, context,
                    "coordinate_refine", emptyTargetBox ? allowConflictingBoundaryReplacement
                            : allowsConflictingBoundaryReplacementAfterRefine(singleValidation));
            // 每个 region 只做一次 ROI 重定位：ROI 类型已按失败事实选好（空框走完整边缘带，
            // 其它几何风险走候选中心），失败即整页人工审核；不再级联第二种 ROI 重复试错。
            if (refined == null || hasEmptyTargetBox(image, refined.validation.getRegions())) {
                return null;
            }
            combined.regions.add(refined.locate.regions.get(0));
            approved.addAll(refined.validation.getRegions());
            combined.evidence = combined.evidence + "; " + refined.locate.evidence;
        }
        return new Refinement(combined, RegionValidator.ValidationResult.acceptedResult(approved));
    }

    private LocateResponse copyLocateWithoutRegions(LocateResponse source) {
        LocateResponse copy = new LocateResponse();
        copy.page_id = source.page_id;
        copy.reading_rotation = source.reading_rotation;
        copy.direction_confidence = source.direction_confidence;
        copy.status = source.status;
        copy.evidence = source.evidence;
        return copy;
    }

    /**
     * 审计仅发现残字时，对每个已批准候选分别做一次放大精定位；正文异常绝不进入本分支。
     * 双页扫描的两个页码必须独立映射和验证，任一精修失败仍整页失败关闭。
     */
    private Refinement relocateAfterAuditResidual(ExamInput exam, PageInput page, BufferedImage image,
                                             LocateResponse locate, VlmClient.PageImage pageImage, RunContext context) {
        if (locate.regions.isEmpty()) {
            return null;
        }
        if (locate.regions.size() == 1) {
            EraseRegion originalRegion = locate.regions.get(0);
            EdgeRoi roi = candidateCenteredRoi(page.getPageId(), originalRegion,
                    originalRegion.nearest_body_boundary, image);
            Refinement refined = roi == null ? null : relocateOneRegion(exam, page, image, locate, pageImage, originalRegion, roi, context,
                    "audit_coordinate_refine", false);
            return refined != null && !hasEmptyTargetBox(image, refined.validation.getRegions()) ? refined : null;
        }

        // 双页/双栏扫描的局部 ROI 可能同时看见多个页码，模型也可能把它们合并返回为一个
        // 页脚带。不能再假定“返回框数量和原框数量相等”，否则某一个页码残留会让整页失败。
        // 逐个原候选请求，并以可见 page_number_text 作为语义锚点挑回对应框；任何一个锚点
        // 无法唯一对应仍失败关闭，避免以位置顺序猜测而误擦正文。
        LocateResponse combined = copyLocateWithoutRegions(locate);
        List<RegionValidator.PixelRegion> approved = new ArrayList<RegionValidator.PixelRegion>();
        for (EraseRegion originalRegion : locate.regions) {
            LocateResponse single = copyLocateWithoutRegions(locate);
            single.regions.add(originalRegion);
            EdgeRoi roi = candidateCenteredRoi(page.getPageId(), originalRegion,
                    originalRegion.nearest_body_boundary, image);
            if (roi == null) {
                return null;
            }
            Refinement refined = relocateOneRegion(exam, page, image, single, pageImage,
                    originalRegion, roi, context, "audit_coordinate_refine", false);
            // 审计残留只允许一次候选中心 ROI 重定位；失败即保持人工审核，不再级联完整边缘带。
            if (refined == null || hasEmptyTargetBox(image, refined.validation.getRegions())) {
                return null;
            }
            combined.regions.add(refined.locate.regions.get(0));
            approved.addAll(refined.validation.getRegions());
            combined.evidence = combined.evidence + "; " + refined.locate.evidence;
        }
        return new Refinement(combined, RegionValidator.ValidationResult.acceptedResult(approved));
    }

    private Refinement relocateOneRegion(ExamInput exam, PageInput page, BufferedImage image, LocateResponse locate,
                                   VlmClient.PageImage pageImage, EraseRegion originalRegion, EdgeRoi edgeRoi,
                                   RunContext context, String stage, boolean allowConflictingBoundaryReplacement) {
        long startedAt = System.currentTimeMillis();
        context.event(stage, exam.getExamId(), page.getPageId(), "started", originalRegion.region_id, 0);
        // 不污染首次定位结果，只用请求副本把“必须返回 ROI 坐标”这个意图显式传给模型。
        EraseRegion requestRegion = copyRegion(originalRegion);
        requestRegion.safety_margin = "coordinate_refinement_requested";
        RelocateResponse relocated;
        try {
            relocated = vlm.relocateCoordinateRefinement(pageImage, requestRegion, edgeRoi.image);
        } catch (RuntimeException firstFailure) {
            // 仅对完全相同的局部请求重试一次传输/协议失败；已得到的模型语义结论不会重试。
            context.event(stage, exam.getExamId(), page.getPageId(), EventStatus.RETRY.wireValue(),
                    "same_roi_after_transport_or_protocol_failure", 0);
            try {
                relocated = vlm.relocateCoordinateRefinement(pageImage, requestRegion, edgeRoi.image);
            } catch (RuntimeException secondFailure) {
                context.event(stage, exam.getExamId(), page.getPageId(), EventStatus.FAILED.wireValue(),
                        shortError(secondFailure), 0);
                return null;
            }
        }
        context.event(stage, exam.getExamId(), page.getPageId(), "completed", "target_found=" + relocated.target_found + "; "
                        + shortText(relocated.evidence),
                System.currentTimeMillis() - startedAt);
        // 局部精修的候选框与正文边界必须来自同一张 ROI：整页边界可能落在双页扫描的另一栏，
        // 不能再用它否决已经在当前 ROI 内重新测量的目标行。局部边界仍会回映射到原图并经过
        // 同一套 8px 连续空白带与实质墨迹门禁；ROI 未给出对应方向边界时，只允许既有
        // 像素空白带推断尝试证明安全，仍无法证明就失败关闭，不回退到其他 region 的边界。
        if (!relocated.target_found || relocated.refined_region == null) {
            context.event(stage, exam.getExamId(), page.getPageId(), "denied",
                    "relocate_target_not_found", 0);
            return null;
        }
        return validateMappedSingleRefinement(locate, originalRegion, relocated.refined_region, relocated.nearest_body_boundary,
                edgeRoi, image,
                allowConflictingBoundaryReplacement, context, stage, exam, page, relocated);
    }

    private Refinement validateMappedSingleRefinement(LocateResponse locate, EraseRegion originalRegion,
                                                       LocalRegion localRegion, BodyBoundary localBoundary,
                                                       EdgeRoi edgeRoi, BufferedImage image,
                                                       boolean allowConflictingBoundaryReplacement, RunContext context,
                                                       String stage, ExamInput exam, PageInput page,
                                                       RelocateResponse relocated) {
        EraseRegion refined = mapRefinedRegion(originalRegion, localRegion, edgeRoi, image);
        // 局部模型为抗锯齿/可读性通常会在朝正文一侧多给少量空白内边距。正文安全距离
        // 必须从实际目标墨迹计算：这里只删除获批框内已证明为空白的 padding，不扩框、不
        // 搜索新文字，再交给同一套像素门禁复核，因而不会降低正文保护。
        if (!"local_vlm_coordinate_refined".equals(refined.safety_margin)) {
            refined = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        }
        LocateResponse refinedLocate = new LocateResponse();
        refinedLocate.page_id = locate.page_id;
        refinedLocate.reading_rotation = 0;
        refinedLocate.direction_confidence = locate.direction_confidence;
        refinedLocate.status = locate.status;
        refinedLocate.regions.add(refined);
        BodyBoundary mappedBoundary = localBoundary == null ? null
                : mapBoundary(localBoundary, edgeRoi.transform, image.getWidth(), image.getHeight());
        refined.nearest_body_boundary = hasDirectionalBoundaryForRegion(refined, mappedBoundary)
                ? mappedBoundary : null;
        refinedLocate.evidence = locate.evidence + "; coordinate_relocated=" + relocated.evidence;
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(refinedLocate.page_id, refinedLocate.status, refinedLocate.regions), image);
        if (!validation.isAccepted() && isOnlyBodyGapConflict(validation)) {
            EraseRegion trimmed = "local_vlm_coordinate_refined".equals(refined.safety_margin)
                    ? refined : RegionValidator.trimBodyFacingBlankPadding(refined, image);
            BodyBoundary effectiveBoundary = RegionValidator.replaceConflictingBodyBoundary(originalRegion, trimmed,
                    refined.nearest_body_boundary, image);
            if (effectiveBoundary != null) {
                refined = trimmed;
                refined.nearest_body_boundary = effectiveBoundary;
                refinedLocate.regions.clear();
                refinedLocate.regions.add(refined);
                refinedLocate.evidence = refinedLocate.evidence + "; conflicting_boundary_replaced=" + effectiveBoundary.basis;
                validation = RegionValidator.validate(
                        new RegionValidator.PageLocateResult(refinedLocate.page_id, refinedLocate.status, refinedLocate.regions), image);
            }
        }
        context.event(stage, exam.getExamId(), page.getPageId(),
                validation.isAccepted() ? "mapped" : "rejected",
                "region=" + refined.x1 + "," + refined.y1 + "," + refined.x2 + "," + refined.y2
                        + "; body=" + boundaryEvidence(refined.nearest_body_boundary)
                        + "; reasons=" + validation.getReasons(), 0);
        return validation.isAccepted() ? new Refinement(refinedLocate, validation) : null;
    }

    private EraseRegion mapRefinedRegion(EraseRegion originalRegion, LocalRegion localRegion,
                                         EdgeRoi edgeRoi, BufferedImage image) {
        EraseRegion refined = copyRegion(originalRegion);
        RoiTransform.PixelRect rect = edgeRoi.transform.localRectToFullPixels(
                localRegion.x1, localRegion.y1, localRegion.x2, localRegion.y2);
        applyRoiMappingGuard(refined, rect, image.getWidth(), image.getHeight());
        refined.safety_margin = "local_vlm_coordinate_refined";
        return refined;
    }

    private String boundaryEvidence(BodyBoundary boundary) {
        return boundary == null ? "null" : boundary.x + "," + boundary.y;
    }

    /** 底/顶页脚只接受 y 边界，左/右页边只接受 x 边界；错误轴或空边界不能参与当前 region 校验。 */
    private boolean hasDirectionalBoundaryForRegion(EraseRegion region, BodyBoundary boundary) {
        if (region == null || boundary == null) {
            return false;
        }
        if (region.y2 <= 0.20 || region.y1 >= 0.80) {
            return boundary.y != null;
        }
        if (region.x2 <= 0.20 || region.x1 >= 0.80) {
            return boundary.x != null;
        }
        return false;
    }

    private static String shortText(String value) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.substring(0, Math.min(240, compact.length()));
    }

    /**
     * 取完整的边界带作为 ROI，避免局部候选框落在相邻空白或非目标区域。ROI 放大不改变坐标系。
     * todo me:换一个视野（整条 20% 边缘带）+ 给出首次的字面量锚点，让模型在边缘带内重新挑字形位置
     * @param pageId
     * @param region
     * @param image
     * @return
     */
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
        //todo @me:以bottom为例，直接取最底下页面20%区域作为ROI
        RoiTransform transform = RoiTransform.fromEdge(image.getWidth(), image.getHeight(), edge, null, 0);
        // 与候选中心 ROI 保持同一视觉测量条件：边缘带仍映射回未经处理的原图，但送检图先
        // 提升文字/背景反差并放大。否则整幅 20% 页脚带中的小号页码会在模型看来过小，虽能
        // 读出语义却容易把坐标落在相邻空白处。
        //todo me:【取20%底部后，再提升文字/背景反差并放大3倍】
        BufferedImage enlarged = enlarge(inkContrast(crop(image, transform)), 3);
        return new EdgeRoi(transform, new VlmClient.RoiImage(pageId, region.region_id, enlarged));
    }

    /**
     * 以 locate 候选框为中心生成局部精修 ROI，并合并最近正文边界证据。
     * 首轮 locate 解决“这是什么”，本方法只解决“像素框到哪里”；ROI 放大不改变坐标系。
     * 所有输入 region/window 均为整图归一化坐标，RoiTransform 负责转换为整图像素裁剪框；
     * 放大只改变送检图像，不改变坐标系，模型返回的 ROI 坐标必须随后映射回原图再校验。
     *
     * @param pageId 页面稳定标识
     * @param region 首次 locate 的整图归一化候选框
     * @param boundary 最近正文边界
     * @param image 旋正后的原图
     * @return 包含 ROI 变换和放大图的局部请求；候选不在边缘时由调用方处理为空
     */
    private EdgeRoi candidateCenteredRoi(String pageId, EraseRegion region,
                                         BodyBoundary boundary, BufferedImage image) {
        int candidateHeight = Math.max(1, (int) Math.ceil((region.y2 - region.y1) * image.getHeight()));
        int candidateWidth = Math.max(1, (int) Math.ceil((region.x2 - region.x1) * image.getWidth()));
        int margin = Math.max(16, Math.min(160, 2 * Math.min(candidateHeight, candidateWidth)));
        RoiTransform transform = RoiTransform.fromNormalizedCandidate(image.getWidth(), image.getHeight(), region, boundary, margin);
        // 背透文字常使整页语义定位正确、局部像素框却粘到浅灰伪墨迹。这里不做任何
        // 坐标推断，只把同一原图 ROI 变为高对比黑白证据再放大；模型仍须按文字锚点
        // 决定是否是页码行，返回坐标也仍由 transform 映射回未经修改的原图。
        BufferedImage enlarged = enlarge(inkContrast(crop(image, transform)), 3);
        return new EdgeRoi(transform, new VlmClient.RoiImage(pageId, region.region_id, enlarged));
    }

    /**
     * 将局部 ROI 内的正文边界归一化坐标映射回整图归一化坐标。
     *
     * @param local ROI 相对正文边界
     * @param transform ROI 在整图中的像素位置和尺寸
     * @param fullWidth 整图宽度
     * @param fullHeight 整图高度
     * @return 整图归一化正文边界
     */
    private BodyBoundary mapBoundary(BodyBoundary local, RoiTransform transform, int fullWidth, int fullHeight) {
        // full = (ROI左上角像素 + local比例 × ROI尺寸) / 整图尺寸。
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
        copy.nearest_body_boundary = source.nearest_body_boundary;
        return copy;
    }

    private List<EraseRegion> copyRegions(List<EraseRegion> sources) {
        List<EraseRegion> copies = new ArrayList<EraseRegion>();
        for (EraseRegion source : sources) copies.add(copyRegion(source));
        return copies;
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

    /** 对每个风险候选执行一次逐 region ROI Relocate，并把几何结果映射回整图。 */
    private RelocationResult relocateAndMap(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized,
                                            PageTransforms transforms, LocateResponse locate,
                                            VlmClient.PageImage pageImage, RunContext context) {
        LocateResponse relocatedLocate = copyLocateWithoutRegions(locate);
        for (EraseRegion region : locate.regions) {
            long startedAt = System.currentTimeMillis();
            context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), "started", region.region_id, 0);

            RoiTransform transform = relocateRoiTransform(normalized, region);
            RelocateResponse relocation = relocateWithSingleRetry(exam, page, pageImage, region,
                    new VlmClient.RoiImage(page.getPageId(), region.region_id, crop(normalized, transform)), context, region.region_id);
            if (relocation == null) {
                return RelocationResult.denied(manual(page, original, normalized, transforms, "relocate_error", locate));
            }
            context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), "completed",
                    relocation.target_found ? "target_found" : "target_not_found",
                    System.currentTimeMillis() - startedAt);

            if (!relocation.target_found || relocation.refined_region == null) {
                context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), EventStatus.DENIED,
                        "target_not_found; evidence=" + shortText(relocation.evidence), 0);
                return RelocationResult.denied(manual(page, original, normalized, transforms,
                        "relocate_target_not_found", locate));
            }
            EraseRegion mapped = mapRelocatedRegion(region, relocation.refined_region,
                    relocation.nearest_body_boundary, transform, normalized);
            RegionValidator.ValidationResult mappedValidation = RegionValidator.validate(
                    new RegionValidator.PageLocateResult(relocatedLocate.page_id, relocatedLocate.status,
                            Collections.singletonList(mapped)), normalized);
            if (mappedValidation.isAccepted() && hasErasableTargetInk(normalized, mappedValidation.getRegions())) {
                relocatedLocate.regions.add(mapped);
            } else {
                // 局部几何结果不满足 Java 门禁时保留已经通过首次校验的原框，
                // 不因迁就局部模型的紧框而放宽或自行扩框。
                relocatedLocate.regions.add(copyRegion(region));
                context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), EventStatus.REJECTED,
                        "mapped_relocation_rejected_original_geometry_retained:"
                                + (mappedValidation.isAccepted() ? "refined box has no erasable target ink"
                                : mappedValidation.getReasons().toString()), 0);
            }
        }
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(relocatedLocate.page_id, relocatedLocate.status, relocatedLocate.regions), normalized);
        if (!validation.isAccepted()) {
            context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), EventStatus.REJECTED,
                    "mapped_relocate_regions=" + relocatedLocate.regions.size() + "; reasons=" + validation.getReasons(), 0);
            return RelocationResult.denied(manual(page, original, normalized, transforms,
                    "relocate_validation_rejected: " + validation.getReasons(), relocatedLocate));
        }
        return RelocationResult.accepted(relocatedLocate, validation);
    }

    /**
     * 构造 ROI Relocate 用的 ROI：候选框四周留固定上下文，并保证画面包含该候选所属的物理页面边缘。
     *
     * <p>提示词约定模型看到的是“边缘 ROI”。若裁剪图不含页面物理边缘，贴底或贴顶的页脚
     * 运行章名（例如页脚同行的“第八章 …”）在画面里与正文标题没有区别，模型只能保守拒绝，
     * 把一个本来安全的页脚行升级成人工审核。补齐边缘只增加“该目标行确实位于页面终止带”
     * 的可见证据：不改变坐标系、不放宽门禁、也不改变模型可以命中的目标行。</p>
     *
     * @param image 旋正后的整图
     * @param region 当前整图归一化候选框
     * @return 用于 Relocate 的 ROI 变换；候选无法归属任何边缘时保持原候选窗口
     */
    private RoiTransform relocateRoiTransform(BufferedImage image, EraseRegion region) {
        RoiTransform base = RoiTransform.fromNormalizedCandidate(image.getWidth(), image.getHeight(),
                region, region.nearest_body_boundary, RELOCATE_ROI_MARGIN_PIXELS);
        int x = base.getX();
        int y = base.getY();
        int width = base.getWidth();
        int height = base.getHeight();
        switch (RegionValidator.edgeBand(region)) {
            case TOP:
                height += y;
                y = 0;
                break;
            case BOTTOM:
                height = image.getHeight() - y;
                break;
            case LEFT:
                width += x;
                x = 0;
                break;
            case RIGHT:
                width = image.getWidth() - x;
                break;
            default:
                return base;
        }
        return new RoiTransform(x, y, width, height, image.getWidth(), image.getHeight());
    }

    private EraseRegion mapRelocatedRegion(EraseRegion original, com.xb.sgc.papererase.model.ExamModels.LocalRegion local,
                                           com.xb.sgc.papererase.model.ExamModels.BodyBoundary boundary,
                                           RoiTransform transform, BufferedImage image) {
        if (local == null) {
            throw new IllegalArgumentException("relocate target_found must provide refined_region");
        }
        RoiTransform.PixelRect rect = transform.localRectToFullPixels(local.x1, local.y1, local.x2, local.y2);
        EraseRegion mapped = copyRegion(original);
        applyRoiMappingGuard(mapped, rect, image.getWidth(), image.getHeight());
        mapped.safety_margin = "relocate_coordinate_refined";
        mapped.nearest_body_boundary = boundary;
        return RegionValidator.trimBodyFacingBlankPadding(mapped, image);
    }

    /**
     * 对同一 ROI 的网络或协议失败仅重发一次；模型已给出的安全/不安全结论不通过重试推翻。
     */
    private RelocateResponse relocateWithSingleRetry(ExamInput exam, PageInput page, VlmClient.PageImage pageImage,
                                                     EraseRegion region, VlmClient.RoiImage roi,
                                                     RunContext context, String regionId) {
        try {
            return vlm.relocateCoordinateRefinement(pageImage, region, roi);
        } catch (RuntimeException firstFailure) {
            context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), EventStatus.RETRY,
                    "same_roi_after_transport_or_protocol_failure:" + regionId, 0);
            try {
                return vlm.relocateCoordinateRefinement(pageImage, region, roi);
            } catch (RuntimeException secondFailure) {
                context.event(PipelineStage.RELOCATE, exam.getExamId(), page.getPageId(), EventStatus.FAILED,
                        shortError(secondFailure), 0);
                return null;
            }
        }
    }

    /**
     * 擦除与最终审计的收口点。擦除器只能改批准区域，PixelDiffGate 检查批准区域外零像素变化，
     * audit 再用原图/擦除图确认“正文未变、目标已消失”；审计残留只允许一次局部坐标精修，
     * 正文变化永远不进入重试放行路径。
     */
    private PageOutcome eraseAndAudit(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                                      LocateResponse locate, VlmClient.PageImage pageImage,
                                      List<RegionValidator.PixelRegion> pixelRegions,
                                      boolean auditRetried, List<EraseRegion> initialLocateRegions,
                                      boolean localRelocationConfirmed, boolean vlmCoordinateRefined, RunContext context) {
        // 5. 擦除执行：只接收 RegionValidator 已批准的像素框。
        BufferedImage candidate = normalized;
        List<RegionValidator.PixelRegion> erasedRegions = new ArrayList<RegionValidator.PixelRegion>();
        for (RegionValidator.PixelRegion pixelRegion : pixelRegions) {
            // 5.1 掩码擦除：InkMaskEraser 只在批准框内重建背景，不扩大目标区域。
            // 擦除器执行掩码级修改，并由像素差分门禁保证候选框外零改动。
            InkMaskEraser.EraseOutcome erase = InkMaskEraser.erase(candidate, pixelRegion);
            if (erase.getStatus() != InkMaskEraser.Status.SAFE_TO_ERASE) {
                // 两个 VLM region 可能在局部精修后变成同一个完整页脚行。若当前批准框已被
                // 前一个成功擦除的批准框完整包含，空掩码只表示目标已经移除，不是擦除失败。
                // 只接受严格几何包含；部分重叠、近似 IoU 或其他失败原因仍然失败关闭。
                if ("no target ink found".equals(erase.getReason())
                        && isContainedByAny(pixelRegion, erasedRegions)) {
                    context.event(PipelineStage.ERASE, exam.getExamId(), page.getPageId(), "skipped",
                            "already_erased_by_containing_approved_region:" + pixelRegion.getRegionId(), 0);
                    continue;
                }
                context.event(PipelineStage.ERASE, exam.getExamId(), page.getPageId(), "rejected", erase.getReason(), 0);
                // 诊断 audit：擦除门禁（长线/竖线/贴边等）拦下的框先白填试擦（仅内存），让 VLM
                // 复核该框是否真伤正文；人工审核由此区分"伤正文"与"门禁过敏"。
                AuditResponse gateAudit = diagnosticAudit(exam, page, normalized, locate, pageImage, context);
                if (!auditRetried && gateAudit != null && gateAudit.original_target_is_non_body
                        && !gateAudit.body_changed && gateAudit.target_removed) {
                    // 诊断 audit 确认框不伤正文：给一次坐标精修重试（例如把通栏分隔线排出框外），
                    // 精修后重走完整擦除+审计；重试后 audit 仍以三项硬条件为准，红线不变。
                    Refinement retry = relocateRejectedRegions(exam, page, normalized, locate, pageImage, context, false);
                    if (retry != null) {
                        context.event(PipelineStage.ERASE, exam.getExamId(), page.getPageId(), "accepted",
                                "coordinate_refined_after_gate_audit", 0);
                        return eraseAndAudit(exam, page, original, normalized, transforms, retry.locate, pageImage,
                                retry.validation.getRegions(), true, initialLocateRegions,
                                localRelocationConfirmed, true, context);
                    }
                }
                return new PageOutcome(page.getPageId(), "manual_review",
                        "erase_failed: " + erase.getReason() + gateAuditSuffix(gateAudit),
                        original, normalized, normalized, transforms, locate.regions, locate, gateAudit);
            }
            candidate = erase.getCandidate();
            erasedRegions.add(pixelRegion);
        }
        context.event(PipelineStage.ERASE, exam.getExamId(), page.getPageId(), "completed", "region_count=" + pixelRegions.size(), 0);
        // 6. 像素完整性：擦除器内部已执行 PixelDiffGate，任何批准掩码外变化都会回退原图。
        long auditStartedAt = System.currentTimeMillis();

        context.event(PipelineStage.AUDIT, exam.getExamId(), page.getPageId(), "started", null, 0);
        // 7. audit 视觉审计：对原图、擦除图和局部 ROI 同时复核正文与目标。
        VlmClient.PageImage erasedPageImage = new VlmClient.PageImage(page.getPageId(), candidate);
        // Audit 必须看到真正发生写入的最终 PixelRegion，而不是可能已被 trim/rescue/refine
        // 过时的 VLM 粗框；ROI 额外保留固定上下文供模型判断正文与目标的相对关系。
        List<VlmClient.RoiImage> auditRois = auditRois(page.getPageId(), pixelRegions, normalized, candidate);

        //todo me:擦除后审计（核心逻辑）：送给大模型的图片有：原图/擦除后图、扩充24px的每个region对应的原图ROI以及擦除后的ROI（每个region一个）
        AuditResponse audit = vlm.audit(pageImage, erasedPageImage, locate.regions, auditRois);
        List<ExamOutcome.ApprovedRegion> approvedEvidence = approvedRegions(initialLocateRegions, locate, pixelRegions,
                normalized, localRelocationConfirmed, vlmCoordinateRefined);
        context.event(PipelineStage.AUDIT, exam.getExamId(), page.getPageId(), "completed", audit.decision,
                System.currentTimeMillis() - auditStartedAt);
        // 真实客户端由 ResponseParser 拦截非法协议；这里再次校验，避免替身或未来客户端绕过解析器时
        // 将“三项硬条件”和 decision 矛盾的审计结果误带入交付分支。
        boolean auditHardConditionsPassed = audit.original_target_is_non_body
                && !audit.body_changed && audit.target_removed;
        if ("pass".equals(audit.decision) != auditHardConditionsPassed) {
            return new PageOutcome(page.getPageId(), "manual_review", "audit_protocol_inconsistent", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        // 原始目标确认非正文、正文不变、目标确实消失是三项交付硬条件；背景色仅是质量告警。
        if (!audit.original_target_is_non_body) {
            // 语义判定为正文/不确定时不得以“坐标精修”尝试挽救，避免把正文当残留页码继续擦除。
            return new PageOutcome(page.getPageId(), "manual_review", "audit_original_target_is_body", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        if (audit.body_changed && audit.target_removed) {
            // 7.1 正文变化且目标确已移除：没有任何可重试的残留目标，不允许通过色差降级放行。
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        if (!audit.target_removed) {
            // 7.2 目标残留可做一次局部坐标精修；正文变化与目标残留同时出现时也走这一次重试，
            // 因为“残留目标”是唯一可由高清 ROI 重新证明的失效模式，而重试后的 audit 仍以
            // body_changed=false 为硬条件，正文安全性不因重试而放宽。
            if (!auditRetried) {
                Refinement refinement = relocateAfterAuditResidual(exam, page, normalized, locate, pageImage, context);
                if (refinement != null) {
                    context.event(PipelineStage.AUDIT_COORDINATE_REFINE, exam.getExamId(), page.getPageId(), "accepted", "target_residual", 0);
                    return eraseAndAudit(exam, page, original, normalized, transforms, refinement.locate, pageImage,
                            refinement.validation.getRegions(), true, initialLocateRegions,
                            localRelocationConfirmed, true, context);
                }
            }
            if (audit.body_changed) {
                // 重试后仍同时报“正文变化 + 目标残留”：证据互相矛盾，失败关闭不改判。
                return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
            }
            return new PageOutcome(page.getPageId(), "manual_review", "audit_target_not_removed", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        if (!"pass".equals(audit.decision)) {
            // Parser 已做真值表校验；此处仍先失败关闭，避免未来协议调整后背景色告警抢先放行。
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized, normalized, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        if (!audit.background_acceptable) {
            // 7.3 背景色只记录告警，正文安全已满足即可交付擦除图。
            return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass_with_color_warning", original, normalized, candidate, transforms, locate.regions, locate, audit, approvedEvidence);
        }
        return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass", original, normalized, candidate, transforms, locate.regions, locate, audit, approvedEvidence);
    }

    private static boolean isContainedByAny(RegionValidator.PixelRegion candidate,
                                            List<RegionValidator.PixelRegion> containers) {
        int candidateRight = candidate.getX() + candidate.getWidth();
        int candidateBottom = candidate.getY() + candidate.getHeight();
        for (RegionValidator.PixelRegion container : containers) {
            if (candidate.getX() >= container.getX() && candidate.getY() >= container.getY()
                    && candidateRight <= container.getX() + container.getWidth()
                    && candidateBottom <= container.getY() + container.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /** 仅用于输出证据：由已经批准的像素框生成审计记录，不参与任何门禁或擦除判断。 */
    private List<ExamOutcome.ApprovedRegion> approvedRegions(List<EraseRegion> initialLocateRegions, LocateResponse finalLocate,
                                                               List<RegionValidator.PixelRegion> pixels, BufferedImage image,
                                                               boolean localRelocationConfirmed, boolean vlmCoordinateRefined) {
        List<ExamOutcome.ApprovedRegion> result = new ArrayList<ExamOutcome.ApprovedRegion>();
        for (RegionValidator.PixelRegion pixel : pixels) {
            EraseRegion source = null;
            for (EraseRegion region : initialLocateRegions) {
                if (pixel.getRegionId().equals(region.region_id)) { source = region; break; }
            }
            EraseRegion finalVlm = null;
            for (EraseRegion region : finalLocate.regions) {
                if (pixel.getRegionId().equals(region.region_id)) { finalVlm = region; break; }
            }
            EraseRegion originalBox = source == null ? null : copyRegion(source);
            EraseRegion finalBox = new EraseRegion();
            finalBox.region_id = pixel.getRegionId();
            finalBox.x1 = pixel.getX() / (double) image.getWidth();
            finalBox.y1 = pixel.getY() / (double) image.getHeight();
            finalBox.x2 = (pixel.getX() + pixel.getWidth()) / (double) image.getWidth();
            finalBox.y2 = (pixel.getY() + pixel.getHeight()) / (double) image.getHeight();
            boolean expanded = finalVlm != null && (Math.abs(finalBox.x1 - finalVlm.x1) > 0.000001
                    || Math.abs(finalBox.y1 - finalVlm.y1) > 0.000001 || Math.abs(finalBox.x2 - finalVlm.x2) > 0.000001
                    || Math.abs(finalBox.y2 - finalVlm.y2) > 0.000001);
            result.add(new ExamOutcome.ApprovedRegion(pixel.getRegionId(), originalBox, pixel.getX(), pixel.getY(),
                    pixel.getWidth(), pixel.getHeight(), finalBox, expanded, pixel.isCoordinateRescued(), vlmCoordinateRefined,
                    localRelocationConfirmed ? "relocate_geometry_confirmed" : "locate_semantic_confirmed"));
        }
        return result;
    }

    private VlmClient.RoiImage roi(String pageId, EraseRegion region, BodyBoundary boundary, BufferedImage image) {
        RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                image.getWidth(), image.getHeight(), region, boundary, 24);
        return new VlmClient.RoiImage(pageId, region.region_id, crop(image, transform));
    }

    /**
     * 将 ROI 局部像素矩形转换成整图归一化候选框，并统一增加抗锯齿保护边。
     * 保护边只用于坐标量化补偿，之后仍必须重新经过 RegionValidator 和 PixelDiffGate；
     * 朝正文方向的保护边会被刻意禁止扩张。
     *
     * @param region 要被写回的整图归一化候选框
     * @param rect ROI 模型坐标映射得到的整图像素矩形
     * @param width 整图宽度
     * @param height 整图高度
     */
    private void applyRoiMappingGuard(EraseRegion region, RoiTransform.PixelRect rect, int width, int height) {
        int left = Math.max(0, rect.getX() - ROI_MAPPING_GUARD_PIXELS);
        int top = Math.max(0, rect.getY() - ROI_MAPPING_GUARD_PIXELS);
        int right = Math.min(width, rect.getX() + rect.getWidth() + ROI_MAPPING_GUARD_PIXELS);
        int bottom = Math.min(height, rect.getY() + rect.getHeight() + ROI_MAPPING_GUARD_PIXELS);
        // 坐标量化保护边绝不能向正文方向扩张：底部页脚的上边、顶部页眉的下边（左右
        // 同理）一旦向正文回拉，即使模型精框原本安全也会制造假阳性的“间隙有墨”。
        // 目标笔画是否贴边仍由 RegionValidator 的同一像素契约处理，证明不了则失败关闭。
        if (region.y1 >= 0.80D) top = rect.getY();
        if (region.y2 <= 0.20D) bottom = rect.getY() + rect.getHeight();
        if (region.x1 >= 0.80D) left = rect.getX();
        if (region.x2 <= 0.20D) right = rect.getX() + rect.getWidth();
        region.x1 = left / (double) width;
        region.y1 = top / (double) height;
        region.x2 = right / (double) width;
        region.y2 = bottom / (double) height;
    }

    private List<VlmClient.RoiImage> auditRois(String pageId, List<RegionValidator.PixelRegion> regions,
                                               BufferedImage original, BufferedImage erased) {
        List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
        for (RegionValidator.PixelRegion region : regions) {
            RoiTransform transform = RoiTransform.fromCandidate(
                    original.getWidth(), original.getHeight(), region, null, 24);
            // audit 的 ROI 原图与擦除图必须用同一倍率放大后再送审：细切口（约 5px）在原始
            // ROI 尺寸下低于模型分辨力，放大后模型才能看清框边是否切到正文笔画。倍率与
            // relocate 的 3 倍口径一致；只放大这两张 ROI，整页图仍按 max_preview_long_edge 走。
            rois.add(new VlmClient.RoiImage(pageId, region.getRegionId(), auditRoi(original, transform), "ORIGINAL"));
            rois.add(new VlmClient.RoiImage(pageId, region.getRegionId(), auditRoi(erased, transform), "ERASED"));
        }
        return rois;
    }

    /** 审计用局部图：裁剪后固定放大 3 倍；原图与擦除图共用同一变换，保证两张图可逐位置对齐。 */
    private BufferedImage auditRoi(BufferedImage source, RoiTransform transform) {
        return enlarge(crop(source, transform), AUDIT_ROI_SCALE);
    }

    private BufferedImage enlarge(BufferedImage source, int factor) {
        BufferedImage enlarged = new BufferedImage(source.getWidth() * factor, source.getHeight() * factor, source.getType());
        Graphics2D graphics = enlarged.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, enlarged.getWidth(), enlarged.getHeight(), null);
        graphics.dispose();
        return enlarged;
    }

    /**
     * 仅供局部坐标复核的显示预处理：保留深色前景，压掉扫描背透/纸纹的浅灰噪点。
     * 它不参与擦除、门禁或坐标计算，因此不会扩大任何可修改像素的范围。
     */
    private BufferedImage inkContrast(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
                result.setRGB(x, y, luminance < 180 ? 0x000000 : 0xFFFFFF);
            }
        }
        return result;
    }

    /**
     * 按 RoiTransform 的整图像素矩形裁剪局部图；该裁剪不改变原图坐标，变换对象负责之后
     * 将模型返回的 ROI 相对坐标还原回整图。
     *
     * @param image 原图
     * @param transform ROI 像素范围
     * @return ROI 图像副本
     */
    private BufferedImage crop(BufferedImage image, RoiTransform transform) {
        return image.getSubimage(transform.getX(), transform.getY(), transform.getWidth(), transform.getHeight());
    }

    /**
     * 诊断性 audit：region 在交付前被几何/擦除门禁拦截、尚未产生真实擦除图时，把候选框在
     * 内存副本上白填试擦，连同原图和 ROI 交给 VLM audit 复核。试擦图不落地、不参与交付；
     * 返回值仅用于给人工审核页标注"伤正文/门禁过敏"，以及决定是否再给一次坐标精修重试。
     */
    private AuditResponse diagnosticAudit(ExamInput exam, PageInput page, BufferedImage normalized,
                                          LocateResponse locate, VlmClient.PageImage pageImage, RunContext context) {
        if (locate == null || locate.regions == null || locate.regions.isEmpty()) {
            return null;
        }
        try {
            BufferedImage trial = new BufferedImage(normalized.getWidth(), normalized.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = trial.createGraphics();
            graphics.drawImage(normalized, 0, 0, null);
            graphics.setColor(java.awt.Color.WHITE);
            for (EraseRegion region : locate.regions) {
                int left = (int) Math.floor(region.x1 * trial.getWidth());
                int top = (int) Math.floor(region.y1 * trial.getHeight());
                int right = (int) Math.ceil(region.x2 * trial.getWidth());
                int bottom = (int) Math.ceil(region.y2 * trial.getHeight());
                graphics.fillRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
            }
            graphics.dispose();
            List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
            for (EraseRegion region : locate.regions) {
                RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                        normalized.getWidth(), normalized.getHeight(), region, null, 24);
                rois.add(new VlmClient.RoiImage(page.getPageId(), region.region_id, auditRoi(normalized, transform), "ORIGINAL"));
                rois.add(new VlmClient.RoiImage(page.getPageId(), region.region_id, auditRoi(trial, transform), "ERASED"));
            }
            AuditResponse audit = vlm.audit(pageImage,
                    new VlmClient.PageImage(page.getPageId(), trial), locate.regions, rois);
            context.event(PipelineStage.AUDIT, exam.getExamId(), page.getPageId(), "completed",
                    "gate_diagnostic:" + audit.decision, 0);
            return audit;
        } catch (RuntimeException failure) {
            context.event(PipelineStage.AUDIT, exam.getExamId(), page.getPageId(), EventStatus.FAILED,
                    "gate_diagnostic:" + shortError(failure), 0);
            return null;
        }
    }

    /** 把诊断 audit 结论压缩成 reason 后缀，便于人工审核按 reason 直接分流。 */
    private static String gateAuditSuffix(AuditResponse audit) {
        if (audit == null) {
            return "; gate_audit=unavailable";
        }
        if (audit.body_changed) {
            return "; gate_audit=body_damaged";
        }
        if (!audit.original_target_is_non_body) {
            return "; gate_audit=target_is_body";
        }
        if (!audit.target_removed) {
            return "; gate_audit=target_residual";
        }
        return "; gate_audit=clean";
    }

    private PageOutcome manual(PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                               String reason, LocateResponse locate) {
        if (transforms == null) {
            transforms = transform(original, normalized, 0);
        }
        List<EraseRegion> regions = (locate == null) ? Collections.<EraseRegion>emptyList() : locate.regions;
        return new PageOutcome(page.getPageId(), "manual_review", reason, original, normalized, normalized, transforms, regions, locate, null);
    }

    private PageTransforms transform(BufferedImage original, BufferedImage normalized, int readingRotation) {
        return new PageTransforms(original.getWidth(), original.getHeight(), normalized.getWidth(), normalized.getHeight(), readingRotation);
    }

    private ExamOutcome wholeExamFallback(ExamInput exam, Map<String, BufferedImage> originals, String reason, String detail) {
        List<PageOutcome> pages = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            BufferedImage image = originals.get(page.getPageId());
            pages.add(new PageOutcome(page.getPageId(), "manual_review", detail, image, image, image, transform(image, image, 0), Collections.<EraseRegion>emptyList(), null, null));
        }
        return new ExamOutcome(exam.getExamId(), "manual_review", reason, pages);
    }

    private Map<String, BufferedImage> readOriginals(ExamInput exam) {
        Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();
        for (PageInput page : exam.getPages()) {
            try {
                BufferedImage image = ImageIO.read(page.getImagePath().toFile());
                if (image == null) throw new IOException("ImageIO returned null");
                images.put(page.getPageId(), image);
            } catch (IOException e) {
                throw new RuntimeException("cannot read page image: " + page.getPageId(), e);
            }
        }
        return images;
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

    private static final class Refinement {
        final LocateResponse locate;
        final RegionValidator.ValidationResult validation;

        Refinement(LocateResponse locate, RegionValidator.ValidationResult validation) {
            this.locate = locate;
            this.validation = validation;
        }
    }

    /** ROI Relocate 结果只有在坐标映射并通过整页像素门禁后才可继续擦除。 */
    private static final class RelocationResult {
        final PageOutcome denied;
        final LocateResponse locate;
        final RegionValidator.ValidationResult validation;

        private RelocationResult(PageOutcome denied, LocateResponse locate,
                                   RegionValidator.ValidationResult validation) {
            this.denied = denied;
            this.locate = locate;
            this.validation = validation;
        }

        static RelocationResult denied(PageOutcome outcome) {
            return new RelocationResult(outcome, null, null);
        }

        static RelocationResult accepted(LocateResponse locate, RegionValidator.ValidationResult validation) {
            return new RelocationResult(null, locate, validation);
        }
    }

    /** _progress.ndjson 的稳定阶段名；wireValue 保持历史字符串兼容。 */
    public enum PipelineStage {
        EXAM("exam"), IMAGE_LOAD("image_load"), PAGE("page"), PAGE_ERROR("page_error"),
NORMALIZE("normalize"), LOCATE("locate"),
        VALIDATION("validation"), RELOCATE("relocate"), ERASE("erase"), AUDIT("audit"),
        AUDIT_COORDINATE_REFINE("audit_coordinate_refine"), OUTPUT("output");

        private final String wireValue;

        PipelineStage(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
    }

    /** _progress.ndjson 的常用固定状态；动态业务状态仍可按原字符串写入。 */
    public enum EventStatus {
        STARTED("started"), COMPLETED("completed"), FAILED("failed"), RETRY("retry"), SKIPPED("skipped"),
        ACCEPTED("accepted"), REJECTED("rejected"), DENIED("denied"), NOT_ACCEPTED("not_accepted");

        private final String wireValue;

        EventStatus(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
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

        public void event(PipelineStage stage, String examId, String pageId, EventStatus status,
                          String reason, long elapsedMillis) {
            event(stage.wireValue(), examId, pageId, status.wireValue(), reason, elapsedMillis);
        }

        public void event(PipelineStage stage, String examId, String pageId, String status,
                          String reason, long elapsedMillis) {
            event(stage.wireValue(), examId, pageId, status, reason, elapsedMillis);
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
