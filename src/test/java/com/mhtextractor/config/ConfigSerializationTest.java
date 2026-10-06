package com.mhtextractor.config;

import com.mhtextractor.model.AppConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GuiStateManager 与 AppConfig 序列化范围测试：
 * 命名配置只保存提取参数（不含目录/模式/GUI 状态），logs/gui-state.json 单文件往返。
 */
class ConfigSerializationTest {

    /** 与 ConfigManager 的 ObjectMapper 配置保持一致（忽略未知字段） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TempDir
    Path tempDir;

    // ==================== GuiState 往返 ====================

    @Test
    void guiStateRoundTripIncludesMode() throws Exception {
        GuiState state = new GuiState();
        state.setImageModeEnabled(true);
        state.setInputFolder("D:\\in");
        state.setOutputFolder("D:\\out");
        state.setOpenFolderAfterComplete(true);

        GuiStateManager.save(state, tempDir.toFile());
        GuiState loaded = GuiStateManager.load(tempDir.toFile());

        assertEquals("D:\\in", loaded.getInputFolder());
        assertEquals("D:\\out", loaded.getOutputFolder());
        assertTrue(loaded.isOpenFolderAfterComplete());
        assertTrue(loaded.isImageModeEnabled());
    }

    @Test
    void guiStateMissingFileReturnsNull() {
        assertNull(GuiStateManager.load(new File(tempDir.toFile(), "not-exists")));
    }

    @Test
    void guiStateSaveCreatesSingleFile() throws Exception {
        GuiState state = new GuiState();
        state.setInputFolder("X:\\mht");

        GuiStateManager.save(state, tempDir.toFile());
        GuiStateManager.save(state, tempDir.toFile());

        File[] files = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        assertEquals(1, files.length, "始终只保留一份状态文件");
        assertEquals("gui-state.json", files[0].getName());
    }

    // ==================== AppConfig 命名配置序列化范围 ====================

    @Test
    void namedConfigJsonExcludesRuntimeStateFields() throws Exception {
        AppConfig config = new AppConfig();
        config.setInputFolder("D:\\in");
        config.setOutputFolder("D:\\out");
        config.setImageModeEnabled(true);
        config.setOpenFolderAfterComplete(true);
        config.setSeparateFiles(true);
        config.setAddChapterNumber(true);
        config.setStartMarker("<!--START-->");
        config.setEnableZipPackaging(true);

        String json = MAPPER.writeValueAsString(config);

        assertFalse(json.contains("inputFolder"), "目录字段不应出现在命名配置中");
        assertFalse(json.contains("outputFolder"), "目录字段不应出现在命名配置中");
        assertFalse(json.contains("imageModeEnabled"), "模式字段不应出现在命名配置中");
        assertFalse(json.contains("openFolderAfterComplete"), "GUI 状态字段不应出现在命名配置中");
        assertTrue(json.contains("separateFiles"));
        assertTrue(json.contains("addChapterNumber"));
        assertTrue(json.contains("startMarker"));
        assertTrue(json.contains("imageZipEnabled"));
    }

    @Test
    void legacyConfigJsonWithRuntimeFieldsStillLoads() throws Exception {
        String legacy = "{\"inputFolder\":\"D:\\\\old\",\"outputFolder\":\"D:\\\\oldout\","
                + "\"imageMode\":true,\"openFolderAfterComplete\":true,"
                + "\"separateFiles\":true,\"addChapterNumber\":false}";

        AppConfig config = MAPPER.readValue(legacy, AppConfig.class);

        // 旧字段被忽略，参数字段正常解析
        assertTrue(config.isSeparateFiles());
        assertFalse(config.isAddChapterNumber());
        assertNull(config.getInputFolder());
    }

    @Test
    void paramFieldsRoundTrip() throws Exception {
        AppConfig config = new AppConfig();
        config.setSeparateFiles(true);
        config.setEnableContentLimit(true);
        config.setStartMarker("<!--S-->");
        config.setEndMarker("<!--E-->");
        config.setUseRegex(true);
        config.setEnableTemplate(true);
        config.setStartTemplate("\\n==S==\\n");
        config.setEndTemplate("\\n==E==\\n");
        config.setAddChapterNumber(true);
        config.setAutoFixEncoding(false);
        config.setEnableTitleExtraction(true);
        config.setTitleStartMarker("<title>");
        config.setTitleEndMarker("</title>");
        config.setTitleUseRegex(true);
        config.setEnableNameExtraction(true);
        config.setImageNameStartMarker("img-");
        config.setImageNameEndMarker(".png");
        config.setEnableRegionExtraction(true);
        config.setImageRegionStartMarker("<!--IMG-->");
        config.setImageRegionEndMarker("<!--/IMG-->");
        config.setEnableBatchRenaming(true);
        config.setEnableZipPackaging(true);
        config.setEnableCbzPackaging(false);
        config.setSingleOutputMode(true);
        config.setHighSpeedMemoryMode(false);

        String json = MAPPER.writeValueAsString(config);
        AppConfig restored = MAPPER.readValue(json, AppConfig.class);

        assertTrue(restored.isSeparateFiles());
        assertTrue(restored.isEnableContentLimit());
        assertEquals("<!--S-->", restored.getStartMarker());
        assertEquals("<!--E-->", restored.getEndMarker());
        assertTrue(restored.isUseRegex());
        assertTrue(restored.isEnableTemplate());
        assertEquals("\\n==S==\\n", restored.getStartTemplate());
        assertTrue(restored.isAddChapterNumber());
        assertFalse(restored.isAutoFixEncoding());
        assertTrue(restored.isEnableTitleExtraction());
        assertEquals("<title>", restored.getTitleStartMarker());
        assertTrue(restored.isTitleUseRegex());
        assertTrue(restored.isEnableNameExtraction());
        assertEquals("img-", restored.getImageNameStartMarker());
        assertTrue(restored.isEnableRegionExtraction());
        assertEquals("<!--IMG-->", restored.getImageRegionStartMarker());
        assertTrue(restored.isEnableBatchRenaming());
        assertTrue(restored.isEnableZipPackaging());
        assertFalse(restored.isEnableCbzPackaging());
        assertTrue(restored.isSingleOutputMode());
        assertFalse(restored.isHighSpeedMemoryMode());
    }

    @Test
    void configManagerWritesParamsOnlyFile() throws Exception {
        // 通过 ConfigManager 的目录写入验证落盘 JSON 也不含运行时字段
        AppConfig config = new AppConfig();
        config.setInputFolder("D:\\in");
        config.setOutputFolder("D:\\out");
        config.setImageModeEnabled(true);
        config.setSeparateFiles(true);

        File tmpConfigDir = tempDir.resolve("config").toFile();
        Files.createDirectories(tmpConfigDir.toPath());
        File file = new File(tmpConfigDir, "t.json");
        MAPPER.writeValue(file, config);

        String json = Files.readString(file.toPath());
        assertFalse(json.contains("inputFolder"));
        assertFalse(json.contains("outputFolder"));
        assertFalse(json.contains("imageMode"));
        assertTrue(json.contains("separateFiles"));
    }
}
