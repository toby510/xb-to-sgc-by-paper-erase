package com.xb.sgc.papererase.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全链路数据契约：承载输入页面、pattern/locate/verify/audit 响应、候选框、正文边界和状态。
 * 归一化坐标用于 VLM 协议，PixelRegion 的像素坐标用于 Java 校验与擦除；两者不可混用。
 */
public final class ExamModels {
    private ExamModels() {
    }

    /** 试卷级输入：同一试卷的有序页面及扫描异常。 */
    public static final class ExamInput {
        private final String subject;
        private final String examId;
        private final String schoolId;
        private final List<PageInput> pages;
        private final boolean pageSequenceIncomplete;
        private final List<String> anomalies;

        public ExamInput(String subject, String examId, String schoolId, List<PageInput> pages,
                         boolean pageSequenceIncomplete, List<String> anomalies) {
            this.subject = subject;
            this.examId = examId;
            this.schoolId = schoolId;
            this.pages = Collections.unmodifiableList(new ArrayList<PageInput>(pages));
            this.pageSequenceIncomplete = pageSequenceIncomplete;
            this.anomalies = Collections.unmodifiableList(new ArrayList<String>(anomalies));
        }

        public String getSubject() {
            return subject;
        }

        public String getExamId() {
            return examId;
        }

        public String getSchoolId() {
            return schoolId;
        }

        public List<PageInput> getPages() {
            return pages;
        }

        public boolean isPageSequenceIncomplete() {
            return pageSequenceIncomplete;
        }

        public List<String> getAnomalies() {
            return anomalies;
        }
    }

    /** 扫描结果：可处理试卷与结构性拒绝试卷分开保存。 */
    public static final class ScanResult {
        private final List<ExamInput> exams;
        private final List<RejectedExam> rejectedExams;

        public ScanResult(List<ExamInput> exams, List<RejectedExam> rejectedExams) {
            this.exams = Collections.unmodifiableList(new ArrayList<ExamInput>(exams));
            this.rejectedExams = Collections.unmodifiableList(new ArrayList<RejectedExam>(rejectedExams));
        }

        public List<ExamInput> getExams() {
            return exams;
        }

        public List<RejectedExam> getRejectedExams() {
            return rejectedExams;
        }
    }

    /** 结构性拒绝记录：未进入 VLM 的试卷及其原因。 */
    public static final class RejectedExam {
        private final String subject;
        private final String examId;
        private final String reason;

        public RejectedExam(String subject, String examId, String reason) {
            this.subject = subject;
            this.examId = examId;
            this.reason = reason;
        }

        public String getSubject() {
            return subject;
        }

        public String getExamId() {
            return examId;
        }

        public String getReason() {
            return reason;
        }
    }

    /** 单页输入：稳定 page_id、原始顺序和原图路径。 */
    public static final class PageInput {
        private final String pageId;
        private final String examId;
        private final int pageOrder;
        private final Path imagePath;

        public PageInput(String pageId, String examId, int pageOrder, Path imagePath) {
            this.pageId = pageId;
            this.examId = examId;
            this.pageOrder = pageOrder;
            this.imagePath = imagePath;
        }

        public String getPageId() {
            return pageId;
        }

        public String getExamId() {
            return examId;
        }

        public int getPageOrder() {
            return pageOrder;
        }

        public Path getImagePath() {
            return imagePath;
        }
    }

