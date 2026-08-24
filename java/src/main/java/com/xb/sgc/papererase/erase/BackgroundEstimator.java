package com.xb.sgc.papererase.erase;

import com.xb.sgc.papererase.safety.RegionValidator;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BackgroundEstimator {
    private static final int MIN_SAMPLES = 40;
    private static final double MAX_FIT_RESIDUAL = 7.0;

    private BackgroundEstimator() {
    }

    public static Estimate estimate(BufferedImage source, RegionValidator.PixelRegion region, boolean[][] mask) {
        /*
         * 用批准框内的非目标像素拟合背景平面：跳过掩码目标和过暗像素，分别拟合 RGB 通道
         * 的局部渐变。彩色非目标、样本不足或拟合残差过大都拒绝，调用方随后可在批准掩码
         * 内降级纯白；背景估计失败绝不能扩大擦除区域或放宽正文安全门禁。
         */
        List<Integer> alphas = new ArrayList<Integer>();
        List<Sample> samples = new ArrayList<Sample>();

        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                ColorParts c = parts(argb);
                if (mask[y][x]) {
                    continue;
                }
                if (isColoredNonTarget(c)) {
                    return Estimate.rejected("colored non-target inside region");
                }
                if (c.luminance < 205) {
                    continue;
                }
                alphas.add(c.alpha);
                samples.add(new Sample(x, y, c));
            }
        }

        if (samples.size() < MIN_SAMPLES) {
            return Estimate.rejected("insufficient background samples");
        }
        Plane red = fit(samples, Channel.RED);
        Plane green = fit(samples, Channel.GREEN);
        Plane blue = fit(samples, Channel.BLUE);
        if (red == null || green == null || blue == null) {
            return Estimate.rejected("background fit failed");
        }
        double residual = (red.residual + green.residual + blue.residual) / 3.0;
        if (residual > MAX_FIT_RESIDUAL) {
            return Estimate.rejected("background fit residual too high");
        }
        int alpha = median(alphas);

        return Estimate.accepted(alpha, red, green, blue);
    }

    /**
     * 已通过视觉与像素门禁的页码框需要整框重建，才能清掉灰色抗锯齿残影。背景样本只能
     * 来自框外的空白环，绝不能把框内页码残笔当作纸张颜色；样本不足时由调用方降级纯白。
     */
    /**
     * 使用候选框外侧 3px 环带估计纸张背景，避免把框内页码残墨当成背景样本。
     *
     * @param source 原图
     * @param region 已批准候选框
     * @return 拟合成功的背景模型；样本不足、彩色非目标或残差过大时返回拒绝结果
     */
    public static Estimate estimateFromOuterRing(BufferedImage source, RegionValidator.PixelRegion region) {
        List<Integer> alphas = new ArrayList<Integer>();
        List<Sample> samples = new ArrayList<Sample>();
        int ring = 3;
        int left = Math.max(0, region.getX() - ring);
        int top = Math.max(0, region.getY() - ring);
        int right = Math.min(source.getWidth(), region.getX() + region.getWidth() + ring);
        int bottom = Math.min(source.getHeight(), region.getY() + region.getHeight() + ring);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (x >= region.getX() && x < region.getX() + region.getWidth()
                        && y >= region.getY() && y < region.getY() + region.getHeight()) {
                    continue;
                }
                ColorParts c = parts(source.getRGB(x, y));
                if (isColoredNonTarget(c)) {
                    return Estimate.rejected("colored non-target in outer ring");
                }
                if (c.luminance < 205) {
                    continue;
                }
                alphas.add(c.alpha);
                samples.add(new Sample(x, y, c));
            }
        }
        if (samples.size() < MIN_SAMPLES) {
            return Estimate.rejected("insufficient outer-ring background samples");
        }
        Plane red = fit(samples, Channel.RED);
        Plane green = fit(samples, Channel.GREEN);
        Plane blue = fit(samples, Channel.BLUE);
        if (red == null || green == null || blue == null) {
            return Estimate.rejected("outer-ring background fit failed");
        }
        double residual = (red.residual + green.residual + blue.residual) / 3.0;
        if (residual > MAX_FIT_RESIDUAL) {
            return Estimate.rejected("outer-ring background fit residual too high");
        }
        return Estimate.accepted(median(alphas), red, green, blue);
    }

    /** 擦除掩码的统一像素契约；校验器必须用同一规则证明批准框边缘无可擦墨迹。 */
    public static boolean isErasableInk(int argb, int backgroundLum, boolean coloredTargetVerified) {
        ColorParts c = parts(argb);
        return isInk(c, backgroundLum)
                || (coloredTargetVerified && isColoredNonTarget(c) && c.luminance <= backgroundLum - 25);
    }

    /** 供安全边界检测使用：彩色印刷内容也不能被误判为纸张空白。 */
    public static boolean isColoredMark(int argb) {
        return isColoredNonTarget(parts(argb));
    }

    /** 与擦除掩码一致的局部背景亮度估计，避免两层采用不同阈值。 */
    public static int medianLightLuminance(BufferedImage source, RegionValidator.PixelRegion region) {
        int[] counts = new int[256];
        int total = 0;
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                ColorParts c = parts(source.getRGB(x, y));
                if (c.luminance >= 180 && !isColoredNonTarget(c)) {
                    counts[c.luminance]++;
                    total++;
                }
            }
        }
        if (total == 0) {
            return 255;
        }
        int seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen > total / 2) {
                return i;
            }
        }
        return 255;
    }

    static boolean isInk(ColorParts c, int backgroundLum) {
        return c.luminance <= backgroundLum - 25 && c.red <= 230 && c.green <= 230 && c.blue <= 230;
    }

    static boolean isColoredNonTarget(ColorParts c) {
        int max = Math.max(c.red, Math.max(c.green, c.blue));
        int min = Math.min(c.red, Math.min(c.green, c.blue));
        return max - min > 60 && c.luminance < 235;
    }

    static boolean isGrayRule(ColorParts c) {
        int max = Math.max(c.red, Math.max(c.green, c.blue));
        int min = Math.min(c.red, Math.min(c.green, c.blue));
        return max - min <= 12 && c.luminance >= 155 && c.luminance < 205;
    }

    static ColorParts parts(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return new ColorParts(alpha, red, green, blue);
    }

    private static Plane fit(List<Sample> samples, Channel channel) {
        double[][] a = new double[3][3];
        double[] b = new double[3];
        for (Sample sample : samples) {
            double x = sample.x;
            double y = sample.y;
            double v = value(sample.color, channel);
            a[0][0] += x * x;
            a[0][1] += x * y;
            a[0][2] += x;
            a[1][0] += x * y;
            a[1][1] += y * y;
            a[1][2] += y;
            a[2][0] += x;
            a[2][1] += y;
            a[2][2] += 1;
            b[0] += x * v;
            b[1] += y * v;
            b[2] += v;
        }
        double[] coeff = solve(a, b);
        if (coeff == null) {
            return null;
        }
        double sum = 0.0;
        for (Sample sample : samples) {
            double d = value(sample.color, channel) - (coeff[0] * sample.x + coeff[1] * sample.y + coeff[2]);
            sum += d * d;
        }
        return new Plane(coeff[0], coeff[1], coeff[2], Math.sqrt(sum / samples.size()));
    }

    private static double[] solve(double[][] matrix, double[] rhs) {
        double[][] a = new double[3][4];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(matrix[row], 0, a[row], 0, 3);
            a[row][3] = rhs[row];
        }
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 3; row++) {
                if (Math.abs(a[row][pivot]) > Math.abs(a[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(a[best][pivot]) < 0.000001) {
                return null;
            }
            double[] tmp = a[pivot];
            a[pivot] = a[best];
            a[best] = tmp;
            double divisor = a[pivot][pivot];
            for (int col = pivot; col < 4; col++) {
                a[pivot][col] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == pivot) {
                    continue;
                }
                double factor = a[row][pivot];
                for (int col = pivot; col < 4; col++) {
                    a[row][col] -= factor * a[pivot][col];
                }
            }
        }
        return new double[]{a[0][3], a[1][3], a[2][3]};
    }

    private static int value(ColorParts color, Channel channel) {
        if (channel == Channel.RED) {
            return color.red;
        }
        if (channel == Channel.GREEN) {
            return color.green;
        }
        return color.blue;
    }

    private static int median(List<Integer> values) {
        Collections.sort(values);
        return values.get(values.size() / 2).intValue();
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    static final class ColorParts {
        final int alpha;
        final int red;
        final int green;
        final int blue;
        final int luminance;

        ColorParts(int alpha, int red, int green, int blue) {
            this.alpha = alpha;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        }
    }

    public static final class Estimate {
        private final boolean accepted;
        private final int alpha;
        private final Plane red;
        private final Plane green;
        private final Plane blue;
        private final String reason;

        private Estimate(boolean accepted, int alpha, Plane red, Plane green, Plane blue, String reason) {
            this.accepted = accepted;
            this.alpha = alpha;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.reason = reason;
        }

        static Estimate accepted(int alpha, Plane red, Plane green, Plane blue) {
            return new Estimate(true, alpha, red, green, blue, "plane background");
        }

        static Estimate rejected(String reason) {
            return new Estimate(false, 0, null, null, null, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public int getArgb() {
            return argbAt(0, 0);
        }

        public int argbAt(int x, int y) {
            return argb(alpha, clamp(red.predict(x, y)), clamp(green.predict(x, y)), clamp(blue.predict(x, y)));
        }

        public String getReason() {
            return reason;
        }

        private static int clamp(double value) {
            return Math.max(0, Math.min(255, (int) Math.round(value)));
        }
    }

    static final class Sample {
        final int x;
        final int y;
        final ColorParts color;

        Sample(int x, int y, ColorParts color) {
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    static final class Plane {
        final double xCoeff;
        final double yCoeff;
        final double intercept;
        final double residual;

        Plane(double xCoeff, double yCoeff, double intercept, double residual) {
            this.xCoeff = xCoeff;
            this.yCoeff = yCoeff;
            this.intercept = intercept;
            this.residual = residual;
        }

        double predict(int x, int y) {
            return xCoeff * x + yCoeff * y + intercept;
        }
    }

    private enum Channel {
        RED, GREEN, BLUE
    }
}
