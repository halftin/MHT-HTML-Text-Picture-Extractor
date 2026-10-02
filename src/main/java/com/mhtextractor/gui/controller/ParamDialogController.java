package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.ConfigManager;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.ParamScope;
import javafx.scene.Scene;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.IOException;

/**
 * 提取参数设置弹窗控制器（模态）。
 *
 * <p>按当前提取模式显示文本参数组或图片参数组；当前模式控件由参数目标（全局或某组，见
 * {@link ParamScope.Target}）的已确认参数初始化（组未设置时回退全局参数），另一模式控件由全局
 * 缓存初始化，切模式不丢失。仅"确定"提交生效：把两组控件值构建为参数快照返回，
 * 由主窗口写入对应目标（组目标写入 GroupItem 的参数缓存）；"取消"/右上角 X 丢弃本次编辑。
 * 组参数弹窗不持久化"编辑中"状态。配置加载只填充当前模式参数组（不点确定不生效）；
 * 配置保存为纯参数（不含目录/模式）。</p>
 *
 * <p><b>本类只是"模态窗口壳 + 命名配置存取"</b>：参数表单本体（控件、联动、载入/收集、切模式）在
 * 父类 {@link ParamFormPane}（纯 {@code Pane}，可同样嵌入 Q6a 的非模态侧边栏）。本类继承它，因此
 * 弹窗本身就是那个面板节点；{@code @FXML} 字段声明在父类，{@code ParamDialog.fxml} 用
 * {@code fx:root} 把本实例同时作为根与控制器（见 {@link ParamFormPane} 的类注释）。</p>
 *
 * <p>入口：真实用户流程 {@link #openAndWait(Window, boolean, AppConfig, AppConfig, ParamScope.Target)}（模态）；
 * agent 演练/自动化测试 {@link #showDirect(Window, boolean, AppConfig, AppConfig, ParamScope.Target)}（非模态）。</p>
 */
@Slf4j
public class ParamDialogController extends ParamFormPane {

    private final ConfigManager configManager = new ConfigManager();
    private Stage stage;
    private boolean confirmed = false;

    // ==================== 窗口入口 ====================

    /**
     * 模态打开参数设置窗口（真实用户流程），窗口关闭后返回控制器；
     * 通过 {@link #isConfirmed()} 判定是否点过"确定"。target 指定参数目标：
     * 全局或某组（当前模式控件从该目标的已确认参数取初值）。
     */
    public static ParamDialogController openAndWait(Window owner, boolean imageMode,
                                                   AppConfig textCache, AppConfig imageCache,
                                                   ParamScope.Target target)
            throws IOException {
        ParamDialogController ctrl = create(imageMode, textCache, imageCache, target);
        ctrl.stage.initOwner(owner);
        ctrl.stage.initModality(Modality.APPLICATION_MODAL);
        ctrl.stage.showAndWait();
        return ctrl;
    }

    /**
     * 非模态直驱入口（agent 演练 / 自动化测试用，避免阻塞调用线程）
     */
    public static ParamDialogController showDirect(Window owner, boolean imageMode,
                                                   AppConfig textCache, AppConfig imageCache,
                                                   ParamScope.Target target)
            throws IOException {
        ParamDialogController ctrl = create(imageMode, textCache, imageCache, target);
        if (owner != null) {
            ctrl.stage.initOwner(owner);
        }
        ctrl.stage.show();
        return ctrl;
    }

    private static ParamDialogController create(boolean imageMode,
                                                AppConfig textCache, AppConfig imageCache,
                                                ParamScope.Target target)
            throws IOException {
        // 构造即载入 ParamDialog.fxml（fx:root：本实例同时是根节点与控制器，@FXML 字段注入到本实例）
        ParamDialogController ctrl = new ParamDialogController();

        Stage stage = new Stage();
        stage.setTitle(I18n.get("dialog.title"));
        // 会话 19：本窗口也要装卸载点 —— 提醒必须落在自己窗口里，不能跑到主窗口后面
        StackPane shell = new StackPane(ctrl);
        Scene scene = new Scene(shell);
        NotificationHost.install(scene, shell);
        stage.setScene(scene);
        stage.setWidth(640);
        stage.setHeight(620);
        stage.setMinWidth(520);
        stage.setMinHeight(480);
        stage.setOnCloseRequest(e -> ctrl.confirmed = false);

        ctrl.stage = stage;
        ctrl.installHostActions();
        ctrl.configure(imageMode, textCache, imageCache, target);
        return ctrl;
    }

    // ==================== 宿主接线（窗口关闭 / 命名配置 / 提醒） ====================

