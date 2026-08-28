package com.xb.sgc.papererase.safety;

public final class RiskGate {
    /** 触发局部 verify 的最低候选框置信度；低于该值只会增加复核，不会放宽正文保护。 */
    private static final double MIN_CONFIDENCE = 0.97;

    /** 工具类禁止实例化。 */
    private RiskGate() {
    }

    /**
     * 判断是否值得消耗一次局部 VLM 二检。页面方向、正文空白带和候选置信度全部满足时
     * 走快速路径；旋转、双栏、缺页或边界风险任一出现就升级局部复核。
     * 该门禁只会增加检查，不会替代正文像素门禁，也不会把失败页自动放行。
     */
    public static boolean requiresLocalVerify(PageContext context, RegionValidator.ValidationResult validation) {
        // 4.1 入口保护：上下文或首次 Java 校验结果不完整时，无法证明页面属于低风险。
        // 返回 true 表示“需要局部 verify”，而不是“允许擦除”；最终是否擦除仍由后续门禁决定。
        if (context == null || validation == null || !validation.isAccepted() || validation.getRegions().isEmpty()) {
            return true;
        }
        // 页面 ID 用于把 VLM 返回的候选框和当前原图绑定；缺失时不能确认坐标归属。
        if (blank(context.getPageId())) {
            return true;
        }
        // Java 未证明正文方向存在安全空白带时，不能走快速擦除路径。
        if (!context.isJavaBlankGap()) {
            return true;
        }
        // 4.2 页面级高风险：这些情况会让整页坐标关系发生变化，或让正文边界判断更不可靠。
        // maskTouchesBoundary：候选擦除掩码碰到框边，可能漏字或越出目标；
        // readingRotation：页面旋转后，视觉上的上下左右与原图坐标关系更容易偏移；
        // doublePage：一张图包含两页，普通单页边缘假设不再成立；
        // heterogeneousFirstOrLast：首尾页版式可能不同于中间页，不能盲继承共性；
        // bodyBoundaryConflict：候选框与正文边界证据冲突；
        // pageSequenceIncomplete/missingPageRisk：页序或页集合不完整，跨页共性证据不足。
        if (context.isMaskTouchesBoundary() || context.getReadingRotation() != 0 || context.isDoublePage()
                || context.isHeterogeneousFirstOrLast() || context.isBodyBoundaryConflict()
                || context.isPageSequenceIncomplete() || context.isMissingPageRisk()) {
            return true;
        }
        // 4.3 逐框风险：页面整体稳定不代表每个候选框都稳定，因此必须逐个检查。
        for (RegionValidator.PixelRegion region : validation.getRegions()) {
            // 候选框的 pageId 必须与当前页面一致，防止模型响应串页或坐标错配。
            if (!context.getPageId().equals(region.getPageId())) {
                return true;
            }
            // 置信度必须是有限数且达到 0.97；NaN/Infinity 也按低置信度处理，失败关闭。
            // 这里的阈值只决定是否追加 verify，不会放宽正文保护规则。
            if (Double.isNaN(region.getConfidence()) || Double.isInfinite(region.getConfidence())
                    || region.getConfidence() < MIN_CONFIDENCE) {
                return true;
            }
        }
        // 所有页面级、逐框级条件均稳定：跳过局部 VLM verify，进入后续擦除和 audit。
        // “跳过 verify”不等于“跳过校验”，前面的 RegionValidator 和后面的 PixelDiffGate/audit 仍必须执行。
        return false;
    }

    private static boolean blank(String value) {
        // null 和去除首尾空白后为空字符串，都表示无法建立可靠的页面关联。
        return value == null || value.trim().length() == 0;
    }

    /**
     * 单页风险上下文：保存 locate 和 Java 像素校验阶段提供给 RiskGate 的事实。
     * 该对象只描述风险，不执行擦除；字段任一不稳定时，RiskGate 只会要求追加 verify。
     */
    public static final class PageContext {
        /** 当前正在处理的图片 ID，用于防止候选框串页。 */
        private final String pageId;
        /** Java 是否已证明候选框与正文之间存在安全空白带。 */
        private final boolean javaBlankGap;
        /** 擦除掩码是否触及候选区域边界。 */
        private final boolean maskTouchesBoundary;
        /** 页面阅读方向相对标准方向的旋转角度。0 表示无需旋转。 */
        private final int readingRotation;
        /** 当前图片是否可能包含左右两页或上下两页。 */
        private final boolean doublePage;
        /** 当前页是否为与中间页版式可能不同的首/尾页。 */
        private final boolean heterogeneousFirstOrLast;
        /** 候选框与正文边界证据是否发生冲突。 */
        private final boolean bodyBoundaryConflict;
        /** 当前试卷的页序是否存在缺口。 */
        private final boolean pageSequenceIncomplete;
        /** 是否存在无法确认页码连续性或页面归属的风险。 */
        private final boolean missingPageRisk;

