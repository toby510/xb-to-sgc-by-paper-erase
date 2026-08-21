package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.InkMaskEraser;

import java.awt.image.BufferedImage;

public final class PixelDiffGate {
    private PixelDiffGate() {
    }

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
