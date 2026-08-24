package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.erase.BackgroundEstimator;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 擦除前的确定性防火墙。模型可以误判，但这里的规则要求候选目标行（页码及获批同行
 * 非正文元数据）位于独立边缘带，且与正文之间存在真实、无墨的像素安全带；任意一项无法
 * 证明即拒绝自动擦除。它不以候选框宽度判断语义，因此可安全接受完整独立目标行。
 */
public final class RegionValidator {
    private static final double EDGE_BAND = 0.20;
    private static final int MIN_BODY_GAP_PIXELS = 8;

    private RegionValidator() {
    }

    /**
     * 执行页面候选区域的完整正文保护校验，按“协议→几何→正文安全带→候选墨迹”逐层收紧。
     * 任一层无法证明安全就返回拒绝；该方法只读原图，不执行任何像素写入。
     *
     * @param locateResult locate 模型结果，包含整图归一化候选框和正文边界
     * @param image 旋正后的原图；所有像素校验均基于此图
     * @return 通过时返回可供擦除器使用的像素区域；失败时返回原因列表且不返回可写区域
     */
    public static ValidationResult validate(PageLocateResult locateResult, BufferedImage image) {
        // 3.0 总入口：以下步骤只读原图，不写任何像素；任一层失败都返回拒绝。
        if (locateResult == null) {
            return ValidationResult.rejected("locate result is required");
        }
        if (image == null) {
            return ValidationResult.rejected("image is required");
        }

        List<String> reasons = new ArrayList<String>();
        List<PixelRegion> pixelRegions = new ArrayList<PixelRegion>();
        Set<String> regionIds = new HashSet<String>();

        // 3.1 协议完整性：先确认页面、状态和 region 容器存在，再读取任何坐标。
        if (locateResult.getPageId() == null || locateResult.getPageId().trim().length() == 0) {
            return ValidationResult.rejected("page_id is required");
        }
        if (!"safe_to_erase".equals(locateResult.getStatus())) {
            return ValidationResult.rejected("status must be safe_to_erase");
        }
        if (locateResult.getRegions() == null) {
            return ValidationResult.rejected("regions are required");
        }
        if (locateResult.getRegions().isEmpty()) {
            return ValidationResult.rejected("regions must not be empty");
        }

        // 3.2 region 几何协议：逐框校验 ID、归一化坐标、置信度和正面积。
        for (EraseRegion region : locateResult.getRegions()) {
            if (region == null) {
                reasons.add("region is required");
                continue;
            }
            if (region.region_id == null || region.region_id.trim().length() == 0) {
                reasons.add("region_id is required");
                continue;
            }
            String canonicalRegionId = region.region_id.trim();
            if (!regionIds.add(canonicalRegionId)) {
                reasons.add("duplicate region_id: " + canonicalRegionId);
                continue;
            }
            if (!finite(region.x1) || !finite(region.y1) || !finite(region.x2) || !finite(region.y2)) {
                reasons.add("coordinates must be finite");
                continue;
            }
            if (!finite(region.confidence)) {
                reasons.add("confidence must be finite");
                continue;
            }
            if (region.confidence < 0 || region.confidence > 1) {
                reasons.add("confidence must be between 0 and 1");
                continue;
            }
            if (!(0 <= region.x1 && region.x1 <= 1 && 0 <= region.x2 && region.x2 <= 1
                    && 0 <= region.y1 && region.y1 <= 1 && 0 <= region.y2 && region.y2 <= 1)) {
                reasons.add("coordinates must satisfy 0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1");
                continue;
            }
            if (region.x1 >= region.x2 || region.y1 >= region.y2
                    || (region.x2 - region.x1) * (region.y2 - region.y1) <= 0) {
                reasons.add("region must have strictly positive area");
                continue;
            }

            // 3.3 边缘带门禁：非边缘候选不具备“页码与正文分离”的可证明条件，不能靠置信度放行。
            // 极端横向短条页的底部页码会占据相对高度较大的比例；仅在候选中心已落入
            // 对应边缘 25%、候选本身仍较窄时，允许后续像素空白带门禁继续判断。
            Edge edge = edgeForValidation(region, image);
            if (edge == Edge.NONE) {
                reasons.add("region must be inside an edge band");
                continue;
            }
            // 3.4 坐标还原：把 VLM 的 [0,1] 左上角(x1,y1)、右下角(x2,y2)映射为原图像素矩形。
            PixelRegion pixelRegion = toPixelRegion(locateResult.getPageId(), canonicalRegionId, region, image);
            if (pixelRegion.getWidth() <= 0 || pixelRegion.getHeight() <= 0
                    || pixelRegion.getX() < 0 || pixelRegion.getY() < 0
                    || pixelRegion.getX() + pixelRegion.getWidth() > image.getWidth()
                    || pixelRegion.getY() + pixelRegion.getHeight() > image.getHeight()) {
                reasons.add("coordinates map outside image bounds");
                continue;
            }
            // 3.5 正文边界：优先使用模型提供的边界；缺失时必须由像素空白证据推断，不能猜。
            BodyBoundary boundary = locateResult.getNearestBodyBoundary();
            String boundaryReason = invalidBodyBoundaryReason(edge, boundary);
            if (boundaryReason != null && isMissingDirectionalBoundary(edge, boundary)) {
                // 模型有时只给出精确页码框而漏报正文边界。仅在朝正文方向紧邻 16px 都是
                // 连续无墨带时，才把该带末端作为保守边界；没有这份像素证据仍失败关闭。
                boundary = inferBoundaryFromBlankBand(edge, pixelRegion, image);
                if (boundary == null) {
                    reasons.add(boundaryReason);
                    continue;
                }
            } else if (boundaryReason != null) {
                reasons.add(boundaryReason);
                continue;
            }
            // 3.6 正文安全带：从候选框朝正文方向扫描至少 8px 连续无实质墨迹，抵抗边界幻觉。
            String gapReason = invalidPixelGapReason(edge, pixelRegion, boundary, image);
            if ("body blank gap contains ink".equals(gapReason)) {
                /*
                 * VLM 坐标是语义定位，常把页码最外侧一两列抗锯齿笔画排除在框外。不能把
                 * 这类“与框内页码连通”的残笔直接当正文，也不能无条件放宽安全带：只把
                 * 连通分量向正文方向扩一像素白边，再重新验证 8px 的真实无墨安全带。
                 */
                pixelRegion = hasConservativeInk(image, pixelRegion)
                        ? expandConnectedTargetInk(edge, pixelRegion, boundary, image)
                        : rescueEmptyModelBox(edge, pixelRegion, bodyLimit(edge, boundary, image), image);
                gapReason = invalidPixelGapReason(edge, pixelRegion, boundary, image);
            }
            if (gapReason != null) {
                reasons.add(gapReason);
                continue;
            }
            // 3.7 空框坐标救援：模型框落在邻近空白时，只能沿已证明安全的同侧走廊找回目标。
            // 模型可能把整个框偏到页码相邻空白处。框内完全无墨时，只能在已证明远离正文的
            // 同侧走廊中找回目标；该分支会标记 coordinateRescued 并强制局部二检。
            if (!hasConservativeInk(image, pixelRegion)) {
                pixelRegion = rescueEmptyModelBox(edge, pixelRegion,
                        bodyLimit(edge, boundary, image), image);
                gapReason = invalidPixelGapReason(edge, pixelRegion, boundary, image);
                if (gapReason != null) {
                    reasons.add(gapReason);
                    continue;
                }
            }
            // 3.8 框边墨迹门禁：定位坐标落在字形抗锯齿边缘时，先尝试向空白侧扩到安全边界。
            // 定位坐标落在字形抗锯齿边缘时，擦除器会因“掩码贴框”拒绝。这里与擦除器
            // 使用同一类墨迹判定，并把每一边扩到两条连续空白扫描线为止。这样批准框本身
            // 带有可验证的空白边界；无法在最多半个字高/16px 内找到空白，宁可拒绝也不
            // 把相邻文字或正文吞进擦除范围。
            if (maskTouchesCandidateBox(image, pixelRegion)) {
                PixelRegion expanded = expandToBlankBoundary(edge, pixelRegion, boundary, image);
                String expandedGapReason = expanded == null ? null
                        : invalidPixelGapReason(edge, expanded, boundary, image);
                if (expanded != null && expandedGapReason == null && !maskTouchesCandidateBox(image, expanded)) {
                    pixelRegion = expanded;
                } else {
                    reasons.add(expandedGapReason == null ? "ink mask touches candidate box" : expandedGapReason);
                    continue;
                }
            }
            pixelRegions.add(pixelRegion);
        }

        // 3.9 聚合决策：一页内任一候选失败，整页失败关闭；不擦除“部分看起来安全”的框。
        if (!reasons.isEmpty()) {
            return new ValidationResult(false, Collections.<PixelRegion>emptyList(), reasons);
        }
        return new ValidationResult(true, pixelRegions, Collections.<String>emptyList());
    }

