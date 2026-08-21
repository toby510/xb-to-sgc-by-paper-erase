package com.xb.sgc.papererase.safety;

public final class RiskGate {
    private static final double MIN_CONFIDENCE = 0.97;

    private RiskGate() {
    }

    public static boolean requiresLocalVerify(PageContext context, RegionValidator.ValidationResult validation) {
        if (context == null || validation == null || !validation.isAccepted() || validation.getRegions().isEmpty()) {
            return true;
        }
        if (!context.isStablePattern() || !context.isEdgeMatchesPattern() || !context.isJavaBlankGap()) {
            return true;
        }
        if (context.isMaskTouchesBoundary() || context.getReadingRotation() != 0 || context.isDoublePage()
                || context.isHeterogeneousFirstOrLast() || context.isBodyBoundaryConflict()
                || context.isPageSequenceIncomplete() || context.isMissingPageRisk()) {
            return true;
        }
        if ("mixed".equals(context.getConsensusState()) || "uncertain".equals(context.getConsensusState())) {
            return true;
        }
        for (RegionValidator.PixelRegion region : validation.getRegions()) {
            if (Double.isNaN(region.getConfidence()) || Double.isInfinite(region.getConfidence())
                    || region.getConfidence() < MIN_CONFIDENCE) {
                return true;
            }
        }
        return false;
    }

    public static final class PageContext {
        private final String pageId;
        private final String patternGroupId;
        private final String consensusState;
        private final boolean stablePattern;
        private final boolean edgeMatchesPattern;
        private final boolean javaBlankGap;
        private final boolean maskTouchesBoundary;
        private final int readingRotation;
        private final boolean doublePage;
        private final boolean heterogeneousFirstOrLast;
        private final boolean bodyBoundaryConflict;
        private final boolean pageSequenceIncomplete;
        private final boolean missingPageRisk;

        private PageContext(String pageId, String patternGroupId, String consensusState, boolean stablePattern,
                            boolean edgeMatchesPattern, boolean javaBlankGap, boolean maskTouchesBoundary,
                            int readingRotation, boolean doublePage, boolean heterogeneousFirstOrLast,
                            boolean bodyBoundaryConflict, boolean pageSequenceIncomplete, boolean missingPageRisk) {
            this.pageId = pageId;
            this.patternGroupId = patternGroupId;
            this.consensusState = consensusState;
            this.stablePattern = stablePattern;
            this.edgeMatchesPattern = edgeMatchesPattern;
            this.javaBlankGap = javaBlankGap;
            this.maskTouchesBoundary = maskTouchesBoundary;
            this.readingRotation = readingRotation;
            this.doublePage = doublePage;
            this.heterogeneousFirstOrLast = heterogeneousFirstOrLast;
            this.bodyBoundaryConflict = bodyBoundaryConflict;
            this.pageSequenceIncomplete = pageSequenceIncomplete;
            this.missingPageRisk = missingPageRisk;
        }

        public static PageContext stable(String pageId) {
            return new PageContext(pageId, null, "stable", true, true, true, false,
                    0, false, false, false, false, false);
        }

        public PageContext withPatternGroupId(String patternGroupId) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withConsensusState(String consensusState) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withStablePattern(boolean stablePattern) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withEdgeMatchesPattern(boolean edgeMatchesPattern) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withJavaBlankGap(boolean javaBlankGap) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withMaskTouchesBoundary(boolean maskTouchesBoundary) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withReadingRotation(int readingRotation) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withDoublePage(boolean doublePage) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withHeterogeneousFirstOrLast(boolean heterogeneousFirstOrLast) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withBodyBoundaryConflict(boolean bodyBoundaryConflict) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withPageSequenceIncomplete(boolean pageSequenceIncomplete) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public PageContext withMissingPageRisk(boolean missingPageRisk) {
            return new PageContext(pageId, patternGroupId, consensusState, stablePattern, edgeMatchesPattern,
                    javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        public String getPageId() {
            return pageId;
        }

        public String getPatternGroupId() {
            return patternGroupId;
        }

        public String getConsensusState() {
            return consensusState;
        }

        public boolean isStablePattern() {
            return stablePattern;
        }

        public boolean isEdgeMatchesPattern() {
            return edgeMatchesPattern;
        }

        public boolean isJavaBlankGap() {
            return javaBlankGap;
        }

        public boolean isMaskTouchesBoundary() {
            return maskTouchesBoundary;
        }

        public int getReadingRotation() {
            return readingRotation;
        }

        public boolean isDoublePage() {
            return doublePage;
        }

        public boolean isHeterogeneousFirstOrLast() {
            return heterogeneousFirstOrLast;
        }

        public boolean isBodyBoundaryConflict() {
            return bodyBoundaryConflict;
        }

        public boolean isPageSequenceIncomplete() {
            return pageSequenceIncomplete;
        }

        public boolean isMissingPageRisk() {
            return missingPageRisk;
        }
    }
}
