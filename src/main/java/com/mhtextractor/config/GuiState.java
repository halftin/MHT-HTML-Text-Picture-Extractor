package com.mhtextractor.config;

import lombok.Data;

/**
 * 主界面 GUI 状态（提取模式、输入/输出目录、完成后打开输出文件夹）。
 * 持久化为 logs/gui-state.json，程序启动时恢复；永远只保留一份。
 */
@Data
public class GuiState {

    private boolean imageModeEnabled;
    private String inputFolder;
    private String outputFolder;
    private boolean openFolderAfterComplete;
}
