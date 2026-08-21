package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GateDatasetSelector {
    public List<ExamInput> select(Path badRoot, Path fullRoot) throws IOException {
        Set<String> badExamIds = collectExamIds(badRoot);
        ScanResult full = new ExamScanner().scanWithRejections(fullRoot);
        for (com.xb.sgc.papererase.model.ExamModels.RejectedExam rejected : full.getRejectedExams()) {
            if (badExamIds.contains(rejected.getExamId())) {
                throw new IllegalStateException("selected full exam rejected: "
                        + rejected.getSubject() + "/" + rejected.getExamId() + ": " + rejected.getReason());
            }
        }
        Map<String, ExamInput> byExamId = new LinkedHashMap<String, ExamInput>();
        for (ExamInput exam : full.getExams()) {
            if (badExamIds.contains(exam.getExamId()) && !byExamId.containsKey(exam.getExamId())) {
                byExamId.put(exam.getExamId(), exam);
            }
        }
        if (byExamId.size() != badExamIds.size()) {
            Set<String> missing = new HashSet<String>(badExamIds);
            missing.removeAll(byExamId.keySet());
            throw new IllegalStateException("missing full exams for bad exam ids: " + missing);
        }
        List<ExamInput> selected = new ArrayList<ExamInput>(byExamId.values());
        Collections.sort(selected, new Comparator<ExamInput>() {
            @Override
            public int compare(ExamInput left, ExamInput right) {
                return left.getExamId().compareTo(right.getExamId());
            }
        });
        return selected;
    }

    private Set<String> collectExamIds(Path root) throws IOException {
        Set<String> ids = new HashSet<String>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (containsOutputSegment(root.relativize(file))) {
                    continue;
                }
                Path parent = file.getParent();
                if (parent != null && Files.isRegularFile(file) && isImage(file)) {
                    ids.add(parent.getFileName().toString());
                }
            }
        }
        return ids;
    }

    private static boolean isImage(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private static boolean containsOutputSegment(Path path) {
        for (Path segment : path) {
            if (segment.getFileName().toString().contains("output")) {
                return true;
            }
        }
        return false;
    }
}
