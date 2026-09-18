package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.RelocateResponse;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型的 locate/relocate/audit 协议。此接口故意只返回结构化业务结果，响应 JSON 校验集中在
 * {@link ResponseParser}，避免调用方在“坐标不可信”时仍继续擦除。
 */
public interface VlmClient {
    /** 导出本次客户端构造时冻结的提示词，仅供 run 元数据快照，不含任何密钥。 */
    default Map<String, String> frozenPrompts() {
        return java.util.Collections.emptyMap();
    }
    /** 1-locate：在整页版式中确认页码语义，并输出整图归一化候选框。 */
    LocateResponse locate(PageImage page);

    /**
     * 仅在上一轮响应无法通过 VLM Contract 时重发同一整页；默认实现保持测试替身兼容。
     * repairInstruction 不改变任务语义，只指出 JSON 合同必须重新输出。
     */
    default LocateResponse locate(PageImage page, String repairInstruction) {
        return locate(page);
    }

    /** 协议失败后的固定修复提醒；视觉规则仍唯一由 locate 提示词维护。 */
    static String locateProtocolRepairInstruction() {
        return "\n上一响应未通过 JSON 协议校验。请重新观察当前图并完整重写唯一 JSON，不得沿用上次的 regions。"
                + "若返回 safe_to_erase，每个 region 必须各自包含非空、当前图真实可见的 page_number_text，"
                + "并使用精确字段名 same_line_metadata；无法满足时返回 manual_review 与 regions:[]。";
    }
    /**
     * 局部 locate 只发送候选框生成的 ROI。真实客户端不得把整页与 ROI 同时发送，以免降低局部坐标精度。
     */
    default LocateResponse locate(PageImage page, RoiImage roi) {
        return locate(page);
    }

    /**
     * 坐标漂移后的局部重定位：沿用 locate 的“页码行语义”能力，但只发送边缘 ROI，
     * 且携带首次识别出的文字锚点。默认实现兼容旧测试替身。
     */
    /** ROI Relocate：只测量当前 semanticAnchor 对应的局部几何框，不重新裁决页码语义。 */
    RelocateResponse relocateCoordinateRefinement(PageImage page, EraseRegion semanticAnchor, RoiImage roi);

