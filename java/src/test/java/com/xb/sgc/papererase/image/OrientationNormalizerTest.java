package com.xb.sgc.papererase.image;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class OrientationNormalizerTest {
    @Test
    public void normalizeRotatesRightAnglesWithoutLosingPixels() {
        BufferedImage image = makeImage();

        assertNormalized(OrientationNormalizer.normalize(image, 0), 3, 2,
                new int[]{red(), green(), blue(), yellow(), cyan(), magenta()});
        // 90 = 顺时针：原图顶行 R G B 变成旋正图最右列（自上而下）。
        assertNormalized(OrientationNormalizer.normalize(image, 90), 2, 3,
                new int[]{yellow(), red(), cyan(), green(), magenta(), blue()});
        assertNormalized(OrientationNormalizer.normalize(image, 180), 3, 2,
                new int[]{magenta(), cyan(), yellow(), blue(), green(), red()});
        // 270 = 逆时针：原图顶行 R G B 变成旋正图最左列（自下而上）。
        assertNormalized(OrientationNormalizer.normalize(image, 270), 2, 3,
                new int[]{blue(), magenta(), green(), cyan(), red(), yellow()});
    }

    @Test
    public void normalizeRejectsNonRightAngleRotation() {
        try {
            OrientationNormalizer.normalize(makeImage(), 45);
        } catch (IllegalArgumentException expected) {
            assertEquals("readingRotation must be one of 0, 90, 180, 270", expected.getMessage());
            return;
        }
        throw new AssertionError("Expected non-right-angle rotation to fail closed");
    }

    @Test
    public void normalizedImageDoesNotExposeMutableInternalImage() {
        BufferedImage source = makeImage();
        OrientationNormalizer.NormalizedImage normalized = OrientationNormalizer.normalize(source, 0);

        source.setRGB(0, 0, magenta());
        assertEquals(red(), normalized.getImage().getRGB(0, 0));

        BufferedImage firstRead = normalized.getImage();
        firstRead.setRGB(0, 0, magenta());

        BufferedImage secondRead = normalized.getImage();
        assertEquals(red(), secondRead.getRGB(0, 0));
        assertNotEquals(firstRead.getRGB(0, 0), secondRead.getRGB(0, 0));
    }

    private void assertNormalized(OrientationNormalizer.NormalizedImage normalized,
                                  int width, int height, int[] expectedPixels) {
        assertEquals(width, normalized.getImage().getWidth());
        assertEquals(height, normalized.getImage().getHeight());
        assertEquals(3, normalized.getOriginalWidth());
        assertEquals(2, normalized.getOriginalHeight());
        assertEquals(width, normalized.getNormalizedWidth());
        assertEquals(height, normalized.getNormalizedHeight());

        int[] actualPixels = pixels(normalized.getImage());
        assertArrayEquals(expectedPixels, actualPixels);

        int[] actualSorted = actualPixels.clone();
        Arrays.sort(actualSorted);
        int[] sourceSorted = pixels(makeImage());
        Arrays.sort(sourceSorted);
        assertArrayEquals(sourceSorted, actualSorted);
    }

    private int[] pixels(BufferedImage image) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        int index = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[index++] = image.getRGB(x, y);
            }
        }
        return pixels;
    }

    private BufferedImage makeImage() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, red());
        image.setRGB(1, 0, green());
        image.setRGB(2, 0, blue());
        image.setRGB(0, 1, yellow());
        image.setRGB(1, 1, cyan());
        image.setRGB(2, 1, magenta());
        return image;
    }

    private int red() {
        return 0xFFFF0000;
    }

    private int green() {
        return 0xFF00FF00;
    }

    private int blue() {
        return 0xFF0000FF;
    }

    private int yellow() {
        return 0xFFFFFF00;
    }

    private int cyan() {
        return 0xFF00FFFF;
    }

    private int magenta() {
        return 0xFFFF00FF;
    }
}
