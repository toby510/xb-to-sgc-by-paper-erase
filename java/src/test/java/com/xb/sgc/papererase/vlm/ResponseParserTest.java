package com.xb.sgc.papererase.vlm;

import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.RelocateResponse;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResponseParserTest {

    @Test
    public void parsesLocateRelocateAndAuditWithStrictCoordinatesAndDecisions() {
        LocateResponse locate = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"}}],"
                + "\"evidence\":\"ok\"}", "p1");
        assertEquals("r1", locate.regions.get(0).region_id);
        assertEquals(0, locate.reading_rotation);
        assertEquals(0.99, locate.direction_confidence, 0.0);

        RelocateResponse relocate = ResponseParser.parseRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\","
                + "\"target_found\":true,\"evidence\":\"ok\","
                + "\"refined_region\":{\"x1\":0.2,\"y1\":0.7,\"x2\":0.8,\"y2\":0.9},"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.1,\"basis\":\"roi\"}}", "p1", "r1");
        assertTrue(relocate.target_found);

        AuditResponse audit = ResponseParser.parseAudit("{\"page_id\":\"p1\",\"decision\":\"pass\","
                + "\"original_target_is_non_body\":true,\"body_changed\":false,\"target_removed\":true,\"background_acceptable\":true,"
                + "\"evidence\":\"ok\"}", "p1");
        assertFalse(audit.body_changed);
    }

    @Test
    public void parsesRoiRelativeRefinedCoordinatesOnlyAsAnExplicitPair() {
        RelocateResponse relocate = ResponseParser.parseRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\","
                + "\"target_found\":true,\"evidence\":\"ok\","
                + "\"refined_region\":{\"x1\":0.20,\"y1\":0.30,\"x2\":0.40,\"y2\":0.50},"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.10,\"basis\":\"body\"}}", "p1", "r1");
        assertEquals(0.30, relocate.refined_region.y1, 0.0);
        assertEquals(0.10, relocate.nearest_body_boundary.y, 0.0);
    }

    @Test
    public void rejectsLocateWhenBoundaryOmitsAnExplicitNullAxis() {
        // Contract v1 固定 boundary JSON shape：省略 x:null 不能与“该轴无边界”混为一谈。
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"y\":0.88,\"basis\":\"body\"}}],\"evidence\":\"ok\"}", "missing field");

        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.02,\"y1\":0.45,\"x2\":0.04,\"y2\":0.55,"
                + "\"page_number_text\":\"2\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.96,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":0.06,\"basis\":\"page left edge\"}}],\"evidence\":\"ok\"}", "missing field");

        // 边界必须至少保留 basis：只有坐标、无 basis 仍拒绝，避免放空协议。
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"y\":0.88}}],\"evidence\":\"ok\"}", "missing field");
    }

    @Test
    public void parsesMandatoryPerRegionBodyBoundaryAndRejectsTheRemovedRootField() {
        LocateResponse locate = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"same footer lane\"}}],"
                + "\"evidence\":\"ok\"}", "p1");

        assertEquals(0.88, locate.regions.get(0).nearest_body_boundary.y, 0.0);
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"no_pagenum\","
                + "\"regions\":[],\"nearest_body_boundary\":{\"x\":null,\"y\":0.80,\"basis\":\"removed\"},\"evidence\":\"x\"}",
                "unknown");
    }

    @Test
    public void parsesLocateWhenArkWrapsOtherwiseValidJsonInOneCodeFence() {
        LocateResponse locate = ResponseParser.parseLocate("```json\n{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"}}],"
                + "\"evidence\":\"ok\"}\n```", "p1");

        assertEquals("r1", locate.regions.get(0).region_id);
    }

    @Test
    public void extractsOnlyOneSchemaValidJsonObjectFromModelExplanation() {
        String json = "{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"}}],"
                + "\"evidence\":\"ok\"}";

        LocateResponse locate = ResponseParser.parseLocate("分析完成：\n" + json + "\n以上为结果。", "p1");
        assertEquals("r1", locate.regions.get(0).region_id);
        assertBadLocate(json + "\n另一个对象：{}", "strict JSON");
    }

    @Test
    public void rejectsUnsafeLocateRelocateAuditAndStoresShortSafeRawSummary() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe\",\"regions\":[],"
                + "\"evidence\":\"x\"}", "status");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.1,\"y1\":0.9,\"x2\":0.2,\"y2\":NaN,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"java\"}}],\"evidence\":\"x\"}", "strict JSON");
        assertBadRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"evidence\":\"x\","
                + "\"refined_region\":null,\"nearest_body_boundary\":null}", "missing field");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"original_target_is_non_body\":true,\"body_changed\":false,"
                + "\"target_removed\":false,\"background_acceptable\":true,\"evidence\":\"x\"}", "decision must exactly match");

        ResponseParser.ParseException ex = ResponseParser.parseFailure("token=secret-1234567890 " + repeat("x", 500));
        assertTrue(ex.getRawSummary().length() <= 240);
        assertFalse(ex.getRawSummary().contains("secret-1234567890"));
    }

    @Test
    public void acceptsAuditPassWithOnlyBackgroundWarning() {
        AuditResponse audit = ResponseParser.parseAudit("{\"page_id\":\"p1\",\"decision\":\"pass\","
                + "\"original_target_is_non_body\":true,\"body_changed\":false,\"target_removed\":true,\"background_acceptable\":false,"
                + "\"evidence\":\"only background tone differs\"}", "p1");
        assertFalse(audit.background_acceptable);
    }

    @Test
    public void requiresAValidDirectionOnEveryLocateResponse() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":45,\"direction_confidence\":0.99,"
                + "\"status\":\"manual_review\",\"regions\":[],\"evidence\":\"direction only\"}",
                "rotation");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":90,\"status\":\"manual_review\","
                + "\"regions\":[],\"evidence\":\"direction only\"}", "missing field");
    }

    @Test
    public void rejectsLocateStateAndRegionCombinationsThatCannotBeSafelyExecuted() {
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":0,\"direction_confidence\":0.99,"
                + "\"status\":\"no_pagenum\",\"regions\":[{\"region_id\":\"r1\",\"x1\":0.4,\"y1\":0.9,\"x2\":0.6,\"y2\":0.95,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,\"confidence\":0.99,\"safety_margin\":\"blank\","
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"body\"}}],\"evidence\":\"x\"}", "empty regions");
        assertBadLocate("{\"page_id\":\"p1\",\"reading_rotation\":90,\"direction_confidence\":0.99,"
                + "\"status\":\"safe_to_erase\",\"regions\":[],\"evidence\":\"x\"}", "rotated locate");
    }

    @Test
    public void rejectsRelocateAndAuditPayloadsThatContradictTheirDecision() {
        // 精修框与局部正文边界必须成对出现：只有精修框而漏掉边界，等于隐式丢弃正文证据。
        assertBadRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"target_found\":false,\"evidence\":\"x\","
                + "\"refined_region\":null,\"nearest_body_boundary\":{\"x\":null,\"y\":0.1,\"basis\":\"roi\"}}",
                "nearest_body_boundary requires refined_region");
        assertBadRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"target_found\":true,\"evidence\":\"x\","
                + "\"refined_region\":null,\"nearest_body_boundary\":null}", "requires refined_region");
        assertBadRelocate("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"target_found\":false,\"evidence\":\"x\","
                + "\"refined_region\":{\"x1\":0.1,\"y1\":0.1,\"x2\":0.2,\"y2\":0.2},"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.1,\"basis\":\"roi\"}}", "null refined_region");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"manual_review\",\"original_target_is_non_body\":true,"
                + "\"body_changed\":false,\"target_removed\":true,\"background_acceptable\":false,\"evidence\":\"x\"}", "decision must exactly match");
    }

    @Test
    public void rejectsAuditPassWhenOriginalTargetIsBodyOrFieldIsMissing() {
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"body_changed\":false,"
                + "\"target_removed\":true,\"background_acceptable\":true,\"evidence\":\"x\"}",
                "original_target_is_non_body");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"original_target_is_non_body\":false,"
                + "\"body_changed\":false,\"target_removed\":true,\"background_acceptable\":true,\"evidence\":\"x\"}",
                "decision must exactly match");
    }

    private void assertBadLocate(String json, String messagePart) {
        try {
            ResponseParser.parseLocate(json, "p1");
            throw new AssertionError("locate should be rejected");
        } catch (ResponseParser.ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private void assertBadRelocate(String json, String messagePart) {
        try {
            ResponseParser.parseRelocate(json, "p1", "r1");
            throw new AssertionError("relocate should be rejected");
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
