package com.mhtextractor.uicore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 主界面 GUI 状态的单一 JSON 持久化（logs/gui-state.json）。
 * 保存时覆盖写入，始终只有一份文件；加载失败/文件缺失时返回 null，走默认启动逻辑。
 */
@Slf4j
public class GuiStateManager {

    private static final String STATE_DIR = "logs";
    private static final String STATE_FILE = "gui-state.json";

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private GuiStateManager() {
    }

    public static GuiState load() {
        return load(new File(STATE_DIR));
    }

    /** 从指定目录加载（单元测试用） */
    static GuiState load(File dir) {
        File file = new File(dir, STATE_FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, GuiState.class);
        } catch (IOException e) {
            log.warn(I18n.msg("log.guistatemanager.01", e.getMessage()));
            return null;
        }
    }

    public static void save(GuiState state) {
        save(state, new File(STATE_DIR));
    }

    /** 保存到指定目录（单元测试用） */
    static void save(GuiState state, File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn(I18n.msg("log.guistatemanager.02", dir.getAbsolutePath()));
            return;
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(dir, STATE_FILE), state);
        } catch (IOException e) {
            log.warn(I18n.msg("log.guistatemanager.03", e.getMessage()));
        }
    }
}
