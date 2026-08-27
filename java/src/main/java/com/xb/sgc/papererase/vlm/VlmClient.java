package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型的四角色协议。此接口故意只返回结构化业务结果，响应 JSON 校验集中在
 * {@link ResponseParser}，避免调用方在“坐标不可信”时仍继续擦除。
 */
public interface VlmClient {
    /** 1-pattern：跨页识别阅读方向、页码共性和粗略边缘窗口。 */
    PatternResponse pattern(List<PageImage> pages);

    /**
     * 仅在严格 JSON 解析失败后重发同一批图片，并明确要求模型修正协议字段；默认实现保留
     * 既有 fake 的兼容性。网络异常不走此分支，仍由各真实客户端自己的网络重试处理。
     */
    default PatternResponse correctPatternAfterProtocolError(List<PageImage> pages, List<String> expectedPageIds, String error) {
        return pattern(pages);
    }

    /** 2-locate：在整页版式中确认页码语义，并输出整图归一化候选框。 */
    LocateResponse locate(PageImage page, PatternGroup group);

    /** 同一页、同一图的 locate 协议纠错重试；不允许脱离图片仅修补 JSON。 */
    default LocateResponse correctLocateAfterProtocolError(PageImage page, PatternGroup group, String error) {
        return locate(page, group);
    }

    /**
     * 局部精修 locate 只发送 pattern/候选框生成的 ROI。默认实现保留旧 fake/client 兼容；
     * 真实客户端覆写后不得把整页与 ROI 同时发送，以免降低局部坐标精度。
     */
    default LocateResponse locate(PageImage page, PatternGroup group, RoiImage roi) {
        return locate(page, group);
    }

    /**
     * 坐标漂移后的局部重定位：沿用 locate 的“页码行语义”能力，但只发送 pattern 边缘
     * ROI，且携带首次识别出的文字锚点。默认实现兼容旧测试替身。
     */
    default LocateResponse relocateCoordinateRefinement(PageImage page, PatternGroup group,
                                                        EraseRegion semanticAnchor, RoiImage roi) {
        VerifyResponse verified = verifyCoordinateRefinement(page, semanticAnchor, roi);
        LocateResponse relocated = new LocateResponse();
        relocated.page_id = page.getPageId();
        relocated.status = verified.decision;
        relocated.evidence = verified.evidence;
        if ("safe_to_erase".equals(verified.decision) && verified.refined_region != null) {
            EraseRegion local = new EraseRegion();
            local.region_id = semanticAnchor.region_id;
            local.x1 = verified.refined_region.x1;
            local.y1 = verified.refined_region.y1;
            local.x2 = verified.refined_region.x2;
            local.y2 = verified.refined_region.y2;
            local.page_number_text = semanticAnchor.page_number_text;
            local.same_line_metadata = semanticAnchor.same_line_metadata;
            local.on_line = semanticAnchor.on_line;
            local.confidence = semanticAnchor.confidence;
            local.safety_margin = semanticAnchor.safety_margin;
            relocated.regions.add(local);
        }
        return relocated;
    }

