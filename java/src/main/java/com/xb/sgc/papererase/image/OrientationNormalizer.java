package com.xb.sgc.papererase.image;

import java.awt.image.BufferedImage;

public final class OrientationNormalizer {
    private OrientationNormalizer() {
    }

    /**
     * 按 pattern 识别的阅读方向旋正图片，使后续所有边缘、正文和坐标规则统一在正常阅读方向。
     * 旋转 90/270 度时输出宽高互换；像素坐标通过下方映射公式逐点搬运，不做缩放。
     *
     * @param source 原始扫描图
     * @param readingRotation 顺时针阅读旋转角度，仅支持 0/90/180/270
     * @return 旋正图及原始/旋正尺寸、旋转角度元数据
     */
    public static NormalizedImage normalize(BufferedImage source, int readingRotation) {
        if (source == null) {
            throw new IllegalArgumentException("source image is required");
        }
        if (readingRotation != 0 && readingRotation != 90 && readingRotation != 180 && readingRotation != 270) {
            throw new IllegalArgumentException("readingRotation must be one of 0, 90, 180, 270");
        }

        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage normalized = new BufferedImage(
                readingRotation == 90 || readingRotation == 270 ? height : width,
                readingRotation == 90 || readingRotation == 270 ? width : height,
                source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : source.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                if (readingRotation == 0) {
                    targetX = x;
                    targetY = y;
                } else if (readingRotation == 90) {
                    // 原图 (x,y) → 旋正图 (y, width-1-x)，宽高互换。
                    targetX = y;
                    targetY = width - 1 - x;
                } else if (readingRotation == 180) {
                    // 原图 (x,y) → 旋正图 (width-1-x, height-1-y)。
                    targetX = width - 1 - x;
                    targetY = height - 1 - y;
                } else {
                    // 原图 (x,y) → 旋正图 (height-1-y, x)，宽高互换。
                    targetX = height - 1 - y;
                    targetY = x;
                }
                normalized.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }

        return new NormalizedImage(copyOf(normalized), width, height, readingRotation);
    }

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(),
                source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : source.getType());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return copy;
    }

    public static final class NormalizedImage {
        private final BufferedImage image;
        private final int originalWidth;
        private final int originalHeight;
        private final int readingRotation;

        private NormalizedImage(BufferedImage image, int originalWidth, int originalHeight, int readingRotation) {
            this.image = copyOf(image);
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.readingRotation = readingRotation;
        }

        public BufferedImage getImage() {
            return copyOf(image);
        }

        public int getOriginalWidth() {
            return originalWidth;
        }

        public int getOriginalHeight() {
            return originalHeight;
        }

        public int getNormalizedWidth() {
            return image.getWidth();
        }

        public int getNormalizedHeight() {
            return image.getHeight();
        }

        public int getReadingRotation() {
            return readingRotation;
        }
    }
}
