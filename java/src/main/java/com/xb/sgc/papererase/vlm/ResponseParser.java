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
import com.xb.sgc.papererase.model.ExamModels.PageDirection;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            requireFields(item, "group_id", "edge", "alignment", "layout_description", "page_ids", "confidence");
            rejectUnknown(item, "group_id", "edge", "alignment", "layout_description", "page_ids", "confidence");
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
            response.pattern_groups.add(group);
        }
        copyStringArray(root, "heterogeneous_page_ids", response.heterogeneous_page_ids, expected, raw);
        copyStringArray(root, "no_pagenum_page_ids", response.no_pagenum_page_ids, expected, raw);
        copyStringArray(root, "ungrouped_page_ids", response.ungrouped_page_ids, expected, raw);
        return response;
    }

    public static LocateResponse parseLocate(String raw, String expectedPageId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "status", "regions", "nearest_body_boundary", "evidence");
        rejectUnknown(root, "page_id", "status", "regions", "nearest_body_boundary", "evidence");
        LocateResponse response = new LocateResponse();
        response.page_id = requiredText(root, "page_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        response.status = enumText(root, "status", "safe_to_erase", "no_pagenum", "manual_review");
        response.evidence = requiredText(root, "evidence");
        Set<String> regionIds = new HashSet<String>();
        for (JsonNode item : array(root, "regions")) {
            requireFields(item, "region_id", "x1", "y1", "x2", "y2", "page_number_text",
                    "same_line_metadata", "on_line", "confidence", "safety_margin");
            rejectUnknown(item, "region_id", "x1", "y1", "x2", "y2", "page_number_text",
                    "same_line_metadata", "on_line", "confidence", "safety_margin");
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
            response.regions.add(region);
        }
        response.nearest_body_boundary = parseBoundary(root.path("nearest_body_boundary"), raw);
        if ("safe_to_erase".equals(response.status) && response.regions.isEmpty()) {
            throw bad("safe_to_erase locate requires regions", raw);
        }
        return response;
    }

    public static VerifyResponse parseVerify(String raw, String expectedPageId, String expectedRegionId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "region_id", "decision", "allowed_scope", "evidence");
        rejectUnknown(root, "page_id", "region_id", "decision", "allowed_scope", "evidence");
        VerifyResponse response = new VerifyResponse();
        response.page_id = requiredText(root, "page_id");
        response.region_id = requiredText(root, "region_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        requireEqual(response.region_id, expectedRegionId, "region_id", raw);
        response.decision = enumText(root, "decision", "safe_to_erase", "no_pagenum", "manual_review");
        response.allowed_scope = requiredText(root, "allowed_scope");
        response.evidence = requiredText(root, "evidence");
        return response;
    }

    public static AuditResponse parseAudit(String raw, String expectedPageId) {
        JsonNode root = root(raw);
        requireFields(root, "page_id", "decision", "body_unchanged", "target_removed", "background_acceptable", "evidence");
        rejectUnknown(root, "page_id", "decision", "body_unchanged", "target_removed", "background_acceptable", "evidence");
        AuditResponse response = new AuditResponse();
        response.page_id = requiredText(root, "page_id");
        requireEqual(response.page_id, expectedPageId, "page_id", raw);
        response.decision = enumText(root, "decision", "pass", "manual_review");
        response.body_unchanged = requiredBoolean(root, "body_unchanged");
        response.target_removed = requiredBoolean(root, "target_removed");
        response.background_acceptable = requiredBoolean(root, "background_acceptable");
        response.evidence = requiredText(root, "evidence");
        if ("pass".equals(response.decision)
                && (!response.body_unchanged || !response.target_removed || !response.background_acceptable)) {
            throw bad("audit pass requires body_unchanged/target_removed/background_acceptable all true", raw);
        }
        return response;
    }

    public static ParseException parseFailure(String raw) {
        return new ParseException("parse failure", safeSummary(raw));
    }

    private static BodyBoundary parseBoundary(JsonNode node, String raw) {
        requireFields(node, "x", "y", "basis");
        rejectUnknown(node, "x", "y", "basis");
        BodyBoundary boundary = new BodyBoundary();
        boundary.x = nullableFiniteUnit(node, "x", raw);
        boundary.y = nullableFiniteUnit(node, "y", raw);
        boundary.basis = requiredText(node, "basis");
        return boundary;
    }

    private static JsonNode root(String raw) {
        if (raw == null || !raw.trim().startsWith("{") || !raw.trim().endsWith("}")) {
            throw bad("strict JSON object required", raw);
        }
        try {
            JsonParser parser = MAPPER.getFactory().createParser(raw);
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
