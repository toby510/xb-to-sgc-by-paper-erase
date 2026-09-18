package com.xb.sgc.papererase.vlm;

import com.xb.sgc.papererase.image.RoiTransform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 诊断探针：只调用 audit 角色，用来单独评估审计提示词的识别能力，不参与交付流水线。
 *
 * <p>用法（在 java 目录下执行）：</p>
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=com.xb.sgc.papererase.vlm.AuditProbe \
 *   -Dexec.args="&lt;skillRoot&gt; &lt;original.png&gt; &lt;erased.png&gt; &lt;pageId&gt; \
 *   &lt;regionId&gt; &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt; &lt;pageNumberText&gt;"
 * </pre>
 *
 * <p>输出单行 JSON：page_id、decision、三个硬条件、evidence。</p>
 */
public final class AuditProbe {
    private AuditProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 4 && "--cases".equals(args[1])) {
            int roiScale = args.length >= 5 ? Integer.parseInt(args[4]) : 1;
            runCases(Paths.get(args[0]), Paths.get(args[2]), Integer.parseInt(args[3]), roiScale);
            return;
        }
        if (args.length < 10) {
            System.err.println("Usage: AuditProbe <skillRoot> <original.png> <erased.png> <pageId> <regionId>"
                    + " <x1> <y1> <x2> <y2> <pageNumberText>");
            System.exit(2);
        }
        Path skillRoot = Paths.get(args[0]);
        BufferedImage original = ImageIO.read(new File(args[1]));
        BufferedImage erased = ImageIO.read(new File(args[2]));
        String pageId = args[3];
        String regionId = args[4];

        EraseRegion region = new EraseRegion();
        region.region_id = regionId;
        region.x1 = Double.parseDouble(args[5]);
        region.y1 = Double.parseDouble(args[6]);
        region.x2 = Double.parseDouble(args[7]);
        region.y2 = Double.parseDouble(args[8]);
        region.page_number_text = args[9];
        region.same_line_metadata = "";
        region.on_line = false;
        region.confidence = 0.99;
        region.safety_margin = "probe";
        region.nearest_body_boundary = new BodyBoundary();
        region.nearest_body_boundary.x = null;
        region.nearest_body_boundary.y = null;
        region.nearest_body_boundary.basis = "probe: 未提供正文边界";

        List<EraseRegion> regions = new ArrayList<EraseRegion>();
        regions.add(region);

        List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
        RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                original.getWidth(), original.getHeight(), region, null, 24);
        rois.add(new VlmClient.RoiImage(pageId, regionId, crop(original, transform), "ORIGINAL"));
        rois.add(new VlmClient.RoiImage(pageId, regionId, crop(erased, transform), "ERASED"));

        VlmConfig config = VlmConfig.load(skillRoot.resolve("config/vlm-providers.json"));
        VlmClient vlm = VlmClient.create(config, skillRoot, VlmUsageSink.NOOP);
        AuditResponse audit = vlm.audit(new VlmClient.PageImage(pageId, original),
                new VlmClient.PageImage(pageId, erased), regions, rois);

        StringBuilder json = new StringBuilder();
        json.append("{\"page_id\":\"").append(audit.page_id)
                .append("\",\"decision\":\"").append(audit.decision)
                .append("\",\"original_target_is_non_body\":").append(audit.original_target_is_non_body)
                .append(",\"body_changed\":").append(audit.body_changed)
                .append(",\"target_removed\":").append(audit.target_removed)
                .append(",\"background_acceptable\":").append(audit.background_acceptable)
                .append(",\"evidence\":\"").append(String.valueOf(audit.evidence).replace("\\", "\\\\")
                        .replace("\"", "\\\"").replace("\n", " ").replace("\r", " "))
                .append("\"}");
        System.out.println(json);
    }

    /**
     * 批量模式：对 <casesDir>/cases.json 里的每个 case 重复调用 audit，逐行输出结果。
     * 用于评估审计提示词对"故意伤害正文"样本的识别率。
     */
    private static void runCases(Path skillRoot, Path casesDir, int repeats, int roiScale) throws Exception {
        JsonNode root = new ObjectMapper().readTree(casesDir.resolve("cases.json").toFile());
        JsonNode cases = root.get("cases");
        VlmConfig config = VlmConfig.load(skillRoot.resolve("config/vlm-providers.json"));
        VlmClient vlm = VlmClient.create(config, skillRoot, VlmUsageSink.NOOP);
        java.util.Iterator<String> names = cases.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode node = cases.get(name);
            JsonNode rect = node.get("region");
            BufferedImage original = ImageIO.read(casesDir.resolve(name + "_ORIGINAL.png").toFile());
            BufferedImage erased = ImageIO.read(casesDir.resolve(name + "_ERASED.png").toFile());
            EraseRegion region = regionOf("probe:1", "r1", rect, "第7页/共10页");
            List<EraseRegion> regions = new ArrayList<EraseRegion>();
            regions.add(region);
            List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
            RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                    original.getWidth(), original.getHeight(), region, null, 24);
            // 原图与擦除后 ROI 必须用同一倍率放大，保持两张图可逐位置对齐。
            rois.add(new VlmClient.RoiImage("probe:1", "r1", scaleUp(crop(original, transform), roiScale), "ORIGINAL"));
            rois.add(new VlmClient.RoiImage("probe:1", "r1", scaleUp(crop(erased, transform), roiScale), "ERASED"));
            for (int round = 1; round <= repeats; round++) {
                try {
                    AuditResponse audit = vlm.audit(new VlmClient.PageImage("probe:1", original),
                            new VlmClient.PageImage("probe:1", erased), regions, rois);
                    System.out.println("RESULT\t" + name + "\t" + node.get("label").asText() + "\t" + round + "\t"
                            + audit.decision + "\t" + audit.body_changed + "\t" + audit.target_removed + "\t"
                            + String.valueOf(audit.evidence).replace('\n', ' ').replace('\t', ' '));
                } catch (RuntimeException failure) {
                    String message = String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\t', ' ');
                    if (message.length() > 700) {
                        message = message.substring(0, 700);
                    }
                    if (failure instanceof ResponseParser.ParseException) {
                        String raw = String.valueOf(((ResponseParser.ParseException) failure).getRawSummary())
                                .replace('\n', ' ').replace('\t', ' ');
                        if (raw.length() > 700) {
                            raw = raw.substring(0, 700);
                        }
                        message = message + " | RAW=" + raw;
                    }
                    System.out.println("RESULT\t" + name + "\t" + node.get("label").asText() + "\t" + round
                            + "\tERROR\t-\t-\t" + failure.getClass().getSimpleName() + ": " + message);
                }
            }
        }
    }

    private static EraseRegion regionOf(String pageId, String regionId, JsonNode rect, String text) {
        EraseRegion region = new EraseRegion();
        region.region_id = regionId;
        region.x1 = rect.get(0).asDouble();
        region.y1 = rect.get(1).asDouble();
        region.x2 = rect.get(2).asDouble();
        region.y2 = rect.get(3).asDouble();
        region.page_number_text = text;
        region.same_line_metadata = "";
        region.on_line = false;
        region.confidence = 0.99;
        region.safety_margin = "probe";
        region.nearest_body_boundary = new BodyBoundary();
        region.nearest_body_boundary.x = null;
        region.nearest_body_boundary.y = null;
        region.nearest_body_boundary.basis = "probe: 未提供正文边界";
        return region;
    }

    /** 按 RoiTransform 的整图像素矩形裁剪；与流水线 auditRois 使用同一变换口径。 */
    private static BufferedImage crop(BufferedImage source, RoiTransform transform) {
        int x = Math.max(0, transform.getX());
        int y = Math.max(0, transform.getY());
        int width = Math.min(source.getWidth() - x, transform.getWidth());
        int height = Math.min(source.getHeight() - y, transform.getHeight());
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = cropped.createGraphics();
        graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        graphics.dispose();
        return cropped;
    }

    /** 放大 ROI 裁剪图；factor<=1 时原样返回。插值口径与流水线 relocate 的 3 倍放大一致。 */
    private static BufferedImage scaleUp(BufferedImage source, int factor) {
        if (factor <= 1) {
            return source;
        }
        BufferedImage enlarged = new BufferedImage(
                source.getWidth() * factor, source.getHeight() * factor, source.getType());
        java.awt.Graphics2D graphics = enlarged.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, enlarged.getWidth(), enlarged.getHeight(), null);
        graphics.dispose();
        return enlarged;
    }
}
