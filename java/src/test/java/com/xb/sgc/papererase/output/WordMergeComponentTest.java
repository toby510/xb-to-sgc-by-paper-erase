package com.xb.sgc.papererase.output;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        component.merge(Arrays.asList(originalOne, originalTwo), originalWord, false);
        component.merge(Arrays.asList(erasedOne, manualPreviewTwo), erasedWord, false);

        assertEquals(2, mediaCount(originalWord));
        assertEquals(2, mediaCount(erasedWord));
        String documentXml = entry(erasedWord, "word/document.xml");
        assertEquals("copied legacy writer must use page anchors", 2, count(documentXml, "<wp:anchor"));
        assertEquals("legacy calibrated writer must rely on natural page flow", 0,
                count(documentXml, "<w:br w:type=\"page\""));
        assertEquals("legacy calibrated writer should not use stretching inline drawings", 0,
                count(documentXml, "<wp:inline"));
        List<String> imageTargets = imageTargets(erasedWord);
        assertEquals(Color.GREEN.getRGB(), firstPixel(erasedWord, imageTargets.get(0)));
        assertEquals(Color.RED.getRGB(), firstPixel(erasedWord, imageTargets.get(1)));
    }

    @Test
    public void addsOneHomepageQrBannerByDefaultWithoutChangingSourceImageMedia() throws Exception {
        Path dir = Files.createTempDirectory("word-qrcode-");
        Path source = png(dir.resolve("p1_原图.png"), Color.CYAN);
        Path word = dir.resolve("exam_原图.docx");

        new WordMergeComponent().merge(Arrays.asList(source), word, true, 1_728_000L);

        assertEquals("一张试卷图 + 一张首页二维码横幅", 2, mediaCount(word));
        String documentXml = entry(word, "word/document.xml");
        assertEquals("二维码与原图都是浮动对象，且仅出现在首页", 2, count(documentXml, "<wp:anchor"));
        assertEquals("二维码横幅按指定宽度等比例缩放", 1,
                count(documentXml, "<wp:extent cx=\"1728000\" cy=\"771429\""));
        assertTrue("原图媒体内容保持不变", containsFirstPixel(word, imageTargets(word), Color.CYAN.getRGB()));
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

    private static List<String> imageTargets(Path docx) throws Exception {
        String rels = entry(docx, "word/_rels/document.xml.rels");
        List<String> targets = new ArrayList<String>();
        int index = 0;
        while ((index = rels.indexOf("<Relationship", index)) >= 0) {
            int end = rels.indexOf("/>", index);
            String relationship = rels.substring(index, end);
            if (relationship.contains("/relationships/image")) {
                int targetIndex = relationship.indexOf("Target=\"") + "Target=\"".length();
                int targetEnd = relationship.indexOf('"', targetIndex);
                String target = relationship.substring(targetIndex, targetEnd);
                targets.add(target.startsWith("/") ? target.substring(1) : "word/" + target);
            }
            index = end + 2;
        }
        return targets;
    }

    private static int firstPixel(Path docx, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            try (InputStream input = zip.getInputStream(entry)) {
                return ImageIO.read(input).getRGB(0, 0);
            }
        }
    }

    private static boolean containsFirstPixel(Path docx, List<String> entryNames, int expectedPixel) throws Exception {
        for (String entryName : entryNames) {
            if (firstPixel(docx, entryName) == expectedPixel) return true;
        }
        return false;
    }

    private static int count(String source, String text) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(text, index)) >= 0) {
            count++;
            index += text.length();
        }
        return count;
    }
}
