package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExamScannerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void scanGroupsByDirectoryExamIdAndSortsPagesWithAnomalies() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "数学", "2305721916932448794", "15065_9999999999999999999_3.png");
        touch(root, "数学", "2305721916932448794", "15065_2305721916932448794_1.png");
        touch(root, "数学", "2305721916932448794", "15065_2305721916932448794_4.jpg");
        touch(root, "语文", "2316009299909402922", "777_2316009299909402922_1.png");
        touch(root, "语文", "2316009299909402922", "777_2316009299909402922_2.webp");

        List<ExamInput> exams = new ExamScanner().scan(root.toPath());

        assertEquals(2, exams.size());
        ExamInput math = exams.get(0);
        assertEquals("数学", math.getSubject());
        assertEquals("2305721916932448794", math.getExamId());
        assertEquals("15065", math.getSchoolId());
        assertTrue(math.isPageSequenceIncomplete());
        assertEquals(1, math.getAnomalies().size());
        assertTrue(math.getAnomalies().get(0).contains("9999999999999999999"));

        List<PageInput> pages = math.getPages();
        assertEquals(3, pages.size());
        assertEquals(1, pages.get(0).getPageOrder());
        assertEquals(3, pages.get(1).getPageOrder());
        assertEquals(4, pages.get(2).getPageOrder());
        assertEquals("2305721916932448794:1", pages.get(0).getPageId());
        assertTrue(pages.get(0).getImagePath().getFileName().toString().endsWith("_1.png"));

        ExamInput chinese = exams.get(1);
        assertEquals("语文", chinese.getSubject());
        assertEquals("2316009299909402922", chinese.getExamId());
        assertFalse(chinese.isPageSequenceIncomplete());
    }

    @Test
    public void scanRejectsDuplicatePageOrderForWholeExam() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_1.png");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_001.jpg");

        try {
            new ExamScanner().scan(root.toPath());
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("duplicate page_order"));
            assertTrue(expected.getMessage().contains("2305721916932448794"));
            return;
        }
        throw new AssertionError("Expected duplicate page_order to reject the exam");
    }

    @Test
    public void scanRejectsUnparseablePageOrderForWholeExam() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_last.png");

        try {
            new ExamScanner().scan(root.toPath());
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unparseable page_order"));
            assertTrue(expected.getMessage().contains("15065_2305721916932448794_last.png"));
            return;
        }
        throw new AssertionError("Expected unparseable page_order to reject the exam");
    }

    private void touch(File root, String subject, String examId, String filename) throws Exception {
        File examDir = new File(new File(root, subject), examId);
        assertTrue(examDir.mkdirs() || examDir.isDirectory());
        Files.write(new File(examDir, filename).toPath(), new byte[]{1});
    }
}
