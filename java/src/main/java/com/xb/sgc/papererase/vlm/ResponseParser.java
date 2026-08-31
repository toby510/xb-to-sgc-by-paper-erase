package com.xb.sgc.papererase.vlm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.LocateWindow;
import com.xb.sgc.papererase.model.ExamModels.PageDirection;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * VLM 协议解析器：把四个角色的原始 JSON 转换为强约束业务对象，并校验 page_id、状态和字段。
 * 解析失败必须失败关闭，禁止调用方拿不完整坐标继续擦除。
 */
public final class ResponseParser {
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private ResponseParser() {
    }

    public static PatternResponse parsePattern(String raw, List<String> expectedPageIds) {
        JsonNode root = root(raw);
        requireFields(root, "page_directions", "pattern_groups", "heterogeneous_page_ids",
                "no_pagenum_page_ids", "ungrouped_page_ids");
        rejectUnknown(root, "page_directions", "pattern_groups", "heterogeneous_page_ids",
                "no_pagenum_page_ids", "ungrouped_page_ids");
        PatternResponse response = new PatternResponse();
        Set<String> seenDirections = new HashSet<String>();
        for (JsonNode item : array(root, "page_directions")) {
            requireFields(item, "page_id", "reading_rotation", "confidence");
            rejectUnknown(item, "page_id", "reading_rotation", "confidence");
            PageDirection direction = new PageDirection();
            direction.page_id = requiredText(item, "page_id");
            if (!seenDirections.add(direction.page_id)) {
                throw bad("duplicate page_id: " + direction.page_id, raw);
            }
            direction.reading_rotation = requiredInt(item, "reading_rotation");
            if (!(direction.reading_rotation == 0 || direction.reading_rotation == 90
                    || direction.reading_rotation == 180 || direction.reading_rotation == 270)) {
                throw bad("reading rotation must be 0/90/180/270", raw);
            }
            direction.confidence = requiredFiniteUnit(item, "confidence", raw);
            response.page_directions.add(direction);
        }
        Set<String> expected = new HashSet<String>(expectedPageIds);
        if (!seenDirections.equals(expected)) {
            throw bad("pattern batch page ids must match exactly", raw);
        }
        for (JsonNode item : array(root, "pattern_groups")) {
            requireFields(item, "group_id", "edge", "alignment", "layout_description", "page_ids", "confidence", "locate_window");
            rejectUnknown(item, "group_id", "edge", "alignment", "layout_description", "page_ids", "confidence", "locate_window");
            PatternGroup group = new PatternGroup();
            group.group_id = requiredText(item, "group_id");
            group.edge = enumText(item, "edge", "bottom", "top", "left", "right", "mixed");
            group.alignment = enumText(item, "alignment", "left", "center", "right", "spread", "unknown");
            group.layout_description = requiredText(item, "layout_description");
            for (JsonNode pageId : array(item, "page_ids")) {
                String id = pageId.asText();
                if (!expected.contains(id)) {
                    throw bad("pattern group contains page outside batch: " + id, raw);
                }
                group.page_ids.add(id);
            }
            group.confidence = requiredFiniteUnit(item, "confidence", raw);
            group.locate_window = parseLocateWindow(item.path("locate_window"), raw);
            response.pattern_groups.add(group);
        }
        copyStringArray(root, "heterogeneous_page_ids", response.heterogeneous_page_ids, expected, raw);
        copyStringArray(root, "no_pagenum_page_ids", response.no_pagenum_page_ids, expected, raw);
        copyStringArray(root, "ungrouped_page_ids", response.ungrouped_page_ids, expected, raw);
        requireExactPatternClassification(response, expected, raw);
        return response;
    }

