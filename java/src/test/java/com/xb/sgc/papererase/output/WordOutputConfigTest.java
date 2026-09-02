package com.xb.sgc.papererase.output;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WordOutputConfigTest {
    @Test
    public void readsHumanReadableQrcodeSettingsFromTemplateConfig() throws Exception {
        WordOutputConfig config = WordOutputConfig.load(Paths.get("../config/word-template.json"));

        assertTrue(config.isQrcodeEnabled());
        assertEquals(1_728_000L, config.getQrcodeWidthEmu());
        assertEquals(540_000L, config.getQrcodeRightInsetEmu());
        assertEquals(108_000L, config.getQrcodeTopInsetEmu());
        assertEquals("https://t.ewt360.com/bmIbma", config.getQrcodeShortLink());
        assertEquals("打开升学e网通app", config.getQrcodeTextLine1());
        assertEquals("扫码查看试题和解析", config.getQrcodeTextLine2());
    }
}
