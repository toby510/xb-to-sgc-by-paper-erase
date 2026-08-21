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
                + "\"layout_description\":\"footer\",\"page_ids\":[\"p1\",\"p2\"],\"confidence\":0.97}],"
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
        LocateResponse locate = ResponseParser.parseLocate("{\"page_id\":\"p1\",\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"},"
                + "\"evidence\":\"ok\"}", "p1");
        assertEquals("r1", locate.regions.get(0).region_id);

        VerifyResponse verify = ResponseParser.parseVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\","
                + "\"decision\":\"safe_to_erase\",\"allowed_scope\":\"page number only\","
                + "\"evidence\":\"ok\"}", "p1", "r1");
        assertEquals("safe_to_erase", verify.decision);

        AuditResponse audit = ResponseParser.parseAudit("{\"page_id\":\"p1\",\"decision\":\"pass\","
                + "\"body_unchanged\":true,\"target_removed\":true,\"background_acceptable\":true,"
                + "\"evidence\":\"ok\"}", "p1");
        assertTrue(audit.body_unchanged);
    }

    @Test
    public void parsesLocateWhenArkWrapsOtherwiseValidJsonInOneCodeFence() {
        LocateResponse locate = ResponseParser.parseLocate("```json\n{\"page_id\":\"p1\",\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.45,\"y1\":0.94,\"x2\":0.55,\"y2\":0.98,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"page only\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.88,\"basis\":\"java\"},"
                + "\"evidence\":\"ok\"}\n```", "p1");

        assertEquals("r1", locate.regions.get(0).region_id);
    }

    @Test
    public void rejectsUnsafeLocateVerifyAuditAndStoresShortSafeRawSummary() {
        assertBadLocate("{\"page_id\":\"p1\",\"status\":\"safe\",\"regions\":[],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"java\"},\"evidence\":\"x\"}", "status");
        assertBadLocate("{\"page_id\":\"p1\",\"status\":\"safe_to_erase\","
                + "\"regions\":[{\"region_id\":\"r1\",\"x1\":0.1,\"y1\":0.9,\"x2\":0.2,\"y2\":NaN,"
                + "\"page_number_text\":\"1\",\"same_line_metadata\":\"\",\"on_line\":false,"
                + "\"confidence\":0.99,\"safety_margin\":\"blank\"}],"
                + "\"nearest_body_boundary\":{\"x\":null,\"y\":0.8,\"basis\":\"java\"},\"evidence\":\"x\"}", "strict JSON");
        assertBadVerify("{\"page_id\":\"p1\",\"region_id\":\"r1\",\"decision\":\"erase\","
                + "\"allowed_scope\":\"x\",\"evidence\":\"x\"}", "decision");
        assertBadAudit("{\"page_id\":\"p1\",\"decision\":\"pass\",\"body_unchanged\":true,"
                + "\"target_removed\":false,\"background_acceptable\":true,\"evidence\":\"x\"}", "audit pass");

        ResponseParser.ParseException ex = ResponseParser.parseFailure("token=secret-1234567890 " + repeat("x", 500));
        assertTrue(ex.getRawSummary().length() <= 240);
        assertFalse(ex.getRawSummary().contains("secret-1234567890"));
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
                + "\"layout_description\":\"footer\",\"page_ids\":" + groupPageIds + ",\"confidence\":0.9}],"
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
                + "\"layout_description\":\"footer\",\"page_ids\":" + firstGroupPageIds + ",\"confidence\":0.9},"
                + "{\"group_id\":\"g2\",\"edge\":\"top\",\"alignment\":\"center\","
                + "\"layout_description\":\"header\",\"page_ids\":" + secondGroupPageIds + ",\"confidence\":0.9}],"
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
