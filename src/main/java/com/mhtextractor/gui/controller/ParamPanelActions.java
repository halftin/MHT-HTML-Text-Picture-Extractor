package com.mhtextractor.gui.controller;

import com.mhtextractor.app.NamedConfigService;
import com.mhtextractor.app.Texts;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.component.NotificationHost;
import javafx.scene.control.TextInputDialog;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

/**
 * 参数面板的「宿主侧动作」：命名配置 CRUD（保存 / 加载 / 删除）与「高速内存模式」确认卡。
 *
 * <p><b>为什么单独一个类</b>：Q6a 起同一个 {@link ParamFormPane} 有两个宿主
 * （模态窗口壳与非模态侧边栏），D 起只剩侧边栏 {@link ParamSidePanel}。
 * 这两段动作里带日志键（{@code log.paramdialogcontroller.01..06}）与提醒卡片文案，
 * <b>属产品行为</b>：复制一份会让日志调用点凭空翻倍（本项目对 {@code log.*} 逐键对账），
 * 所以抽成共用件，两个宿主各自 {@code new ParamPanelActions(pane).install()} 一次即可，
 * 文案 / 图标 / 日志键与拆分前逐字一致。</p>
 *
 * <p>它<b>不持窗口</b>：命名配置名仍走既有的模态 {@link TextInputDialog}（产品语义不变），
 * 提醒卡片的锚点就是面板里的控件（{@code configComboBox} / {@code highSpeedMemoryCheck}），
 * 由 {@code NotificationHost} 落到控件所在窗口的提醒层上 —— 侧边栏场景下自然落在主窗口，
 * 不再另开窗口。</p>
 *
 * <p>包级可见：它是 {@code ParamFormPane} 的宿主侧实现细节，需要访问面板的包级接缝
 * （{@code applyLoadedConfig} / {@code setConfigNames} / {@code highSpeedMemoryCheck}）。</p>
 */
@Slf4j
final class ParamPanelActions {

    private final ParamFormPane pane;
    private final NamedConfigService namedConfigService = new NamedConfigService();

    ParamPanelActions(ParamFormPane pane) {
        this.pane = pane;
    }

    /** 接线：命名配置三动作 + 高速内存模式监听 + 配置列表初值。调用时机与拆分前一致。 */
    void install() {
        pane.setNamedConfigActions(this::saveNamedConfig, this::loadNamedConfig, this::deleteNamedConfig);
        // 高速内存模式警告（ToggleSwitch 不支持 onAction，改用属性监听）
        pane.highSpeedMemoryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                onHighSpeedMemoryChanged();
            }
        });
        refreshConfigList();
    }

    // ==================== 配置管理（加载只填充当前模式参数组） ====================

    private void saveNamedConfig() {
        TextInputDialog dialog = new TextInputDialog("my_config");
        dialog.setTitle(Texts.get("dialog.saveConfig"));
        dialog.setHeaderText(Texts.get("dialog.inputConfigName"));
        dialog.setContentText(Texts.get("dialog.saveConfigNote"));
        dialog.showAndWait().ifPresent(name -> {
            try {
                namedConfigService.save(name, pane.collect());
                refreshConfigList();
                pane.configComboBox.getSelectionModel().select(name);
                log.info(Texts.msg("log.paramdialogcontroller.01", name));
            } catch (Exception e) {
                log.error(Texts.msg("log.paramdialogcontroller.02"), e);
                NotificationHost.error(pane.configComboBox, Texts.get("error.CONFIG_SAVE_FAILED"), FontAwesomeSolid.SAVE);
            }
        });
    }

    private void loadNamedConfig() {
        String selected = pane.configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            NotificationHost.toastWarning(pane.configComboBox, Texts.get("error.CONFIG_SELECT_TO_LOAD"), FontAwesomeSolid.HAND_POINTER);
            return;
        }
        try {
            AppConfig config = namedConfigService.load(selected);
            // 只填充当前模式参数组，另一组保持隔离（确定后才生效）
            pane.applyLoadedConfig(config);
            log.info(Texts.msg("log.paramdialogcontroller.03", selected));
        } catch (Exception e) {
            log.error(Texts.msg("log.paramdialogcontroller.04"), e);
            NotificationHost.error(pane.configComboBox, Texts.get("error.CONFIG_LOAD_FAILED"), FontAwesomeSolid.FILE_DOWNLOAD);
        }
    }

    private void deleteNamedConfig() {
        String selected = pane.configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            NotificationHost.toastWarning(pane.configComboBox, Texts.get("error.CONFIG_SELECT_TO_DELETE"), FontAwesomeSolid.HAND_POINTER);
            return;
        }
        NotificationHost.confirm(pane.configComboBox,
                Texts.get("error.CONFIG_DELETE_CONFIRM", "name", selected), FontAwesomeSolid.TRASH_ALT, ok -> {
                    if (!ok) {
                        return;
                    }
                    try {
                        namedConfigService.delete(selected);
                        refreshConfigList();
                        log.info(Texts.msg("log.paramdialogcontroller.05", selected));
                    } catch (Exception e) {
                        log.error(Texts.msg("log.paramdialogcontroller.06"), e);
                        NotificationHost.error(pane.configComboBox, Texts.get("error.CONFIG_DELETE_FAILED"), FontAwesomeSolid.TRASH_ALT);
                    }
                });
    }

    private void refreshConfigList() {
        pane.setConfigNames(namedConfigService.names());
    }

    /** 高速内存模式确认（会话 19：窗口内居中确认卡片，取代嵌套模态 Alert） */
    private void onHighSpeedMemoryChanged() {
        if (pane.highSpeedMemoryCheck.isSelected()) {
            String msg = Texts.get("dialog.highSpeedConfirmTitle") + "\n"
                    + Texts.get("dialog.highSpeedConfirmMsg");
            NotificationHost.confirm(pane.highSpeedMemoryCheck, msg, FontAwesomeSolid.MEMORY, ok -> {
                if (!ok) {
                    pane.highSpeedMemoryCheck.setSelected(false);
                }
            });
        }
    }
}
