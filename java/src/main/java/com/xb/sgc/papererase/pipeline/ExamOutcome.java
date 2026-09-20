package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 试卷处理结果对象：汇总整卷状态、逐页结果、擦除图和审计证据。
 * status/reason 是报告与人工审核入口，PageOutcome 内的原图永远是失败关闭时的回退基准。
 */
public final class ExamOutcome {
    private final String examId;
    private final String status;
    private final String reason;
    private final List<PageOutcome> pages;

    public ExamOutcome(String examId, String status, String reason, List<PageOutcome> pages) {
        this.examId = examId;
        this.status = status;
        this.reason = reason;
        this.pages = Collections.unmodifiableList(new ArrayList<PageOutcome>(pages));
    }

    public String getExamId() {
        return examId;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public List<PageOutcome> getPages() {
        return pages;
    }


    /** writeExam 落盘成功后由 Main 逐卷调用，释放本卷所有页大图，避免全量累积超堆。 */
    public void releaseImages() {
        for (PageOutcome page : pages) {
            page.releaseImages();
        }
    }

    public PageOutcome page(String pageId) {
        for (PageOutcome page : pages) {
            if (page.getPageId().equals(pageId)) {
                return page;
            }
        }
        throw new IllegalArgumentException("unknown page: " + pageId);
    }

    /** 单页终态：原图、候选/擦除图、门禁状态、定位和审计证据。 */
    public static final class ApprovedRegion {
        public final String region_id;
        public final EraseRegion original_locate_normalized_box;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final EraseRegion final_normalized_box;
        public final boolean java_expanded;
        public final boolean java_coordinate_rescued;
        public final boolean vlm_coordinate_refined;
        public final String semantic_confirmation;

        public ApprovedRegion(String regionId, EraseRegion originalLocate, int x, int y, int width, int height,
                              EraseRegion finalNormalized, boolean javaExpanded, boolean javaCoordinateRescued,
                              boolean vlmCoordinateRefined, String semanticConfirmation) {
            this.region_id = regionId;
            this.original_locate_normalized_box = originalLocate;
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.final_normalized_box = finalNormalized;
            this.java_expanded = javaExpanded;
            this.java_coordinate_rescued = javaCoordinateRescued;
            this.vlm_coordinate_refined = vlmCoordinateRefined;
            this.semantic_confirmation = semanticConfirmation;
        }
    }

    public static final class PageOutcome {
        private final String pageId;
        private final String status;
        private final String reason;
        // 非 final：writeExam 落盘后由 releaseImages() 置空，让 GC 跨卷回收大图，避免全量 100 卷累积超堆。
        private BufferedImage original;
        private BufferedImage normalized;
        private BufferedImage candidate;
        private final PageTransforms transforms;
        private final List<EraseRegion> regions;
        private final LocateResponse locate;
        private final AuditResponse audit;
        private List<ApprovedRegion> approvedRegions;
        private ModelFallback modelFallback;

        public PageOutcome(String pageId, String status, String reason, BufferedImage original, BufferedImage normalized,
                           BufferedImage candidate, PageTransforms transforms,
                           List<EraseRegion> regions, LocateResponse locate, AuditResponse audit) {
            this.pageId = pageId;
            this.status = status;
            this.reason = reason;
            this.original = original;
            this.normalized = normalized;
            this.candidate = candidate;
            this.transforms = transforms;
            this.regions = regions == null
                    ? Collections.<EraseRegion>emptyList()
                    : Collections.unmodifiableList(new ArrayList<EraseRegion>(regions));
            this.locate = locate;
            this.audit = audit;
            this.approvedRegions = Collections.emptyList();
        }

        public PageOutcome(String pageId, String status, String reason, BufferedImage original, BufferedImage normalized,
                           BufferedImage candidate, PageTransforms transforms,
                           List<EraseRegion> regions, LocateResponse locate, AuditResponse audit,
                           List<ApprovedRegion> approvedRegions) {
            this(pageId, status, reason, original, normalized, candidate, transforms, regions, locate, audit);
            this.approvedRegions = approvedRegions == null ? Collections.<ApprovedRegion>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ApprovedRegion>(approvedRegions));
        }

        public String getPageId() {
            return pageId;
        }

        public String getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        public BufferedImage getOriginal() {
            return original;
        }

        public BufferedImage getNormalized() {
            return normalized;
        }

        public BufferedImage getCandidate() {
            return candidate;
        }

        /** 落盘后释放本页大图引用；releaseImages() 之后仍可读 status/reason/regions 等元数据。 */
        public void releaseImages() {
            this.original = null;
            this.normalized = null;
            this.candidate = null;
        }

        public PageTransforms getTransforms() {
            return transforms;
        }


        public List<EraseRegion> getRegions() {
            return regions;
        }

        public LocateResponse getLocate() {
            return locate;
        }

        public AuditResponse getAudit() {
            return audit;
        }

        public List<ApprovedRegion> getApprovedRegions() { return approvedRegions; }

        /** 主模型人工审核后的独立补救链路；为空代表本页未触发模型升级。 */
        public ModelFallback getModelFallback() { return modelFallback; }

        public void setModelFallback(ModelFallback modelFallback) {
            this.modelFallback = modelFallback;
        }
    }

    /** 仅记录模型层级补救的输入/输出终态，不携带图片、提示词或任何密钥。 */
    public static final class ModelFallback {
        public final String source_model;
        public final String source_status;
        public final String source_reason;
        public final String fallback_model;
        public final String fallback_status;
        public final String fallback_reason;
        public final String final_source;

        public ModelFallback(String sourceModel, String sourceStatus, String sourceReason,
                             String fallbackModel, String fallbackStatus, String fallbackReason,
                             String finalSource) {
            this.source_model = sourceModel;
            this.source_status = sourceStatus;
            this.source_reason = sourceReason;
            this.fallback_model = fallbackModel;
            this.fallback_status = fallbackStatus;
            this.fallback_reason = fallbackReason;
            this.final_source = finalSource;
        }
    }

    /** 原图与旋正图之间的尺寸和阅读方向元数据，用于输出坐标还原。 */
    public static final class PageTransforms {
        private final int originalWidth;
        private final int originalHeight;
        private final int normalizedWidth;
        private final int normalizedHeight;
        private final int readingRotation;

        public PageTransforms(int originalWidth, int originalHeight, int normalizedWidth, int normalizedHeight,
                              int readingRotation) {
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.normalizedWidth = normalizedWidth;
            this.normalizedHeight = normalizedHeight;
            this.readingRotation = readingRotation;
        }

        public int getOriginalWidth() {
            return originalWidth;
        }

        public int getOriginalHeight() {
            return originalHeight;
        }

        public int getNormalizedWidth() {
            return normalizedWidth;
        }

        public int getNormalizedHeight() {
            return normalizedHeight;
        }

        public int getReadingRotation() {
            return readingRotation;
        }
    }
}