    /**
     * 解析 pattern 的归一化粗窗口。这里只做协议和正面积校验，不把粗窗口当成擦除坐标。
     *
     * @param node JSON locate_window 节点
     * @param raw 原始响应，用于构造可追溯错误
     * @return 合法的 0..1 归一化矩形
     */
    private static LocateWindow parseLocateWindow(JsonNode node, String raw) {
        requireFields(node, "x1", "y1", "x2", "y2");
        rejectUnknown(node, "x1", "y1", "x2", "y2");
        LocateWindow window = new LocateWindow();
        window.x1 = requiredFiniteUnit(node, "x1", raw);
        window.y1 = requiredFiniteUnit(node, "y1", raw);
        window.x2 = requiredFiniteUnit(node, "x2", raw);
        window.y2 = requiredFiniteUnit(node, "y2", raw);
        if (window.x1 >= window.x2 || window.y1 >= window.y2) {
            throw bad("locate_window must have positive area", raw);
        }
        return window;
    }

    private static void requireExactPatternClassification(PatternResponse response, Set<String> expected, String raw) {
        Set<String> seen = new HashSet<String>();
        for (PatternGroup group : response.pattern_groups) {
            for (String pageId : group.page_ids) {
                if (!expected.contains(pageId)) {
                    throw bad("unknown page_id in pattern classification: " + pageId, raw);
                }
                if (!seen.add(pageId)) {
                    throw bad("page_ids must be classified exactly once", raw);
                }
            }
        }
        addClassified(response.heterogeneous_page_ids, expected, seen, raw);
        addClassified(response.no_pagenum_page_ids, expected, seen, raw);
        addClassified(response.ungrouped_page_ids, expected, seen, raw);
        if (!seen.equals(expected)) {
            throw bad("page_ids must be classified exactly once", raw);
        }
    }

    private static void addClassified(List<String> pageIds, Set<String> expected, Set<String> seen, String raw) {
        for (String pageId : pageIds) {
            if (!expected.contains(pageId)) {
                throw bad("unknown page_id in pattern classification: " + pageId, raw);
            }
            if (!seen.add(pageId)) {
                throw bad("page_ids must be classified exactly once", raw);
            }
        }
    }

    public static LocateResponse parseLocate(String raw, String expectedPageId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "reading_rotation", "direction_confidence", "status", "regions", "evidence");
        rejectUnknown(root, "page_id", "reading_rotation", "direction_confidence", "status", "regions", "evidence");
        LocateResponse response = new LocateResponse();
        response.page_id = requiredText(root, "page_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        response.reading_rotation = requiredInt(root, "reading_rotation");
        if (!(response.reading_rotation == 0 || response.reading_rotation == 90
                || response.reading_rotation == 180 || response.reading_rotation == 270)) {
            throw bad("reading rotation must be 0/90/180/270", raw);
        }
        response.direction_confidence = requiredFiniteUnit(root, "direction_confidence", raw);
        response.status = enumText(root, "status", "safe_to_erase", "no_pagenum", "manual_review");
        response.evidence = requiredText(root, "evidence");
        Set<String> regionIds = new HashSet<String>();
        for (JsonNode item : array(root, "regions")) {
            requireFields(item, "region_id", "x1", "y1", "x2", "y2", "page_number_text",
                    "same_line_metadata", "on_line", "confidence", "safety_margin", "nearest_body_boundary");
            rejectUnknown(item, "region_id", "x1", "y1", "x2", "y2", "page_number_text",
                    "same_line_metadata", "on_line", "confidence", "safety_margin", "nearest_body_boundary");
            EraseRegion region = new EraseRegion();
            region.region_id = requiredText(item, "region_id");
            if (!regionIds.add(region.region_id)) {
                throw bad("duplicate region_id: " + region.region_id, raw);
            }
            region.x1 = requiredFiniteUnit(item, "x1", raw);
            region.y1 = requiredFiniteUnit(item, "y1", raw);
            region.x2 = requiredFiniteUnit(item, "x2", raw);
            region.y2 = requiredFiniteUnit(item, "y2", raw);
            if (region.x1 >= region.x2 || region.y1 >= region.y2) {
                throw bad("coordinates must satisfy x1 < x2 and y1 < y2", raw);
            }
            region.page_number_text = requiredText(item, "page_number_text");
            region.same_line_metadata = text(item, "same_line_metadata");
            region.on_line = requiredBoolean(item, "on_line");
            region.confidence = requiredFiniteUnit(item, "confidence", raw);
            region.safety_margin = requiredText(item, "safety_margin");
            region.nearest_body_boundary = parseBoundary(item.path("nearest_body_boundary"), raw);
            response.regions.add(region);
        }
        // 未旋正图的候选坐标不能映射到最终擦除图，只允许 Java 旋正后重新定位。
        if (response.reading_rotation != 0
                && ("safe_to_erase".equals(response.status) || !response.regions.isEmpty())) {
            throw bad("rotated locate requires non-safe status and empty regions", raw);
        }
        if ("safe_to_erase".equals(response.status) && response.regions.isEmpty()) {
            throw bad("safe_to_erase locate requires regions", raw);
        }
        // 非放行状态没有可擦除语义；携带候选框会让后续调用误把保守结论当作可用坐标。
        if (!"safe_to_erase".equals(response.status) && !response.regions.isEmpty()) {
            throw bad("non-safe locate requires empty regions", raw);
        }
        return response;
    }

