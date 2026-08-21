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
}
