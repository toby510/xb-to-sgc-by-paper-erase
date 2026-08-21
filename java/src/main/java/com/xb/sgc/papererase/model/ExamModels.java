package com.xb.sgc.papererase.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExamModels {
    private ExamModels() {
    }

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

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class PageDirection {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public int reading_rotation;
        @JsonProperty(required = true)
        public double confidence;
    }

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
    }

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

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class EraseRegion {
        @JsonProperty(required = true)
        public String region_id;
        @JsonProperty(required = true)
        public double x1;
        @JsonProperty(required = true)
        public double y1;
        @JsonProperty(required = true)
        public double x2;
        @JsonProperty(required = true)
        public double y2;
        @JsonProperty(required = true)
        public String page_number_text;
        @JsonProperty(required = true)
        public String same_line_metadata;
        @JsonProperty(required = true)
        public boolean on_line;
        @JsonProperty(required = true)
        public double confidence;
        @JsonProperty(required = true)
        public String safety_margin;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class BodyBoundary {
        @JsonProperty(required = true)
        public Double x;
        @JsonProperty(required = true)
        public Double y;
        @JsonProperty(required = true)
        public String basis;
    }

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
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class AuditResponse {
        @JsonProperty(required = true)
        public String page_id;
        @JsonProperty(required = true)
        public String decision;
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
