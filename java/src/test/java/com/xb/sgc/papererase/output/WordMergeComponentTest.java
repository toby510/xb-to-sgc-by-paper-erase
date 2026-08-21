package com.xb.sgc.papererase.output;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WordMergeComponentTest {
    @Test
    public void writesSamePageCountAndUsesManualPreviewForErasedWord() throws Exception {
        Path dir = Files.createTempDirectory("word-");
        Path originalOne = png(dir.resolve("p1_原图.png"), Color.WHITE);
        Path originalTwo = png(dir.resolve("p2_原图.png"), Color.YELLOW);
        Path erasedOne = png(dir.resolve("p1_擦除后.png"), Color.GREEN);
        Path manualPreviewTwo = png(dir.resolve("p2_人工审核预览.png"), Color.RED);
        Path originalWord = dir.resolve("exam_原图.docx");
        Path erasedWord = dir.resolve("exam_擦除后.docx");

        WordMergeComponent component = new WordMergeComponent();
        component.merge(Arrays.asList(originalOne, originalTwo), originalWord);
        component.merge(Arrays.asList(erasedOne, manualPreviewTwo), erasedWord);

        assertEquals(2, mediaCount(originalWord));
        assertEquals(2, mediaCount(erasedWord));
        String documentXml = entry(erasedWord, "word/document.xml");
        assertTrue(documentXml.indexOf("p1_擦除后.png") < documentXml.indexOf("p2_人工审核预览.png"));
    }

    private static Path png(Path path, Color color) throws Exception {
        BufferedImage image = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static int mediaCount(Path docx) throws Exception {
        int count = 0;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("word/media/image")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String entry(Path docx, String name) throws Exception {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = new byte[(int) entry.getSize()];
                int read = input.read(bytes);
                return new String(bytes, 0, read, StandardCharsets.UTF_8);
            }
        }
    }
}