    public static VerifyResponse parseVerify(String raw, String expectedPageId, String expectedRegionId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "region_id", "decision", "allowed_scope", "evidence", "refined_region");
        rejectUnknown(root, "page_id", "region_id", "decision", "allowed_scope", "evidence", "refined_region", "refined_nearest_body_boundary");
        VerifyResponse response = new VerifyResponse();
        response.page_id = requiredText(root, "page_id");
        response.region_id = requiredText(root, "region_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        requireEqual(response.region_id, expectedRegionId, "region_id", raw);
        response.decision = enumText(root, "decision", "safe_to_erase", "no_pagenum", "manual_review");
        response.allowed_scope = requiredText(root, "allowed_scope");
        response.evidence = requiredText(root, "evidence");
        if (!root.path("refined_region").isNull()) {
            response.refined_region = parseLocalRegion(root.path("refined_region"), raw);
            if (root.has("refined_nearest_body_boundary") && !root.path("refined_nearest_body_boundary").isNull()) {
                // 兼容旧版响应；新局部精定位协议不会再采纳或请求此字段。
                response.refined_nearest_body_boundary = parseBoundary(root.path("refined_nearest_body_boundary"), raw);
            }
        } else if (root.has("refined_nearest_body_boundary") && !root.path("refined_nearest_body_boundary").isNull()) {
            throw bad("refined_nearest_body_boundary requires refined_region", raw);
        }
        if ("safe_to_erase".equals(response.decision) && response.refined_region == null) {
            throw bad("safe_to_erase verify requires refined_region", raw);
        }
        if (!"safe_to_erase".equals(response.decision) && response.refined_region != null) {
            throw bad("non-safe verify requires null refined_region", raw);
        }
        return response;
    }

    /**
     * 解析局部 ROI 返回的归一化矩形。该坐标系只属于当前 ROI，不能直接当作整图坐标。
     *
     * @param node refined_region JSON 节点
     * @param raw 原始响应
     * @return ROI 相对 0..1 矩形
     */
    private static com.xb.sgc.papererase.model.ExamModels.LocalRegion parseLocalRegion(JsonNode node, String raw) {
        requireFields(node, "x1", "y1", "x2", "y2");
        rejectUnknown(node, "x1", "y1", "x2", "y2");
        com.xb.sgc.papererase.model.ExamModels.LocalRegion region = new com.xb.sgc.papererase.model.ExamModels.LocalRegion();
        region.x1 = requiredFiniteUnit(node, "x1", raw);
        region.y1 = requiredFiniteUnit(node, "y1", raw);
        region.x2 = requiredFiniteUnit(node, "x2", raw);
        region.y2 = requiredFiniteUnit(node, "y2", raw);
        if (region.x1 >= region.x2 || region.y1 >= region.y2) {
            throw bad("refined_region must have positive area", raw);
        }
        return region;
    }

