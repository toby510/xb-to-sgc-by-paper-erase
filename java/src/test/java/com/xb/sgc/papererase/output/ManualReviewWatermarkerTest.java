package com.xb.sgc.papererase.output;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ManualReviewWatermarkerTest {
    @Test
    public void formalImagesStayUnchangedAndOnlyPreviewReceivesRedMark() throws Exception {
        Path dir = Files.createTempDirectory("watermark-");
        Path original = dir.resolve("page_原图.png");
        Path erased = dir.resolve("page_擦除后.png");
        Path preview = dir.resolve("page_人工审核预览.png");
        BufferedImage image = solid(160, 120, Color.WHITE);
        ImageIO.write(image, "png", original.toFile());
        ImageIO.write(image, "png", erased.toFile());
        byte[] originalBytes = Files.readAllBytes(original);
        byte[] erasedBytes = Files.readAllBytes(erased);

        ManualReviewWatermarker.writePreview(image, preview);

        assertEquals(originalBytes.length, Files.readAllBytes(original).length);
        assertEquals(erasedBytes.length, Files.readAllBytes(erased).length);
        BufferedImage marked = ImageIO.read(preview.toFile());
        assertNotEquals("preview should contain red pixels", 0, countRed(marked));
        assertEquals("formal original remains unmarked", 0, countRed(ImageIO.read(original.toFile())));
        assertEquals("formal erased remains unmarked", 0, countRed(ImageIO.read(erased.toFile())));
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private static int countRed(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = new Color(image.getRGB(x, y));
                if (c.getRed() > 180 && c.getGreen() < 120 && c.getBlue() < 120) {
                    count++;
                }
            }
        }
        return count;
    }
}
