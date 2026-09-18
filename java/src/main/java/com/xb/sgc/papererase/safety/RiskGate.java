package com.xb.sgc.papererase.safety;

public final class RiskGate {
    /** 触发局部 Relocate 的最低候选框置信度；低于该值只会增加测量，不会放宽正文保护。 */
    private static final double MIN_CONFIDENCE = 0.97;

    /** 工具类禁止实例化。 */
    private RiskGate() {
    }

    /**
     * 判断是否值得消耗一次局部 VLM 二检。页面方向、页序和候选置信度全部满足时走快速
     * 路径；旋转或缺页风险任一出现就升级局部复核。
     * 该门禁只会增加检查，不会替代正文像素门禁，也不会把失败页自动放行。
     */
    public static boolean requiresRelocation(PageContext context, RegionValidator.ValidationResult validation) {
        // 4.1 入口保护：上下文或首次 Java 校验结果不完整时，无法证明页面属于低风险。
        // 返回 true 表示“需要局部 Relocate”，而不是“允许擦除”；最终是否擦除仍由后续门禁决定。
        if (context == null || validation == null || !validation.isAccepted() || validation.getRegions().isEmpty()) {
            return true;
        }
        // 页面 ID 用于把 VLM 返回的候选框和当前原图绑定；缺失时不能确认坐标归属。
        if (blank(context.getPageId())) {
            return true;
        }
        // 4.2 页面级高风险：旋转页的上下左右与原图坐标关系容易偏移；页序不完整时整卷页
        // 号连续性证据不足。两者都只要求追加一次局部复核。
        if (context.getReadingRotation() != 0 || context.isPageSequenceIncomplete()) {
            return true;
        }
        // 4.3 逐框风险：页面整体稳定不代表每个候选框都稳定，因此必须逐个检查。
        for (RegionValidator.PixelRegion region : validation.getRegions()) {
            // 候选框的 pageId 必须与当前页面一致，防止模型响应串页或坐标错配。
            if (!context.getPageId().equals(region.getPageId())) {
                return true;
            }
            // 置信度必须是有限数且达到 0.97；NaN/Infinity 也按低置信度处理，失败关闭。
            // 这里的阈值只决定是否追加 Relocate，不会放宽正文保护规则。
            if (Double.isNaN(region.getConfidence()) || Double.isInfinite(region.getConfidence())
                    || region.getConfidence() < MIN_CONFIDENCE) {
                return true;
            }
        }
        // 所有页面级、逐框级条件均稳定：跳过局部 VLM Relocate，进入后续擦除和 audit。
        // “跳过 Relocate”不等于“跳过校验”，前面的 RegionValidator 和后面的 PixelDiffGate/audit 仍必须执行。
        return false;
    }

    private static boolean blank(String value) {
        // null 和去除首尾空白后为空字符串，都表示无法建立可靠的页面关联。
        return value == null || value.trim().length() == 0;
    }

    /**
     * 单页风险上下文：保存 pipeline 在 Java 像素校验阶段提供给 RiskGate 的事实。
     * 该对象只描述风险，不执行擦除；字段任一不稳定时，RiskGate 只会要求追加 Relocate。
     */
    public static final class PageContext {
        /** 当前正在处理的图片 ID，用于防止候选框串页。 */
        private final String pageId;
        /** 页面阅读方向相对标准方向的旋转角度。0 表示无需旋转。 */
        private final int readingRotation;
        /** 当前试卷的页序是否存在缺口。 */
        private final boolean pageSequenceIncomplete;

        /** 创建不可变风险上下文；withXxx 通过复制实现“只替换一个风险事实”。 */
        private PageContext(String pageId, int readingRotation, boolean pageSequenceIncomplete) {
            this.pageId = pageId;
            this.readingRotation = readingRotation;
            this.pageSequenceIncomplete = pageSequenceIncomplete;
        }

        /** 创建默认稳定上下文：无旋转、页序完整。 */
        public static PageContext stable(String pageId) {
            return new PageContext(pageId, 0, false);
        }

        /** 返回仅替换页面阅读旋转角度的新上下文。 */
        public PageContext withReadingRotation(int readingRotation) {
            return new PageContext(pageId, readingRotation, pageSequenceIncomplete);
        }

        /** 返回仅替换“页序不完整”标志的新上下文。 */
        public PageContext withPageSequenceIncomplete(boolean pageSequenceIncomplete) {
            return new PageContext(pageId, readingRotation, pageSequenceIncomplete);
        }

        /** 返回当前页面 ID。 */
        public String getPageId() {
            return pageId;
        }

        /** 返回页面阅读旋转角度。 */
        public int getReadingRotation() {
            return readingRotation;
        }

        /** 返回试卷页序是否不完整。 */
        public boolean isPageSequenceIncomplete() {
            return pageSequenceIncomplete;
        }
    }
}
