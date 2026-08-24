package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExamOutcome {
    private final String examId;
    private final String status;
    private final String reason;
    private final List<PageOutcome> pages;
    private final List<PatternGroup> consensus;

    public ExamOutcome(String examId, String status, String reason, List<PageOutcome> pages, List<PatternGroup> consensus) {
        this.examId = examId;
        this.status = status;
        this.reason = reason;
        this.pages = Collections.unmodifiableList(new ArrayList<PageOutcome>(pages));
        this.consensus = Collections.unmodifiableList(new ArrayList<PatternGroup>(consensus));
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

    public List<PatternGroup> getConsensus() {
        return consensus;
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

    public static final class PageOutcome {
        private final String pageId;
        private final String status;
        private final String reason;
        // 非 final：writeExam 落盘后由 releaseImages() 置空，让 GC 跨卷回收大图，避免全量 100 卷累积超堆。
        private BufferedImage original;
        private BufferedImage normalized;
        private BufferedImage candidate;
        private final PageTransforms transforms;
        private final PatternGroup consensus;
        private final List<EraseRegion> regions;
        private final LocateResponse locate;
        private final AuditResponse audit;

        public PageOutcome(String pageId, String status, String reason, BufferedImage original, BufferedImage normalized,
                           BufferedImage candidate, PageTransforms transforms, PatternGroup consensus,
                           List<EraseRegion> regions, LocateResponse locate, AuditResponse audit) {
            this.pageId = pageId;
            this.status = status;
            this.reason = reason;
            this.original = original;
            this.normalized = normalized;
            this.candidate = candidate;
            this.transforms = transforms;
            this.consensus = consensus;
            this.regions = regions == null
                    ? Collections.<EraseRegion>emptyList()
                    : Collections.unmodifiableList(new ArrayList<EraseRegion>(regions));
            this.locate = locate;
            this.audit = audit;
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

        public PatternGroup getConsensus() {
            return consensus;
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
    }

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
