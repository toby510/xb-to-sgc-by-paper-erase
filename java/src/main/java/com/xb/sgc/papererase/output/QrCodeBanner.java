package com.xb.sgc.papererase.output;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Word 首页二维码横幅。
 *
 * <p>二维码的业务内容始终由短链生成，横幅只是展示载体，绝不修改或重新编码试卷图片。</p>
 */
final class QrCodeBanner {
    static final int WIDTH_PX = 560;
    static final int HEIGHT_PX = 250;
    static final int QR_SIZE_PX = 210;

    private QrCodeBanner() {
    }

    static Path createTemporaryPng(String shortLink, String textLine1, String textLine2) throws IOException, WriterException {
        if (shortLink == null || shortLink.trim().isEmpty()) {
            throw new IllegalArgumentException("二维码短链不能为空");
        }
        BufferedImage banner = new BufferedImage(WIDTH_PX, HEIGHT_PX, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = banner.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH_PX, HEIGHT_PX);
            graphics.setColor(new Color(80, 80, 80));
            graphics.drawRoundRect(1, 1, WIDTH_PX - 3, HEIGHT_PX - 3, 5, 5);

            BufferedImage code = createQrCode(shortLink.trim());
            graphics.drawImage(code, 20, 20, null);
            graphics.setColor(new Color(40, 40, 40));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
            graphics.drawString(textLine1, 255, 102);
            graphics.drawString(textLine2, 255, 165);
        } finally {
            graphics.dispose();
        }
        Path temporary = Files.createTempFile("paper-erase-qrcode-", ".png");
        ImageIO.write(banner, "png", temporary.toFile());
        return temporary;
    }

    private static BufferedImage createQrCode(String shortLink) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, Integer.valueOf(1));
        BitMatrix matrix = new QRCodeWriter().encode(shortLink, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints);
        BufferedImage image = new BufferedImage(QR_SIZE_PX, QR_SIZE_PX, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < QR_SIZE_PX; y++) {
            for (int x = 0; x < QR_SIZE_PX; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
