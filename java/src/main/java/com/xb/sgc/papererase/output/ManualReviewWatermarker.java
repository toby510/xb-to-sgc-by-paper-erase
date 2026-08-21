package com.xb.sgc.papererase.output;

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
}
