package com.mhtextractor.app;

import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;

/**
 * GUI 会话状态持久化（logs/gui-state.json）的无头服务（P3-3b-5）：把
 * {@code gui.controller.MainController} 的 {@code restoreGuiState}/{@code saveGuiState}
 * 对 {@link GuiState}/{@link GuiStateManager} 的直接使用原样下沉到 app 层 ——
 * 持久化文件路径/字段名/读写语义/时序一律不变（只搬不改）。
 *
 * <p>locale 拼接规则（{@code language + "_" + country}，country 为空则不加后缀）仍留在 gui 层
 * 由 {@code I18n.currentLocale()} 算好后传入，本类只透传已拼好的字符串。</p>
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层。</p>
 */
public final class SessionStateService {

    private SessionStateService() {
    }

    /**
     * 会话状态快照：与 {@link GuiState} 字段一一对应
     * （inputFolder / outputFolder / openFolderAfterComplete / extractCheckedOnly /
     * imageModeEnabled / locale）。
     */
    public record Session(
            String inputFolder,
            String outputFolder,
            boolean openFolderAfterComplete,
            boolean extractCheckedOnly,
            boolean imageModeEnabled,
            String locale) {
    }

    /**
     * 加载会话：转调 {@link GuiStateManager#load()}，null 原样返回 null（文件缺失/加载失败
     * 与搬迁前 {@code restoreGuiState} 的短路语义一致），否则逐字段映射。
     */
    public static Session load() {
        GuiState state = GuiStateManager.load();
        if (state == null) {
            return null;
        }
        return new Session(
                state.getInputFolder(),
                state.getOutputFolder(),
                state.isOpenFolderAfterComplete(),
                state.isExtractCheckedOnly(),
                state.isImageModeEnabled(),
                state.getLocale());
    }

    /**
     * 保存会话：内部 {@code new GuiState()}，按搬迁前 {@code MainController.saveGuiState} 的
     * 同序 set（imageModeEnabled → inputFolder → outputFolder → openFolderAfterComplete →
     * extractCheckedOnly → locale），最后 {@link GuiStateManager#save(GuiState)}。
     */
    public static void save(Session session) {
        GuiState state = new GuiState();
        state.setImageModeEnabled(session.imageModeEnabled());
        state.setInputFolder(session.inputFolder());
        state.setOutputFolder(session.outputFolder());
        state.setOpenFolderAfterComplete(session.openFolderAfterComplete());
        state.setExtractCheckedOnly(session.extractCheckedOnly());
        state.setLocale(session.locale());
        GuiStateManager.save(state);
    }
}
