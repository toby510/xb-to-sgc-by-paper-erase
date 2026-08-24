package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.InkMaskEraser;

import java.awt.image.BufferedImage;

/**
 * 最终像素完整性门禁：候选图只允许在已批准掩码、且仍位于模型批准矩形内发生变化。
 * 该检查不依赖模型判断，因此可兜住模型幻觉和本地擦除实现的越界缺陷。
 */
public final class PixelDiffGate {
    private PixelDiffGate() {
    }

    /**
     * 逐像素比较原图与候选图：批准掩码内允许变化，掩码外任何一个像素变化都立即失败。
     * 这是防止模型幻觉、坐标映射错误或擦除器越界伤及正文的最后确定性兜底；它不判断目标
     * 语义，也不因“变化很小”而放宽正文保护。
     */
    /**
     * 证明候选图只在批准掩码内发生变化。
     *
     * @param original 擦除前原图
     * @param candidate 擦除器生成的候选图
     * @param approvedMask 允许变化的整图尺寸掩码及其批准区域约束
     * @return 通过结果，或包含首个越界/无变化原因的失败结果
     */
    public static GateResult check(BufferedImage original, BufferedImage candidate, InkMaskEraser.ApprovedMask approvedMask) {
        if (original == null || candidate == null || approvedMask == null) {
            return GateResult.failed("original, candidate and approved mask are required");
        }
        if (original.getWidth() != candidate.getWidth() || original.getHeight() != candidate.getHeight()) {
            return GateResult.failed("image dimensions differ");
        }
        if (original.getType() != candidate.getType()) {
            return GateResult.failed("image types differ");
        }
        if (approvedMask.getImageWidth() != original.getWidth() || approvedMask.getImageHeight() != original.getHeight()) {
            return GateResult.failed("approved mask dimensions differ");
        }
        boolean[][] mask = approvedMask.toArray();
        boolean anyApprovedChange = false;
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                boolean changed = original.getRGB(x, y) != candidate.getRGB(x, y);
                if (mask[y][x]) {
                    if (!approvedMask.containsRegion(x, y)) {
                        return GateResult.failed("approved mask outside region");
                    }
                    anyApprovedChange = anyApprovedChange || changed;
                } else if (changed) {
                    // 任何掩码外改动均视为正文风险，不接受“看起来影响不大”的例外。
                    return GateResult.failed("mask outside pixel changed at " + x + "," + y);
                }
            }
        }
        if (!anyApprovedChange) {
            return GateResult.failed("no approved pixel changed");
        }
        return GateResult.passed();
    }

    public static final class GateResult {
        private final boolean passed;
        private final String reason;

        private GateResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        static GateResult passed() {
            return new GateResult(true, "passed");
        }

        static GateResult failed(String reason) {
            return new GateResult(false, reason);
        }

        public boolean isPassed() {
            return passed;
        }

        public String getReason() {
            return reason;
        }
    }
}
