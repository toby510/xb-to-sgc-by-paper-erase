package com.xb.sgc.papererase.safety;

import java.awt.image.BufferedImage;

public final class PixelDiffGate {
    private PixelDiffGate() {
    }

    public static GateResult check(BufferedImage original, BufferedImage candidate, boolean[][] approvedMask) {
        if (original == null || candidate == null || approvedMask == null) {
            return GateResult.failed("original, candidate and approved mask are required");
        }
        if (original.getWidth() != candidate.getWidth() || original.getHeight() != candidate.getHeight()) {
            return GateResult.failed("image dimensions differ");
        }
        if (original.getType() != candidate.getType()) {
            return GateResult.failed("image types differ");
        }
        if (approvedMask.length != original.getHeight()) {
            return GateResult.failed("approved mask dimensions differ");
        }
        boolean anyApprovedChange = false;
        for (int y = 0; y < original.getHeight(); y++) {
            if (approvedMask[y] == null || approvedMask[y].length != original.getWidth()) {
                return GateResult.failed("approved mask dimensions differ");
            }
            for (int x = 0; x < original.getWidth(); x++) {
                boolean changed = original.getRGB(x, y) != candidate.getRGB(x, y);
                if (approvedMask[y][x]) {
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
