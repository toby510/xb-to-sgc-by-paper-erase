package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.PageInput;

import java.util.ArrayList;
import java.util.List;

public final class PageBatcher {
    private PageBatcher() {
    }

    public static List<List<PageInput>> overlapping(List<PageInput> pages, int max, int overlap) {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        if (overlap < 0 || overlap >= max) {
            throw new IllegalArgumentException("overlap must be >= 0 and < max");
        }
        List<List<PageInput>> batches = new ArrayList<List<PageInput>>();
        if (pages.isEmpty()) {
            return batches;
        }
        int start = 0;
        while (start < pages.size()) {
            int end = Math.min(start + max, pages.size());
            batches.add(new ArrayList<PageInput>(pages.subList(start, end)));
            if (end == pages.size()) {
                break;
            }
            start = end - overlap;
        }
        return batches;
    }

    /**
     * 从有序试卷中均匀选取代表页。首尾页始终被覆盖；0 表示全量，防止配置切换时改变页序。
     */
    public static List<PageInput> representative(List<PageInput> pages, int maxPages) {
        if (pages == null) {
            throw new IllegalArgumentException("pages are required");
        }
        if (maxPages < 0) {
            throw new IllegalArgumentException("maxPages must be >= 0");
        }
        if (maxPages == 0 || pages.size() <= maxPages) {
            return new ArrayList<PageInput>(pages);
        }
        List<PageInput> selected = new ArrayList<PageInput>();
        for (int i = 0; i < maxPages; i++) {
            int index = (int) Math.round(i * (pages.size() - 1D) / (maxPages - 1D));
            selected.add(pages.get(index));
        }
        return selected;
    }
}