    /** 4-verify：仅对风险候选或坐标精修 ROI 做局部安全复核。 */
    VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi);

    /**
     * 双页扫描的局部 ROI 需要知道候选属于哪一种整卷版式；默认仍走旧方法，保证所有
     * 既有替身和非双页调用的行为不变。真实客户端仅在 spread 共性时追加这一事实。
     */
    default VerifyResponse verify(PageImage page, PatternGroup group, EraseRegion region, RoiImage roi) {
        return verify(page, region, roi);
    }

    /**
     * 坐标门禁拒绝后的二次定位只允许模型看到候选中心 ROI。整页版式已在首次 locate
     * 中确认；此处再附整页会稀释小页码的像素定位精度。默认实现兼容测试替身。
     */
    default VerifyResponse verifyCoordinateRefinement(PageImage page, EraseRegion region, RoiImage roi) {
        return verify(page, region, roi);
    }

    /** 7-audit：对原图、擦除图和局部 ROI 复核正文未变及目标已移除。 */
    AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois);

    /**
     * active 是唯一的提供方选择入口。四个业务角色始终共享同一个协议客户端，避免出现
     * pattern 走一个平台而 audit 走另一个平台的不可追溯结果。
     */
    static VlmClient create(VlmConfig config, Path skillRoot) {
        if (config == null || skillRoot == null) {
            throw new IllegalArgumentException("config and skillRoot are required");
        }
        if ("openai-compatible".equals(config.getProviderKind())) {
            return new OpenAiCompatible(config, skillRoot);
        }
        if ("ark-responses".equals(config.getProviderKind())) {
            return new ArkResponses(config, skillRoot);
        }
        throw new IllegalStateException("unsupported VLM provider kind: " + config.getProviderKind());
    }

    /** 将精定位意图写进本次请求文本，避免模型把它误当普通的 yes/no 安全复核。 */
    static String refinementInstruction(EraseRegion region) {
        if (region != null && "coordinate_refinement_requested".equals(region.safety_margin)) {
            return "\nCOORDINATE_REFINEMENT: This is a coordinate refinement request. Return non-null refined_region "
                    + "in ROI-relative 0..1 coordinates when and only when safe_to_erase. Do not return or infer a body boundary; "
                    + "the whole-page boundary from locate remains authoritative. "
                    + "The first-pass semantic clues are page_number_text='" + safePromptText(region.page_number_text)
                    + "' and same_line_metadata='" + safePromptText(region.same_line_metadata) + "'. "
                    + "Search the entire ROI for the actually visible literal markers and measure that line's ink, rather than "
                    + "blindly preserving the first-pass coordinates or selecting a nearby body line.";
        }
        return "";
    }

    static String relocationInstruction(EraseRegion region) {
        return " LOCAL_RELOCATE: The image is an edge ROI, so all returned coordinates are ROI-relative. "
                + "The whole-page semantic anchor is page_number_text='" + safePromptText(region.page_number_text)
                + "' and same_line_metadata='" + safePromptText(region.same_line_metadata) + "'. "
                + "Search the complete ROI for the visible matching line. The old coordinates are not evidence and must not be copied.";
    }

    /** JSON-free instruction text still must not let model evidence break the surrounding quoted clues. */
    static String safePromptText(String value) {
        return value == null ? "" : value.replace("'", "’").replace('\n', ' ').replace('\r', ' ');
    }

    /** 给局部定位提供整卷共性；粗窗口只用于核对，最终坐标仍必须按 ROI 可见墨迹测量。 */
    static String locatePatternEvidence(PatternGroup group) {
        if (group == null || group.locate_window == null) {
            return " edge=unknown";
        }
        return " pattern_group_id=" + safePromptText(group.group_id)
                + " edge=" + group.edge + " alignment=" + group.alignment
                + " layout_description='" + safePromptText(group.layout_description) + "'"
                + " coarse_window_full_page=" + group.locate_window.x1 + "," + group.locate_window.y1
                + "," + group.locate_window.x2 + "," + group.locate_window.y2
                + " (cross-page structural prior only; inspect current full-page ink and measure final coordinates from it)";
    }

    /**
     * verify 只补充双页版式这一项必要事实，其他版式保持原请求完全不变，避免扩大影响面。
     */
    static String verifyPatternEvidence(PatternGroup group) {
        return group != null && "spread".equals(group.alignment) ? locatePatternEvidence(group) : "";
    }

    static String patternProtocolCorrectionInstruction(List<String> expectedPageIds, String error) {
        return " PROTOCOL_CORRECTION: Previous JSON violated the strict classification contract ("
                + safePromptText(error) + "). INPUT_PAGE_IDS=" + expectedPageIds
                + ". Return every input page_id exactly once in page_directions, and exactly once across the union of "
                + "pattern_groups.page_ids, heterogeneous_page_ids, no_pagenum_page_ids, ungrouped_page_ids. "
                + "These classifications are mutually exclusive; uncertain pages must be ungrouped only.";
    }

    static String locateProtocolCorrectionInstruction(String pageId, String error) {
        return exactProtocolPageIdInstruction(pageId) + " PROTOCOL_CORRECTION: Previous JSON violated the strict locate contract ("
                + safePromptText(error) + "). For safe_to_erase every region must provide a non-empty page_number_text "
                + "copied as the visible page-number literal. If it cannot be read, return manual_review with empty regions.";
    }

    static String exactProtocolPageIdInstruction(String pageId) {
        return "REQUEST_PAGE_ID='" + safePromptText(pageId) + "'. JSON page_id MUST exactly equal this string. ";
    }

    /** 审计模型只接收已批准目标的语义锚点，不接收坐标猜测，避免把正文误当作擦除目标。 */
    static String auditTargetManifest(List<EraseRegion> regions) {
        StringBuilder manifest = new StringBuilder(" TARGET_MANIFEST:");
        if (regions == null || regions.isEmpty()) {
            return manifest.append(" []").toString();
        }
        for (EraseRegion region : regions) {
            if (region == null) continue;
            manifest.append(" {region_id=").append(safePromptText(region.region_id))
                    .append(", page_number_text='").append(safePromptText(region.page_number_text))
                    .append("', same_line_metadata='").append(safePromptText(region.same_line_metadata)).append("'}");
        }
        return manifest.toString();
    }

    final class OpenAiCompatible implements VlmClient {
        private final VlmConfig config;
        private final Path skillRoot;
        private final ObjectMapper mapper = new ObjectMapper();

        public OpenAiCompatible(VlmConfig config, Path skillRoot) {
            this.config = config;
            this.skillRoot = skillRoot;
        }

        public PatternResponse pattern(List<PageImage> pages) {
            if (pages.size() > 8) {
                throw new IllegalArgumentException("pattern accepts at most 8 pages");
            }
            List<String> ids = new ArrayList<String>();
            for (PageImage page : pages) {
                ids.add(page.getPageId());
            }
            return ResponseParser.parsePattern(call("pattern", "Analyze batch page_ids=" + ids, pages,
                    java.util.Collections.<RoiImage>emptyList()),
                    ids);
        }

        @Override
        public PatternResponse correctPatternAfterProtocolError(List<PageImage> pages, List<String> expectedPageIds, String error) {
            return ResponseParser.parsePattern(call("pattern", patternProtocolCorrectionInstruction(expectedPageIds, error), pages,
                    java.util.Collections.<RoiImage>emptyList()), expectedPageIds);
        }

        public LocateResponse locate(PageImage page, PatternGroup group) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate")
                    + locatePatternEvidence(group), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse correctLocateAfterProtocolError(PageImage page, PatternGroup group, String error) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate")
                    + locatePatternEvidence(group)
                    + locateProtocolCorrectionInstruction(page.getPageId(), error), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse locate(PageImage page, PatternGroup group, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate ROI")
                    + " pattern_group=" + (group == null ? "none" : group.group_id)
                    + locatePatternEvidence(group),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        @Override
        public LocateResponse relocateCoordinateRefinement(PageImage page, PatternGroup group,
                                                            EraseRegion semanticAnchor, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Relocate")
                    + " pattern_group=" + (group == null ? "none" : group.group_id)
                    + locatePatternEvidence(group) + relocationInstruction(semanticAnchor),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        public VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId + refinementInstruction(region), one(page), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        @Override
        public VerifyResponse verify(PageImage page, PatternGroup group, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId + verifyPatternEvidence(group) + refinementInstruction(region),
                    one(page), java.util.Collections.singletonList(roi)), page.getPageId(), regionId);
        }

        @Override
        public VerifyResponse verifyCoordinateRefinement(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region.region_id;
            return ResponseParser.parseVerify(call("verify", "Refine ROI coordinates only for page_id=" + page.getPageId()
                    + " region_id=" + regionId + refinementInstruction(region),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", "Audit page_id=" + original.getPageId() + auditTargetManifest(regions),
                    java.util.Arrays.asList(original.withImageRole("ORIGINAL"), erased.withImageRole("ERASED")),
                    rois == null ? java.util.Collections.<RoiImage>emptyList() : rois),
                    original.getPageId());
        }

        private String call(String role, String instruction, List<PageImage> pages, List<RoiImage> rois) {
            VlmConfig.RoleConfig roleConfig = config.role(role);
            RuntimeException last = null;
            // 重试仅处理瞬时网络/服务异常；最终失败由上层按失败关闭转人工审核。
            for (int attempt = 0; attempt <= roleConfig.getRetries(); attempt++) {
                try {
                    return http(roleConfig, requestBody(roleConfig, instruction, pages, rois));
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            throw last == null ? new RuntimeException(role + " VLM call failed") : last;
        }

        private String requestBody(VlmConfig.RoleConfig role, String instruction,
                                   List<PageImage> pages, List<RoiImage> rois) {
            return buildRequestBody(role.getModel(), readPrompt(role), instruction, pages, rois);
        }

        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Object> content = new ArrayList<Object>();
                content.add(textPart(prompt + "\n" + instruction));
                for (PageImage page : pages) {
                    // PAGE_ID 与图片紧邻，防止多图响应中出现“按图片顺序猜测”的错页坐标。
                    String label = "PAGE_ID: " + page.getPageId();
                    if (page.getImageRole() != null) {
                        label += "\nIMAGE_ROLE: " + page.getImageRole();
                    }
                    content.add(textPart(label));
                    // 必须是 image_url 多模态块；把 data URL 放进 text 会导致模型只看到字符串。
                    content.add(imagePart(page.previewDataUrl()));
                }
                for (RoiImage roi : rois) {
                    String label = roi.getPageId() == null ? "" : "ROI_PAGE_ID: " + roi.getPageId() + "\n";
                    label += "ROI_REGION_ID: " + roi.getRegionId();
                    if (roi.getImageRole() != null) {
                        label += "\nROI_IMAGE_ROLE: " + roi.getImageRole();
                    }
                    content.add(textPart(label));
                    content.add(imagePart(roi.dataUrl()));
                }
                Map<String, Object> message = new LinkedHashMap<String, Object>();
                message.put("role", "user");
                message.put("content", content);

                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("model", model);
                body.put("temperature", 0);
                // qwen3.8-max 默认开启结构化 reasoning，对每页都做 26~47KB 离线思考
                // （单次 130~470s），实测同图同答：开 reasoning=170.6s，关=9.1s 且坐标
                // 像素级一致、答案字段更完整。生产环境统一关掉。
                body.put("enable_thinking", false);
                body.put("messages", java.util.Collections.singletonList(message));
                return mapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new RuntimeException("cannot build VLM request", e);
            }
        }

        private static Map<String, Object> textPart(String text) {
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "text");
            part.put("text", text);
            return part;
        }

        private static Map<String, Object> imagePart(String dataUrl) {
            Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
            imageUrl.put("url", dataUrl);
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "image_url");
            part.put("image_url", imageUrl);
            return part;
        }

        private String http(VlmConfig.RoleConfig role, String body) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(role.getEndpoint()).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Authorization", "Bearer " + role.getApiKey());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.close();
                int code = conn.getResponseCode();
                java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                JsonNode root = mapper.readTree(stream);
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("VLM HTTP " + code);
                }
                // 兼容文本与内容数组两种 OpenAI 兼容响应，其他形态一律视为协议失败。
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
                if (content.isArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode part : content) {
                        if ("text".equals(part.path("type").asText())) {
                            text.append(part.path("text").asText());
                        }
                    }
                    return text.toString();
                }
                throw new RuntimeException("VLM response choices content is missing");
            } catch (IOException e) {
                throw new RuntimeException("VLM HTTP call failed", e);
            }
        }

        private String readPrompt(VlmConfig.RoleConfig role) {
            try {
                return new String(Files.readAllBytes(skillRoot.resolve(role.getPromptPath())), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("cannot read prompt for " + role.getRole(), e);
            }
        }

        private static List<PageImage> one(PageImage page) {
            List<PageImage> pages = new ArrayList<PageImage>();
            pages.add(page);
            return pages;
        }

        private static String exactPageIdInstruction(String pageId, String action) {
            return action + " REQUEST_PAGE_ID='" + pageId + "'. "
                    + "JSON page_id MUST be exactly this full quoted string; never use an example, page number, or abbreviated id. ";
        }
    }

    /**
     * 火山方舟 Responses API 适配器。它与 Chat Completions 的差异仅限传输协议：请求
     * 使用 input/content 的 input_text、input_image，结果从 output/content/output_text 取出；
     * 上层仍复用同一套四角色解析、坐标校验和失败关闭策略。
     */
    final class ArkResponses implements VlmClient {
        private final VlmConfig config;
        private final Path skillRoot;
        private final ObjectMapper mapper = new ObjectMapper();

        public ArkResponses(VlmConfig config, Path skillRoot) {
            this.config = config;
            this.skillRoot = skillRoot;
        }

        public PatternResponse pattern(List<PageImage> pages) {
            if (pages.size() > 8) {
                throw new IllegalArgumentException("pattern accepts at most 8 pages");
            }
            List<String> ids = new ArrayList<String>();
            for (PageImage page : pages) {
                ids.add(page.getPageId());
            }
            return ResponseParser.parsePattern(call("pattern", "Analyze batch page_ids=" + ids, pages,
                    java.util.Collections.<RoiImage>emptyList()), ids);
        }

        @Override
        public PatternResponse correctPatternAfterProtocolError(List<PageImage> pages, List<String> expectedPageIds, String error) {
            return ResponseParser.parsePattern(call("pattern", patternProtocolCorrectionInstruction(expectedPageIds, error), pages,
                    java.util.Collections.<RoiImage>emptyList()), expectedPageIds);
        }

        public LocateResponse locate(PageImage page, PatternGroup group) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate")
                    + locatePatternEvidence(group), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse correctLocateAfterProtocolError(PageImage page, PatternGroup group, String error) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate")
                    + locatePatternEvidence(group)
                    + locateProtocolCorrectionInstruction(page.getPageId(), error), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse locate(PageImage page, PatternGroup group, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Locate ROI")
                    + " pattern_group=" + (group == null ? "none" : group.group_id)
                    + locatePatternEvidence(group),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        @Override
        public LocateResponse relocateCoordinateRefinement(PageImage page, PatternGroup group,
                                                            EraseRegion semanticAnchor, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "Relocate")
                    + " pattern_group=" + (group == null ? "none" : group.group_id)
                    + locatePatternEvidence(group) + relocationInstruction(semanticAnchor),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        public VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId + refinementInstruction(region), one(page), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        @Override
        public VerifyResponse verify(PageImage page, PatternGroup group, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId + verifyPatternEvidence(group) + refinementInstruction(region),
                    one(page), java.util.Collections.singletonList(roi)), page.getPageId(), regionId);
        }

        @Override
        public VerifyResponse verifyCoordinateRefinement(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region.region_id;
            return ResponseParser.parseVerify(call("verify", "Refine ROI coordinates only for page_id=" + page.getPageId()
                    + " region_id=" + regionId + refinementInstruction(region),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", "Audit page_id=" + original.getPageId() + auditTargetManifest(regions),
                    java.util.Arrays.asList(original.withImageRole("ORIGINAL"), erased.withImageRole("ERASED")),
                    rois == null ? java.util.Collections.<RoiImage>emptyList() : rois), original.getPageId());
        }

        private String call(String role, String instruction, List<PageImage> pages, List<RoiImage> rois) {
            VlmConfig.RoleConfig roleConfig = config.role(role);
            RuntimeException last = null;
            for (int attempt = 0; attempt <= roleConfig.getRetries(); attempt++) {
                long startedAt = System.currentTimeMillis();
                log(role, attempt, "started", pages.size(), rois.size(), 0, null);
                try {
                    String response = http(roleConfig, buildRequestBody(roleConfig.getModel(), readPrompt(roleConfig), instruction,
                            pages, rois, roleConfig.getMaxOutputTokens(), roleConfig.getImageDetail(),
                            roleConfig.getThinkingType(), roleConfig.getReasoningEffort()));
                    log(role, attempt, "completed", pages.size(), rois.size(), System.currentTimeMillis() - startedAt, null);
                    return response;
                } catch (RuntimeException e) {
                    last = e;
                    log(role, attempt, "failed", pages.size(), rois.size(), System.currentTimeMillis() - startedAt,
                            e.getClass().getSimpleName());
                }
            }
            throw last == null ? new RuntimeException(role + " Ark call failed") : last;
        }

        /** 对应 Ark Responses：input 是消息数组，图片 URL 是字符串而非 image_url.url 对象。 */
        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois) {
            return buildRequestBody(model, prompt, instruction, pages, rois, 32768, "high");
        }

        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois,
                                              int maxOutputTokens, String imageDetail) {
            return buildRequestBody(model, prompt, instruction, pages, rois, maxOutputTokens, imageDetail, null, null);
        }

        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois,
                                              int maxOutputTokens, String imageDetail,
                                              String thinkingType, String reasoningEffort) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Object> content = new ArrayList<Object>();
                content.add(arkTextPart(prompt + "\n" + instruction));
                for (PageImage page : pages) {
                    String label = "PAGE_ID: " + page.getPageId();
                    if (page.getImageRole() != null) {
                        label += "\nIMAGE_ROLE: " + page.getImageRole();
                    }
                    content.add(arkTextPart(label));
                    content.add(arkImagePart(page.previewDataUrl(), imageDetail));
                }
                for (RoiImage roi : rois) {
                    String label = roi.getPageId() == null ? "" : "ROI_PAGE_ID: " + roi.getPageId() + "\n";
                    label += "ROI_REGION_ID: " + roi.getRegionId();
                    if (roi.getImageRole() != null) {
                        label += "\nROI_IMAGE_ROLE: " + roi.getImageRole();
                    }
                    content.add(arkTextPart(label));
                    content.add(arkImagePart(roi.dataUrl(), imageDetail));
                }
                Map<String, Object> inputMessage = new LinkedHashMap<String, Object>();
                inputMessage.put("role", "user");
                inputMessage.put("content", content);
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("model", model);
                if (maxOutputTokens > 0) {
                    body.put("max_output_tokens", maxOutputTokens);
                }
                if (thinkingType != null && thinkingType.trim().length() > 0) {
                    Map<String, Object> thinking = new LinkedHashMap<String, Object>();
                    thinking.put("type", thinkingType);
                    body.put("thinking", thinking);
                }
                if (reasoningEffort != null && reasoningEffort.trim().length() > 0) {
                    Map<String, Object> reasoning = new LinkedHashMap<String, Object>();
                    reasoning.put("effort", reasoningEffort);
                    body.put("reasoning", reasoning);
                }
                body.put("input", java.util.Collections.singletonList(inputMessage));
                return mapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new RuntimeException("cannot build Ark request", e);
            }
        }

        private static Map<String, Object> arkTextPart(String text) {
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "input_text");
            part.put("text", text);
            return part;
        }

        private static Map<String, Object> arkImagePart(String dataUrl, String imageDetail) {
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "input_image");
            part.put("image_url", dataUrl);
            part.put("detail", imageDetail);
            return part;
        }

        private String http(VlmConfig.RoleConfig role, String body) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(role.getEndpoint()).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Authorization", "Bearer " + role.getApiKey());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.close();
                int code = conn.getResponseCode();
                java.io.InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                JsonNode root = mapper.readTree(stream);
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("Ark HTTP " + code);
                }
                return responseText(root);
            } catch (IOException e) {
                throw new RuntimeException("Ark HTTP call failed", e);
            }
        }

        /** 只接收已完成响应中的 output_text；缺少该字段即协议失败，交由上层失败关闭。 */
        static String responseText(JsonNode root) {
            if (!"completed".equals(root.path("status").asText())) {
                throw new RuntimeException("Ark response is not completed");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode output : root.path("output")) {
                for (JsonNode content : output.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        text.append(content.path("text").asText());
                    }
                }
            }
            if (text.length() == 0) {
                throw new RuntimeException("Ark response output_text is missing");
            }
            return text.toString();
        }

        private String readPrompt(VlmConfig.RoleConfig role) {
            try {
                return new String(Files.readAllBytes(skillRoot.resolve(role.getPromptPath())), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("cannot read prompt for " + role.getRole(), e);
            }
        }

        private static List<PageImage> one(PageImage page) {
            List<PageImage> pages = new ArrayList<PageImage>();
            pages.add(page);
            return pages;
        }

        private static String exactPageIdInstruction(String pageId, String action) {
            return action + " REQUEST_PAGE_ID='" + pageId + "'. "
                    + "JSON page_id MUST be exactly this full quoted string; never use an example, page number, or abbreviated id. ";
        }

        /** 仅记录角色、数量、耗时和异常类型；不记录提示词、图片 data URL 或任何凭据。 */
        private static void log(String role, int attempt, String event, int pageCount, int roiCount,
                                long elapsedMillis, String errorType) {
            String suffix = errorType == null ? "" : " error_type=" + errorType;
            System.err.println("vlm_event=" + event + " provider=ark-responses role=" + role
                    + " attempt=" + (attempt + 1) + " page_count=" + pageCount + " roi_count=" + roiCount
                    + " elapsed_ms=" + elapsedMillis + suffix);
        }
    }

    final class PageImage {
        private final String pageId;
        private final BufferedImage image;
        private final String imageRole;

        public PageImage(String pageId, BufferedImage image) {
            this(pageId, image, null);
        }

        public PageImage(String pageId, BufferedImage image, String imageRole) {
            if (pageId == null || image == null) {
                throw new IllegalArgumentException("pageId and image are required");
            }
            this.pageId = pageId;
            this.image = image;
            this.imageRole = imageRole;
        }

        public String getPageId() {
            return pageId;
        }

        public BufferedImage getImage() {
            return image;
        }

        public String getImageRole() {
            return imageRole;
        }

        private PageImage withImageRole(String role) {
            return new PageImage(pageId, image, role);
        }

        public String previewDataUrl() {
            return dataUrl(resize(image, 1536));
        }
    }

    final class RoiImage {
        private final String pageId;
        private final String regionId;
        private final BufferedImage image;
        private final String imageRole;

        public RoiImage(String regionId, BufferedImage image) {
            this(null, regionId, image, null);
        }

        public RoiImage(String pageId, String regionId, BufferedImage image) {
            this(pageId, regionId, image, null);
        }

        public RoiImage(String pageId, String regionId, BufferedImage image, String imageRole) {
            if (regionId == null || image == null) {
                throw new IllegalArgumentException("regionId and image are required");
            }
            this.pageId = pageId;
            this.regionId = regionId;
            this.image = image;
            this.imageRole = imageRole;
        }

        public String getPageId() {
            return pageId;
        }

        public String getRegionId() {
            return regionId;
        }

        public String getImageRole() {
            return imageRole;
        }

        public String dataUrl() {
            return VlmClient.dataUrl(image);
        }
    }

    static BufferedImage resize(BufferedImage source, int maxLongEdge) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxLongEdge) {
            return source;
        }
        double scale = maxLongEdge / (double) longEdge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        // 仅用于整页“共性分析”预览；ROI 复核始终保留原始分辨率，避免小页码被缩没。
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return resized;
    }

    static String dataUrl(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("cannot encode image", e);
        }
    }
}