    public static AuditResponse parseAudit(String raw, String expectedPageId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "decision", "original_target_is_non_body", "body_unchanged", "target_removed", "background_acceptable", "evidence");
        rejectUnknown(root, "page_id", "decision", "original_target_is_non_body", "body_unchanged", "target_removed", "background_acceptable", "evidence");
        AuditResponse response = new AuditResponse();
        response.page_id = requiredText(root, "page_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        response.decision = enumText(root, "decision", "pass", "manual_review");
        response.original_target_is_non_body = requiredBoolean(root, "original_target_is_non_body");
        response.body_unchanged = requiredBoolean(root, "body_unchanged");
        response.target_removed = requiredBoolean(root, "target_removed");
        response.background_acceptable = requiredBoolean(root, "background_acceptable");
        response.evidence = requiredText(root, "evidence");
        boolean hardConditionsPassed = response.original_target_is_non_body
                && response.body_unchanged && response.target_removed;
        if ("pass".equals(response.decision) != hardConditionsPassed) {
            throw bad("audit decision must exactly match original_target_is_non_body, body_unchanged and target_removed", raw);
        }
        return response;
    }

    public static ParseException parseFailure(String raw) {
        return new ParseException("parse failure", safeSummary(raw));
    }

    private static BodyBoundary parseBoundary(JsonNode node, String raw) {
        // 两个轴都必须显式出现；当前边缘无关的轴使用 null。这样 JSON shape 固定，调用方
        // 不会把模型“漏字段”误当成“已判断该轴不存在正文边界”。
        rejectUnknown(node, "x", "y", "basis");
        requireFields(node, "x", "y", "basis");
        BodyBoundary boundary = new BodyBoundary();
        if (node.has("x")) {
            boundary.x = nullableFiniteUnit(node, "x", raw);
        }
        if (node.has("y")) {
            boundary.y = nullableFiniteUnit(node, "y", raw);
        }
        boundary.basis = requiredText(node, "basis");
        return boundary;
    }

