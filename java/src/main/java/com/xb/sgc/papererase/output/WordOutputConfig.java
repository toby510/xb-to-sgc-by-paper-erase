package com.xb.sgc.papererase.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Word 合成附加能力的独立配置；仅控制二维码，不参与图片擦除、VLM 或正文版式计算。 */
public final class WordOutputConfig {
    public static final long EMU_PER_CM = 360_000L;
    public static final double MIN_QRCODE_WIDTH_CM = 4.0D;
    public static final double MAX_QRCODE_WIDTH_CM = 5.6D;
    public static final boolean DEFAULT_QRCODE_ENABLED = true;
    public static final double DEFAULT_QRCODE_WIDTH_CM = 4.8D;
    public static final double DEFAULT_QRCODE_RIGHT_INSET_CM = 1.5D;
    public static final double DEFAULT_QRCODE_TOP_INSET_CM = 0.3D;
    public static final String DEFAULT_QRCODE_SHORT_LINK = "https://t.ewt360.com/bmIbma";
    public static final String DEFAULT_QRCODE_TEXT_LINE_1 = "打开升学e网通app";
    public static final String DEFAULT_QRCODE_TEXT_LINE_2 = "扫码查看试题和解析";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean qrcodeEnabled;
    private final double qrcodeWidthCm;
    private final double qrcodeRightInsetCm;
    private final double qrcodeTopInsetCm;
    private final String qrcodeShortLink;
    private final String qrcodeTextLine1;
    private final String qrcodeTextLine2;

    private WordOutputConfig(boolean qrcodeEnabled, double qrcodeWidthCm, double qrcodeRightInsetCm,
                             double qrcodeTopInsetCm,
                             String qrcodeShortLink,
                             String qrcodeTextLine1, String qrcodeTextLine2) {
        this.qrcodeEnabled = qrcodeEnabled;
        this.qrcodeWidthCm = qrcodeWidthCm;
        this.qrcodeRightInsetCm = qrcodeRightInsetCm;
        this.qrcodeTopInsetCm = qrcodeTopInsetCm;
        this.qrcodeShortLink = qrcodeShortLink;
        this.qrcodeTextLine1 = qrcodeTextLine1;
        this.qrcodeTextLine2 = qrcodeTextLine2;
    }

    public static WordOutputConfig load(Path configPath) throws IOException {
        WordOutputConfig defaults = defaults();
        if (configPath == null || !Files.isRegularFile(configPath)) return defaults;
        JsonNode qrcode = MAPPER.readTree(configPath.toFile()).path("qrcode");
        if (qrcode.isMissingNode() || qrcode.isNull()) return defaults;
        boolean enabled = qrcode.path("enabled").isBoolean()
                ? qrcode.path("enabled").asBoolean() : defaults.qrcodeEnabled;
        double widthCm = qrcode.path("width_cm").isNumber()
                ? qrcode.path("width_cm").asDouble() : defaults.qrcodeWidthCm;
        double rightInsetCm = qrcode.path("right_inset_cm").isNumber()
                ? qrcode.path("right_inset_cm").asDouble() : defaults.qrcodeRightInsetCm;
        double topInsetCm = qrcode.path("top_inset_cm").isNumber()
                ? qrcode.path("top_inset_cm").asDouble() : defaults.qrcodeTopInsetCm;
        String shortLink = qrcode.path("short_link").isTextual()
                ? qrcode.path("short_link").asText().trim() : defaults.qrcodeShortLink;
        String textLine1 = text(qrcode, "text_line_1", defaults.qrcodeTextLine1);
        String textLine2 = text(qrcode, "text_line_2", defaults.qrcodeTextLine2);
        validate(widthCm, rightInsetCm, topInsetCm, shortLink, textLine1, textLine2);
        return new WordOutputConfig(enabled, widthCm, rightInsetCm, topInsetCm, shortLink, textLine1, textLine2);
    }

    public static WordOutputConfig defaults() {
        return new WordOutputConfig(DEFAULT_QRCODE_ENABLED, DEFAULT_QRCODE_WIDTH_CM,
                DEFAULT_QRCODE_RIGHT_INSET_CM, DEFAULT_QRCODE_TOP_INSET_CM, DEFAULT_QRCODE_SHORT_LINK, DEFAULT_QRCODE_TEXT_LINE_1,
                DEFAULT_QRCODE_TEXT_LINE_2);
    }

    public boolean isQrcodeEnabled() {
        return qrcodeEnabled;
    }

    public long getQrcodeWidthEmu() {
        return Math.round(qrcodeWidthCm * EMU_PER_CM);
    }

    public long getQrcodeRightInsetEmu() {
        return Math.round(qrcodeRightInsetCm * EMU_PER_CM);
    }

    public long getQrcodeTopInsetEmu() {
        return Math.round(qrcodeTopInsetCm * EMU_PER_CM);
    }

    public String getQrcodeShortLink() {
        return qrcodeShortLink;
    }

    public String getQrcodeTextLine1() {
        return qrcodeTextLine1;
    }

    public String getQrcodeTextLine2() {
        return qrcodeTextLine2;
    }

    public static long qrcodeWidthEmu(double widthCm) {
        validate(widthCm, DEFAULT_QRCODE_RIGHT_INSET_CM, DEFAULT_QRCODE_TOP_INSET_CM, DEFAULT_QRCODE_SHORT_LINK,
                DEFAULT_QRCODE_TEXT_LINE_1, DEFAULT_QRCODE_TEXT_LINE_2);
        return Math.round(widthCm * EMU_PER_CM);
    }

    private static String text(JsonNode qrcode, String field, String defaultValue) {
        return qrcode.path(field).isTextual() ? qrcode.path(field).asText().trim() : defaultValue;
    }

    private static void validate(double widthCm, double rightInsetCm, double topInsetCm, String shortLink,
                                 String textLine1, String textLine2) {
        if (widthCm < MIN_QRCODE_WIDTH_CM || widthCm > MAX_QRCODE_WIDTH_CM) {
            throw new IllegalArgumentException("二维码宽度必须在 " + MIN_QRCODE_WIDTH_CM + " 到 "
                    + MAX_QRCODE_WIDTH_CM + "cm 之间，以保证扫码和顶部留白");
        }
        if (shortLink == null || shortLink.isEmpty()) {
            throw new IllegalArgumentException("二维码短链不能为空");
        }
        if (rightInsetCm < 0D || rightInsetCm > 3D) {
            throw new IllegalArgumentException("二维码右边距必须在 0 到 3cm 之间");
        }
        if (topInsetCm < 0D || topInsetCm > 1D) {
            throw new IllegalArgumentException("二维码上边距必须在 0 到 1cm 之间");
        }
        if (textLine1 == null || textLine1.isEmpty() || textLine2 == null || textLine2.isEmpty()) {
            throw new IllegalArgumentException("二维码两行文案不能为空");
        }
    }
}
