package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.PageInput;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PageBatcherTest {
    @Test
    public void overlappingBuildsMaxEightBatchesWithOnePageOverlap() {
        List<PageInput> pages = new ArrayList<PageInput>();
        for (int pageOrder = 1; pageOrder <= 18; pageOrder++) {
            pages.add(new PageInput("exam-1:" + pageOrder, "exam-1", pageOrder, Paths.get(pageOrder + ".png")));
        }

        List<List<PageInput>> batches = PageBatcher.overlapping(pages, 8, 1);

        assertEquals(3, batches.size());
        assertOrders(batches.get(0), 1, 8);
        assertOrders(batches.get(1), 8, 15);
        assertOrders(batches.get(2), 15, 18);
    }

    private void assertOrders(List<PageInput> batch, int first, int last) {
        assertEquals(first, batch.get(0).getPageOrder());
        assertEquals(last, batch.get(batch.size() - 1).getPageOrder());
        assertEquals(last - first + 1, batch.size());
    }
}
