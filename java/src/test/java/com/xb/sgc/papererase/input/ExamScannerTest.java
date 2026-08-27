package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.RejectedExam;
import com.xb.sgc.papererase.model.ExamModels.ScanResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.awt.image.BufferedImage;
import java.awt.Color;
import javax.imageio.ImageIO;
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
    public void scanWithRejectionsRejectsDuplicatePageOrderForWholeExam() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_1.png");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_001.jpg");

        ScanResult result = new ExamScanner().scanWithRejections(root.toPath());

        assertEquals(0, result.getExams().size());
        assertEquals(1, result.getRejectedExams().size());
        RejectedExam rejected = result.getRejectedExams().get(0);
        assertEquals("英语", rejected.getSubject());
        assertEquals("2305721916932448794", rejected.getExamId());
        assertTrue(rejected.getReason().contains("duplicate page_order"));
    }

    @Test
    public void scanWithRejectionsRejectsUnparseablePageOrderForWholeExam() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "2305721916932448794", "15065_2305721916932448794_last.png");

        ScanResult result = new ExamScanner().scanWithRejections(root.toPath());

        assertEquals(0, result.getExams().size());
        assertEquals(1, result.getRejectedExams().size());
        RejectedExam rejected = result.getRejectedExams().get(0);
        assertEquals("英语", rejected.getSubject());
        assertEquals("2305721916932448794", rejected.getExamId());
        assertTrue(rejected.getReason().contains("unparseable page_order"));
        assertTrue(rejected.getReason().contains("15065_2305721916932448794_last.png"));
    }

    @Test
    public void scanWithRejectionsKeepsGoodExamWhenAnotherExamIsRejected() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "bad-exam", "15065_bad-exam_1.png");
        touch(root, "英语", "bad-exam", "15065_bad-exam_001.jpg");
        touch(root, "数学", "good-exam", "15066_good-exam_1.png");

        ScanResult result = new ExamScanner().scanWithRejections(root.toPath());

        assertEquals(1, result.getExams().size());
        assertEquals("数学", result.getExams().get(0).getSubject());
        assertEquals("good-exam", result.getExams().get(0).getExamId());
        assertEquals(1, result.getRejectedExams().size());
        assertEquals("英语", result.getRejectedExams().get(0).getSubject());
        assertEquals("bad-exam", result.getRejectedExams().get(0).getExamId());
        assertTrue(result.getRejectedExams().get(0).getReason().contains("duplicate page_order"));
    }

    @Test
    public void scanWithRejectionsRejectsOnlyExamWhoseImageCannotBeDecoded() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        corrupt(root, "英语", "bad-image", "15065_bad-image_1.png");
        touch(root, "数学", "good-image", "15066_good-image_1.png");

        ScanResult result = new ExamScanner().scanWithRejections(root.toPath());

        assertEquals(1, result.getExams().size());
        assertEquals("good-image", result.getExams().get(0).getExamId());
        assertEquals(1, result.getRejectedExams().size());
        assertTrue(result.getRejectedExams().get(0).getReason().contains("cannot decode image"));
    }

    @Test
    public void scanFailsClosedWhenAnyExamIsRejected() throws Exception {
        File root = temporaryFolder.newFolder("dataset");
        touch(root, "英语", "bad-exam", "15065_bad-exam_1.png");
        touch(root, "英语", "bad-exam", "15065_bad-exam_001.jpg");
        touch(root, "数学", "good-exam", "15066_good-exam_1.png");

        try {
            new ExamScanner().scan(root.toPath());
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("rejected exams: 1"));
            assertTrue(expected.getMessage().contains("英语"));
            assertTrue(expected.getMessage().contains("bad-exam"));
            assertTrue(expected.getMessage().contains("duplicate page_order"));
            return;
        }
        throw new AssertionError("Expected scan(Path) to fail closed when any exam is rejected");
    }

    private void touch(File root, String subject, String examId, String filename) throws Exception {
        File examDir = new File(new File(root, subject), examId);
        assertTrue(examDir.mkdirs() || examDir.isDirectory());
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        ImageIO.write(image, "png", new File(examDir, filename));
    }

    private void corrupt(File root, String subject, String examId, String filename) throws Exception {
        File examDir = new File(new File(root, subject), examId);
        assertTrue(examDir.mkdirs() || examDir.isDirectory());
        Files.write(new File(examDir, filename).toPath(), new byte[]{1});
    }
}
