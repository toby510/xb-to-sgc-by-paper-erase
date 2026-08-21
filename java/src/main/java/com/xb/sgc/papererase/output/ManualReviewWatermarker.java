package com.xb.sgc.papererase.output;

import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ManualReviewWatermarker {
    private ManualReviewWatermarker() {
    }

    public static void writePreview(BufferedImage source, Path output) throws IOException {
        if (source == null || output == null) {
            throw new IllegalArgumentException("source and output are required");
        }
        Files.createDirectories(output.getParent());
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setColor(Color.RED);
            int fontSize = Math.max(18, Math.min(source.getWidth(), source.getHeight()) / 8);
            g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
            FontMetrics metrics = g.getFontMetrics();
            String text = "需人工审核";
            int x = Math.max(4, (source.getWidth() - metrics.stringWidth(text)) / 2);
            int y = Math.max(metrics.getAscent(), source.getHeight() / 2);
            g.drawString(text, x, y);
            g.drawLine(0, 0, source.getWidth(), source.getHeight());
            g.drawLine(source.getWidth(), 0, 0, source.getHeight());
        } finally {
            g.dispose();
        }
        ImageIO.write(copy, "png", output.toFile());
    }

    /**
     * 仅用于解释模型坐标为何未通过门禁：按 VLM 原始框做白底模拟擦除，再明确标注不可交付。
     * 它绝不参与 Word 合并，也不替代经过像素门禁的正式擦除结果。
     */
    public static void writeCoordinateErasePreview(BufferedImage source, List<EraseRegion> regions, Path output) throws IOException {
        if (source == null || regions == null || regions.isEmpty() || output == null) {
            throw new IllegalArgumentException("source, regions and output are required");
        }
        Files.createDirectories(output.getParent());
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            g.setColor(Color.WHITE);
            for (EraseRegion region : regions) {
                int x1 = Math.max(0, Math.min(source.getWidth(), (int) Math.floor(region.x1 * source.getWidth())));
                int y1 = Math.max(0, Math.min(source.getHeight(), (int) Math.floor(region.y1 * source.getHeight())));
                int x2 = Math.max(x1, Math.min(source.getWidth(), (int) Math.ceil(region.x2 * source.getWidth())));
                int y2 = Math.max(y1, Math.min(source.getHeight(), (int) Math.ceil(region.y2 * source.getHeight())));
                g.fillRect(x1, y1, x2 - x1, y2 - y1);
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setColor(Color.RED);
            int fontSize = Math.max(18, Math.min(source.getWidth(), source.getHeight()) / 11);
            g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
            String text = "模型坐标擦除预览（不可交付）";
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(text, Math.max(4, (source.getWidth() - metrics.stringWidth(text)) / 2),
                    Math.max(metrics.getAscent(), source.getHeight() / 2));
        } finally {
            g.dispose();
        }
        ImageIO.write(copy, "png", output.toFile());
    }
}