    /** 2-audit：对原图、擦除图和局部 ROI 复核正文未变及目标已移除。 */
    AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois);

    /**
     * active 是唯一的提供方选择入口。业务角色始终共享同一个协议客户端，避免出现
     * 业务角色与协议客户端始终保持单一连接，便于归因。
     */
    static VlmClient create(VlmConfig config, Path skillRoot) {
        return create(config, skillRoot, VlmUsageSink.NOOP);
    }

    /**
     * 创建业务客户端并可选接入旁路 usage 采集器。采集器不属于业务协议，任何采集故障均被
     * 客户端吞掉，不能改变原有的请求、重试或失败关闭行为。
     */
    static VlmClient create(VlmConfig config, Path skillRoot, VlmUsageSink usageSink) {
        if (config == null || skillRoot == null) {
            throw new IllegalArgumentException("config and skillRoot are required");
        }
        if ("openai-compatible".equals(config.getProviderKind())) {
            return new OpenAiCompatible(config, skillRoot, usageSink);
        }
        if ("ark-responses".equals(config.getProviderKind())) {
            return new ArkResponses(config, skillRoot, usageSink);
        }
        throw new IllegalStateException("unsupported VLM provider kind: " + config.getProviderKind());
    }

    /** 动态值仅提供本次 ROI 的语义锚点；视觉规则与协议统一维护在 relocate 提示词。 */
    static String relocationAnchor(EraseRegion region) {
        return "\n本次 ROI 语义锚点：page_number_text='" + safePromptText(region.page_number_text)
                + "'，same_line_metadata='" + safePromptText(region.same_line_metadata) + "'。";
    }

    /** JSON-free instruction text still must not let model evidence break the surrounding quoted clues. */
    static String safePromptText(String value) {
        return value == null ? "" : value.replace("'", "’").replace('\n', ' ').replace('\r', ' ');
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

    /** 将请求中的整页/ROI 统一还原为可归集的页面 ID；不记录任何图片内容。 */
    static List<String> requestPageIds(List<PageImage> pages, List<RoiImage> rois) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        if (pages != null) for (PageImage page : pages) if (page != null) ids.add(page.getPageId());
        if (rois != null) for (RoiImage roi : rois) if (roi != null && roi.getPageId() != null) ids.add(roi.getPageId());
        return new ArrayList<String>(ids);
    }

    /** ROI 的 region ID 只用于调用成本追溯，不携带模型文字、坐标或图片。 */
    static List<String> requestRoiRegionIds(List<RoiImage> rois) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        if (rois != null) for (RoiImage roi : rois) if (roi != null) ids.add(roi.getRegionId());
        return new ArrayList<String>(ids);
    }

    /** 观测写入是旁路能力；任何磁盘或配置异常都不得中断一次已存在的 VLM 调用。 */
    static void recordUsage(VlmUsageSink sink, String providerKind, String model, String role, int attempt,
                            List<PageImage> pages, List<RoiImage> rois, long elapsedMillis,
                            VlmUsage usage, String errorType) {
        try {
            (sink == null ? VlmUsageSink.NOOP : sink).record(providerKind, model, role, attempt,
                    requestPageIds(pages, rois), requestRoiRegionIds(rois), elapsedMillis,
                    usage == null ? VlmUsage.unavailable() : usage, errorType);
        } catch (RuntimeException ignored) {
            // 旁路可观测性失败不改变主链路的既有成功/失败语义。
        }
    }

    /** HTTP 层只增加 usage 携带，不改变业务解析到的文本内容。 */
    final class HttpResponse {
        final String text;
        final VlmUsage usage;
        final String model;

        HttpResponse(String text, VlmUsage usage, String model) {
            this.text = text;
            this.usage = usage == null ? VlmUsage.unavailable() : usage;
            this.model = model == null || model.trim().length() == 0 ? "unknown" : model;
        }
    }

    final class OpenAiCompatible implements VlmClient {
        private final VlmConfig config;
        private final Path skillRoot;
        private final ObjectMapper mapper = new ObjectMapper();
        private final VlmUsageSink usageSink;
        /** 一次运行内冻结角色提示词，防止运行中编辑文件造成同一 run 混用版本。 */
        private final Map<String, String> prompts = new LinkedHashMap<String, String>();

        public OpenAiCompatible(VlmConfig config, Path skillRoot) {
            this(config, skillRoot, VlmUsageSink.NOOP);
        }

        public OpenAiCompatible(VlmConfig config, Path skillRoot, VlmUsageSink usageSink) {
            this.config = config;
            this.skillRoot = skillRoot;
            this.usageSink = usageSink == null ? VlmUsageSink.NOOP : usageSink;
            freezePrompts();
        }

        private void freezePrompts() {
            prompts.put("locate", loadPrompt(config.role("locate")));
            prompts.put("relocate", loadPrompt(config.role("relocate")));
            prompts.put("audit", loadPrompt(config.role("audit")));
        }

        @Override
        public Map<String, String> frozenPrompts() {
            return java.util.Collections.unmodifiableMap(prompts);
        }

        

        @Override
        public LocateResponse locate(PageImage page) {
            return locate(page, "");
        }

        @Override
        public LocateResponse locate(PageImage page, String repairInstruction) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "任务：整页定位")
                    + (repairInstruction == null ? "" : repairInstruction), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse locate(PageImage page, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "任务：局部 ROI 定位")
                    ,
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        @Override
        public RelocateResponse relocateCoordinateRefinement(PageImage page,
                                                             EraseRegion semanticAnchor, RoiImage roi) {
            return ResponseParser.parseRelocate(call("relocate", exactPageIdInstruction(page.getPageId(), "任务：同一目标行局部重定位")
                    + relocationAnchor(semanticAnchor),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)),
                    page.getPageId(), semanticAnchor.region_id);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", exactPageIdInstruction(original.getPageId(), "任务：擦除结果审计")
                    + auditTargetManifest(regions),
                    java.util.Arrays.asList(original.withImageRole("ORIGINAL"), erased.withImageRole("ERASED")),
                    rois == null ? java.util.Collections.<RoiImage>emptyList() : rois),
                    original.getPageId());
        }

        private String call(String role, String instruction, List<PageImage> pages, List<RoiImage> rois) {
            VlmConfig.RoleConfig roleConfig = config.role(role);
            RuntimeException last = null;
            // 重试仅处理瞬时网络/服务异常；最终失败由上层按失败关闭转人工审核。
            for (int attempt = 0; attempt <= roleConfig.getRetries(); attempt++) {
                long startedAt = System.currentTimeMillis();
                try {
                    HttpResponse response = http(roleConfig, requestBody(roleConfig, instruction, pages, rois));
                    recordUsage(usageSink, "openai-compatible", response.model, role, attempt + 1,
                            pages, rois, System.currentTimeMillis() - startedAt, response.usage, null);
                    return response.text;
                } catch (RuntimeException e) {
                    last = e;
                    recordUsage(usageSink, "openai-compatible", roleConfig.getModel(), role, attempt + 1,
                            pages, rois, System.currentTimeMillis() - startedAt, VlmUsage.unavailable(),
                            e.getClass().getSimpleName());
                }
            }
            throw last == null ? new RuntimeException(role + " VLM call failed") : last;
        }

        private String requestBody(VlmConfig.RoleConfig role, String instruction,
                                   List<PageImage> pages, List<RoiImage> rois) {
            return buildRequestBody(role.getModel(), readPrompt(role), instruction, pages, rois,
                    config.getMaxPreviewLongEdge());
        }

        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois) {
            return buildRequestBody(model, prompt, instruction, pages, rois, 1536);
        }

        static String buildRequestBody(String model, String prompt, String instruction,
                                       List<PageImage> pages, List<RoiImage> rois, int maxPreviewLongEdge) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Object> content = new ArrayList<Object>();
                // 提示词正文与本次调用指令同语种（中文），用空行分隔，避免模型把指令当成正文示例。
                content.add(textPart(prompt + "\n\n" + instruction));
                for (PageImage page : pages) {
                    // PAGE_ID 与图片紧邻，防止多图响应中出现“按图片顺序猜测”的错页坐标。
                    String label = "PAGE_ID: " + page.getPageId();
                    if (page.getImageRole() != null) {
                        label += "\nIMAGE_ROLE: " + page.getImageRole();
                    }
                    content.add(textPart(label));
                    // 必须是 image_url 多模态块；把 data URL 放进 text 会导致模型只看到字符串。
                    content.add(imagePart(page.previewDataUrl(maxPreviewLongEdge)));
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

        private HttpResponse http(VlmConfig.RoleConfig role, String body) {
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
                    return new HttpResponse(content.asText(), VlmUsage.fromOpenAiCompatible(root),
                            root.path("model").asText(role.getModel()));
                }
                if (content.isArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode part : content) {
                        if ("text".equals(part.path("type").asText())) {
                            text.append(part.path("text").asText());
                        }
                    }
                    return new HttpResponse(text.toString(), VlmUsage.fromOpenAiCompatible(root),
                            root.path("model").asText(role.getModel()));
                }
                throw new RuntimeException("VLM response choices content is missing");
            } catch (IOException e) {
                throw new RuntimeException("VLM HTTP call failed", e);
            }
        }

        private String readPrompt(VlmConfig.RoleConfig role) {
            return prompts.get(role.getRole());
        }

        private String loadPrompt(VlmConfig.RoleConfig role) {
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
            return action + "。REQUEST_PAGE_ID='" + pageId + "'。"
                    + "返回 JSON 中的 page_id 必须原样回显上面这个完整字符串，不得使用示例值、页序数字或缩写形式。";
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
        private final VlmUsageSink usageSink;
        /** 一次运行内冻结角色提示词，防止运行中编辑文件造成同一 run 混用版本。 */
        private final Map<String, String> prompts = new LinkedHashMap<String, String>();

        public ArkResponses(VlmConfig config, Path skillRoot) {
            this(config, skillRoot, VlmUsageSink.NOOP);
        }

        public ArkResponses(VlmConfig config, Path skillRoot, VlmUsageSink usageSink) {
            this.config = config;
            this.skillRoot = skillRoot;
            this.usageSink = usageSink == null ? VlmUsageSink.NOOP : usageSink;
            freezePrompts();
        }

        private void freezePrompts() {
            prompts.put("locate", loadPrompt(config.role("locate")));
            prompts.put("relocate", loadPrompt(config.role("relocate")));
            prompts.put("audit", loadPrompt(config.role("audit")));
        }

        @Override
        public Map<String, String> frozenPrompts() {
            return java.util.Collections.unmodifiableMap(prompts);
        }


        public LocateResponse locate(PageImage page) {
            return locate(page, "");
        }

        @Override
        public LocateResponse locate(PageImage page, String repairInstruction) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "任务：整页定位")
                    + (repairInstruction == null ? "" : repairInstruction), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        @Override
        public LocateResponse locate(PageImage page, RoiImage roi) {
            return ResponseParser.parseLocate(call("locate", exactPageIdInstruction(page.getPageId(), "任务：局部 ROI 定位")
                    ,
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)), page.getPageId());
        }

        @Override
        public RelocateResponse relocateCoordinateRefinement(PageImage page,
                                                             EraseRegion semanticAnchor, RoiImage roi) {
            return ResponseParser.parseRelocate(call("relocate", exactPageIdInstruction(page.getPageId(), "任务：同一目标行局部重定位")
                    + relocationAnchor(semanticAnchor),
                    java.util.Collections.<PageImage>emptyList(), java.util.Collections.singletonList(roi)),
                    page.getPageId(), semanticAnchor.region_id);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", exactPageIdInstruction(original.getPageId(), "任务：擦除结果审计")
                    + auditTargetManifest(regions),
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
                    HttpResponse response = http(roleConfig, buildRequestBody(roleConfig.getModel(), readPrompt(roleConfig), instruction,
                            pages, rois, roleConfig.getMaxOutputTokens(), roleConfig.getImageDetail(),
                            roleConfig.getThinkingType(), roleConfig.getReasoningEffort(),
                            config.getMaxPreviewLongEdge()));
                    long elapsedMillis = System.currentTimeMillis() - startedAt;
                    recordUsage(usageSink, "ark-responses", response.model, role, attempt + 1,
                            pages, rois, elapsedMillis, response.usage, null);
                    log(role, attempt, "completed", pages.size(), rois.size(), elapsedMillis, null);
                    return response.text;
                } catch (RuntimeException e) {
                    last = e;
                    long elapsedMillis = System.currentTimeMillis() - startedAt;
                    recordUsage(usageSink, "ark-responses", roleConfig.getModel(), role, attempt + 1,
                            pages, rois, elapsedMillis, VlmUsage.unavailable(), e.getClass().getSimpleName());
                    log(role, attempt, "failed", pages.size(), rois.size(), elapsedMillis,
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
            return buildRequestBody(model, prompt, instruction, pages, rois, maxOutputTokens, imageDetail,
                    thinkingType, reasoningEffort, 1536);
        }

        static String buildRequestBody(String model, String prompt, String instruction,
                                       List<PageImage> pages, List<RoiImage> rois,
                                       int maxOutputTokens, String imageDetail,
                                       String thinkingType, String reasoningEffort, int maxPreviewLongEdge) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Object> content = new ArrayList<Object>();
                // 提示词正文与本次调用指令同语种（中文），用空行分隔，避免模型把指令当成正文示例。
                content.add(arkTextPart(prompt + "\n\n" + instruction));
                for (PageImage page : pages) {
                    String label = "PAGE_ID: " + page.getPageId();
                    if (page.getImageRole() != null) {
                        label += "\nIMAGE_ROLE: " + page.getImageRole();
                    }
                    content.add(arkTextPart(label));
                    content.add(arkImagePart(page.previewDataUrl(maxPreviewLongEdge), imageDetail));
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

        private HttpResponse http(VlmConfig.RoleConfig role, String body) {
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
                return new HttpResponse(responseText(root), VlmUsage.fromArkResponses(root),
                        root.path("model").asText(role.getModel()));
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
            return prompts.get(role.getRole());
        }

        private String loadPrompt(VlmConfig.RoleConfig role) {
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
            return action + "。REQUEST_PAGE_ID='" + pageId + "'。"
                    + "返回 JSON 中的 page_id 必须原样回显上面这个完整字符串，不得使用示例值、页序数字或缩写形式。";
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
            return previewDataUrl(1536);
        }

        /** 整页预览读取冻结的配置上限；ROI 仍由 {@link RoiImage} 保留原始局部清晰度。 */
        public String previewDataUrl(int maxPreviewLongEdge) {
            if (maxPreviewLongEdge <= 0) {
                throw new IllegalArgumentException("maxPreviewLongEdge must be positive");
            }
            return dataUrl(resize(image, maxPreviewLongEdge));
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
