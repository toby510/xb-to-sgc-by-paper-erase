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

public interface VlmClient {
    PatternResponse pattern(List<PageImage> pages);

    LocateResponse locate(PageImage page, PatternGroup group);

    VerifyResponse verify(PageImage page, EraseRegion region, RoiImage roi);

    AuditResponse audit(PageImage original, PageImage erased, List<EraseRegion> regions, List<RoiImage> rois);

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
                    String label = "PAGE_ID: " + page.getPageId();
                    if (page.getImageRole() != null) {
                        label += "\nIMAGE_ROLE: " + page.getImageRole();
                    }
                    content.add(textPart(label));
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