    private static JsonNode root(String raw) {
        String json = unwrapSingleCodeFence(raw);
        if (json != null && (!json.startsWith("{") || !json.endsWith("}"))) {
            json = extractSingleJsonObject(json);
        }
        if (json == null || !json.startsWith("{") || !json.endsWith("}")) {
            throw bad("strict JSON object required", raw);
        }
        try {
            JsonParser parser = MAPPER.getFactory().createParser(json);
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) {
                throw bad("strict JSON object required", raw);
            }
            JsonNode node = MAPPER.readTree(parser);
            if (parser.nextToken() != null) {
                throw bad("strict JSON object required", raw);
            }
            return node;
        } catch (JsonParseException e) {
            throw bad("strict JSON parse failed", raw);
        } catch (IOException e) {
            throw bad("strict JSON parse failed", raw);
        }
    }

    /**
     * 仅容忍一个完整 JSON 对象外包裹的简短模型说明。扫描时识别字符串转义和嵌套对象；
     * 出现第二个对象、括号不闭合或对象后仍有结构化片段均拒绝。提取后仍由原有严格字段、
     * page_id、枚举和坐标协议校验，不能借此绕过业务安全契约。
     */
    private static String extractSingleJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        int end = -1;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                if (--depth == 0) {
                    end = i + 1;
                    break;
                }
                if (depth < 0) {
                    return null;
                }
            }
        }
        if (end < 0 || quoted || depth != 0) {
            return null;
        }
        String suffix = raw.substring(end);
        if (suffix.indexOf('{') >= 0 || suffix.indexOf('}') >= 0) {
            return null;
        }
        return raw.substring(start, end);
    }

    /**
     * Ark 偶尔会把本来完整的结构化答案包进唯一的 Markdown 代码围栏。
     * 这里只接受纯围栏包装，围栏前后出现任何解释文字仍拒绝，避免放宽 JSON 协议。
     */
    private static String unwrapSingleCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int headerEnd = trimmed.indexOf('\n');
        if (headerEnd < 0) {
            return trimmed;
        }
        String header = trimmed.substring(0, headerEnd).trim();
        if (!("```".equals(header) || "```json".equalsIgnoreCase(header))) {
            return trimmed;
        }
        if (!trimmed.endsWith("```")) {
            return trimmed;
        }
        String content = trimmed.substring(headerEnd + 1, trimmed.length() - 3);
        if (!content.endsWith("\n")) {
            return trimmed;
        }
        return content.substring(0, content.length() - 1).trim();
    }

    private static void requireFields(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node == null || !node.has(field)) {
                throw new ParseException("missing field: " + field, "");
            }
        }
    }

    private static void rejectUnknown(JsonNode node, String... fields) {
        Set<String> allowed = new HashSet<String>(Arrays.asList(fields));
        java.util.Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ParseException("unknown field: " + name, "");
            }
        }
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            throw new ParseException(field + " must be an array", "");
        }
        return array;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.trim().length() == 0) {
            throw new ParseException(field + " is required", "");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static String enumText(JsonNode node, String field, String... values) {
        String value = requiredText(node, field);
        for (String allowed : values) {
            if (allowed.equals(value)) {
                return value;
            }
        }
        throw new ParseException(field + " has illegal decision/status/enum value", "");
    }

    private static int requiredInt(JsonNode node, String field) {
        if (!node.path(field).isInt()) {
            throw new ParseException(field + " must be integer", "");
        }
        return node.path(field).asInt();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        if (!node.path(field).isBoolean()) {
            throw new ParseException(field + " must be boolean", "");
        }
        return node.path(field).asBoolean();
    }

    /**
     * 读取必须存在、有限且位于 0..1 的归一化坐标/置信度字段。
     *
     * @param node JSON 节点
     * @param field 字段名
     * @param raw 原始响应
     * @return 合法 double
     */
    private static double requiredFiniteUnit(JsonNode node, String field, String raw) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw bad(field + " must be numeric", raw);
        }
        double number = value.asDouble();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 1) {
            throw bad(field + " must be finite 0..1", raw);
        }
        return number;
    }

    private static Double nullableFiniteUnit(JsonNode node, String field, String raw) {
        JsonNode value = node.path(field);
        if (value.isNull()) {
            return null;
        }
        return requiredFiniteUnit(node, field, raw);
    }

    private static void copyStringArray(JsonNode root, String field, List<String> target, Set<String> expected, String raw) {
        Set<String> seen = new HashSet<String>();
        for (JsonNode item : array(root, field)) {
            if (!item.isTextual()) {
                throw bad(field + " must contain page_id strings", raw);
            }
            String id = item.asText();
            if (!expected.contains(id)) {
                throw bad(field + " contains page outside batch: " + id, raw);
            }
            if (!seen.add(id)) {
                throw bad("duplicate page_id in " + field + ": " + id, raw);
            }
            target.add(id);
        }
    }

    private static void requireEqual(String actual, String expected, String field, String raw) {
        if (!expected.equals(actual)) {
            throw bad(field + " mismatch", raw);
        }
    }

    private static ParseException bad(String message, String raw) {
        return new ParseException(message, safeSummary(raw));
    }

    private static String safeSummary(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.replaceAll("(?i)(secret|token|api[_-]?key)[^\\s,}\"]*", "$1=<redacted>");
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    public static class ParseException extends RuntimeException {
        private final String rawSummary;

        public ParseException(String message, String rawSummary) {
            super(message);
            this.rawSummary = rawSummary == null ? "" : rawSummary;
        }

        public String getRawSummary() {
            return rawSummary;
        }
    }
}