    /** 1-pattern 返回的整卷共性与页面分类。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class PatternResponse {
        @JsonProperty(required = true)
        public List<PageDirection> page_directions = new ArrayList<PageDirection>();
        @JsonProperty(required = true)
        public List<PatternGroup> pattern_groups = new ArrayList<PatternGroup>();
        @JsonProperty(required = true)
        public List<String> heterogeneous_page_ids = new ArrayList<String>();
        @JsonProperty(required = true)
        public List<String> no_pagenum_page_ids = new ArrayList<String>();
        @JsonProperty(required = true)
        public List<String> ungrouped_page_ids = new ArrayList<String>();
    }

    /** pattern 为每页提供的阅读方向；方向不可信时禁止坐标换算。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class PageDirection {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public int reading_rotation;
        @JsonProperty(required = true)
        public double confidence;
    }

    /** 同一页码版式的共性分组及其粗定位窗口。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class PatternGroup {
        @JsonProperty(required = true)
        public String group_id;
        @JsonProperty(required = true)
        public String edge;
        @JsonProperty(required = true)
        public String alignment;
        @JsonProperty(required = true)
        public String layout_description;
        @JsonProperty(required = true)
        public List<String> page_ids = new ArrayList<String>();
        @JsonProperty(required = true)
        public double confidence;
        @JsonProperty(required = true)
        public LocateWindow locate_window;
    }

    /** pattern 给 locate 的宽松工作窗口；坐标属于 Java 旋正后的页面，不可直接擦除。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class LocateWindow {
        /** 归一化粗窗口左上角 X；仅用于裁 ROI，不是擦除框。 */
        @JsonProperty(required = true)
        public double x1;
        /** 归一化粗窗口左上角 Y；仅用于裁 ROI，不是擦除框。 */
        @JsonProperty(required = true)
        public double y1;
        /** 归一化粗窗口右下角 X；右边界按 exclusive 语义使用。 */
        @JsonProperty(required = true)
        public double x2;
        /** 归一化粗窗口右下角 Y；下边界按 exclusive 语义使用。 */
        @JsonProperty(required = true)
        public double y2;
    }

    /** 2-locate 的整页语义结果及候选擦除框。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class LocateResponse {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public String status;
        @JsonProperty(required = true)
        public List<EraseRegion> regions = new ArrayList<EraseRegion>();
        @JsonProperty(required = true)
        public BodyBoundary nearest_body_boundary;
        @JsonProperty(required = true)
        public String evidence;
    }

    /** locate 输出的整页归一化候选框；通过 RegionValidator 后才转换为像素区域。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class EraseRegion {
        @JsonProperty(required = true)
        public String region_id;
        @JsonProperty(required = true)
        /** VLM 整页归一化候选框左上角 X。 */
        public double x1;
        @JsonProperty(required = true)
        /** VLM 整页归一化候选框左上角 Y。 */
        public double y1;
        @JsonProperty(required = true)
        /** VLM 整页归一化候选框右下角 X，映射像素时为 exclusive 边界。 */
        public double x2;
        @JsonProperty(required = true)
        /** VLM 整页归一化候选框右下角 Y，映射像素时为 exclusive 边界。 */
        public double y2;
        @JsonProperty(required = true)
        public String page_number_text;
        @JsonProperty(required = true)
        public String same_line_metadata;
        @JsonProperty(required = true)
        /**
         * 页码目标是否与正文、答题横线或表格线处于同一条视觉行带。
         * true 只表示“需要更谨慎地做局部 verify”，不表示该区域一定不允许擦除。
         */
        public boolean on_line;
        @JsonProperty(required = true)
        public double confidence;
        @JsonProperty(required = true)
        public String safety_margin;
    }

    /** 最近正文边界证据；x/y 只表示对应方向的正文侧边界。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class BodyBoundary {
        @JsonProperty(required = true)
        public Double x;
        @JsonProperty(required = true)
        public Double y;
        @JsonProperty(required = true)
        public String basis;
    }

    /** 4-verify 的局部风险复核结果及可选精修框。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class VerifyResponse {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public String region_id;
        @JsonProperty(required = true)
        public String decision;
        @JsonProperty(required = true)
        public String allowed_scope;
        @JsonProperty(required = true)
        public String evidence;
        /**
         * 仅在首次定位框没有覆盖任何墨迹时由局部二检返回。坐标以本次 ROI 为 0..1
         * 坐标系；普通安全复核可以为 null，避免把局部坐标误当成整页坐标使用。
         */
        public LocalRegion refined_region;
        /** 局部二检确认的最近正文边界，坐标同样属于 ROI；无精定位时必须为 null。 */
        public BodyBoundary refined_nearest_body_boundary;
    }

    /** verify ROI 内的局部归一化框，必须经 RoiTransform 映射回整图。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class LocalRegion {
        @JsonProperty(required = true)
        /** 局部 ROI 归一化矩形左上角 X，不是整图坐标。 */
        public double x1;
        @JsonProperty(required = true)
        /** 局部 ROI 归一化矩形左上角 Y，不是整图坐标。 */
        public double y1;
        @JsonProperty(required = true)
        /** 局部 ROI 归一化矩形右下角 X，不是整图坐标。 */
        public double x2;
        @JsonProperty(required = true)
        /** 局部 ROI 归一化矩形右下角 Y，不是整图坐标。 */
        public double y2;
    }

    /** 7-audit 的最终审计结论：原始目标非正文、正文未变和目标移除均为硬条件。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class AuditResponse {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public String decision;
        /** ORIGINAL 中获批擦除目标确认为非正文页码/同行元数据；不确定也必须为 false。 */
        @JsonProperty(required = true)
        public boolean original_target_is_non_body;
        @JsonProperty(required = true)
        public boolean body_unchanged;
        @JsonProperty(required = true)
        public boolean target_removed;
        @JsonProperty(required = true)
        public boolean background_acceptable;
        @JsonProperty(required = true)
        public String evidence;
    }
}