        /**
         * 创建不可变风险上下文。
         * 参数按字段顺序保存，withXxx 方法通过复制全部字段实现“只替换一个风险事实”。
         */
        private PageContext(String pageId, boolean javaBlankGap, boolean maskTouchesBoundary,
                            int readingRotation, boolean doublePage, boolean heterogeneousFirstOrLast,
                            boolean bodyBoundaryConflict, boolean pageSequenceIncomplete, boolean missingPageRisk) {
            this.pageId = pageId;
            this.javaBlankGap = javaBlankGap;
            this.maskTouchesBoundary = maskTouchesBoundary;
            this.readingRotation = readingRotation;
            this.doublePage = doublePage;
            this.heterogeneousFirstOrLast = heterogeneousFirstOrLast;
            this.bodyBoundaryConflict = bodyBoundaryConflict;
            this.pageSequenceIncomplete = pageSequenceIncomplete;
            this.missingPageRisk = missingPageRisk;
        }

        /**
         * 创建默认稳定上下文。
         * 该工厂只提供低风险初始值，调用方仍应继续补充页面事实。
         */
        public static PageContext stable(String pageId) {
            return new PageContext(pageId, true, false,
                    0, false, false, false, false, false);
        }

        /** 返回仅替换“正文方向安全空白带是否通过”标志的新上下文。 */
        public PageContext withJavaBlankGap(boolean javaBlankGap) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“擦除掩码是否触边”标志的新上下文。 */
        public PageContext withMaskTouchesBoundary(boolean maskTouchesBoundary) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换页面阅读旋转角度的新上下文。 */
        public PageContext withReadingRotation(int readingRotation) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“双页风险”标志的新上下文。 */
        public PageContext withDoublePage(boolean doublePage) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“首尾页版式异构”标志的新上下文。 */
        public PageContext withHeterogeneousFirstOrLast(boolean heterogeneousFirstOrLast) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“正文边界冲突”标志的新上下文。 */
        public PageContext withBodyBoundaryConflict(boolean bodyBoundaryConflict) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“页序不完整”标志的新上下文。 */
        public PageContext withPageSequenceIncomplete(boolean pageSequenceIncomplete) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回仅替换“缺页或页面归属风险”标志的新上下文。 */
        public PageContext withMissingPageRisk(boolean missingPageRisk) {
            return new PageContext(pageId, javaBlankGap, maskTouchesBoundary, readingRotation, doublePage, heterogeneousFirstOrLast,
                    bodyBoundaryConflict, pageSequenceIncomplete, missingPageRisk);
        }

        /** 返回当前页面 ID。 */
        public String getPageId() {
            return pageId;
        }

        /** 返回 Java 是否证明正文方向存在安全空白带。 */
        public boolean isJavaBlankGap() {
            return javaBlankGap;
        }

        /** 返回擦除掩码是否触及候选区域边界。 */
        public boolean isMaskTouchesBoundary() {
            return maskTouchesBoundary;
        }

        /** 返回页面阅读旋转角度。 */
        public int getReadingRotation() {
            return readingRotation;
        }

        /** 返回当前图片是否存在双页风险。 */
        public boolean isDoublePage() {
            return doublePage;
        }

        /** 返回当前页是否存在首尾页版式异构风险。 */
        public boolean isHeterogeneousFirstOrLast() {
            return heterogeneousFirstOrLast;
        }

        /** 返回候选框与正文边界证据是否冲突。 */
        public boolean isBodyBoundaryConflict() {
            return bodyBoundaryConflict;
        }

        /** 返回试卷页序是否不完整。 */
        public boolean isPageSequenceIncomplete() {
            return pageSequenceIncomplete;
        }

        /** 返回是否存在缺页或页面归属风险。 */
        public boolean isMissingPageRisk() {
            return missingPageRisk;
        }
    }
}
