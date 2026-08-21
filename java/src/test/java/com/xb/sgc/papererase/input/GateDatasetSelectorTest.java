package com.xb.sgc.papererase.input;

import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GateDatasetSelectorTest {
    @Test
    public void mapsBadExamIdsAcrossSubjectsAndExcludesOutputDirectories() throws Exception {
        Path bad = Files.createTempDirectory("bad-root-");
        Path full = Files.createTempDirectory("full-root-");
        png(bad.resolve("数学/2316009299909402922/15065_2316009299909402922_1.png"));
        png(full.resolve("语文/2316009299909402922/15065_2316009299909402922_1.png"));
        png(full.resolve("语文/2316009299909402922/15065_2316009299909402922_2.png"));
        png(full.resolve("英语/888/bad-name.png"));
        png(full.resolve("xb-to-sgc-by-paper-erase-output/exam-page-only/runs/old/数学/999/15065_999_1.png"));

        List<ExamInput> selected = new GateDatasetSelector().select(bad, full);

        assertEquals(1, selected.size());
        assertEquals("语文", selected.get(0).getSubject());
        assertEquals("2316009299909402922", selected.get(0).getExamId());
        assertEquals(2, selected.get(0).getPages().size());
    }

    private static void png(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