    /**
     * 局部高清二检已经重新确认页码框，但整页 locate 的正文边界恰好穿过该框时，不能直接
     * 相信这条自相矛盾的模型边界。这个方法只提供一条由像素证明的替代边界，调用方仍必须
     * 将它交回 {@link #validate(PageLocateResult, BufferedImage)} 做完整门禁。
     *
     * <p>它刻意不处理缺失边界、普通候选框或任何含墨的空白带：只要不能同时证明同一边缘、
     * 基线投影重叠、候选内确有墨迹和最终 8px 正文安全带，即返回 {@code null}。当正文侧
     * 墨迹贴住精框、最终校验还会补两行空白边界时，内部才为这两行额外预留像素。</p>
     */
    public static BodyBoundary replaceConflictingBodyBoundary(EraseRegion originalRegion, EraseRegion refinedRegion,
                                                              BodyBoundary originalBoundary, BufferedImage image) {
        if (originalRegion == null || refinedRegion == null || image == null) {
            return null;
        }
        Edge originalEdge = edge(originalRegion);
        Edge refinedEdge = edge(refinedRegion);
        if (originalEdge == Edge.NONE || originalEdge != refinedEdge
                || !hasDirectionalBoundary(originalEdge, originalBoundary)
                || !baselineProjectionOverlaps(originalRegion, refinedRegion, originalEdge)) {
            return null;
        }
        PixelRegion refined = toPixelRegion("boundary-conflict", "refined", refinedRegion, image);
        if (refined.getWidth() <= 0 || refined.getHeight() <= 0 || !hasConservativeInk(image, refined)) {
            return null;
        }
        // 模型框常保留几像素空白内边距。正文安全距离必须从实际目标墨迹而非这个空白框边计算；
        // 只裁去朝正文方向、框内已证明为空白的像素，绝不裁墨迹、绝不在框外寻找新目标。
        refined = trimBlankPaddingTowardBody(refinedEdge, refined, image);
        // 不在这里向正文方向搜索或吸附“目标墨迹”。局部 VLM 已给出精框；背透、透印或
        // 邻近正文的浅影若被 Java 当作候选的一部分，会反过来制造虚假的边界冲突。
        // Java 在此只验证 VLM 框外的真实安全带，不改变其正文侧几何含义。
        // 若精框恰好少包了正文方向最外侧 1~2px 抗锯齿，先沿连续墨迹走到两条空白线
        // 为止，再从“真实目标最外缘”计算安全带。该扫描遇到两条空白立即停止，不会跨行。
        refined = expandTargetTowardBody(refinedEdge, refined, image);
        // 最终硬门禁要求批准框与正文相隔 8px。若正文侧墨迹仍贴框，validate 会先向该侧
        // 补入两条空白扫描线，替代边界必须为这次确定性扩框预留 2px；若框内已经有空白，
        // 则不额外预扣，避免把恰好满足 8px 的安全页码误拒。
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, refined);
        boolean bodySideTouches = sideTouchesCandidateBox(
                image, refined, towardBodySide(refinedEdge), backgroundLum);
        final int requiredBlankPixels = MIN_BODY_GAP_PIXELS + (bodySideTouches ? 2 : 0);
        int left = refined.getX();
        int top = refined.getY();
        int right = left + refined.getWidth();
        int bottom = top + refined.getHeight();
        BodyBoundary effective = new BodyBoundary();
        if (refinedEdge == Edge.TOP) {
            if (bandHasSubstantiveInk(image, refined, left, bottom, right, bottom + requiredBlankPixels, true))
                return null;
            effective.y = (bottom + requiredBlankPixels) / (double) image.getHeight();
        } else if (refinedEdge == Edge.BOTTOM) {
            if (bandHasSubstantiveInk(image, refined, left, top - requiredBlankPixels, right, top, true)) return null;
            effective.y = (top - requiredBlankPixels) / (double) image.getHeight();
        } else if (refinedEdge == Edge.LEFT) {
            if (bandHasSubstantiveInk(image, refined, right, top, right + requiredBlankPixels, bottom, true))
                return null;
            effective.x = (right + requiredBlankPixels) / (double) image.getWidth();
        } else if (refinedEdge == Edge.RIGHT) {
            if (bandHasSubstantiveInk(image, refined, left - requiredBlankPixels, top, left, bottom, true)) return null;
            effective.x = (left - requiredBlankPixels) / (double) image.getWidth();
        } else {
            return null;
        }
        effective.basis = "java_8px_blank_band_replaced_conflicting_vlm_boundary"
                + "; original_basis=" + safeBasis(originalBoundary.basis)
                + "; original_x=" + originalBoundary.x + "; original_y=" + originalBoundary.y;
        return effective;
    }

    /**
     * 将候选框朝正文一侧的纯空白内边距裁掉，并把像素结果重新归一化。
     *
     * @param region VLM 返回的整图归一化候选框
     * @param image 旋正后的原图
     * @return 缩小后的归一化候选框；无法判断边缘或输入无效时返回原对象
     */
    public static EraseRegion trimBodyFacingBlankPadding(EraseRegion region, BufferedImage image) {
        if (region == null || image == null) {
            return region;
        }
        Edge edge = edge(region);
        if (edge == Edge.NONE) {
            return region;
        }
        PixelRegion pixel = toPixelRegion("trim-padding", region.region_id == null ? "region" : region.region_id, region, image);
        if (pixel.getWidth() <= 0 || pixel.getHeight() <= 0) {
            return region;
        }
        PixelRegion trimmed = trimBlankPaddingTowardBody(edge, pixel, image);
        EraseRegion copy = new EraseRegion();
        copy.region_id = region.region_id;
        copy.page_number_text = region.page_number_text;
        copy.same_line_metadata = region.same_line_metadata;
        copy.on_line = region.on_line;
        copy.confidence = region.confidence;
        copy.safety_margin = region.safety_margin;
        copy.x1 = trimmed.getX() / (double) image.getWidth();
        copy.y1 = trimmed.getY() / (double) image.getHeight();
        copy.x2 = (trimmed.getX() + trimmed.getWidth()) / (double) image.getWidth();
        copy.y2 = (trimmed.getY() + trimmed.getHeight()) / (double) image.getHeight();
        return copy;
    }

    /**
     * 在像素坐标中删除模型框朝正文一侧的纯空白内边距，并保留 2px 抗锯齿余量。
     * 该操作只缩小框，仅用于 conflicting-boundary 局部精定位；没有检测到墨迹时保持原框。
     *
     * @param edge 候选框所在页面边缘
     * @param region 原图像素候选框
     * @param image 旋正后的原图
     * @return 只缩小正文侧空白内边距后的像素框
     */
    private static PixelRegion trimBlankPaddingTowardBody(Edge edge, PixelRegion region, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        final int padding = 2;
        if (edge == Edge.BOTTOM) {
            int firstInk = firstTargetInkRow(image, left, top, right, bottom, backgroundLum, true);
            if (firstInk >= top) top = Math.max(region.getY(), firstInk - padding);
        } else if (edge == Edge.TOP) {
            int lastInk = firstTargetInkRow(image, left, top, right, bottom, backgroundLum, false);
            if (lastInk >= top) bottom = Math.min(region.getY() + region.getHeight(), lastInk + 1 + padding);
        } else if (edge == Edge.RIGHT) {
            for (int x = left; x < right; x++) {
                if (bandHasErasableInk(image, x, top, x + 1, bottom, backgroundLum)) {
                    left = Math.max(region.getX(), x - padding);
                    break;
                }
            }
        } else if (edge == Edge.LEFT) {
            for (int x = right - 1; x >= left; x--) {
                if (bandHasErasableInk(image, x, top, x + 1, bottom, backgroundLum)) {
                    right = Math.min(region.getX() + region.getWidth(), x + 1 + padding);
                    break;
                }
            }
        }
        return new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(),
                region.isCoordinateRescued());
    }

    /**
     * 沿指定方向寻找第一条实质目标墨迹行，并跳过已证明与目标断开的细分隔线。
     * 只有细线连续、后有完整空白行且后方存在文字带时才跳过，避免把页码字符误当装饰线。
     */
    private static int firstTargetInkRow(BufferedImage image, int left, int top, int right, int bottom,
                                         int backgroundLum, boolean forward) {
        int step = forward ? 1 : -1;
        int start = forward ? top : bottom - 1;
        for (int y = start; y >= top && y < bottom; y += step) {
            int ink = inkCount(image, left, y, right, y + 1, backgroundLum);
            if (ink == 0) continue;
            if (ink <= 4) {
                // 分栏细线可能仅以末端 1~2px 伸入模型精框。只在框内向目标方向计算长度，
                // 会把它误当页码首笔；向正文方向最多回看 32px，证明它是一根持续细线后，
                // 才允许越过后续完整空白行去找真正的文字带。
                int lineStart = y;
                int backwardLimit = forward ? Math.max(0, y - 32) : Math.min(image.getHeight() - 1, y + 32);
                int previous = lineStart - step;
                while (previous >= 0 && previous < image.getHeight()
                        && (forward ? previous >= backwardLimit : previous <= backwardLimit)) {
                    int previousInk = inkCount(image, left, previous, right, previous + 1, backgroundLum);
                    if (previousInk == 0 || previousInk > 4) break;
                    lineStart = previous;
                    previous -= step;
                }
                int lineEnd = y;
                while (lineEnd + step >= top && lineEnd + step < bottom
                        && inkCount(image, left, lineEnd + step, right, lineEnd + step + 1, backgroundLum) > 0
                        && inkCount(image, left, lineEnd + step, right, lineEnd + step + 1, backgroundLum) <= 4) {
                    lineEnd += step;
                }
                int lineLength = Math.abs(lineEnd - lineStart) + 1;
                int blank = lineEnd + step;
                int next = blank + step;
                int searchLimit = forward ? Math.min(bottom - 1, blank + 32) : Math.max(top, blank - 32);
                while (next >= top && next < bottom && (forward ? next <= searchLimit : next >= searchLimit)
                        && inkCount(image, left, next, right, next + 1, backgroundLum) == 0) {
                    next += step;
                }
                if (lineLength >= 4 && blank >= top && blank < bottom
                        && inkCount(image, left, blank, right, blank + 1, backgroundLum) == 0
                        && next >= top && next < bottom
                        && inkCount(image, left, next, right, next + 1, backgroundLum) >= 5) {
                    y = blank;
                    continue;
                }
            }
            return y;
        }
        return -1;
    }

    /** 统计指定像素矩形中的保守墨迹像素数，用于区分空白扫描线和目标文字行。 */
    private static int inkCount(BufferedImage image, int left, int top, int right, int bottom, int backgroundLum) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (BackgroundEstimator.isErasableInk(image.getRGB(x, y), backgroundLum, false)) count++;
            }
        }
        return count;
    }

    /**
     * 沿正文方向逐像素扩展已确认目标框，最多 32px；连续两条空白扫描线即停止，禁止跨越
     * 空白去吸附另一行正文、表格线或分栏线。
     *
     * @param edge 候选框所在页面边缘
     * @param region 已有的原图像素候选框
     * @param image 旋正后的原图
     * @return 扩展后的像素框；越界或扩展不再位于边缘带时返回原框
     */
    private static PixelRegion expandTargetTowardBody(Edge edge, PixelRegion region, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        int originalLeft = left, originalTop = top, originalRight = right, originalBottom = bottom;
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        // 局部精框在正文侧已经自带两条空白扫描线时，目标墨迹不可能还在框外与其连通。
        // 继续向外搜索只会吸附紧邻框外的分栏线或表格边框，造成错误扩框。
        if (hasTwoBodyFacingBlankRowsInside(edge, region, image, backgroundLum)) {
            return region;
        }
        int blankLines = 0;
        for (int step = 1; step <= 32; step++) {
            int scanLeft = left, scanTop = top, scanRight = right, scanBottom = bottom;
            if (edge == Edge.TOP) {
                scanTop = originalBottom + step - 1;
                scanBottom = scanTop + 1;
            }
            if (edge == Edge.BOTTOM) {
                scanBottom = originalTop - step + 1;
                scanTop = scanBottom - 1;
            }
            if (edge == Edge.LEFT) {
                scanLeft = originalRight + step - 1;
                scanRight = scanLeft + 1;
            }
            if (edge == Edge.RIGHT) {
                scanRight = originalLeft - step + 1;
                scanLeft = scanRight - 1;
            }
            if (scanLeft < 0 || scanTop < 0 || scanRight > image.getWidth() || scanBottom > image.getHeight()) {
                break;
            }
            if (bandHasErasableInk(image, scanLeft, scanTop, scanRight, scanBottom, backgroundLum)) {
                blankLines = 0;
                if (edge == Edge.TOP) bottom = scanBottom;
                if (edge == Edge.BOTTOM) top = scanTop;
                if (edge == Edge.LEFT) right = scanRight;
                if (edge == Edge.RIGHT) left = scanLeft;
            } else if (++blankLines >= 2) {
                break;
            }
        }
        return new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(),
                region.isCoordinateRescued());
    }

    /** 判断候选框朝正文一侧的两行像素是否均为空白，防止无意义地继续扩框。 */
    private static boolean hasTwoBodyFacingBlankRowsInside(Edge edge, PixelRegion region, BufferedImage image,
                                                           int backgroundLum) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        if ((edge == Edge.TOP || edge == Edge.BOTTOM) && region.getHeight() < 2) return false;
        if ((edge == Edge.LEFT || edge == Edge.RIGHT) && region.getWidth() < 2) return false;
        if (edge == Edge.TOP) return !bandHasErasableInk(image, left, bottom - 2, right, bottom, backgroundLum);
        if (edge == Edge.BOTTOM) return !bandHasErasableInk(image, left, top, right, top + 2, backgroundLum);
        if (edge == Edge.LEFT) return !bandHasErasableInk(image, right - 2, top, right, bottom, backgroundLum);
        if (edge == Edge.RIGHT) return !bandHasErasableInk(image, left, top, left + 2, bottom, backgroundLum);
        return false;
    }

    /** 判断正文边界是否提供了当前边缘方向所需的有效 x 或 y 坐标。 */
    private static boolean hasDirectionalBoundary(Edge edge, BodyBoundary boundary) {
        if (boundary == null) return false;
        Double coordinate = (edge == Edge.TOP || edge == Edge.BOTTOM) ? boundary.y : boundary.x;
        return coordinate != null && finite(coordinate) && coordinate >= 0 && coordinate <= 1;
    }

    /** 判断原始框与局部精修框在页面边缘基线方向的投影重叠是否至少达到 50%。 */
    private static boolean baselineProjectionOverlaps(EraseRegion original, EraseRegion refined, Edge edge) {
        double originalStart = (edge == Edge.TOP || edge == Edge.BOTTOM) ? original.x1 : original.y1;
        double originalEnd = (edge == Edge.TOP || edge == Edge.BOTTOM) ? original.x2 : original.y2;
        double refinedStart = (edge == Edge.TOP || edge == Edge.BOTTOM) ? refined.x1 : refined.y1;
        double refinedEnd = (edge == Edge.TOP || edge == Edge.BOTTOM) ? refined.x2 : refined.y2;
        double overlap = Math.max(0D, Math.min(originalEnd, refinedEnd) - Math.max(originalStart, refinedStart));
        return overlap / Math.min(originalEnd - originalStart, refinedEnd - refinedStart) >= 0.5D;
    }

    /** 清理正文边界证据文本中的分号，避免写入审计 basis 时破坏内部字段格式。 */
    private static String safeBasis(String basis) {
        return basis == null ? "null" : basis.replace(';', ',');
    }

    /** 判断坐标或置信度是否为有限数，拒绝 NaN 和无穷大。 */
    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** 将 VLM 整图归一化矩形转换为原图像素半开矩形，并保留原始坐标用于追溯。 */
    private static PixelRegion toPixelRegion(String pageId, String regionId, EraseRegion region, BufferedImage image) {
        /*
         * VLM 返回的是 0..1 归一化坐标，不能直接用于 BufferedImage。这里统一完成一次
         * “归一化坐标 → 原图像素坐标”映射：左上角向下取 floor，右下角向上取 ceil，
         * 形成 [x, x+width) × [y, y+height) 的半开区间，保证抗锯齿边缘不会因取整被漏掉。
         * 原始 x1..y2 同时保留，便于审计、ROI 映射和报告追溯；后续擦除只使用 x/y/width/height。
         */
        int left = clamp((int) Math.floor(region.x1 * image.getWidth()), 0, image.getWidth());
        int top = clamp((int) Math.floor(region.y1 * image.getHeight()), 0, image.getHeight());
        int rightExclusive = clamp((int) Math.ceil(region.x2 * image.getWidth()), 0, image.getWidth());
        int bottomExclusive = clamp((int) Math.ceil(region.y2 * image.getHeight()), 0, image.getHeight());
        return new PixelRegion(pageId, regionId, left, top, rightExclusive - left, bottomExclusive - top,
                region.x1, region.y1, region.x2, region.y2, region.confidence);
    }

    /**
     * 向候选框四侧寻找两条连续空白扫描线，建立可覆盖抗锯齿的安全擦除边界。
     *
     * @param edge 页码所在页面边缘
     * @param region 当前像素候选框
     * @param boundary 正文边界，用于阻止朝正文方向越过 8px 安全带
     * @param image 旋正后的原图
     * @return 扩展后的像素框；任一方向无法证明安全时返回 {@code null}
     */
    private static PixelRegion expandToBlankBoundary(Edge edge, PixelRegion region, BodyBoundary boundary,
                                                     BufferedImage image) {
        int maxSteps = Math.min(32, Math.max(24, Math.max(1, region.getHeight())));
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        PixelRegion expanded = region;
        for (Side side : Side.values()) {
            // 正文侧本来已有框内空白时不额外扩出 2px，避免无意义侵占 8px 安全带。
            // 其余三侧保留既有的两条空白边界契约，供擦除器安全覆盖抗锯齿笔画。
            if (side == towardBodySide(edge)
                    && !sideTouchesCandidateBox(image, expanded, side, backgroundLum)) {
                continue;
            }
            expanded = expandSide(edge, expanded, boundary, image, side, maxSteps, backgroundLum);
            if (expanded == null) {
                return null;
            }
        }
        return expanded.getWidth() > 0 && expanded.getHeight() > 0 ? expanded : null;
    }

    /** 判断候选框指定边缘的单像素扫描线是否仍含目标墨迹。 */
    private static boolean sideTouchesCandidateBox(BufferedImage image, PixelRegion region, Side side,
                                                   int backgroundLum) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        if (side == Side.LEFT) return bandHasErasableInk(image, left, top, left + 1, bottom, backgroundLum);
        if (side == Side.RIGHT) return bandHasErasableInk(image, right - 1, top, right, bottom, backgroundLum);
        if (side == Side.TOP) return bandHasErasableInk(image, left, top, right, top + 1, backgroundLum);
        return bandHasErasableInk(image, left, bottom - 1, right, bottom, backgroundLum);
    }

    /** 将页码所在页面边缘转换为朝正文的候选框侧边。 */
    private static Side towardBodySide(Edge edge) {
        if (edge == Edge.TOP) return Side.BOTTOM;
        if (edge == Edge.BOTTOM) return Side.TOP;
        if (edge == Edge.LEFT) return Side.RIGHT;
        return Side.LEFT;
    }

    /**
     * 沿一个具体 side 逐扫描线扩展候选框，直到出现两条空白扫描线，并检查正文安全带和页面边界。
     *
     * @param edge 页码语义所在边缘
     * @param region 原始像素候选框
     * @param boundary 正文边界
     * @param image 原图
     * @param side 本次扩展方向
     * @param maxSteps 最大扫描像素数
     * @param backgroundLum 局部背景亮度
     * @return 找到两条空白线后的扩展框，或无法证明安全时返回 {@code null}
     */
    private static PixelRegion expandSide(Edge edge, PixelRegion region, BodyBoundary boundary, BufferedImage image,
                                          Side side, int maxSteps, int backgroundLum) {
        int originalLeft = region.getX();
        int originalTop = region.getY();
        int originalRight = originalLeft + region.getWidth();
        int originalBottom = originalTop + region.getHeight();
        int blanks = 0;
        for (int step = 1; step <= maxSteps; step++) {
            int left = originalLeft;
            int top = originalTop;
            int right = originalRight;
            int bottom = originalBottom;
            if (side == Side.LEFT) {
                left = originalLeft - step;
                right = left + 1;
            }
            if (side == Side.RIGHT) {
                left = originalRight + step - 1;
                right = left + 1;
            }
            if (side == Side.TOP) {
                top = originalTop - step;
                bottom = top + 1;
            }
            if (side == Side.BOTTOM) {
                top = originalBottom + step - 1;
                bottom = top + 1;
            }
            // 页码色块可能直接印到物理页边。仅沿“远离正文”的同一页面边缘扩展时，
            // 图像边界可作为最终安全边界；朝正文的一侧仍必须找到两条真实空白扫描线。
            if (left < 0 || top < 0 || right > image.getWidth() || bottom > image.getHeight()) {
                return isTowardPhysicalPageEdge(edge, side)
                        ? expandToPhysicalPageEdge(region, side, image)
                        : null;
            }
            if (crossesBodySafetyBand(edge, side, left, top, right, bottom, boundary, image)) {
                return null;
            }
            if (bandHasErasableInk(image, left, top, right, bottom, backgroundLum)) {
                blanks = 0;
                continue;
            }
            if (++blanks == 2) {
                int expandedLeft = side == Side.LEFT ? left : originalLeft;
                int expandedTop = side == Side.TOP ? top : originalTop;
                int expandedRight = side == Side.RIGHT ? right : originalRight;
                int expandedBottom = side == Side.BOTTOM ? bottom : originalBottom;
                return new PixelRegion(region.getPageId(), region.getRegionId(), expandedLeft, expandedTop,
                        expandedRight - expandedLeft, expandedBottom - expandedTop, region.getX1(), region.getY1(),
                        // 这只是把已被模型框住的同一页码笔画扩到相邻空白边界，不是从空框
                        // 搜索或替换目标，因而不应额外触发 VLM 精定位。
                        region.getX2(), region.getY2(), region.getConfidence(), region.isCoordinateRescued());
            }
        }
        return null;
    }

    /** 判断扩展方向是否远离正文并朝向物理页面边缘。 */
    private static boolean isTowardPhysicalPageEdge(Edge edge, Side side) {
        return (edge == Edge.TOP && side == Side.TOP)
                || (edge == Edge.BOTTOM && side == Side.BOTTOM)
                || (edge == Edge.LEFT && side == Side.LEFT)
                || (edge == Edge.RIGHT && side == Side.RIGHT);
    }

    /** 将候选框沿远离正文的方向扩展到原图物理边界。 */
    private static PixelRegion expandToPhysicalPageEdge(PixelRegion region, Side side, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        if (side == Side.LEFT) left = 0;
        if (side == Side.RIGHT) right = image.getWidth();
        if (side == Side.TOP) top = 0;
        if (side == Side.BOTTOM) bottom = image.getHeight();
        return new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(),
                region.isCoordinateRescued());
    }

    /** 判断扩展扫描线是否越过正文边界前的 8px 安全带；只有朝正文的一侧受该边界限制。 */
    private static boolean crossesBodySafetyBand(Edge edge, Side side, int left, int top, int right, int bottom,
                                                 BodyBoundary boundary, BufferedImage image) {
        int limit = bodyLimit(edge, boundary, image);
        if (limit < 0) return true;
        if (edge == Edge.TOP && side == Side.BOTTOM) return bottom > limit - MIN_BODY_GAP_PIXELS;
        if (edge == Edge.BOTTOM && side == Side.TOP) return top < limit + MIN_BODY_GAP_PIXELS;
        if (edge == Edge.LEFT && side == Side.RIGHT) return right > limit - MIN_BODY_GAP_PIXELS;
        if (edge == Edge.RIGHT && side == Side.LEFT) return left < limit + MIN_BODY_GAP_PIXELS;
        return false;
    }

    /*
     * Four directions are deliberately evaluated against the already-expanded box. This prevents a blank
     * row/column being proven only across the original tight model box while an added neighbouring glyph
     * still reaches the final corner.
     */
    private enum Side {
        LEFT, TOP, RIGHT, BOTTOM
    }

    /** 将像素坐标限制在闭区间 {@code [min,max]} 内。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 根据整图归一化候选框是否落在 20% 边缘带内，返回其页面边缘方向。 */
    private static Edge edge(EraseRegion region) {
        if (region.y2 <= EDGE_BAND) {
            return Edge.TOP;
        }
        if (region.y1 >= 1 - EDGE_BAND) {
            return Edge.BOTTOM;
        }
        if (region.x2 <= EDGE_BAND) {
            return Edge.LEFT;
        }
        if (region.x1 >= 1 - EDGE_BAND) {
            return Edge.RIGHT;
        }
        return Edge.NONE;
    }

    /** 候选框必须位于 20% 边缘带；极端横向短条页允许中心落入对应 25% 特例，之后仍需像素门禁。 */
    private static Edge edgeForValidation(EraseRegion region, BufferedImage image) {
        Edge strict = edge(region);
        if (strict != Edge.NONE || !isExtremeHorizontalStrip(image)
                || region.y2 - region.y1 > EDGE_BAND) {
            return strict;
        }
        double centerY = (region.y1 + region.y2) / 2.0;
        if (centerY <= 0.25) return Edge.TOP;
        if (centerY >= 0.75) return Edge.BOTTOM;
        return Edge.NONE;
    }

    /** 判断图片是否为宽高比至少 4:1 的极端横向条带图。 */
    private static boolean isExtremeHorizontalStrip(BufferedImage image) {
        return image != null && image.getHeight() > 0
                && image.getWidth() >= image.getHeight() * 4;
    }

    /**
     * 校验 VLM 正文边界是否存在且属于当前边缘方向。
     *
     * @param edge 候选框所在边缘
     * @param boundary VLM 给出的最近正文边界
     * @return {@code null} 表示合法；否则返回拒绝原因
     */
    private static String invalidBodyBoundaryReason(Edge edge, BodyBoundary boundary) {
        if (boundary == null) {
            return "body blank gap is insufficient";
        }
        if (edge == Edge.TOP || edge == Edge.BOTTOM) {
            if (boundary.y == null) {
                return "body blank gap is insufficient";
            }
            if (!finite(boundary.y)) {
                return "body boundary y must be finite";
            }
            if (boundary.y < 0 || boundary.y > 1) {
                return "body boundary y must be between 0 and 1";
            }
        } else {
            if (boundary.x == null) {
                return "body blank gap is insufficient";
            }
            if (!finite(boundary.x)) {
                return "body boundary x must be finite";
            }
            if (boundary.x < 0 || boundary.x > 1) {
                return "body boundary x must be between 0 and 1";
            }
        }
        return null;
    }

    /** 判断正文边界是否仅缺失当前 TOP/BOTTOM 所需的 y 或 LEFT/RIGHT 所需的 x。 */
    private static boolean isMissingDirectionalBoundary(Edge edge, BodyBoundary boundary) {
        return boundary == null || ((edge == Edge.TOP || edge == Edge.BOTTOM) ? boundary.y == null : boundary.x == null);
    }

    /**
     * 当模型漏报方向边界时，仅用候选框朝正文侧 16px 连续无墨带推导保守边界；
     * 畸形或越界边界仍由协议校验拒绝。
     *
     * @param edge 候选框所在边缘
     * @param region 原图像素候选框
     * @param image 旋正后的原图
     * @return 像素证据充分时返回归一化边界，否则返回 {@code null}
     */
    private static BodyBoundary inferBoundaryFromBlankBand(Edge edge, PixelRegion region, BufferedImage image) {
        final int required = 16;
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        boolean blank;
        BodyBoundary boundary = new BodyBoundary();
        boundary.basis = "java_16px_continuous_blank_band";
        if (edge == Edge.TOP) {
            blank = !bandHasInk(image, region, region.getX(), bottom, right, bottom + required);
            if (blank) {
                boundary.x = null;
                boundary.y = (bottom + required) / (double) image.getHeight();
                return boundary;
            }
        } else if (edge == Edge.BOTTOM) {
            blank = !bandHasInk(image, region, region.getX(), region.getY() - required, right, region.getY());
            if (blank) {
                boundary.x = null;
                boundary.y = (region.getY() - required) / (double) image.getHeight();
                return boundary;
            }
        } else if (edge == Edge.LEFT) {
            blank = !bandHasInk(image, region, right, region.getY(), right + required, bottom);
            if (blank) {
                boundary.x = (right + required) / (double) image.getWidth();
                boundary.y = null;
                return boundary;
            }
        } else if (edge == Edge.RIGHT) {
            blank = !bandHasInk(image, region, region.getX() - required, region.getY(), region.getX(), bottom);
            if (blank) {
                boundary.x = (region.getX() - required) / (double) image.getWidth();
                boundary.y = null;
                return boundary;
            }
        }
        return null;
    }

    /**
     * 检查候选框与正文边界之间的像素安全带。
     *
     * @param edge     候选框所在页面边缘，决定正文安全带位于候选框的哪一侧
     * @param region   已从归一化坐标映射得到的原图像素候选框
     * @param boundary VLM 返回或 Java 像素证据推导出的最近正文边界
     * @param image    旋正后的原图，所有像素坐标均相对于该图
     * @return {@code null} 表示距离和安全带均通过；否则返回稳定的拒绝原因
     */
    private static String invalidPixelGapReason(Edge edge, PixelRegion region, BodyBoundary boundary, BufferedImage image) {
        /*
         * 将归一化正文边界转换为原图像素后，沿页码与正文相邻方向取固定安全带。先检查
         * 几何距离，再检查安全带内实际墨迹；两者任一不满足就拒绝。这样即使 VLM 的
         * boundary 坐标有偏移，像素证据仍能阻止候选框贴近正文。
         */
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) {
            // TOP：页面纵向示意：C(上) | G=[bottom,bottom+8) | B(下)。
            int bodyY = (int) Math.floor(boundary.y * image.getHeight());
            if (bodyY - bottom < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            /*
             * bandHasBlockingInk 实际圈选的像素矩形（原图像素坐标）：
             *   左上角 (x1,y1) = (region.x, region.y + region.height)
             *   右下角 (x2,y2) = (region.x + region.width, region.y + region.height + 8)
             * 即候选框正下方 8px 横条；x2/y2 为 exclusive 边界，不包含候选框本身。
             */
            return bandHasBlockingInk(image, region, region.getX(), bottom, right, bottom + MIN_BODY_GAP_PIXELS, boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.BOTTOM) {
            // BOTTOM：页面纵向示意：B(上) | G=[top-8,top) | C(下)。
            int bodyY = (int) Math.ceil(boundary.y * image.getHeight());
            if (region.getY() - bodyY < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            /*
             * bandHasBlockingInk 实际圈选的像素矩形（原图像素坐标）：
             *   左上角 (x1,y1) = (region.x, region.y - 8)
             *   右下角 (x2,y2) = (region.x + region.width, region.y)
             * 即候选框正上方 8px 横条；x2/y2 为 exclusive 边界。
             */
            return bandHasBlockingInk(image, region, region.getX(), region.getY() - MIN_BODY_GAP_PIXELS, right, region.getY(), boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.LEFT) {
            // LEFT：页面横向示意：C(左) | G=[right,right+8) | B(右)。
            int bodyX = (int) Math.floor(boundary.x * image.getWidth());
            if (bodyX - right < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            /*
             * bandHasBlockingInk 实际圈选的像素矩形（原图像素坐标）：
             *   左上角 (x1,y1) = (region.x + region.width, region.y)
             *   右下角 (x2,y2) = (region.x + region.width + 8, region.y + region.height)
             * 即候选框正右方 8px 竖条；x2/y2 为 exclusive 边界。
             */
            return bandHasBlockingInk(image, region, right, region.getY(), right + MIN_BODY_GAP_PIXELS, bottom, boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.RIGHT) {
            // RIGHT：页面横向示意：B(左) | G=[left-8,left) | C(右)。
            int bodyX = (int) Math.ceil(boundary.x * image.getWidth());
            if (region.getX() - bodyX < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            /*
             * bandHasBlockingInk 实际圈选的像素矩形（原图像素坐标）：
             *   左上角 (x1,y1) = (region.x - 8, region.y)
             *   右下角 (x2,y2) = (region.x, region.y + region.height)
             * 即候选框正左方 8px 竖条；x2/y2 为 exclusive 边界。
             */
            return bandHasBlockingInk(image, region, region.getX() - MIN_BODY_GAP_PIXELS, region.getY(), region.getX(), bottom, boundary)
                    ? "body blank gap contains ink" : null;
        }
        return "body blank gap is insufficient";
    }

    /**
     * 判断正文安全带中是否存在足以阻断自动擦除的墨迹。
     *
     * @param image    旋正后的原图
     * @param region   当前候选框，用于估计局部背景亮度
     * @param left     安全带左边界，等价于矩形左上角 x1，包含
     * @param top      安全带上边界，等价于矩形左上角 y1，包含
     * @param right    安全带右边界，等价于矩形右下角 x2，不包含
     * @param bottom   安全带下边界，等价于矩形右下角 y2，不包含
     * @param boundary 正文边界证据，用于判断是否允许局部浅影豁免
     * @return {@code true} 表示发现正文级墨迹，候选不得自动写图
     */
    private static boolean bandHasBlockingInk(BufferedImage image, PixelRegion region,
                                              int left, int top, int right, int bottom, BodyBoundary boundary) {
        /*
         * 核心正文保护算法：先用 bandHasInk 做“任何墨迹”快速筛查，再测量墨迹包围盒。
         * 位于批准框外的单根细竖线/横线（轴向厚度不超过 4px）可以视为分栏线、装订线
         * 或裁切线；除此之外的多点、多行、块状墨迹全部阻断。此方法只检查、不擦除安全带，
         * 返回 true 的含义是“当前候选不能自动写图”，不是“发现了可擦除目标”。
         */
        if (!bandHasInk(image, region, left, top, right, bottom)) return false;
        // 这条 boundary 已由上一步同一像素带的“局部精框冲突替换”产生。完整复核必须沿用
        // 同一份孤立噪点判据，否则会出现替换成功、随即又被同一两个灰点否决的自相矛盾。
        // 仅该 Java 证据可用；普通 VLM boundary 仍保持任意可疑墨迹失败关闭。
        if (isPixelProvenReplacement(boundary)
                && !bandHasSubstantiveInk(image, region, left, top, right, bottom)) {
            return false;
        }
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x++) {
                if (isBlockingGapMark(image.getRGB(x, y), backgroundLum, boundary)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        boolean thinVertical = width <= 4 && height >= 4;
        boolean thinHorizontal = height <= 4 && width >= 4;
        return !(thinVertical || thinHorizontal);
    }

    /** 判断正文边界是否来自 Java 8px 像素空白证据替换，而非直接来自 VLM。 */
    private static boolean isPixelProvenReplacement(BodyBoundary boundary) {
        return boundary != null && boundary.basis != null
                && boundary.basis.startsWith("java_8px_blank_band_replaced_conflicting_vlm_boundary");
    }

    /** 将安全带内像素按普通墨迹、背透浅影和彩色标记分类；仅明确排除的浅影可豁免。 */
    private static boolean isBlockingGapMark(int argb, int backgroundLum, BodyBoundary boundary) {
        if (!isConservativeMark(argb, backgroundLum)) return false;
        if (!declaresBleedExcluded(boundary)) return true;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        return luminance <= backgroundLum - 55 || BackgroundEstimator.isColoredMark(argb);
    }

    /** 判断正文边界证据是否明确声明已经排除背透、透印或浅影。 */
    private static boolean declaresBleedExcluded(BodyBoundary boundary) {
        String basis = boundary == null ? "" : boundary.basis;
        return basis != null && (basis.contains("背透") || basis.contains("透印") || basis.contains("浅影"));
    }

    /** 沿同一目标的连续墨迹方向扩展像素框，最多 32px；连续两条空白扫描线立即停止。 */
    private static PixelRegion expandConnectedTargetInk(Edge edge, PixelRegion region,
                                                          BodyBoundary boundary, BufferedImage image) {
        int bodyLimit = bodyLimit(edge, boundary, image);
        int maxSteps = Math.min(32, Math.max(24, Math.max(1, region.getHeight())));
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        if (bodyLimit < 0 || maxSteps <= 0) {
            return region;
        }
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        int originalLeft = left;
        int originalTop = top;
        int originalRight = right;
        int originalBottom = bottom;
        int blankLines = 0;
        for (int step = 1; step <= maxSteps; step++) {
            int scanLeft = left, scanTop = top, scanRight = right, scanBottom = bottom;
            if (edge == Edge.TOP) {
                scanTop = originalBottom + step - 1;
                scanBottom = scanTop + 1;
            }
            if (edge == Edge.BOTTOM) {
                scanBottom = originalTop - step + 1;
                scanTop = scanBottom - 1;
            }
            if (edge == Edge.LEFT) {
                scanLeft = originalRight + step - 1;
                scanRight = scanLeft + 1;
            }
            if (edge == Edge.RIGHT) {
                scanRight = originalLeft - step + 1;
                scanLeft = scanRight - 1;
            }
            if (scanLeft < 0 || scanTop < 0 || scanRight > image.getWidth() || scanBottom > image.getHeight()) break;
            if (bandHasErasableInk(image, scanLeft, scanTop, scanRight, scanBottom, backgroundLum)) {
                blankLines = 0;
                if (edge == Edge.TOP) bottom = scanBottom;
                if (edge == Edge.BOTTOM) top = scanTop;
                if (edge == Edge.LEFT) right = scanRight;
                if (edge == Edge.RIGHT) left = scanLeft;
            } else if (++blankLines >= 2) {
                break;
            }
        }
        PixelRegion expanded = new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(),
                left != region.getX() || top != region.getY() || right != region.getX() + region.getWidth() || bottom != region.getY() + region.getHeight());
        return remainsInEdgeBand(edge, expanded, image) ? expanded : region;
    }

    /** 处理模型语义正确但框偏到空白处的情形：只在框与正文边界之间的同侧走廊找回墨迹。 */
    private static PixelRegion rescueEmptyModelBox(Edge edge, PixelRegion region, int bodyLimit, BufferedImage image) {
        Bounds corridor = inwardCorridor(edge, region, bodyLimit);
        if (corridor == null || !corridor.isInside(image)) {
            return region;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        for (int y = corridor.top; y < corridor.bottom; y++) {
            for (int x = corridor.left; x < corridor.right; x++) {
                if (!BackgroundEstimator.isErasableInk(image.getRGB(x, y), backgroundLum, false)) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return region;
        }
        return paddedExpandedRegion(edge, region, minX, minY, maxX, maxY, bodyLimit, image);
    }

    /** 根据候选框和正文限制构造仅向同一页边缘内侧延伸的像素搜索走廊。 */
    private static Bounds inwardCorridor(Edge edge, PixelRegion region, int bodyLimit) {
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) {
            return new Bounds(region.getX(), bottom, right, bodyLimit - MIN_BODY_GAP_PIXELS);
        }
        if (edge == Edge.BOTTOM) {
            return new Bounds(region.getX(), bodyLimit + MIN_BODY_GAP_PIXELS, right, region.getY());
        }
        if (edge == Edge.LEFT) {
            return new Bounds(right, region.getY(), bodyLimit - MIN_BODY_GAP_PIXELS, bottom);
        }
        if (edge == Edge.RIGHT) {
            return new Bounds(bodyLimit + MIN_BODY_GAP_PIXELS, region.getY(), region.getX(), bottom);
        }
        return null;
    }

    /** 将正文边界转换为该页面边缘方向上的像素限制线；无有效边界返回 -1。 */
    private static int bodyLimit(Edge edge, BodyBoundary boundary, BufferedImage image) {
        if (edge == Edge.TOP) return (int) Math.floor(boundary.y * image.getHeight());
        if (edge == Edge.BOTTOM) return (int) Math.ceil(boundary.y * image.getHeight());
        if (edge == Edge.LEFT) return (int) Math.floor(boundary.x * image.getWidth());
        if (edge == Edge.RIGHT) return (int) Math.ceil(boundary.x * image.getWidth());
        return -1;
    }

    /** 将走廊中找回的墨迹包围盒补入少量抗锯齿边距，并再次验证正文间隔和边缘带。 */
    private static PixelRegion paddedExpandedRegion(Edge edge, PixelRegion region, int minX, int minY, int maxX,
                                                    int maxY, int bodyLimit, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) bottom = Math.max(bottom, maxY + 2);
        if (edge == Edge.BOTTOM) top = Math.min(top, minY - 1);
        if (edge == Edge.LEFT) right = Math.max(right, maxX + 2);
        if (edge == Edge.RIGHT) left = Math.min(left, minX - 1);
        if ((edge == Edge.TOP && bodyLimit - bottom < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.BOTTOM && top - bodyLimit < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.LEFT && bodyLimit - right < MIN_BODY_GAP_PIXELS)
                || (edge == Edge.RIGHT && left - bodyLimit < MIN_BODY_GAP_PIXELS)) {
            return region;
        }
        PixelRegion expanded = new PixelRegion(region.getPageId(), region.getRegionId(), left, top, right - left, bottom - top,
                region.getX1(), region.getY1(), region.getX2(), region.getY2(), region.getConfidence(), true);
        return remainsInEdgeBand(edge, expanded, image) ? expanded : region;
    }

    /** 判断扩展后的像素框是否仍位于允许的页面边缘带内。 */
    private static boolean remainsInEdgeBand(Edge edge, PixelRegion region, BufferedImage image) {
        if (isExtremeHorizontalStrip(image) && (edge == Edge.TOP || edge == Edge.BOTTOM)
                && region.getHeight() <= Math.ceil(EDGE_BAND * image.getHeight())) {
            double centerY = (region.getY() + region.getHeight() / 2.0) / image.getHeight();
            return edge == Edge.TOP ? centerY <= 0.25 : centerY >= 0.75;
        }
        if (edge == Edge.TOP) return region.getY() + region.getHeight() <= Math.ceil(EDGE_BAND * image.getHeight());
        if (edge == Edge.BOTTOM) return region.getY() >= Math.floor((1 - EDGE_BAND) * image.getHeight());
        if (edge == Edge.LEFT) return region.getX() + region.getWidth() <= Math.ceil(EDGE_BAND * image.getWidth());
        if (edge == Edge.RIGHT) return region.getX() >= Math.floor((1 - EDGE_BAND) * image.getWidth());
        return false;
    }

    /**
     * 对指定像素条带做严格“是否有墨迹”扫描。
     *
     * @param image           旋正后的原图
     * @param reference       用于估计局部背景亮度的候选框；不是本次扫描区域
     * @param left            条带左边界，包含
     * @param top             条带上边界，包含
     * @param rightExclusive  条带右边界，不包含
     * @param bottomExclusive 条带下边界，不包含
     * @return {@code true} 表示至少有一个保守墨迹，或输入越界/为空而无法证明安全；否则为 {@code false}
     */
    private static boolean bandHasInk(BufferedImage image, PixelRegion reference, int left, int top,
                                      int rightExclusive, int bottomExclusive) {
        /*
         * 严格正文安全带扫描器：调用方传入候选框朝正文方向的 8/16px 条带，本方法只回答
         * “条带内是否存在任何保守墨迹”。背景亮度取候选框局部中位数，避免整页阴影拉偏
         * 阈值；越界、空矩形等异常直接返回 true，按“无法证明为空白”失败关闭。返回 true
         * 不代表这些像素会被擦除，而是表示候选框与正文之间的安全距离无法被证明。
         */
        if (left < 0 || top < 0 || rightExclusive > image.getWidth() || bottomExclusive > image.getHeight()
                || left >= rightExclusive || top >= bottomExclusive) {
            return true;
        }
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, reference);
        for (int y = top; y < bottomExclusive; y++) {
            for (int x = left; x < rightExclusive; x++) {
                if (isConservativeMark(image.getRGB(x, y), backgroundLum)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 严格模式的实质墨迹扫描，不允许细分隔线豁免；局部边界替换使用带豁免的重载方法。 */
    private static boolean bandHasSubstantiveInk(BufferedImage image, PixelRegion reference, int left, int top,
                                                   int rightExclusive, int bottomExclusive) {
        return bandHasSubstantiveInk(image, reference, left, top, rightExclusive, bottomExclusive, false);
    }

    /** 统计安全带墨迹；可选地忽略单根细轴向分隔线，但不忽略文字或块状正文。 */
    private static boolean bandHasSubstantiveInk(BufferedImage image, PixelRegion reference, int left, int top,
                                                   int rightExclusive, int bottomExclusive, boolean ignoreThinAxisDivider) {
        /*
         * 这是仅供“Java 像素证据替换 VLM 正文边界”使用的孤立噪点豁免扫描。统计墨点总数、
         * 单行峰值和墨点包围盒：连续笔画/文字会形成足够证据，1~2 个压缩灰点不会推翻
         * 安全带。ignoreThinAxisDivider=true 时，仅单轴细长的分栏/装订线可以豁免；普通
         * locate 校验仍使用严格模式，防止真实题干或表格边框被当作噪点。
         */
        if (left < 0 || top < 0 || rightExclusive > image.getWidth() || bottomExclusive > image.getHeight()
                || left >= rightExclusive || top >= bottomExclusive) {
            return true;
        }
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, reference);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int totalMarks = 0;
        int maxRowMarks = 0;
        for (int y = top; y < bottomExclusive; y++) {
            int rowMarks = 0;
            for (int x = left; x < rightExclusive; x++) {
                if (isConservativeMark(image.getRGB(x, y), backgroundLum)) {
                    rowMarks++;
                    totalMarks++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    if (!ignoreThinAxisDivider && rowMarks >= 3) return true;
                }
            }
            maxRowMarks = Math.max(maxRowMarks, rowMarks);
        }
        if (minX == Integer.MAX_VALUE) return false;
        if (!ignoreThinAxisDivider) return totalMarks >= 6;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (ignoreThinAxisDivider && isSingleAxisThinDivider(width, height)) return false;
        return totalMarks >= 6 || maxRowMarks >= 3;
    }

    /** 判断墨迹包围盒是否为可豁免的单轴细分隔线。 */
    private static boolean isSingleAxisThinDivider(int width, int height) {
        return (width <= 4 && height >= Math.max(4, width * 2))
                || (height <= 4 && width >= Math.max(4, height * 2));
    }

    /**
     * 扫描候选框内部是否存在深色或彩色保守墨迹。
     *
     * @param image 原图
     * @param region 原图像素候选框
     * @return {@code true} 表示框内存在可见墨迹
     */
    private static boolean hasConservativeInk(BufferedImage image, PixelRegion region) {
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        for (int y = region.getY(); y < region.getY() + region.getHeight(); y++) {
            for (int x = region.getX(); x < region.getX() + region.getWidth(); x++) {
                if (isConservativeMark(image.getRGB(x, y), backgroundLum)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 判断候选框四条边是否有墨迹；贴边表示框可能切穿目标，不能直接整框擦除。 */
    private static boolean maskTouchesCandidateBox(BufferedImage image, PixelRegion region) {
        /*
         * 检查候选矩形四条边是否已有墨迹。边缘有墨通常意味着 VLM 框切穿页码笔画或没有
         * 覆盖完整目标；即使主体在框内，也必须先拒绝/精修，不能直接整框重建。
         */
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        int right = region.getX() + region.getWidth() - 1;
        int bottom = region.getY() + region.getHeight() - 1;
        for (int x = region.getX(); x <= right; x++) {
            if (isConservativeMark(image.getRGB(x, region.getY()), backgroundLum)
                    || isConservativeMark(image.getRGB(x, bottom), backgroundLum)) {
                return true;
            }
        }
        for (int y = region.getY(); y <= bottom; y++) {
            if (isConservativeMark(image.getRGB(region.getX(), y), backgroundLum)
                    || isConservativeMark(image.getRGB(right, y), backgroundLum)) {
                return true;
            }
        }
        return false;
    }

    /** 判断指定扫描线/矩形是否仍有可与目标连通的墨迹；越界按有墨处理以失败关闭。 */
    private static boolean bandHasErasableInk(BufferedImage image, int left, int top, int rightExclusive,
                                               int bottomExclusive, int backgroundLum) {
        /*
         * 供沿目标朝正文方向逐扫描线扩框使用。返回 true 表示扫描线仍有与目标相连的墨迹，
         * 可以继续扩展；返回 false 表示遇到空白，外扩应停止。越界按 true 处理，避免坐标
         * 异常被误当成空白而扩大擦除范围。
         */
        if (left < 0 || top < 0 || rightExclusive > image.getWidth() || bottomExclusive > image.getHeight()
                || left >= rightExclusive || top >= bottomExclusive) {
            return true;
        }
        for (int y = top; y < bottomExclusive; y++) {
            for (int x = left; x < rightExclusive; x++) {
                if (isConservativeMark(image.getRGB(x, y), backgroundLum)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 将单个像素按深色墨迹或彩色内容统一分类为保守风险墨迹，防止扩框跨过彩色正文。 */
    private static boolean isConservativeMark(int argb, int backgroundLum) {
        /*
         * 保守墨迹是正文保护的统一底层判据：深色文字/线条和明显彩色内容都算风险。彩色
         * 内容是否确属页码由更高层 verify 授权；在安全带和扩框阶段必须先按风险处理，
         * 防止跨过彩色标题、表格或正文。
         */
        return BackgroundEstimator.isErasableInk(argb, backgroundLum, false)
                || BackgroundEstimator.isColoredMark(argb);
    }

    private enum Edge {
        TOP, BOTTOM, LEFT, RIGHT, NONE
    }

    private static final class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        /** 返回半开像素框宽度。 */
        private int width() {
            return right - left;
        }

        /** 返回半开像素框高度。 */
        private int height() {
            return bottom - top;
        }

        /** 判断该像素框是否在整图范围内且具有正面积。 */
        private boolean isInside(BufferedImage image) {
            return left >= 0 && top >= 0 && right <= image.getWidth() && bottom <= image.getHeight()
                    && left < right && top < bottom;
        }
    }

    /** RegionValidator 的输入适配对象：把 locate 结果和正文边界绑定到同一页面。 */
    public static final class PageLocateResult {
        private final String pageId;
        private final String status;
        private final List<EraseRegion> regions;
        private final BodyBoundary nearestBodyBoundary;

        public PageLocateResult(String pageId, String status, List<EraseRegion> regions, BodyBoundary nearestBodyBoundary) {
            this.pageId = pageId;
            this.status = status;
            this.regions = regions == null ? null : Collections.unmodifiableList(new ArrayList<EraseRegion>(regions));
            this.nearestBodyBoundary = nearestBodyBoundary;
        }

        /** 返回页面稳定标识。 */
        public String getPageId() {
            return pageId;
        }

        /** 返回 locate 状态。 */
        public String getStatus() {
            return status;
        }

        /** 返回 VLM 候选区域列表的不可变视图。 */
        public List<EraseRegion> getRegions() {
            return regions;
        }

        /** 返回最近正文边界证据。 */
        public BodyBoundary getNearestBodyBoundary() {
            return nearestBodyBoundary;
        }
    }

    /** 正文保护门禁结果：通过时携带可擦除像素框，失败时携带全部拒绝原因。 */
    public static final class ValidationResult {
        private final boolean accepted;
        private final List<PixelRegion> regions;
        private final List<String> reasons;

        private ValidationResult(boolean accepted, List<PixelRegion> regions, List<String> reasons) {
            this.accepted = accepted;
            this.regions = Collections.unmodifiableList(new ArrayList<PixelRegion>(regions));
            this.reasons = Collections.unmodifiableList(new ArrayList<String>(reasons));
        }

        private static ValidationResult rejected(String reason) {
            return new ValidationResult(false, Collections.<PixelRegion>emptyList(), Collections.singletonList(reason));
        }

        public static ValidationResult rejectedResult(String reason) {
            return rejected(reason);
        }

        /**
         * 多个彼此独立的候选框已分别走完同一套完整门禁时，流水线可汇总这些像素区域。
         * 该工厂不用于跳过校验：调用方必须只传入来自 {@link RegionValidator#validate}
         * 的已批准区域。
         */
        public static ValidationResult acceptedResult(List<PixelRegion> regions) {
            return new ValidationResult(true, regions, Collections.<String>emptyList());
        }

        /** 返回是否已通过全部正文保护门禁。 */
        public boolean isAccepted() {
            return accepted;
        }

        /** 返回通过校验、可交给擦除器的像素区域；拒绝结果为空。 */
        public List<PixelRegion> getRegions() {
            return regions;
        }

        /** 返回全部拒绝原因，供报告和人工审核定位失败步骤。 */
        public List<String> getReasons() {
            return reasons;
        }
    }

    /** 像素候选框：x/y/width/height 是整图像素矩形，x1..y2 是来源归一化坐标。 */
    public static final class PixelRegion {
        /**
         * 页面稳定标识，不参与像素计算，用于把门禁证据与原始页面对应起来。
         */
        private final String pageId;
        /**
         * 同一页内的候选区域稳定标识，用于局部精修、审计和日志关联。
         */
        private final String regionId;
        /**
         * 原图像素坐标的左上角 X；与 BufferedImage 的列坐标一致，单位为 px。
         */
        private final int x;
        /**
         * 原图像素坐标的左上角 Y；与 BufferedImage 的行坐标一致，单位为 px。
         */
        private final int y;
        /**
         * 像素区域宽度；实际覆盖列为 x <= col < x + width。
         */
        private final int width;
        /**
         * 像素区域高度；实际覆盖行为 y <= row < y + height。
         */
        private final int height;
        /**
         * VLM/业务坐标系中的归一化左边界，范围通常为 0..1，不是像素列号。
         */
        private final double x1;
        /**
         * VLM/业务坐标系中的归一化上边界，范围通常为 0..1，不是像素行号。
         */
        private final double y1;
        /**
         * VLM/业务坐标系中的归一化右边界；映射像素时作为右侧 exclusive 边界。
         */
        private final double x2;
        /**
         * VLM/业务坐标系中的归一化下边界；映射像素时作为下侧 exclusive 边界。
         */
        private final double y2;
        /**
         * 模型对候选语义的置信度；不等同于 Java 像素安全校验结果。
         */
        private final double confidence;
        /**
         * Java 是否曾对模型候选做过像素级坐标救援/扩展；为 true 时会提升 verify 风险。
         */
        private final boolean coordinateRescued;

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence) {
            this(pageId, regionId, x, y, width, height, x1, y1, x2, y2, confidence, false);
        }

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence, boolean coordinateRescued) {
            /*
             * 一个 PixelRegion 同时保存两套坐标，是为了把“模型证据”和“工程写图坐标”绑定
             * 在同一个不可变对象里：x/y/width/height 用于逐像素扫描、掩码和擦除；x1/y1/x2/y2
             * 保留模型原始归一化框，用于日志、ROI 变换、审计和判断是否发生坐标救援。两套值
             * 表示同一候选框的不同坐标系，不能混用，也不能用归一化值直接访问 BufferedImage。
             */
            this.pageId = pageId;
            this.regionId = regionId;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.confidence = confidence;
            this.coordinateRescued = coordinateRescued;
        }

        /** 返回页面稳定标识。 */
        public String getPageId() {
            return pageId;
        }

        /** 返回候选区域稳定标识。 */
        public String getRegionId() {
            return regionId;
        }

        /** 返回像素框左上角 X。 */
        public int getX() {
            return x;
        }

        /** 返回像素框左上角 Y。 */
        public int getY() {
            return y;
        }

        /** 返回像素框宽度，右边界为 x+width exclusive。 */
        public int getWidth() {
            return width;
        }

        /** 返回像素框高度，下边界为 y+height exclusive。 */
        public int getHeight() {
            return height;
        }

        /** 返回原始归一化框左上角 X。 */
        public double getX1() {
            return x1;
        }

        /** 返回原始归一化框左上角 Y。 */
        public double getY1() {
            return y1;
        }

        /** 返回原始归一化框右下角 X。 */
        public double getX2() {
            return x2;
        }

        /** 返回原始归一化框右下角 Y。 */
        public double getY2() {
            return y2;
        }

        /** 返回 VLM 语义置信度，不代表 Java 像素门禁已通过。 */
        public double getConfidence() {
            return confidence;
        }

        /** 返回坐标是否经过 Java 像素救援或扩展。 */
        public boolean isCoordinateRescued() {
            return coordinateRescued;
        }
    }
}