    /**
     * 把"宿主职责"接到表单面板上：确定/取消出口、命名配置三动作、高速内存模式提醒、配置列表刷新。
     * <b>调用时机与拆分前一致</b>：在窗体建好之后、{@link #configure} 载入初值之前
     * （拆分前这些监听与刷新都在 {@code initialize()} 里，同样早于初值载入）。
     */
    private void installHostActions() {
        setCloseHandler(ok -> {
            confirmed = ok;
            stage.close();
        });
        setNamedConfigActions(this::saveNamedConfig, this::loadNamedConfig, this::deleteNamedConfig);
        // 高速内存模式警告（ToggleSwitch 不支持 onAction，改用属性监听）
        highSpeedMemoryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                onHighSpeedMemoryChanged();
            }
        });
        refreshConfigList();
    }

    private void configure(boolean imageMode, AppConfig textCache, AppConfig imageCache,
                           ParamScope.Target target) {
        String scope = target instanceof ParamScope.GroupTarget gt
                ? I18n.get("dialog.parenOpen") + gt.group().getName() + I18n.get("dialog.parenClose") : "";
        stage.setTitle(I18n.get("dialog.titleWithMode",
                "mode", imageMode ? I18n.get("dialog.modeImage") : I18n.get("dialog.modeText")) + scope);
        // 当前模式控件从目标初值初始化（组未设置时回退全局已确认参数，均无则用默认参数）；
        // 另一模式控件仍从全局已确认缓存初始化（隐藏不可见，两组隔离互不影响）
        load(textCache, imageCache, imageMode, target);
    }

    // ==================== 确定 / 取消 ====================

    public boolean isConfirmed() {
        return confirmed;
    }

    /** 以两组控件当前值构建参数快照（仅参数，不含目录/模式/GUI 状态）；= 表单面板的 {@link #collect()} */
    public AppConfig getResult() {
        return collect();
    }

    // ==================== 配置管理（加载只填充当前模式参数组） ====================

    private void saveNamedConfig() {
        TextInputDialog dialog = new TextInputDialog("my_config");
        dialog.setTitle(I18n.get("dialog.saveConfig"));
        dialog.setHeaderText(I18n.get("dialog.inputConfigName"));
        dialog.setContentText(I18n.get("dialog.saveConfigNote"));
        dialog.showAndWait().ifPresent(name -> {
            try {
                configManager.saveConfig(name, getResult());
                refreshConfigList();
                configComboBox.getSelectionModel().select(name);
                log.info(I18n.msg("log.paramdialogcontroller.01", name));
            } catch (Exception e) {
                log.error(I18n.msg("log.paramdialogcontroller.02"), e);
                NotificationHost.error(configComboBox, ErrorCode.CONFIG_SAVE_FAILED.message(), FontAwesomeSolid.SAVE);
            }
        });
    }

    private void loadNamedConfig() {
        String selected = configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            NotificationHost.toastWarning(configComboBox, ErrorCode.CONFIG_SELECT_TO_LOAD.message(), FontAwesomeSolid.HAND_POINTER);
            return;
        }
        try {
            AppConfig config = configManager.loadConfig(selected);
            // 只填充当前模式参数组，另一组保持隔离（确定后才生效）
            applyLoadedConfig(config);
            log.info(I18n.msg("log.paramdialogcontroller.03", selected));
        } catch (Exception e) {
            log.error(I18n.msg("log.paramdialogcontroller.04"), e);
            NotificationHost.error(configComboBox, ErrorCode.CONFIG_LOAD_FAILED.message(), FontAwesomeSolid.FILE_DOWNLOAD);
        }
    }

    private void deleteNamedConfig() {
        String selected = configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            NotificationHost.toastWarning(configComboBox, ErrorCode.CONFIG_SELECT_TO_DELETE.message(), FontAwesomeSolid.HAND_POINTER);
            return;
        }
        NotificationHost.confirm(configComboBox,
                ErrorCode.CONFIG_DELETE_CONFIRM.message("name", selected), FontAwesomeSolid.TRASH_ALT, ok -> {
                    if (!ok) {
                        return;
                    }
                    try {
                        configManager.deleteConfig(selected);
                        refreshConfigList();
                        log.info(I18n.msg("log.paramdialogcontroller.05", selected));
                    } catch (Exception e) {
                        log.error(I18n.msg("log.paramdialogcontroller.06"), e);
                        NotificationHost.error(configComboBox, ErrorCode.CONFIG_DELETE_FAILED.message(), FontAwesomeSolid.TRASH_ALT);
                    }
                });
    }

    private void refreshConfigList() {
        setConfigNames(configManager.getConfigNames());
    }

    /** 高速内存模式确认（会话 19：本窗口内居中确认卡片，取代嵌套模态 Alert） */
    private void onHighSpeedMemoryChanged() {
        if (highSpeedMemoryCheck.isSelected()) {
            String msg = I18n.get("dialog.highSpeedConfirmTitle") + "\n"
                    + I18n.get("dialog.highSpeedConfirmMsg");
            NotificationHost.confirm(highSpeedMemoryCheck, msg, FontAwesomeSolid.MEMORY, ok -> {
                if (!ok) {
                    highSpeedMemoryCheck.setSelected(false);
                }
            });
        }
    }
}
