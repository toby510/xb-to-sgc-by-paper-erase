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

    public static ValidationResult validate(PageLocateResult locateResult, BufferedImage image) {
        if (locateResult == null) {
            return ValidationResult.rejected("locate result is required");
        }
        if (image == null) {
            return ValidationResult.rejected("image is required");
        }

        List<String> reasons = new ArrayList<String>();
        List<PixelRegion> pixelRegions = new ArrayList<PixelRegion>();
        Set<String> regionIds = new HashSet<String>();

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

            // 非边缘候选不具备“页码与正文分离”的可证明条件，不能靠置信度放行。
            Edge edge = edge(region);
            if (edge == Edge.NONE) {
                reasons.add("region must be inside an edge band");
                continue;
            }
            PixelRegion pixelRegion = toPixelRegion(locateResult.getPageId(), canonicalRegionId, region, image);
            if (pixelRegion.getWidth() <= 0 || pixelRegion.getHeight() <= 0
                    || pixelRegion.getX() < 0 || pixelRegion.getY() < 0
                    || pixelRegion.getX() + pixelRegion.getWidth() > image.getWidth()
                    || pixelRegion.getY() + pixelRegion.getHeight() > image.getHeight()) {
                reasons.add("coordinates map outside image bounds");
                continue;
            }
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
            // 用真实像素空白带而非仅模型给出的归一化距离，抵抗边界坐标幻觉。
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
     * 基线投影重叠、候选内确有墨迹和朝正文方向连续 10px 无墨，即返回 {@code null}。</p>
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
        // validate 随后会把候选框向每侧吸附两条空白扫描线。因此要在最终 8px 正文安全
        // 带外额外保留 2px，合计 10px；不能错误地双倍要求 16px。
        final int requiredBlankPixels = MIN_BODY_GAP_PIXELS + 2;
        int left = refined.getX();
        int top = refined.getY();
        int right = left + refined.getWidth();
        int bottom = top + refined.getHeight();
        BodyBoundary effective = new BodyBoundary();
        if (refinedEdge == Edge.TOP) {
            if (bandHasSubstantiveInk(image, refined, left, bottom, right, bottom + requiredBlankPixels)) return null;
            effective.y = (bottom + requiredBlankPixels) / (double) image.getHeight();
        } else if (refinedEdge == Edge.BOTTOM) {
            if (bandHasSubstantiveInk(image, refined, left, top - requiredBlankPixels, right, top)) return null;
            effective.y = (top - requiredBlankPixels) / (double) image.getHeight();
        } else if (refinedEdge == Edge.LEFT) {
            if (bandHasSubstantiveInk(image, refined, right, top, right + requiredBlankPixels, bottom)) return null;
            effective.x = (right + requiredBlankPixels) / (double) image.getWidth();
        } else if (refinedEdge == Edge.RIGHT) {
            if (bandHasSubstantiveInk(image, refined, left - requiredBlankPixels, top, left, bottom)) return null;
            effective.x = (left - requiredBlankPixels) / (double) image.getWidth();
        } else {
            return null;
        }
        effective.basis = "java_10px_blank_band_replaced_conflicting_vlm_boundary"
                + "; original_basis=" + safeBasis(originalBoundary.basis)
                + "; original_x=" + originalBoundary.x + "; original_y=" + originalBoundary.y;
        return effective;
    }

    /**
     * 返回去除朝正文方向纯空白内边距后的模型框。仅调用方已处于“局部 VLM 精定位 + 正文
     * 边界冲突”路径时使用；它不会扩框、不会寻找文字，且没有检测到框内墨迹时原样返回。
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
     * 删除模型框朝正文一侧的纯空白内边距，并保留 2px 抗锯齿缓冲。该操作只缩小框，且只在
     * conflicting-boundary 的局部 VLM 精定位路径使用；没有检测到墨迹时保持原框并由后续门禁关闭。
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
     * 局部模型偶尔把页码上方/下方的独立分栏细线带入框内。只在该细线在朝正文方向连续至少
     * 4px、随后有完整空白行、并且后面确有正常文字带时才跳过它；这证明它与目标行断开，
     * 不会把页码字符当作装饰线裁掉。
     */
    private static int firstTargetInkRow(BufferedImage image, int left, int top, int right, int bottom,
                                         int backgroundLum, boolean forward) {
        int step = forward ? 1 : -1;
        int start = forward ? top : bottom - 1;
        for (int y = start; y >= top && y < bottom; y += step) {
            int ink = inkCount(image, left, y, right, y + 1, backgroundLum);
            if (ink == 0) continue;
            if (ink <= 4) {
                int lineEnd = y;
                while (lineEnd + step >= top && lineEnd + step < bottom
                        && inkCount(image, left, lineEnd + step, right, lineEnd + step + 1, backgroundLum) > 0
                        && inkCount(image, left, lineEnd + step, right, lineEnd + step + 1, backgroundLum) <= 4) {
                    lineEnd += step;
                }
                int lineLength = Math.abs(lineEnd - y) + 1;
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
     * 只用于局部 VLM 已确认的候选框与全局正文边界发生冲突时。扫描最多 32px，连续两条
     * 空白线立即停止；它不寻找新目标，也不跨越空白，因此不能把另一行内容并入页码目标。
     */
    private static PixelRegion expandTargetTowardBody(Edge edge, PixelRegion region, BufferedImage image) {
        int left = region.getX();
        int top = region.getY();
        int right = left + region.getWidth();
        int bottom = top + region.getHeight();
        int originalLeft = left, originalTop = top, originalRight = right, originalBottom = bottom;
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        int blankLines = 0;
        for (int step = 1; step <= 32; step++) {
            int scanLeft = left, scanTop = top, scanRight = right, scanBottom = bottom;
            if (edge == Edge.TOP) { scanTop = originalBottom + step - 1; scanBottom = scanTop + 1; }
            if (edge == Edge.BOTTOM) { scanBottom = originalTop - step + 1; scanTop = scanBottom - 1; }
            if (edge == Edge.LEFT) { scanLeft = originalRight + step - 1; scanRight = scanLeft + 1; }
            if (edge == Edge.RIGHT) { scanRight = originalLeft - step + 1; scanLeft = scanRight - 1; }
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

    private static boolean hasDirectionalBoundary(Edge edge, BodyBoundary boundary) {
        if (boundary == null) return false;
        Double coordinate = (edge == Edge.TOP || edge == Edge.BOTTOM) ? boundary.y : boundary.x;
        return coordinate != null && finite(coordinate) && coordinate >= 0 && coordinate <= 1;
    }

    private static boolean baselineProjectionOverlaps(EraseRegion original, EraseRegion refined, Edge edge) {
        double originalStart = (edge == Edge.TOP || edge == Edge.BOTTOM) ? original.x1 : original.y1;
        double originalEnd = (edge == Edge.TOP || edge == Edge.BOTTOM) ? original.x2 : original.y2;
        double refinedStart = (edge == Edge.TOP || edge == Edge.BOTTOM) ? refined.x1 : refined.y1;
        double refinedEnd = (edge == Edge.TOP || edge == Edge.BOTTOM) ? refined.x2 : refined.y2;
        double overlap = Math.max(0D, Math.min(originalEnd, refinedEnd) - Math.max(originalStart, refinedStart));
        return overlap / Math.min(originalEnd - originalStart, refinedEnd - refinedStart) >= 0.5D;
    }

    private static String safeBasis(String basis) {
        return basis == null ? "null" : basis.replace(';', ',');
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static PixelRegion toPixelRegion(String pageId, String regionId, EraseRegion region, BufferedImage image) {
        int left = clamp((int) Math.floor(region.x1 * image.getWidth()), 0, image.getWidth());
        int top = clamp((int) Math.floor(region.y1 * image.getHeight()), 0, image.getHeight());
        int rightExclusive = clamp((int) Math.ceil(region.x2 * image.getWidth()), 0, image.getWidth());
        int bottomExclusive = clamp((int) Math.ceil(region.y2 * image.getHeight()), 0, image.getHeight());
        return new PixelRegion(pageId, regionId, left, top, rightExclusive - left, bottomExclusive - top,
                region.x1, region.y1, region.x2, region.y2, region.confidence);
    }

    /**
     * 为模型给出的紧框建立可证明的四侧空白边界。每一侧最多向外看一个字高，至少 24px、最多
     * 32px，且必须连续遇到两条全空白扫描线；这两条线也纳入批准区域，保证擦除器与校验器
     * 使用同一个动态 erasableInk 契约。朝正文方向绝不跨越整页正文边界前的 8px 安全带。
     */
    private static PixelRegion expandToBlankBoundary(Edge edge, PixelRegion region, BodyBoundary boundary,
                                                       BufferedImage image) {
        int maxSteps = Math.min(32, Math.max(24, Math.max(1, region.getHeight())));
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, region);
        PixelRegion expanded = region;
        for (Side side : Side.values()) {
            expanded = expandSide(edge, expanded, boundary, image, side, maxSteps, backgroundLum);
            if (expanded == null) {
                return null;
            }
        }
        return expanded.getWidth() > 0 && expanded.getHeight() > 0 ? expanded : null;
    }

    /** 返回包含两条空白扫描线的候选框；null 表示未能证明该方向安全。 */
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
            if (side == Side.LEFT) { left = originalLeft - step; right = left + 1; }
            if (side == Side.RIGHT) { left = originalRight + step - 1; right = left + 1; }
            if (side == Side.TOP) { top = originalTop - step; bottom = top + 1; }
            if (side == Side.BOTTOM) { top = originalBottom + step - 1; bottom = top + 1; }
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

    private static boolean isTowardPhysicalPageEdge(Edge edge, Side side) {
        return (edge == Edge.TOP && side == Side.TOP)
                || (edge == Edge.BOTTOM && side == Side.BOTTOM)
                || (edge == Edge.LEFT && side == Side.LEFT)
                || (edge == Edge.RIGHT && side == Side.RIGHT);
    }

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

    /** 只有朝正文的一侧受正文边界限制；其余三侧仍必须自行找到连续两条空白线。 */
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

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

    private static boolean isMissingDirectionalBoundary(Edge edge, BodyBoundary boundary) {
        return boundary == null || ((edge == Edge.TOP || edge == Edge.BOTTOM) ? boundary.y == null : boundary.x == null);
    }

    /** 仅补全“缺失”边界；畸形、越界等模型协议错误仍由 {@link #invalidBodyBoundaryReason} 拒绝。 */
    private static BodyBoundary inferBoundaryFromBlankBand(Edge edge, PixelRegion region, BufferedImage image) {
        final int required = 16;
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        boolean blank;
        BodyBoundary boundary = new BodyBoundary();
        boundary.basis = "java_16px_continuous_blank_band";
        if (edge == Edge.TOP) {
            blank = !bandHasInk(image, region, region.getX(), bottom, right, bottom + required);
            if (blank) { boundary.x = null; boundary.y = (bottom + required) / (double) image.getHeight(); return boundary; }
        } else if (edge == Edge.BOTTOM) {
            blank = !bandHasInk(image, region, region.getX(), region.getY() - required, right, region.getY());
            if (blank) { boundary.x = null; boundary.y = (region.getY() - required) / (double) image.getHeight(); return boundary; }
        } else if (edge == Edge.LEFT) {
            blank = !bandHasInk(image, region, right, region.getY(), right + required, bottom);
            if (blank) { boundary.x = (right + required) / (double) image.getWidth(); boundary.y = null; return boundary; }
        } else if (edge == Edge.RIGHT) {
            blank = !bandHasInk(image, region, region.getX() - required, region.getY(), region.getX(), bottom);
            if (blank) { boundary.x = (region.getX() - required) / (double) image.getWidth(); boundary.y = null; return boundary; }
        }
        return null;
    }

    private static String invalidPixelGapReason(Edge edge, PixelRegion region, BodyBoundary boundary, BufferedImage image) {
        int right = region.getX() + region.getWidth();
        int bottom = region.getY() + region.getHeight();
        if (edge == Edge.TOP) {
            int bodyY = (int) Math.floor(boundary.y * image.getHeight());
            if (bodyY - bottom < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            // 相邻安全带出现任何保守墨迹都拒绝；宁可漏擦，也不穿透到正文首行。
            return bandHasBlockingInk(image, region, region.getX(), bottom, right, bottom + MIN_BODY_GAP_PIXELS, boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.BOTTOM) {
            int bodyY = (int) Math.ceil(boundary.y * image.getHeight());
            if (region.getY() - bodyY < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasBlockingInk(image, region, region.getX(), region.getY() - MIN_BODY_GAP_PIXELS, right, region.getY(), boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.LEFT) {
            int bodyX = (int) Math.floor(boundary.x * image.getWidth());
            if (bodyX - right < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasBlockingInk(image, region, right, region.getY(), right + MIN_BODY_GAP_PIXELS, bottom, boundary)
                    ? "body blank gap contains ink" : null;
        }
        if (edge == Edge.RIGHT) {
            int bodyX = (int) Math.ceil(boundary.x * image.getWidth());
            if (region.getX() - bodyX < MIN_BODY_GAP_PIXELS) {
                return "body blank gap is insufficient";
            }
            return bandHasBlockingInk(image, region, region.getX() - MIN_BODY_GAP_PIXELS, region.getY(), region.getX(), bottom, boundary)
                    ? "body blank gap contains ink" : null;
        }
        return "body blank gap is insufficient";
    }

    /**
     * 安全带中的单根独立细竖/横线（分栏、装订或裁切线）不属于正文，而且位于批准框外，
     * 擦除不会改动它。只有全部墨迹都落在最多 4px 的单一轴向细线内才忽略；文字、题干、
     * 表格行或两个以上分隔对象都会保留为阻断证据。
     */
    private static boolean bandHasBlockingInk(BufferedImage image, PixelRegion region,
                                              int left, int top, int right, int bottom, BodyBoundary boundary) {
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
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        boolean thinVertical = width <= 4 && height >= 4;
        boolean thinHorizontal = height <= 4 && width >= 4;
        return !(thinVertical || thinHorizontal);
    }

    private static boolean isPixelProvenReplacement(BodyBoundary boundary) {
        return boundary != null && boundary.basis != null
                && boundary.basis.startsWith("java_10px_blank_band_replaced_conflicting_vlm_boundary");
    }

    /**
     * 当整页 VLM 已明确声明正文边界排除了背透/浅影时，安全带中的淡灰扫描伪影不应
     * 反过来推翻该语义结论；它既不在批准框内，也不会被擦除。真正的深色前景仍完全阻断。
     */
    private static boolean isBlockingGapMark(int argb, int backgroundLum, BodyBoundary boundary) {
        if (!isConservativeMark(argb, backgroundLum)) return false;
        if (!declaresBleedExcluded(boundary)) return true;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        return luminance <= backgroundLum - 55 || BackgroundEstimator.isColoredMark(argb);
    }

    /** 模型明确说明已排除背透时，Java 不得再把浅影向正文方向吸附为“目标”。 */
    private static boolean declaresBleedExcluded(BodyBoundary boundary) {
        String basis = boundary == null ? "" : boundary.basis;
        return basis != null && (basis.contains("背透") || basis.contains("透印") || basis.contains("浅影"));
    }

    /**
     * 只向正文方向逐像素扫描，最多一个字高且不超过 32px；连续两条无墨扫描线立即停止。
     * 这能容纳页码中断开的字形/括号抗锯齿，却不会跨越空白去吞并另一行或正文。
     */
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
            if (edge == Edge.TOP) { scanTop = originalBottom + step - 1; scanBottom = scanTop + 1; }
            if (edge == Edge.BOTTOM) { scanBottom = originalTop - step + 1; scanTop = scanBottom - 1; }
            if (edge == Edge.LEFT) { scanLeft = originalRight + step - 1; scanRight = scanLeft + 1; }
            if (edge == Edge.RIGHT) { scanRight = originalLeft - step + 1; scanLeft = scanRight - 1; }
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

    /**
     * 处理“模型语义判断正确、但框整体偏到页外”的情形。仅从模型框与正文边界之间的
     * 走廊寻找墨迹；该走廊外的正文永远不可进入。返回的区域还会在后续流程强制局部二检。
     */
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

    private static int bodyLimit(Edge edge, BodyBoundary boundary, BufferedImage image) {
        if (edge == Edge.TOP) return (int) Math.floor(boundary.y * image.getHeight());
        if (edge == Edge.BOTTOM) return (int) Math.ceil(boundary.y * image.getHeight());
        if (edge == Edge.LEFT) return (int) Math.floor(boundary.x * image.getWidth());
        if (edge == Edge.RIGHT) return (int) Math.ceil(boundary.x * image.getWidth());
        return -1;
    }

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

    private static boolean remainsInEdgeBand(Edge edge, PixelRegion region, BufferedImage image) {
        if (edge == Edge.TOP) return region.getY() + region.getHeight() <= Math.ceil(EDGE_BAND * image.getHeight());
        if (edge == Edge.BOTTOM) return region.getY() >= Math.floor((1 - EDGE_BAND) * image.getHeight());
        if (edge == Edge.LEFT) return region.getX() + region.getWidth() <= Math.ceil(EDGE_BAND * image.getWidth());
        if (edge == Edge.RIGHT) return region.getX() >= Math.floor((1 - EDGE_BAND) * image.getWidth());
        return false;
    }

    private static boolean bandHasInk(BufferedImage image, PixelRegion reference, int left, int top,
                                      int rightExclusive, int bottomExclusive) {
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

    /**
     * 仅供“局部 VLM 精框与整页正文边界冲突”使用的空白带证明。扫描件在纯白区域也常有
     * 1~2 个压缩灰点；把任意单像素都当正文会让正确精框永远无法推翻模型幻觉边界。
     * 连续笔画仍会在某一扫描线形成至少 3 个墨点，或在多行累计至少 6 个墨点，因此继续
     * 失败关闭。普通 locate、缺失边界和最终候选框校验仍使用严格的 bandHasInk。
     */
    private static boolean bandHasSubstantiveInk(BufferedImage image, PixelRegion reference, int left, int top,
                                                   int rightExclusive, int bottomExclusive) {
        if (left < 0 || top < 0 || rightExclusive > image.getWidth() || bottomExclusive > image.getHeight()
                || left >= rightExclusive || top >= bottomExclusive) {
            return true;
        }
        int backgroundLum = BackgroundEstimator.medianLightLuminance(image, reference);
        int totalMarks = 0;
        for (int y = top; y < bottomExclusive; y++) {
            int rowMarks = 0;
            for (int x = left; x < rightExclusive; x++) {
                if (isConservativeMark(image.getRGB(x, y), backgroundLum)) {
                    rowMarks++;
                    totalMarks++;
                    if (rowMarks >= 3 || totalMarks >= 6) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

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

    private static boolean maskTouchesCandidateBox(BufferedImage image, PixelRegion region) {
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

    private static boolean bandHasErasableInk(BufferedImage image, int left, int top, int rightExclusive,
                                               int bottomExclusive, int backgroundLum) {
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

    /** 彩色标记同样不能被当作安全空白；这让页码色块可被保守扩框，也阻止跨越彩色正文。 */
    private static boolean isConservativeMark(int argb, int backgroundLum) {
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

        private int width() { return right - left; }
        private int height() { return bottom - top; }
        private boolean isInside(BufferedImage image) {
            return left >= 0 && top >= 0 && right <= image.getWidth() && bottom <= image.getHeight()
                    && left < right && top < bottom;
        }
    }

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

        public String getPageId() {
            return pageId;
        }

        public String getStatus() {
            return status;
        }

        public List<EraseRegion> getRegions() {
            return regions;
        }

        public BodyBoundary getNearestBodyBoundary() {
            return nearestBodyBoundary;
        }
    }

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

        public boolean isAccepted() {
            return accepted;
        }

        public List<PixelRegion> getRegions() {
            return regions;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }

    public static final class PixelRegion {
        private final String pageId;
        private final String regionId;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        private final double confidence;
        private final boolean coordinateRescued;

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence) {
            this(pageId, regionId, x, y, width, height, x1, y1, x2, y2, confidence, false);
        }

        private PixelRegion(String pageId, String regionId, int x, int y, int width, int height,
                            double x1, double y1, double x2, double y2, double confidence, boolean coordinateRescued) {
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

        public String getPageId() {
            return pageId;
        }

        public String getRegionId() {
            return regionId;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public double getX1() {
            return x1;
        }

        public double getY1() {
            return y1;
        }

        public double getX2() {
            return x2;
        }

        public double getY2() {
            return y2;
        }

        public double getConfidence() {
            return confidence;
        }

        public boolean isCoordinateRescued() {
            return coordinateRescued;
        }
    }
}
