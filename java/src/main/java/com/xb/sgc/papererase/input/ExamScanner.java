package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.RejectedExam;
import com.xb.sgc.papererase.model.ExamModels.ScanResult;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 输入扫描器：按“学科/试卷ID/图片序号”读取目录，校验命名、顺序和缺页，并聚合为 ExamInput。
 * 结构性错误在进入 VLM 前拒绝，避免页面错配后产生不可追溯坐标。
 */
public class ExamScanner {
    public List<ExamInput> scan(Path root) throws IOException {
        ScanResult result = scanWithRejections(root);
        if (!result.getRejectedExams().isEmpty()) {
            RejectedExam first = result.getRejectedExams().get(0);
            throw new IllegalStateException("rejected exams: " + result.getRejectedExams().size()
                    + "; first=" + first.getSubject() + "/" + first.getExamId() + ": " + first.getReason());
        }
        return result.getExams();
    }

    public ScanResult scanWithRejections(Path root) throws IOException {
        List<ExamInput> exams = new ArrayList<ExamInput>();
        List<RejectedExam> rejectedExams = new ArrayList<RejectedExam>();
        try (DirectoryStream<Path> subjects = Files.newDirectoryStream(root)) {
            for (Path subjectDir : subjects) {
                if (!Files.isDirectory(subjectDir)) {
                    continue;
                }
                if (containsOutputSegment(subjectDir)) {
                    continue;
                }
                String subject = subjectDir.getFileName().toString();
                try (DirectoryStream<Path> examDirs = Files.newDirectoryStream(subjectDir)) {
                    for (Path examDir : examDirs) {
                        if (containsOutputSegment(examDir)) {
                            continue;
                        }
                        if (Files.isDirectory(examDir)) {
                            String examId = examDir.getFileName().toString();
                            try {
                                exams.add(scanExam(subject, examDir));
                            } catch (IllegalArgumentException ex) {
                                rejectedExams.add(new RejectedExam(subject, examId, ex.getMessage()));
                            }
                        }
                    }
                }
            }
        }
        Collections.sort(exams, new Comparator<ExamInput>() {
            @Override
            public int compare(ExamInput left, ExamInput right) {
                int byExam = left.getExamId().compareTo(right.getExamId());
                if (byExam != 0) {
                    return byExam;
                }
                return left.getSubject().compareTo(right.getSubject());
            }
        });
        Collections.sort(rejectedExams, new Comparator<RejectedExam>() {
            @Override
            public int compare(RejectedExam left, RejectedExam right) {
                int byExam = left.getExamId().compareTo(right.getExamId());
                if (byExam != 0) {
                    return byExam;
                }
                return left.getSubject().compareTo(right.getSubject());
            }
        });
        return new ScanResult(exams, rejectedExams);
    }

    private ExamInput scanExam(String subject, Path examDir) throws IOException {
        String examId = examDir.getFileName().toString();
        List<String> anomalies = new ArrayList<String>();
        List<PageInput> pages = new ArrayList<PageInput>();
        Map<Integer, Path> seenOrders = new HashMap<Integer, Path>();
        String schoolId = null;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(examDir)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file) || !isImage(file)) {
                    continue;
                }
                ParsedName parsed = parse(file.getFileName().toString(), examId);
                if (schoolId == null) {
                    schoolId = parsed.schoolId;
                }
                if (!examId.equals(parsed.filenameExamId)) {
                    anomalies.add("filename exam_id " + parsed.filenameExamId + " differs from directory exam_id " + examId
                            + ": " + file.getFileName());
                }
                Path previous = seenOrders.put(parsed.pageOrder, file);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate page_order " + parsed.pageOrder + " in exam " + examId);
                }
                pages.add(new PageInput(examId + ":" + parsed.pageOrder, examId, parsed.pageOrder, file));
            }
        }

        Collections.sort(pages, new Comparator<PageInput>() {
            @Override
            public int compare(PageInput left, PageInput right) {
                return Integer.compare(left.getPageOrder(), right.getPageOrder());
            }
        });

        boolean incomplete = hasMissingPageOrder(pages);
        if (schoolId == null) {
            schoolId = "";
        }
        return new ExamInput(subject, examId, schoolId, pages, incomplete, anomalies);
    }

    private ParsedName parse(String filename, String examId) {
        String basename = filename;
        int dot = basename.lastIndexOf('.');
        if (dot >= 0) {
            basename = basename.substring(0, dot);
        }
        String[] parts = basename.split("_");
        if (parts.length < 3) {
            throw new IllegalArgumentException("unparseable page_order in exam " + examId + ": " + filename);
        }
        String pagePart = parts[parts.length - 1];
        if (!pagePart.matches("[0-9]+") || Integer.parseInt(pagePart) <= 0) {
            throw new IllegalArgumentException("unparseable page_order in exam " + examId + ": " + filename);
        }
        String schoolId = parts[0];
        String filenameExamId = parts[parts.length - 2];
        return new ParsedName(schoolId, filenameExamId, Integer.parseInt(pagePart));
    }

    private boolean hasMissingPageOrder(List<PageInput> pages) {
        Set<Integer> seen = new HashSet<Integer>();
        int max = 0;
        for (PageInput page : pages) {
            seen.add(page.getPageOrder());
            max = Math.max(max, page.getPageOrder());
        }
        for (int pageOrder = 1; pageOrder <= max; pageOrder++) {
            if (!seen.contains(pageOrder)) {
                return true;
            }
        }
        return false;
    }

    private boolean isImage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private boolean containsOutputSegment(Path path) {
        for (Path segment : path) {
            if (segment.getFileName().toString().contains("output")) {
                return true;
            }
        }
        return false;
    }

    private static final class ParsedName {
        private final String schoolId;
        private final String filenameExamId;
        private final int pageOrder;

        private ParsedName(String schoolId, String filenameExamId, int pageOrder) {
            this.schoolId = schoolId;
            this.filenameExamId = filenameExamId;
            this.pageOrder = pageOrder;
        }
    }
}
