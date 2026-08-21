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
    PatternResponse pattern(List<PageImage> pages);

    LocateResponse locate(PageImage page, PatternGroup group);

    VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi);

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

        public LocateResponse locate(PageImage page, PatternGroup group) {
            return ResponseParser.parseLocate(call("locate", "Locate page_id=" + page.getPageId()
                    + " pattern_group=" + (group == null ? "none" : group.group_id), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        public VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId, one(page), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", "Audit page_id=" + original.getPageId(),
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
                    content.add(textPart(label + "ROI_REGION_ID: " + roi.getRegionId()));
                    content.add(imagePart(roi.dataUrl()));
                }
                Map<String, Object> message = new LinkedHashMap<String, Object>();
                message.put("role", "user");
                message.put("content", content);

                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("model", model);
                body.put("temperature", 0);
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

        public LocateResponse locate(PageImage page, PatternGroup group) {
            return ResponseParser.parseLocate(call("locate", "Locate page_id=" + page.getPageId()
                    + " pattern_group=" + (group == null ? "none" : group.group_id), one(page),
                    java.util.Collections.<RoiImage>emptyList()), page.getPageId());
        }

        public VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi) {
            String regionId = region == null ? "edge" : region.region_id;
            return ResponseParser.parseVerify(call("verify", "Verify page_id=" + page.getPageId()
                    + " region_id=" + regionId, one(page), java.util.Collections.singletonList(roi)),
                    page.getPageId(), regionId);
        }

        public AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois) {
            return ResponseParser.parseAudit(call("audit", "Audit page_id=" + original.getPageId(),
                    java.util.Arrays.asList(original.withImageRole("ORIGINAL"), erased.withImageRole("ERASED")),
                    rois == null ? java.util.Collections.<RoiImage>emptyList() : rois), original.getPageId());
        }

        private String call(String role, String instruction, List<PageImage> pages, List<RoiImage> rois) {
            VlmConfig.RoleConfig roleConfig = config.role(role);
            RuntimeException last = null;
            for (int attempt = 0; attempt <= roleConfig.getRetries(); attempt++) {
                try {
                    return http(roleConfig, buildRequestBody(roleConfig.getModel(), readPrompt(roleConfig), instruction, pages, rois));
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            throw last == null ? new RuntimeException(role + " Ark call failed") : last;
        }

        /** 对应 Ark Responses：input 是消息数组，图片 URL 是字符串而非 image_url.url 对象。 */
        public static String buildRequestBody(String model, String prompt, String instruction,
                                              List<PageImage> pages, List<RoiImage> rois) {
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
                    content.add(arkImagePart(page.previewDataUrl()));
                }
                for (RoiImage roi : rois) {
                    String label = roi.getPageId() == null ? "" : "ROI_PAGE_ID: " + roi.getPageId() + "\n";
                    content.add(arkTextPart(label + "ROI_REGION_ID: " + roi.getRegionId()));
                    content.add(arkImagePart(roi.dataUrl()));
                }
                Map<String, Object> inputMessage = new LinkedHashMap<String, Object>();
                inputMessage.put("role", "user");
                inputMessage.put("content", content);
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("model", model);
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

        private static Map<String, Object> arkImagePart(String dataUrl) {
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "input_image");
            part.put("image_url", dataUrl);
            part.put("detail", "high");
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

        public RoiImage(String regionId, BufferedImage image) {
            this(null, regionId, image);
        }

        public RoiImage(String pageId, String regionId, BufferedImage image) {
            if (regionId == null || image == null) {
                throw new IllegalArgumentException("regionId and image are required");
            }
            this.pageId = pageId;
            this.regionId = regionId;
            this.image = image;
        }

        public String getPageId() {
            return pageId;
        }

        public String getRegionId() {
            return regionId;
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
