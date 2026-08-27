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
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
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

    /**
     * 试卷级总入口：先建立整卷共性，再逐页执行“定位→像素门禁→风险复核→擦除→审计”。
     *
     * <p>这里故意把页面循环包在单页异常隔离边界内：某页模型超时、坐标非法或审计失败时，
     * 只把该页交给人工，不让异常页影响同卷其他页的原图和产物。</p>
     *
     * @see #buildPattern(ExamInput, Map, RunContext)
     * @see #processPage(ExamInput, PageInput, BufferedImage, PatternBundle, RunContext)
     */
    public ExamOutcome process(ExamInput exam, RunContext context) {
        // 0. 输入与原图装载：所有后续坐标、像素门禁和产物都以这份原图为唯一基准。
        context = context == null ? new RunContext() : context;
        long examStartedAt = System.currentTimeMillis();
        context.event("exam", exam.getExamId(), null, "started", "page_count=" + exam.getPages().size(), 0);
        Map<String, BufferedImage> originals = readOriginals(exam);
        context.event("image_load", exam.getExamId(), null, "completed", "page_count=" + originals.size(), 0);
        PatternBundle bundle;
        try {
            // 1. pattern 共性分析：先建立整卷页码边、阅读方向和分组先验；这里只产生粗窗口。
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
            // 2. locate 及单页安全流水线：每页独立处理，任何一页失败关闭都不影响其他页。
            BufferedImage original = originals.get(page.getPageId());
            long pageStartedAt = System.currentTimeMillis();
            context.event("page", exam.getExamId(), page.getPageId(), "started", null, 0);
            PageOutcome pageOutcome;
            try {
                // 页面异常必须隔离：不能因为一张异常图让后续页绕过审计或丢失产物。
                pageOutcome = published(page, original, bundle, processPage(exam, page, original, bundle, context));
            } catch (RuntimeException e) {
                // 记录可行动的短错误，避免吞掉协议失配后反复调用模型猜根因。
                context.event("page_error", exam.getExamId(), page.getPageId(), "failed", shortError(e), 0);
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

    private PageOutcome published(PageInput page, BufferedImage original, PatternBundle bundle, PageOutcome outcome) {
        if ("safe_to_erase".equals(outcome.getStatus()) || "no_pagenum".equals(outcome.getStatus())
                || "manual_review".equals(outcome.getStatus())) {
            return outcome;
        }
        return manual(page, original, outcome.getNormalized(), outcome.getTransforms(),
                "internal_state_" + outcome.getStatus(), bundle.groupByPage.get(page.getPageId()), outcome.getLocate());
    }

    /**
     * 用同一试卷的代表页/分批页建立页码位置、阅读方向和版式共性。
     * pattern 只提供“先验和粗窗口”，不产生可直接擦除的最终像素框；这样可把跨页共性
     * 用于减少模型漂移，同时仍要求每页 locate 自己证明页码存在和正文安全距离。
     */
    private PatternBundle buildPattern(ExamInput exam, Map<String, BufferedImage> originals, RunContext context) {
        // 1.1 代表页采样：先用少量页面判断整卷是否足够同质，控制调用成本。
        PatternBundle bundle = new PatternBundle();
        List<PageInput> nonBlankPages = new ArrayList<PageInput>();
        for (PageInput page : exam.getPages()) {
            if (isVisiblyBlankPage(originals.get(page.getPageId()))) {
                context.event("pattern", exam.getExamId(), page.getPageId(), "skipped", "java_blank", 0);
            } else {
                nonBlankPages.add(page);
            }
        }
        if (nonBlankPages.isEmpty()) {
            return bundle;
        }
        List<PageInput> representative = PageBatcher.representative(nonBlankPages, patternSampleMaxPages);
        if (representative.size() < nonBlankPages.size()) {
            // 1.2 代表页共性继承：只有方向、分组、页码存在性和置信度全部稳定才可扩散到全卷。
            PatternResponse sampled = analyzePatternBatch(exam, representative, originals, context, "representative");
            if (canInheritRepresentativePattern(sampled, representative)) {
                PatternGroup inherited = copyPatternGroup(sampled.pattern_groups.get(0));
                inherited.page_ids.clear();
                int rotation = sampled.page_directions.get(0).reading_rotation;
                double directionConfidence = sampled.page_directions.get(0).confidence;
                for (PageInput page : nonBlankPages) {
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
            // 1.3 采样不稳定回退：改为相邻重叠批次，确保每页都有真实 pattern 结果。
            context.event("pattern", exam.getExamId(), null, "fallback_full", "representative_not_stable", 0);
        }
        int batchIndex = 0;
        int fullPatternBatchSize = patternSampleMaxPages == 0 ? 8 : 6;
        for (List<PageInput> batch : PageBatcher.overlapping(nonBlankPages, fullPatternBatchSize, 1)) {
            // 1.4 批次合并：按 page_id 建立方向和 group_id 映射；冲突只记录 mixed，不猜测。
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
        // 1.5 请求协议：每张图片显式绑定 page_id，禁止用数组下标推断页面归属。
        for (PageInput page : pages) {
            images.add(new VlmClient.PageImage(page.getPageId(), originals.get(page.getPageId())));
            expected.add(page.getPageId());
        }
        context.event("pattern", exam.getExamId(), null, "started", label + " page_count=" + images.size(), 0);
        PatternResponse response;
        try {
            // 1.6 调用 pattern：模型负责跨页语义共性，Java 负责返回页面集合完整性校验。
            response = vlm.pattern(images);
            validatePatternPageIds(response, expected);
            context.event("pattern", exam.getExamId(), null, "completed", label + " page_count=" + images.size(),
                    System.currentTimeMillis() - startedAt);
        } catch (ResponseParser.ParseException firstFailure) {
            // 1.7 协议纠错：仅对同一批图片重发一次，仍失败则整卷失败关闭。
            // 严格 parser 明确指出协议违例时，带相同图片和精确 page_id 只纠错重发一次；
            // 不把网络波动误当 JSON 问题，也不放宽解析契约。
            context.event("pattern", exam.getExamId(), null, "protocol_correction_retry", shortError(firstFailure), 0);
            try {
                response = vlm.correctPatternAfterProtocolError(images, expected, firstFailure.getMessage());
                validatePatternPageIds(response, expected);
                context.event("pattern", exam.getExamId(), null, "completed", label + " corrected", System.currentTimeMillis() - startedAt);
            } catch (ResponseParser.ParseException secondFailure) {
                context.event("pattern", exam.getExamId(), null, "failed", shortError(secondFailure),
                        System.currentTimeMillis() - startedAt);
                throw secondFailure;
            }
        } catch (RuntimeException e) {
            context.event("pattern", exam.getExamId(), null, "failed", e.getClass().getSimpleName(),
                    System.currentTimeMillis() - startedAt);
            throw e;
        }
        return response;
    }

    private void validatePatternPageIds(PatternResponse response, List<String> expected) {
        if (!pageIds(response).equals(new java.util.HashSet<String>(expected))) {
            throw new ResponseParser.ParseException("batch page ids mismatch", "");
        }
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

    /**
     * 单页安全流水线。核心原则是“模型负责语义，Java 负责几何和像素证据”：模型说这是页码
     * 以后，仍必须由 RegionValidator 证明它处在边缘、远离正文且框边不粘正文，之后才允许
     * InkMaskEraser 写图；任何证明链断裂都返回 manual_review 并保留原图。
     */
    private PageOutcome processPage(ExamInput exam, PageInput page, BufferedImage original, PatternBundle bundle, RunContext context) {
        // 2.1 空白页短路：没有任何可见墨迹时直接判定无页码，不调用后续定位和擦除。
        // 纯白占位页没有可擦页码，也没有正文；在方向门禁前用保守像素证据直接归类，
        // 避免模型对无内容页面的方向置信度不足造成无意义的人工审核。
        if (isVisiblyBlankPage(original)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "blank_page", original, original,
                    original, transform(original, original, 0), bundle.groupByPage.get(page.getPageId()),
                    Collections.<EraseRegion>emptyList(), null, null);
        }
        PageDirection direction = bundle.directions.get(page.getPageId());
        // 2.2 方向置信度门禁：方向不可靠时不进入坐标换算，避免旋转坐标伤正文。
        if (direction == null || direction.confidence < MIN_DIRECTION_CONFIDENCE) {
            return manual(page, original, original, transform(original, original, direction == null ? 0 : direction.reading_rotation),
                    "low_direction_confidence", bundle.groupByPage.get(page.getPageId()), null);
        }

        // 坐标、边缘带和正文间隔均在统一阅读方向中判定，避免横竖页混用坐标系。
        OrientationNormalizer.NormalizedImage normalized = OrientationNormalizer.normalize(original, direction.reading_rotation);
        // 2.3 坐标统一：后续 VLM 坐标、像素扫描和擦除全部使用旋正后的同一坐标系。
        BufferedImage normalizedImage = normalized.getImage();
        context.event("normalize", exam.getExamId(), page.getPageId(), "completed", "rotation=" + direction.reading_rotation, 0);
        PageTransforms transforms = new PageTransforms(normalized.getOriginalWidth(), normalized.getOriginalHeight(),
                normalized.getNormalizedWidth(), normalized.getNormalizedHeight(), normalized.getReadingRotation());
        PatternGroup group = bundle.groupByPage.get(page.getPageId());
        VlmClient.PageImage pageImage = new VlmClient.PageImage(page.getPageId(), normalizedImage);
        LocateResponse locate;
        long startedAt = System.currentTimeMillis();
        context.event("locate", exam.getExamId(), page.getPageId(), "started", null, 0);
        try {
            // 2.4 locate 整页语义定位：判断页码及同行元数据是否独立于正文，并给出粗候选框。
            // 首轮必须保留整页版式：页码与正文、页眉元数据的语义关系只能在整页中稳定判断。
            // pattern 仍提供同卷先验，但它的粗窗口不得替代 V6 风格的整页 locate。
            locate = vlm.locate(pageImage, group);
            context.event("locate", exam.getExamId(), page.getPageId(), "completed", locate.status,
                    System.currentTimeMillis() - startedAt);
        } catch (ResponseParser.ParseException firstFailure) {
            // page_number_text 等必填字段缺失时，用同一整页图和精确 page_id 纠错一次；
            // 仍由严格 parser 决定是否接受，纠错失败立即降级人工审核。
            context.event("locate", exam.getExamId(), page.getPageId(), "protocol_correction_retry", shortError(firstFailure), 0);
            try {
                locate = vlm.correctLocateAfterProtocolError(pageImage, group, firstFailure.getMessage());
                context.event("locate", exam.getExamId(), page.getPageId(), "completed", locate.status,
                        System.currentTimeMillis() - startedAt);
            } catch (RuntimeException secondFailure) {
                context.event("locate", exam.getExamId(), page.getPageId(), "failed", shortError(secondFailure), 0);
                return manual(page, original, normalizedImage, transforms, "locate_error", group, null);
            }
        } catch (RuntimeException firstFailure) {
            // 首轮整页模型偶发在 JSON 外附带解释；同请求只重试一次，避免把临时协议问题
            // 误记为“无页码”或无限消耗调用。
            context.event("locate", exam.getExamId(), page.getPageId(), "retry",
                    "same_page_after_parse_or_transport_failure", 0);
            try {
                locate = vlm.locate(pageImage, group);
                context.event("locate", exam.getExamId(), page.getPageId(), "completed", locate.status,
                        System.currentTimeMillis() - startedAt);
            } catch (RuntimeException secondFailure) {
                context.event("locate", exam.getExamId(), page.getPageId(), "failed", shortError(secondFailure), 0);
                return manual(page, original, normalizedImage, transforms, "locate_error", group, null);
            }
        }
        if ("manual_review".equals(locate.status)) {
            return manual(page, original, normalizedImage, transforms, "locate_manual_review", group, locate);
        }
        if ("no_pagenum".equals(locate.status) || locate.regions.isEmpty()) {
            return handleNoCandidate(exam, page, original, normalizedImage, transforms, bundle, group, locate, pageImage, context);
        }

        // 3. Java 正文保护门禁：VLM 坐标只是候选，通过确定性像素证据前绝不写图。
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(locate.page_id, locate.status, locate.regions, locate.nearest_body_boundary),
                normalizedImage);
        boolean refinedByVlm = false;
        if (!validation.isAccepted()) {
            // 3.1 首次校验失败：只允许在原候选框内裁掉已证明为空白的 padding。
            context.event("validation", exam.getExamId(), page.getPageId(), "rejected", validation.getReasons().toString(), 0);
            // 模型已完成“这是哪一条非正文页码行”的语义判断。若它只是把候选框朝正文侧
            // 多含了一段空白/背透，先在原框内裁掉经像素证明为空白的 padding；不找新文字、
            // 不扩框、不改变语义。这样不会让局部模型的错误重测覆盖正确的整页识别。
            Refinement trimmed = trimOriginalBodyPadding(locate, normalizedImage);
            if (trimmed != null) {
                locate = trimmed.locate;
                validation = trimmed.validation;
                context.event("validation", exam.getExamId(), page.getPageId(), "accepted", "original_candidate_blank_padding_trimmed", 0);
            }
        }
        if (!validation.isAccepted()) {
            // 3.2 局部坐标精修：仅对可由高清 ROI 重新定位的轻微几何风险发起二检。
            // 模型已给出明确页码语义但像素框/正文边界未过门禁时，先让模型在完整边缘高清图
            // 中重测；绝不由 Java 放宽规则或自行移动候选框。
            Refinement refinement = shouldRefineRejected(validation) ?
                    refineEmptyTargetBox(exam, page, normalizedImage, group, locate, pageImage, context,
                            isOnlyBodyGapConflict(validation)) : null;
            if (refinement == null) {
                return manual(page, original, normalizedImage, transforms, "validation_rejected", group, locate);
            }
            locate = refinement.locate;
            validation = refinement.validation;
            refinedByVlm = true;
            context.event("validation", exam.getExamId(), page.getPageId(), "accepted", "coordinate_refined_after_rejection", 0);
        }
        context.event("validation", exam.getExamId(), page.getPageId(), "accepted", "region_count=" + validation.getRegions().size(), 0);
        // 首次定位已经通过空间门禁、但候选框本身没有任何可擦墨迹时，不能让 Java 沿边缘猜
        // 测页码。改由模型查看同一边缘带的高清图，重新给出局部坐标和正文边界；没有明确
        // 坐标就关闭失败。这针对的是“语义识别对、归一化坐标偏移”的模型已知失效模式。
        if (hasEmptyTargetBox(normalizedImage, validation.getRegions())) {
            // 3.3 空框救援：整页语义正确但框内无墨时，按同一边缘逐框局部重定位。
            /*
             * 局部模型把“空框”校回真实页码后，整页模型的全局正文边界可能恰好落在别的栏位，
             * 进而与精框产生表面冲突。这里允许 refineAtRoi 内既有的 16px 投影空白带规则
             * 处理该冲突；它仍要求同边、投影重叠、框内有墨和完整空白带，不能放宽普通定位。
             */
            Refinement refinement = refineEmptyTargetBox(exam, page, normalizedImage, group, locate, pageImage, context, true);
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
        // locate 已在整页上完成“这是不是独立非正文页码行”的语义判定，且上面的
        // RegionValidator 已在原图证明正文方向的空白带。色块、边框和复杂字形本身不能再
        // 触发一个会把局部 no_pagenum 当作语义否决的 verify：局部 ROI 不含完整版式，确实会
        // 漏看这类目标。局部 verify 只保留给真实风险（低置信度、同正文行、坐标救回等）。
        boolean hasCoordinateRescue = hasCoordinateRescue(validation.getRegions(), normalizedImage);
        // 4. 风险复核：稳定低风险页走快速路径；旋转、冲突、救回坐标等风险进入 verify。
        boolean requiresVerify = !refinedByVlm && (RiskGate.requiresLocalVerify(riskContext, validation) || hasOnLineRegion(locate.regions)
                || hasCoordinateRescue);
        if (requiresVerify) {
            PageOutcome denied = verifyThenMaybeManual(exam, page, original, normalizedImage, transforms, group, locate, pageImage,
                    "verify_denied", context);
            if (!"needs_recheck".equals(denied.getStatus())) {
                return denied;
            }
        }
        // 整页 locate 的 safe_to_erase 已是模型对完整独立目标行的语义确认；局部 verify
        // 只会进一步提高坐标精度，不能成为彩色/图形目标的唯一语义授权。
        boolean visuallyConfirmedIndependentTarget = true;
        return eraseAndAudit(exam, page, original, normalizedImage, transforms, group, locate, pageImage,
                validation.getRegions(), visuallyConfirmedIndependentTarget, false, context);
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
     * {@code verify} 对坐标和正文边界进行高清复核。</p>
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
        // 所有候选框都不与正文/答题线同一行，可以不因该项单独触发 verify。
        return false;
    }

    private Refinement trimOriginalBodyPadding(LocateResponse source, BufferedImage image) {
        LocateResponse trimmed = copyLocateWithoutRegions(source);
        for (EraseRegion region : source.regions) {
            trimmed.regions.add(RegionValidator.trimBodyFacingBlankPadding(copyRegion(region), image));
        }
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(trimmed.page_id, trimmed.status, trimmed.regions,
                        trimmed.nearest_body_boundary), image);
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

    private boolean isOnlyBodyGapConflict(RegionValidator.ValidationResult validation) {
        if (validation.getReasons().size() != 1) {
            return false;
        }
        String reason = validation.getReasons().get(0);
        return "body blank gap is insufficient".equals(reason)
                || "body blank gap contains ink".equals(reason);
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

    private Refinement refineEmptyTargetBox(ExamInput exam, PageInput page, BufferedImage image, PatternGroup group, LocateResponse locate,
                                             VlmClient.PageImage pageImage, RunContext context,
                                             boolean allowConflictingBoundaryReplacement) {
        if (locate.regions.size() == 1) {
            EraseRegion originalRegion = locate.regions.get(0);
            EdgeRoi edgeRoi = candidateCenteredRoi(page.getPageId(), originalRegion, group,
                    locate.nearest_body_boundary, image);
            return edgeRoi == null ? null : refineAtRoi(exam, page, image, group, locate, pageImage, originalRegion, edgeRoi, context,
                    "coordinate_refine", allowConflictingBoundaryReplacement);
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
                    new RegionValidator.PageLocateResult(single.page_id, single.status, single.regions,
                            single.nearest_body_boundary), image);
            if (singleValidation.isAccepted()) {
                combined.regions.add(originalRegion);
                approved.addAll(singleValidation.getRegions());
                continue;
            }
            if (!shouldRefineRejected(singleValidation)) {
                return null;
            }
            EdgeRoi edgeRoi = candidateCenteredRoi(page.getPageId(), originalRegion, group,
                    locate.nearest_body_boundary, image);
            if (edgeRoi == null) {
                return null;
            }
            Refinement refined = refineAtRoi(exam, page, image, group, single, pageImage, originalRegion, edgeRoi, context,
                    "coordinate_refine", isOnlyBodyGapConflict(singleValidation));
            if (refined == null) {
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
        copy.status = source.status;
        copy.nearest_body_boundary = source.nearest_body_boundary;
        copy.evidence = source.evidence;
        return copy;
    }

    /**
     * 审计仅发现残字时，对每个已批准候选分别做一次放大精定位；正文异常绝不进入本分支。
     * 双页扫描的两个页码必须独立映射和验证，任一精修失败仍整页失败关闭。
     */
    private Refinement refineAfterAudit(ExamInput exam, PageInput page, BufferedImage image, PatternGroup group,
                                        LocateResponse locate, VlmClient.PageImage pageImage, RunContext context) {
        if (locate.regions.isEmpty()) {
            return null;
        }
        if (locate.regions.size() == 1) {
            EraseRegion originalRegion = locate.regions.get(0);
            EdgeRoi roi = candidateCenteredRoi(page.getPageId(), originalRegion, group, locate.nearest_body_boundary, image);
            return refineAtRoi(exam, page, image, group, locate, pageImage, originalRegion, roi, context,
                    "audit_coordinate_refine", false);
        }

        // 同一页脚带的候选中心 ROI 可能同时包含多个页码。此时局部模型返回多个框是正确的
        // 版式结果，不应被当成协议错误；要求数量与原候选完全一致，再统一映射和像素校验。
        EraseRegion anchor = locate.regions.get(0);
        EdgeRoi roi = candidateCenteredRoi(page.getPageId(), anchor, group, locate.nearest_body_boundary, image);
        return roi == null ? null : refineAtRoi(exam, page, image, group, locate, pageImage,
                anchor, roi, context, "audit_coordinate_refine", false);
    }

    private Refinement refineAtRoi(ExamInput exam, PageInput page, BufferedImage image, PatternGroup group, LocateResponse locate,
                                   VlmClient.PageImage pageImage, EraseRegion originalRegion, EdgeRoi edgeRoi,
                                   RunContext context, String stage, boolean allowConflictingBoundaryReplacement) {
        long startedAt = System.currentTimeMillis();
        context.event(stage, exam.getExamId(), page.getPageId(), "started", originalRegion.region_id, 0);
        // 不污染首次定位结果，只用请求副本把“必须返回 ROI 坐标”这个意图显式传给模型。
        EraseRegion requestRegion = copyRegion(originalRegion);
        requestRegion.safety_margin = "coordinate_refinement_requested";
        LocateResponse relocated;
        try {
            relocated = vlm.relocateCoordinateRefinement(pageImage, group, requestRegion, edgeRoi.image);
        } catch (RuntimeException firstFailure) {
            // 模型偶发在严格 JSON 外输出短说明；同一张局部图仅重试一次，不能退回整页重猜。
            context.event(stage, exam.getExamId(), page.getPageId(), "retry", "same_roi_after_parse_or_transport_failure", 0);
            try {
                relocated = vlm.relocateCoordinateRefinement(pageImage, group, requestRegion, edgeRoi.image);
            } catch (RuntimeException secondFailure) {
                context.event(stage, exam.getExamId(), page.getPageId(), "failed",
                        shortError(secondFailure), 0);
                return null;
            }
        }
        context.event(stage, exam.getExamId(), page.getPageId(), "completed", relocated.status + "; "
                        + shortText(relocated.evidence),
                System.currentTimeMillis() - startedAt);
        // 局部二检只校正页码框。正文边界是首次整页 locate 基于完整版式得出的证据，
        // 不能被不含整页正文的放大 ROI 覆盖或要求其重复输出。
        if (!"safe_to_erase".equals(relocated.status) || relocated.regions.size() != locate.regions.size()) {
            context.event(stage, exam.getExamId(), page.getPageId(), "denied",
                    "unexpected_refinement_response status=" + relocated.status
                            + "; region_count=" + relocated.regions.size(), 0);
            return null;
        }
        if (locate.regions.size() > 1) {
            LocateResponse mappedLocate = copyLocateWithoutRegions(locate);
            for (int i = 0; i < locate.regions.size(); i++) {
                EraseRegion mapped = mapRefinedRegion(locate.regions.get(i), relocated.regions.get(i), edgeRoi, image);
                mappedLocate.regions.add(mapped);
            }
            mappedLocate.evidence = locate.evidence + "; coordinate_relocated=" + relocated.evidence;
            RegionValidator.ValidationResult mappedValidation = RegionValidator.validate(
                    new RegionValidator.PageLocateResult(mappedLocate.page_id, mappedLocate.status, mappedLocate.regions,
                            mappedLocate.nearest_body_boundary), image);
            context.event(stage, exam.getExamId(), page.getPageId(),
                    mappedValidation.isAccepted() ? "mapped" : "rejected",
                    "region_count=" + mappedLocate.regions.size() + "; reasons=" + mappedValidation.getReasons(), 0);
            return mappedValidation.isAccepted() ? new Refinement(mappedLocate, mappedValidation) : null;
        }
        EraseRegion localRegion = relocated.regions.get(0);
        EraseRegion refined = mapRefinedRegion(originalRegion, localRegion, edgeRoi, image);
        // 局部模型为抗锯齿/可读性通常会在朝正文一侧多给少量空白内边距。正文安全距离
        // 必须从实际目标墨迹计算：这里只删除获批框内已证明为空白的 padding，不扩框、不
        // 搜索新文字，再交给同一套像素门禁复核，因而不会降低正文保护。
        refined = RegionValidator.trimBodyFacingBlankPadding(refined, image);
        LocateResponse refinedLocate = new LocateResponse();
        refinedLocate.page_id = locate.page_id;
        refinedLocate.status = locate.status;
        refinedLocate.regions.add(refined);
        refinedLocate.nearest_body_boundary = locate.nearest_body_boundary;
        refinedLocate.evidence = locate.evidence + "; coordinate_relocated=" + relocated.evidence;
        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(refinedLocate.page_id, refinedLocate.status, refinedLocate.regions,
                        refinedLocate.nearest_body_boundary), image);
        if (!validation.isAccepted() && allowConflictingBoundaryReplacement
                && isOnlyBodyGapConflict(validation)) {
            EraseRegion trimmed = RegionValidator.trimBodyFacingBlankPadding(refined, image);
            BodyBoundary effectiveBoundary = RegionValidator.replaceConflictingBodyBoundary(originalRegion, trimmed,
                    locate.nearest_body_boundary, image);
            if (effectiveBoundary != null) {
                refined = trimmed;
                refinedLocate.regions.clear();
                refinedLocate.regions.add(refined);
                refinedLocate.nearest_body_boundary = effectiveBoundary;
                refinedLocate.evidence = refinedLocate.evidence + "; conflicting_boundary_replaced=" + effectiveBoundary.basis;
                validation = RegionValidator.validate(
                        new RegionValidator.PageLocateResult(refinedLocate.page_id, refinedLocate.status, refinedLocate.regions,
                                refinedLocate.nearest_body_boundary), image);
            }
        }
        context.event(stage, exam.getExamId(), page.getPageId(),
                validation.isAccepted() ? "mapped" : "rejected",
                "region=" + refined.x1 + "," + refined.y1 + "," + refined.x2 + "," + refined.y2
                        + "; body=" + boundaryEvidence(refinedLocate.nearest_body_boundary)
                        + "; reasons=" + validation.getReasons(), 0);
        return validation.isAccepted() ? new Refinement(refinedLocate, validation) : null;
    }

    private EraseRegion mapRefinedRegion(EraseRegion originalRegion, EraseRegion localRegion,
                                         EdgeRoi edgeRoi, BufferedImage image) {
        EraseRegion refined = copyRegion(originalRegion);
        RoiTransform.PixelRect rect = edgeRoi.transform.localRectToFullPixels(
                localRegion.x1, localRegion.y1, localRegion.x2, localRegion.y2);
        applyRoiMappingGuard(refined, rect, image.getWidth(), image.getHeight());
        refined.safety_margin = "local_vlm_coordinate_refined";
        return RegionValidator.trimBodyFacingBlankPadding(refined, image);
    }

    private String boundaryEvidence(BodyBoundary boundary) {
        return boundary == null ? "null" : boundary.x + "," + boundary.y;
    }

    private static String shortText(String value) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.substring(0, Math.min(240, compact.length()));
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

    /**
     * 以 locate 候选框为中心生成局部精修 ROI，并合并 pattern 粗窗口与正文边界证据。
     * 首轮 locate 解决“这是什么”，本方法只解决“像素框到哪里”；ROI 放大不改变坐标系。
     * 所有输入 region/window 均为整图归一化坐标，RoiTransform 负责转换为整图像素裁剪框；
     * 放大只改变送检图像，不改变坐标系，模型返回的 ROI 坐标必须随后映射回原图再校验。
     *
     * @param pageId 页面稳定标识
     * @param region 首次 locate 的整图归一化候选框
     * @param group 当前页面对应的 pattern 分组，可为空
     * @param boundary 最近正文边界
     * @param image 旋正后的原图
     * @return 包含 ROI 变换和放大图的局部请求；候选不在边缘时由调用方处理为空
     */
    private EdgeRoi candidateCenteredRoi(String pageId, EraseRegion region, PatternGroup group,
                                         BodyBoundary boundary, BufferedImage image) {
        int candidateHeight = Math.max(1, (int) Math.ceil((region.y2 - region.y1) * image.getHeight()));
        int candidateWidth = Math.max(1, (int) Math.ceil((region.x2 - region.x1) * image.getWidth()));
        int margin = Math.max(16, Math.min(160, 2 * Math.min(candidateHeight, candidateWidth)));
        RoiTransform transform = RoiTransform.fromNormalizedCandidateAndWindow(image.getWidth(), image.getHeight(), region,
                group == null ? null : group.locate_window, boundary, margin);
        // 背透文字常使整页语义定位正确、局部像素框却粘到浅灰伪墨迹。这里不做任何
        // 坐标推断，只把同一原图 ROI 变为高对比黑白证据再放大；模型仍须按文字锚点
        // 决定是否是页码行，返回坐标也仍由 transform 映射回未经修改的原图。
        BufferedImage enlarged = coordinateGrid(enlarge(inkContrast(crop(image, transform)), 3));
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
        VerifyResponse verify = verifyWithSingleRetry(exam, page, pageImage, null,
                edgeRoi(page.getPageId(), group, locate.nearest_body_boundary, normalized), context, "edge");
        if (verify == null) {
            return manual(page, original, normalized, transforms, "edge_verify_error", group, locate);
        }
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
            if (isSameLineMetadataOnly(region, locate.regions)) {
                // 同一独立页脚带内的分隔符、版权标记等没有独立“页码”语义；把它单独送给
                // verify 会必然得到 no_pagenum。只要它与主页码同基线，仍由原图像素门禁、
                // 主页码 verify 和最终 audit 共同保护，不额外新增模型调用。
                context.event("verify", exam.getExamId(), page.getPageId(), "skipped",
                        "same_line_metadata:" + region.region_id, 0);
                continue;
            }
            long startedAt = System.currentTimeMillis();
            context.event("verify", exam.getExamId(), page.getPageId(), "started", region.region_id, 0);
            VerifyResponse verify = verifyWithSingleRetry(exam, page, pageImage, region,
                    roi(page.getPageId(), region, locate.nearest_body_boundary, normalized), context, region.region_id);
            if (verify == null) {
                return manual(page, original, normalized, transforms, "verify_error", group, locate);
            }
            context.event("verify", exam.getExamId(), page.getPageId(), "completed", verify.decision,
                    System.currentTimeMillis() - startedAt);
            if (!"safe_to_erase".equals(verify.decision)) {
                if ("no_pagenum".equals(verify.decision)) {
                    return manual(page, original, normalized, transforms, "verify_target_not_found", group, locate);
                }
                return manual(page, original, normalized, transforms, deniedReason, group, locate);
            }
        }
        return new PageOutcome(page.getPageId(), "needs_recheck", "verify_safe", original, normalized, normalized,
                transforms, group, locate.regions, locate, null);
    }

    /**
     * 判断一个 region 是否只是与页码同一独立行的分隔符/元数据，而不是可单独复核的页码。
     * 仅当它声明了同行元数据、文本本身没有数字或页码标志，且存在纵向重叠的主页码 region
     * 时才跳过局部 verify；任何无法证明同基线关系的文字仍按原有严格流程复核。
     */
    private boolean isSameLineMetadataOnly(EraseRegion candidate, List<EraseRegion> regions) {
        if (candidate.same_line_metadata == null || candidate.same_line_metadata.trim().isEmpty()
                || hasPageNumberMarker(candidate.page_number_text)) {
            return false;
        }
        for (EraseRegion peer : regions) {
            if (peer == candidate || !hasPageNumberMarker(peer.page_number_text)) {
                continue;
            }
            if (candidate.y1 < peer.y2 && peer.y1 < candidate.y2) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPageNumberMarker(String text) {
        if (text == null) return false;
        String value = text.toLowerCase();
        return value.matches(".*[0-9０-９].*") || value.contains("第") || value.contains("页") || value.contains("page");
    }

    /**
     * 普通局部复核与边缘复核都只对协议/网络这类 RuntimeException 重发一次完全相同的请求。
     * 已得到的 safe/no_pagenum/manual_review 语义结论绝不重试，避免把模型的谨慎结论刷掉。
     */
    private VerifyResponse verifyWithSingleRetry(ExamInput exam, PageInput page, VlmClient.PageImage pageImage,
                                                  EraseRegion region, VlmClient.RoiImage roi, RunContext context,
                                                  String regionId) {
        try {
            return vlm.verify(pageImage, region, roi);
        } catch (RuntimeException firstFailure) {
            context.event("verify", exam.getExamId(), page.getPageId(), "retry",
                    "same_roi_after_protocol_or_transport_failure:" + regionId, 0);
            try {
                return vlm.verify(pageImage, region, roi);
            } catch (RuntimeException secondFailure) {
                context.event("verify", exam.getExamId(), page.getPageId(), "failed",
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
                                      PatternGroup group, LocateResponse locate, VlmClient.PageImage pageImage,
                                      List<RegionValidator.PixelRegion> pixelRegions, boolean coloredTargetVerified,
                                      boolean auditRetried, RunContext context) {
        // 5. 擦除执行：只接收 RegionValidator 已批准的像素框。
        BufferedImage candidate = normalized;
        List<RegionValidator.PixelRegion> erasedRegions = new ArrayList<RegionValidator.PixelRegion>();
        for (RegionValidator.PixelRegion pixelRegion : pixelRegions) {
            // 5.1 掩码擦除：InkMaskEraser 只在批准框内重建背景，不扩大目标区域。
            // 擦除器执行掩码级修改，并由像素差分门禁保证候选框外零改动。
            InkMaskEraser.EraseOutcome erase = InkMaskEraser.erase(candidate, pixelRegion, coloredTargetVerified);
            if (erase.getStatus() != InkMaskEraser.Status.SAFE_TO_ERASE) {
                // 两个 VLM region 可能在局部精修后变成同一个完整页脚行。若当前批准框已被
                // 前一个成功擦除的批准框完整包含，空掩码只表示目标已经移除，不是擦除失败。
                // 只接受严格几何包含；部分重叠、近似 IoU 或其他失败原因仍然失败关闭。
                if ("no target ink found".equals(erase.getReason())
                        && isContainedByAny(pixelRegion, erasedRegions)) {
                    context.event("erase", exam.getExamId(), page.getPageId(), "skipped",
                            "already_erased_by_containing_approved_region:" + pixelRegion.getRegionId(), 0);
                    continue;
                }
                context.event("erase", exam.getExamId(), page.getPageId(), "rejected", erase.getReason(), 0);
                return manual(page, original, normalized, transforms, "erase_failed: " + erase.getReason(), group, locate);
            }
            candidate = erase.getCandidate();
            erasedRegions.add(pixelRegion);
        }
        context.event("erase", exam.getExamId(), page.getPageId(), "completed", "region_count=" + pixelRegions.size(), 0);
        // 6. 像素完整性：擦除器内部已执行 PixelDiffGate，任何批准掩码外变化都会回退原图。
        long auditStartedAt = System.currentTimeMillis();
        context.event("audit", exam.getExamId(), page.getPageId(), "started", null, 0);
        // 7. audit 视觉审计：对原图、擦除图和局部 ROI 同时复核正文与目标。
        AuditResponse audit = vlm.audit(pageImage, new VlmClient.PageImage(page.getPageId(), candidate),
                locate.regions, auditRois(page.getPageId(), locate.regions, locate.nearest_body_boundary, normalized, candidate));
        context.event("audit", exam.getExamId(), page.getPageId(), "completed", audit.decision,
                System.currentTimeMillis() - auditStartedAt);
        // 正文不变、目标确实消失是交付的双硬条件；背景色仅是质量告警，不扩大擦除范围。
        if (!audit.body_unchanged) {
            // 7.1 正文变化是绝对失败，不允许通过重试或色差降级放行。
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized,
                    candidate, transforms, group, locate.regions, locate, audit);
        }
        if (!audit.target_removed) {
            // 7.2 仅目标残留可做一次局部坐标精修；正文变化不进入该分支。
            if (!auditRetried) {
                Refinement refinement = refineAfterAudit(exam, page, normalized, group, locate, pageImage, context);
                if (refinement != null) {
                    context.event("audit_coordinate_refine", exam.getExamId(), page.getPageId(), "accepted", "target_residual", 0);
                    return eraseAndAudit(exam, page, original, normalized, transforms, group, refinement.locate, pageImage,
                            refinement.validation.getRegions(), true, true, context);
                }
            }
            return new PageOutcome(page.getPageId(), "manual_review", "audit_target_not_removed", original, normalized,
                    candidate, transforms, group, locate.regions, locate, audit);
        }
        if (!audit.background_acceptable) {
            // 7.3 背景色只记录告警，正文安全已满足即可交付擦除图。
            return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass_with_color_warning", original, normalized,
                    candidate, transforms, group, locate.regions, locate, audit);
        }
        if (!"pass".equals(audit.decision)) {
            // 8. 失败关闭：审计未明确通过时保留擦除效果供人工核对，但状态不可交付。
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized,
                    candidate, transforms, group, locate.regions, locate, audit);
        }
        return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass", original, normalized,
                candidate, transforms, group, locate.regions, locate, audit);
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
            applyRoiMappingGuard(mapped, rect, width, height);
            full.regions.add(mapped);
        }
        return full;
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

    private List<VlmClient.RoiImage> auditRois(String pageId, List<EraseRegion> regions,
                                               BodyBoundary boundary, BufferedImage original, BufferedImage erased) {
        List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
        for (EraseRegion region : regions) {
            RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                    original.getWidth(), original.getHeight(), region, boundary, 24);
            rois.add(new VlmClient.RoiImage(pageId, region.region_id, crop(original, transform), "ORIGINAL"));
            rois.add(new VlmClient.RoiImage(pageId, region.region_id, crop(erased, transform), "ERASED"));
        }
        return rois;
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

    /** 不改变局部图：坐标始终相对未经标尺、网格或其他叠加处理的原始 ROI。 */
    private BufferedImage coordinateGrid(BufferedImage source) {
        return source;
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
                BufferedImage image = ImageIO.read(page.getImagePath().toFile());
                if (image == null) throw new IOException("ImageIO returned null");
                images.put(page.getPageId(), image);
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
