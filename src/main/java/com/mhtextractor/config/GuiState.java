package com.mhtextractor.config;

import lombok.Data;

/**
 * 主界面 GUI 状态（提取模式、输入/输出目录、完成后打开输出文件夹、界面语言）。
 * 持久化为 logs/gui-state.json，程序启动时恢复；永远只保留一份。
 */
@Data
public class GuiState {

    private boolean imageModeEnabled;
    private String inputFolder;
    private String outputFolder;
    private boolean openFolderAfterComplete;

    /**
     * 界面语言，形如 "zh_CN" / "en_US"；null 或空白 = 跟随系统默认（见 I18n.parseLocale）。
     * 持久化契约与语言无关：该字段只是"选择哪种语言"的偏好值，切换语言不改变 JSON 结构。
     */
    private String locale;
}
