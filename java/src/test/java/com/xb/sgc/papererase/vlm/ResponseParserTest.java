package com.xb.sgc.papererase.vlm;

import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResponseParserTest {
    @Test
    public void parsesStrictPatternOnlyWhenBatchPageIdsMatchExactly() {
        String json = "{"
                + "\"page_directions\":["
                + "{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.99},"
                + "{\"page_id\":\"p2\",\"reading_rotation\":90,\"confidence\":0.98}],"
                + "\"pattern_groups\":[{\"group_id\":\"g1\",\"edge\":\"bottom\",\"alignment\":\"center\","
                + "\"layout_description\":\"footer\",\"page_ids\":[\"p1\",\"p2\"],\"confidence\":0.97,\"locate_window\":{\"x1\":0.2,\"y1\":0.8,\"x2\":0.8,\"y2\":1.0}}],"
                + "\"heterogeneous_page_ids\":[],\"no_pagenum_page_ids\":[],\"ungrouped_page_ids\":[]}";

        PatternResponse parsed = ResponseParser.parsePattern(json, Arrays.asList("p1", "p2"));

        assertEquals(2, parsed.page_directions.size());
        assertEquals("p2", parsed.page_directions.get(1).page_id);
        assertEquals("bottom", parsed.pattern_groups.get(0).edge);
    }

    @Test
    public void rejectsMarkdownGarbageUnknownFieldsBadEnumsDuplicateAndMissingPatternPages() {
        assertBadPattern("```json\n{}\n```", "missing field");
        assertBadPattern("{\"page_directions\":[],\"pattern_groups\":[],\"heterogeneous_page_ids\":[],"
                + "\"no_pagenum_page_ids\":[],\"ungrouped_page_ids\":[],\"extra\":true}", "unknown");
        assertBadPattern("{\"page_directions\":[{\"page_id\":\"p1\",\"reading_rotation\":45,\"confidence\":0.9}],"
                + "\"pattern_groups\":[],\"heterogeneous_page_ids\":[],\"no_pagenum_page_ids\":[],"
                + "\"ungrouped_page_ids\":[]}", "rotation");
        assertBadPattern("{\"page_directions\":[{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.9},"
                + "{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.9}],\"pattern_groups\":[],"
                + "\"heterogeneous_page_ids\":[],\"no_pagenum_page_ids\":[],\"ungrouped_page_ids\":[]}", "duplicate");
        assertBadPattern("{\"page_directions\":[{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.9}],"
                + "\"pattern_groups\":[],\"heterogeneous_page_ids\":[],\"no_pagenum_page_ids\":[],"
                + "\"ungrouped_page_ids\":[]}", "batch page ids");
    }

    @Test
    public void rejectsPatternWhenPageIdsAreNotClassifiedExactlyOnce() {
        assertPatternRejected(patternJson("[\"p1\",\"p2\"]", "[\"p2\"]", "[]", "[]"));
        assertPatternRejected(patternJson("[\"p1\",\"p3\"]", "[]", "[]", "[]"));
        assertPatternRejected(patternJson("[\"p1\"]", "[]", "[]", "[]"));
        assertPatternRejected(patternJsonTwoGroups("[\"p1\"]", "[\"p1\"]"));
    }

    @Test
    public void parsesLocateVerifyAndAuditWithStrictCoordinatesAndDecisions() {
        LocateResponse locate = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"},"
                + "\"evidence\":\"ok\"}", "p1");
        assertEquals("r1", locate.regions.get(0).region_id);
        assertEquals(0, locate.reading_rotation);
        assertEquals(0.99, locate.direction_confidence, 0.0);

        VerifyResponse verify = ResponseParser.parseVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\","
                + "\"decision\":\"safe_to_erase\",\"allowed_scope\":\"page number only\","
                + "\"evidence\":\"ok\",\"refined_region\":{\"x1\":0.2,\"y1\":0.7,\"x2\":0.8,\"y2\":0.9}}", "p1", "r1");
        assertEquals("safe_to_erase", verify.decision);

        AuditResponse audit = ResponseParser.parseAudit("{\"page_id\":\"p1\",\"decision\":\"pass\","
                + "\"original_target_is_non_body\":true,\"body_unchanged\":true,\"target_removed\":true,\"background_acceptable\":true,"
                + "\"evidence\":\"ok\"}", "p1");
        assertTrue(audit.body_unchanged);
    }

    @Test
    public void parsesRoiRelativeRefinedCoordinatesOnlyAsAnExplicitPair() {
        VerifyResponse verify = ResponseParser.parseVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\","
                + "\"decision\":\"safe_to_erase\",\"allowed_scope\":\"page number only\",\"evidence\":\"ok\","
                + "\"refined_region\":{\"x1\":0.20,\"y1\":0.30,\"x2\":0.40,\"y2\":0.50},"
                + "\"refined_nearest_body_boundary\":{\"x\":null,\"y\":0.10,\"basis\":\"body\"}}", "p1", "r1");
        assertEquals(0.30, verify.refined_region.y1, 0.0);
        assertEquals(0.10, verify.refined_nearest_body_boundary.y, 0.0);
    }

    @Test
    public void parsesLocateWhenBoundaryOmitsTheNullAxis() {
        // 底部/顶部目标：模型省略 x:null 只返回 y 轴（locate-prompt-v20 允许 null 轴，模型常省略该字段）
        LocateResponse footer = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"y\":0.88,\"basis\":\"java\"},\"evidence\":\"ok\"}", "p1");
        assertTrue(footer.nearest_body_boundary.x == null);
        assertEquals(0.88, footer.nearest_body_boundary.y, 0.0);

        // 左侧/右侧目标：模型省略 y:null 只返回 x 轴
        LocateResponse side = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.02,\"y1\":0.45,\"x2\":0.04,\"y2\":0.55,"
                + "\"page_number_text\":\"2\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.96,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":0.06,\"basis\":\"page left edge\"},\"evidence\":\"ok\"}", "p1");
        assertEquals(0.06, side.nearest_body_boundary.x, 0.0);
        assertTrue(side.nearest_body_boundary.y == null);

        // 边界必须至少保留 basis：只有坐标、无 basis 仍拒绝，避免放空协议。
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"y\":0.88},\"evidence\":\"ok\"}", "missing field");
    }

    @Test
    public void parsesLocateWhenArkWrapsOtherwiseValidJsonInOneCodeFence() {
        LocateResponse locate = ResponseParser.parseLocate("```json\n{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"},"
                + "\"evidence\":\"ok\"}\n```", "p1");

        assertEquals("r1", locate.regions.get(0).region_id);
    }

    @Test
    public void extractsOnlyOneSchemaValidJsonObjectFromModelExplanation() {
        String json = "{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"},"
                + "\"evidence\":\"ok\"}";

        LocateResponse locate = ResponseParser.parseLocate("分析完成：\n" + json + "\n以上为结果。", "p1");
        assertEquals("r1", locate.regions.get(0).region_id);
        assertBadLocate(json + "\n另一个对象：{}", "strict JSON");
    }

    @Test
    public void rejectsUnsafeLocateVerifyAuditAndStoresShortSafeRawSummary() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe\",\"regions\":[],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"java\"},\"evidence\":\"x\"}", "status");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.1,\"y1\":0.9,\"x2\":0.2,\"y2\":NaN,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"java\"},\"evidence\":\"x\"}", "strict JSON");
        assertBadVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"decision\":\"erase\","
                + "\"allowed_scope\":\"x\",\"evidence\":\"x\",\"refined_region\":null,"
                + "\"refined_nearest_body_boundary\":null}", "decision");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"original_target_is_non_body\":true,\"body_unchanged\":true,"
                + "\"target_removed\":false,\"background_acceptable\":true,\"evidence\":\"x\"}", "decision must exactly match");

        ResponseParser.ParseException ex = ResponseParser.parseFailure("token=secret-1234567890 " + repeat("x", 500));
        assertTrue(ex.getRawSummary().length() <= 240);
        assertFalse(ex.getRawSummary().contains("secret-1234567890"));
    }

    @Test
    public void acceptsAuditPassWithOnlyBackgroundWarning() {
        AuditResponse audit = ResponseParser.parseAudit("{\"page_id\":\"p1\",\"decision\":\"pass\","
                + "\"original_target_is_non_body\":true,\"body_unchanged\":true,\"target_removed\":true,\"background_acceptable\":false,"
                + "\"evidence\":\"only background tone differs\"}", "p1");
        assertFalse(audit.background_acceptable);
    }

    @Test
    public void requiresAValidDirectionOnEveryLocateResponse() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":45,\"direction_confidence\":0.99,"
                + "\"status\":\"manual_review\",\"regions\":[],"
                + "\"nearest_body_boundary\":{\"basis\":\"uncertain\"},\"evidence\":\"direction only\"}",
                "rotation");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":90,\"status\":\"manual_review\","
                + "\"regions\":[],\"nearest_body_boundary\":{\"basis\":\"uncertain\"},"
                + "\"evidence\":\"direction only\"}", "missing field");
    }

    @Test
    public void rejectsLocateStateAndRegionCombinationsThatCannotBeSafelyExecuted() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,"
                + "\"status\":\"no_pagenum\",\"regions\":[{\"region_id\":\"r1\",\"x1\":0.4,\"y1\":0.9,\"x2\":0.6,\"y2\":0.95,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"body\"},\"evidence\":\"x\"}", "empty regions");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":90,\"direction_confidence\":0.99,"
                + "\"status\":\"safe_to_erase\",\"regions\":[],\"nearest_body_boundary\":{\"x\":null,\"y\":null,\"basis\":\"rotation\"},\"evidence\":\"x\"}", "rotated locate");
    }

    @Test
    public void rejectsVerifyAndAuditDecisionCombinationsThatContradictTheirPayloads() {
        assertBadVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"decision\":\"safe_to_erase\","
                + "\"allowed_scope\":\"x\",\"evidence\":\"x\",\"refined_region\":null}", "refined_region");
        assertBadVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"decision\":\"manual_review\","
                + "\"allowed_scope\":\"x\",\"evidence\":\"x\",\"refined_region\":{\"x1\":0.1,\"y1\":0.1,\"x2\":0.2,\"y2\":0.2}}", "refined_region");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"manual_review\",\"original_target_is_non_body\":true,"
                + "\"body_unchanged\":true,\"target_removed\":true,\"background_acceptable\":false,\"evidence\":\"x\"}", "decision must exactly match");
    }

    @Test
    public void rejectsAuditPassWhenOriginalTargetIsBodyOrFieldIsMissing() {
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"body_unchanged\":true,"
                + "\"target_removed\":true,\"background_acceptable\":true,\"evidence\":\"x\"}",
                "original_target_is_non_body");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"original_target_is_non_body\":false,"
                + "\"body_unchanged\":true,\"target_removed\":true,\"background_acceptable\":true,\"evidence\":\"x\"}",
                "decision must exactly match");
    }

    private void assertBadPattern(String json, String messagePart) {
        try {
            ResponseParser.parsePattern(json, Arrays.asList("p1", "p2"));
            throw new AssertionError("pattern should be rejected");
        } catch (ResponseParser.ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private void assertPatternRejected(String json) {
        try {
            ResponseParser.parsePattern(json, Arrays.asList("p1", "p2"));
            throw new AssertionError("pattern should be rejected");
        } catch (ResponseParser.ParseException expected) {
            // The safety behavior is the contract; parser wording is deliberately not asserted.
        }
    }

    private String patternJson(String groupPageIds, String heterogeneous, String noPagenum, String ungrouped) {
        return "{\"page_directions\":["
                + "{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.9},"
                + "{\"page_id\":\"p2\",\"reading_rotation\":0,\"confidence\":0.9}],"
                + "\"pattern_groups\":[{\"group_id\":\"g1\",\"edge\":\"bottom\",\"alignment\":\"center\","
                + "\"layout_description\":\"footer\",\"page_ids\":" + groupPageIds + ",\"confidence\":0.9,\"locate_window\":{\"x1\":0.2,\"y1\":0.8,\"x2\":0.8,\"y2\":1.0}}],"
                + "\"heterogeneous_page_ids\":" + heterogeneous + ","
                + "\"no_pagenum_page_ids\":" + noPagenum + ","
                + "\"ungrouped_page_ids\":" + ungrouped + "}";
    }

    private String patternJsonTwoGroups(String firstGroupPageIds, String secondGroupPageIds) {
        return "{\"page_directions\":["
                + "{\"page_id\":\"p1\",\"reading_rotation\":0,\"confidence\":0.9},"
                + "{\"page_id\":\"p2\",\"reading_rotation\":0,\"confidence\":0.9}],"
                + "\"pattern_groups\":["
                + "{\"group_id\":\"g1\",\"edge\":\"bottom\",\"alignment\":\"center\","
                + "\"layout_description\":\"footer\",\"page_ids\":" + firstGroupPageIds + ",\"confidence\":0.9,\"locate_window\":{\"x1\":0.2,\"y1\":0.8,\"x2\":0.8,\"y2\":1.0}},"
                + "{\"group_id\":\"g2\",\"edge\":\"top\",\"alignment\":\"center\","
                + "\"layout_description\":\"header\",\"page_ids\":" + secondGroupPageIds + ",\"confidence\":0.9,\"locate_window\":{\"x1\":0.2,\"y1\":0.0,\"x2\":0.8,\"y2\":0.2}}],"
                + "\"heterogeneous_page_ids\":[],\"no_pagenum_page_ids\":[],\"ungrouped_page_ids\":[\"p2\"]}";
    }

    private void assertBadLocate(String json, String messagePart) {
        try {
            ResponseParser.parseLocate(json, "p1");
            throw new AssertionError("locate should be rejected");
        } catch (ResponseParser.ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private void assertBadVerify(String json, String messagePart) {
        try {
            ResponseParser.parseVerify(json, "p1", "r1");
            throw new AssertionError("verify should be rejected");
        } catch (ResponseParser.ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private void assertBadAudit(String json, String messagePart) {
        try {
            ResponseParser.parseAudit(json, "p1");
            throw new AssertionError("audit should be rejected");
        } catch (ResponseParser.ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private static String repeat(String value, int count) {
        return String.join("", Collections.nCopies(count, value));
    }
}
