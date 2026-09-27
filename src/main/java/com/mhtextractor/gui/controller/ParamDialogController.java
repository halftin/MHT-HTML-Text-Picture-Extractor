package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.ConfigManager;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.ParamScope;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.HorizontalDirection;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.io.IOException;
import java.util.List;

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
 * <p>入口：真实用户流程 {@link #openAndWait(Window, boolean, AppConfig, AppConfig, ParamScope.Target)}（模态）；
 * agent 演练/自动化测试 {@link #showDirect(Window, boolean, AppConfig, AppConfig, ParamScope.Target)}（非模态）。</p>
 */
@Slf4j
public class ParamDialogController {

    // ===== 文本参数组 =====
    @FXML TitledPane textTitlePane;
    @FXML TitledPane textContentLimitPane;
    @FXML TitledPane textTemplatePane;
    @FXML TitledPane textOutputModePane;
    @FXML ToggleSwitch addChapterNumberCheck;
    @FXML ToggleSwitch enableTitleExtractCheck;
    @FXML TextField titleStartMarkerField;
    @FXML TextField titleEndMarkerField;
    @FXML ToggleSwitch titleUseRegexCheck;
    @FXML ToggleSwitch enableLimitCheck;
    @FXML TextField startMarkerField;
    @FXML TextField endMarkerField;
    @FXML ToggleSwitch useRegexCheck;
    @FXML ToggleSwitch enableTemplateCheck;
    @FXML TextField startTemplateField;
    @FXML TextField endTemplateField;
    @FXML ToggleSwitch separateFilesCheck;
    @FXML ToggleSwitch autoFixEncodingCheck;

    // ===== 图片参数组 =====
    @FXML TitledPane imageNamePane;
    @FXML TitledPane imageRegionPane;
    @FXML TitledPane imageProcessPane;
    @FXML TitledPane imageOutputModePane;
    @FXML TitledPane imagePerformancePane;
    @FXML ToggleSwitch imageNameExtractCheck;
    @FXML TextField imageNameStartField;
    @FXML TextField imageNameEndField;
    @FXML ToggleSwitch imageRegionExtractCheck;
    @FXML TextField imageRegionStartField;
    @FXML TextField imageRegionEndField;
    @FXML ToggleSwitch imageRenameCheck;
    @FXML ToggleSwitch imageZipCheck;
    @FXML ToggleSwitch imageCbzCheck;
    @FXML ToggleSwitch singleOutputCheck;
    @FXML ToggleSwitch highSpeedMemoryCheck;

    // ===== 分组与底部操作 =====
    @FXML VBox textParamsBox;
    @FXML VBox imageParamsBox;
    @FXML ComboBox<String> configComboBox;
    @FXML Button cancelButton;
    @FXML Button confirmButton;

    private final ConfigManager configManager = new ConfigManager();
    private Stage stage;
    private boolean imageMode;
    private ParamScope.Target target = ParamScope.GLOBAL;
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
        FXMLLoader loader = new FXMLLoader(
                ParamDialogController.class.getResource("/com/mhtextractor/gui/view/ParamDialog.fxml"));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();
        ParamDialogController ctrl = loader.getController();

        Stage stage = new Stage();
        stage.setTitle(I18n.get("dialog.title"));
        // 会话 19：本窗口也要装卸载点 —— 提醒必须落在自己窗口里，不能跑到主窗口后面
        StackPane shell = new StackPane(root);
        Scene scene = new Scene(shell);
        NotificationHost.install(scene, shell);
        stage.setScene(scene);
        stage.setWidth(640);
        stage.setHeight(620);
        stage.setMinWidth(520);
        stage.setMinHeight(480);
        stage.setOnCloseRequest(e -> ctrl.confirmed = false);

        ctrl.stage = stage;
        ctrl.configure(imageMode, textCache, imageCache, target);
        return ctrl;
    }

    // ==================== 初始化 ====================

    @FXML
    private void initialize() {
        ToggleSwitch[] toggles = {enableLimitCheck, useRegexCheck, enableTemplateCheck,
                addChapterNumberCheck, enableTitleExtractCheck, titleUseRegexCheck,
                separateFilesCheck, autoFixEncodingCheck, imageNameExtractCheck,
                imageRegionExtractCheck, imageRenameCheck, imageZipCheck,
                imageCbzCheck, singleOutputCheck, highSpeedMemoryCheck};
        for (ToggleSwitch t : toggles) {
            t.setLabelPosition(HorizontalDirection.RIGHT);
        }

        // 文本参数组联动
        enableLimitCheck.selectedProperty().addListener((obs, old, newVal) -> toggleLimitSettings());
        enableTemplateCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTemplateSettings());
        enableTitleExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTitleSettings());
        addChapterNumberCheck.selectedProperty().addListener((obs, old, newVal) -> updateTitleExtractTooltip());

        // 图片参数组联动
        imageNameExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageNameSettings());
        imageRegionExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageRegionSettings());

        // 高速内存模式警告（ToggleSwitch 不支持 onAction，改用属性监听）
        highSpeedMemoryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                onHighSpeedMemoryChanged();
            }
        });

        setupTemplateFieldEnterKeyHandler();
        refreshConfigList();
    }

    private void configure(boolean imageMode, AppConfig textCache, AppConfig imageCache,
                           ParamScope.Target target) {
        this.imageMode = imageMode;
        this.target = target;
        String scope = target instanceof ParamScope.GroupTarget gt
                ? I18n.get("dialog.parenOpen") + gt.group().getName() + I18n.get("dialog.parenClose") : "";
        stage.setTitle(I18n.get("dialog.titleWithMode",
                "mode", imageMode ? I18n.get("dialog.modeImage") : I18n.get("dialog.modeText")) + scope);
        // 当前模式控件从目标初值初始化（组未设置时回退全局已确认参数，均无则用默认参数）；
        // 另一模式控件仍从全局已确认缓存初始化（隐藏不可见，两组隔离互不影响）
        AppConfig currentInitial = ParamScope.initialParams(target, imageMode, textCache, imageCache);
        if (currentInitial == null) {
            currentInitial = new AppConfig();
        }
        if (imageMode) {
            applyTextParamsToGui(textCache != null ? textCache : new AppConfig());
            applyImageParamsToGui(currentInitial);
        } else {
            applyTextParamsToGui(currentInitial);
            applyImageParamsToGui(imageCache != null ? imageCache : new AppConfig());
        }
        // 触发联动，保证子控件启用/禁用与初始值一致（含模板默认值回填）
        toggleLimitSettings();
        toggleTemplateSettings();
        toggleTitleSettings();
        toggleImageNameSettings();
        toggleImageRegionSettings();
        updateTitleExtractTooltip();
        showCurrentModeGroup();
    }

    private void showCurrentModeGroup() {
        textParamsBox.setVisible(!imageMode);
        textParamsBox.setManaged(!imageMode);
        imageParamsBox.setVisible(imageMode);
        imageParamsBox.setManaged(imageMode);
    }

    // ==================== 确定 / 取消 ====================

    @FXML
    private void onConfirm() {
        confirmed = true;
        stage.close();
    }

    @FXML
    private void onCancel() {
        confirmed = false;
        stage.close();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /** 以两组控件当前值构建参数快照（仅参数，不含目录/模式/GUI 状态） */
    public AppConfig getResult() {
        AppConfig config = new AppConfig();

        // 文本参数
        config.setSeparateFiles(separateFilesCheck.isSelected());
        config.setEnableContentLimit(enableLimitCheck.isSelected());
        config.setStartMarker(startMarkerField.getText());
        config.setEndMarker(endMarkerField.getText());
        config.setUseRegex(useRegexCheck.isSelected());
        config.setEnableTemplate(enableTemplateCheck.isSelected());
        config.setStartTemplate(startTemplateField.getText());
        config.setEndTemplate(endTemplateField.getText());
        config.setAddChapterNumber(addChapterNumberCheck.isSelected());
        config.setEnableTitleExtraction(enableTitleExtractCheck.isSelected());
        config.setTitleStartMarker(titleStartMarkerField.getText());
        config.setTitleEndMarker(titleEndMarkerField.getText());
        config.setTitleUseRegex(titleUseRegexCheck.isSelected());
        config.setAutoFixEncoding(autoFixEncodingCheck.isSelected());

        // 图片参数
        config.setEnableNameExtraction(imageNameExtractCheck.isSelected());
        config.setImageNameStartMarker(imageNameStartField.getText());
        config.setImageNameEndMarker(imageNameEndField.getText());
        config.setEnableRegionExtraction(imageRegionExtractCheck.isSelected());
        config.setImageRegionStartMarker(
                config.isEnableRegionExtraction() ? imageRegionStartField.getText() : null);
        config.setImageRegionEndMarker(
                config.isEnableRegionExtraction() ? imageRegionEndField.getText() : null);
        config.setEnableBatchRenaming(imageRenameCheck.isSelected());
        config.setEnableZipPackaging(imageZipCheck.isSelected());
        config.setEnableCbzPackaging(imageCbzCheck.isSelected());
        config.setSingleOutputMode(singleOutputCheck.isSelected());
        config.setHighSpeedMemoryMode(highSpeedMemoryCheck.isSelected());

        return config;
    }

    // ==================== 从 AppConfig 填充控件 ====================

    private void applyTextParamsToGui(AppConfig config) {
        separateFilesCheck.setSelected(config.isSeparateFiles());
        enableLimitCheck.setSelected(config.isEnableContentLimit());
        startMarkerField.setText(orEmpty(config.getStartMarker()));
        endMarkerField.setText(orEmpty(config.getEndMarker()));
        useRegexCheck.setSelected(config.isUseRegex());
        enableTemplateCheck.setSelected(config.isEnableTemplate());
        startTemplateField.setText(orEmpty(config.getStartTemplate()));
        endTemplateField.setText(orEmpty(config.getEndTemplate()));
        addChapterNumberCheck.setSelected(config.isAddChapterNumber());
        enableTitleExtractCheck.setSelected(config.isEnableTitleExtraction());
        titleStartMarkerField.setText(orEmpty(config.getTitleStartMarker()));
        titleEndMarkerField.setText(orEmpty(config.getTitleEndMarker()));
        titleUseRegexCheck.setSelected(config.isTitleUseRegex());
        autoFixEncodingCheck.setSelected(config.isAutoFixEncoding());
    }

    private void applyImageParamsToGui(AppConfig config) {
        imageNameExtractCheck.setSelected(config.isEnableNameExtraction());
        imageNameStartField.setText(orEmpty(config.getImageNameStartMarker()));
        imageNameEndField.setText(orEmpty(config.getImageNameEndMarker()));
        imageRegionExtractCheck.setSelected(config.isEnableRegionExtraction());
        imageRegionStartField.setText(orEmpty(config.getImageRegionStartMarker()));
        imageRegionEndField.setText(orEmpty(config.getImageRegionEndMarker()));
        imageRenameCheck.setSelected(config.isEnableBatchRenaming());
        imageZipCheck.setSelected(config.isEnableZipPackaging());
        imageCbzCheck.setSelected(config.isEnableCbzPackaging());
        singleOutputCheck.setSelected(config.isSingleOutputMode());
        highSpeedMemoryCheck.setSelected(config.isHighSpeedMemoryMode());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ==================== 配置管理（加载只填充当前模式参数组） ====================

    @FXML
    private void onSaveConfig() {
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

    @FXML
    private void onLoadConfig() {
        String selected = configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            NotificationHost.toastWarning(configComboBox, ErrorCode.CONFIG_SELECT_TO_LOAD.message(), FontAwesomeSolid.HAND_POINTER);
            return;
        }
        try {
            AppConfig config = configManager.loadConfig(selected);
            // 只填充当前模式参数组，另一组保持隔离（确定后才生效）
            if (imageMode) {
                applyImageParamsToGui(config);
                toggleImageNameSettings();
                toggleImageRegionSettings();
            } else {
                applyTextParamsToGui(config);
                toggleLimitSettings();
                toggleTemplateSettings();
                toggleTitleSettings();
                updateTitleExtractTooltip();
            }
            log.info(I18n.msg("log.paramdialogcontroller.03", selected));
        } catch (Exception e) {
            log.error(I18n.msg("log.paramdialogcontroller.04"), e);
            NotificationHost.error(configComboBox, ErrorCode.CONFIG_LOAD_FAILED.message(), FontAwesomeSolid.FILE_DOWNLOAD);
        }
    }

    @FXML
    private void onDeleteConfig() {
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
        List<String> names = configManager.getConfigNames();
        configComboBox.setItems(FXCollections.observableArrayList(names));
    }

    // ==================== 子控件条件联动 ====================

    /** 内容限定字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleLimitSettings() {
        boolean enabled = enableLimitCheck.isSelected();
        startMarkerField.setDisable(!enabled);
        endMarkerField.setDisable(!enabled);
        useRegexCheck.setDisable(!enabled);
        if (!enabled) {
            startMarkerField.clear();
            endMarkerField.clear();
            useRegexCheck.setSelected(false);
        }
    }

    /** 自定义模板字段联动：未勾选时禁用模板输入框并恢复默认值 */
    private void toggleTemplateSettings() {
        boolean enabled = enableTemplateCheck.isSelected();
        startTemplateField.setDisable(!enabled);
        endTemplateField.setDisable(!enabled);
        if (!enabled) {
            startTemplateField.setText("\\n========== START ==========\\n");
            endTemplateField.setText("\\n========== END ==========\\n");
        }
    }

    /** 标题提取字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleTitleSettings() {
        boolean enabled = enableTitleExtractCheck.isSelected();
        titleStartMarkerField.setDisable(!enabled);
        titleEndMarkerField.setDisable(!enabled);
        titleUseRegexCheck.setDisable(!enabled);
        if (!enabled) {
            titleStartMarkerField.clear();
            titleEndMarkerField.clear();
            titleUseRegexCheck.setSelected(false);
        }
        updateTitleExtractTooltip();
    }

    /** 更新标题提取开关的 tooltip，反映章节序号+标题的组合状态 */
    private void updateTitleExtractTooltip() {
        boolean hasChapterNumber = addChapterNumberCheck.isSelected();
        boolean enableExtract = enableTitleExtractCheck.isSelected();
        String tooltip;
        if (enableExtract) {
            tooltip = hasChapterNumber
                    ? I18n.get("dialog.chapterModeNumberTitle")
                    : I18n.get("dialog.chapterModeTitleOnly");
        } else {
            tooltip = I18n.get("dialog.disabled");
        }
        enableTitleExtractCheck.setTooltip(new Tooltip(tooltip));
    }

    /** 图片名称提取字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleImageNameSettings() {
        boolean enabled = imageNameExtractCheck.isSelected();
        imageNameStartField.setDisable(!enabled);
        imageNameEndField.setDisable(!enabled);
        if (!enabled) {
            imageNameStartField.clear();
            imageNameEndField.clear();
        }
    }

    /** 图片区域限定字段联动：未勾选时禁用标记输入框并清空 */
    private void toggleImageRegionSettings() {
        boolean enabled = imageRegionExtractCheck.isSelected();
        imageRegionStartField.setDisable(!enabled);
        imageRegionEndField.setDisable(!enabled);
        if (!enabled) {
            imageRegionStartField.clear();
            imageRegionEndField.clear();
        }
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

    /** 模板输入框回车处理：按下回车时插入 \n 字符串 */
    private void setupTemplateFieldEnterKeyHandler() {
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> enterHandler = event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                event.consume();
                TextField source = (TextField) event.getSource();
                int caretPos = source.getCaretPosition();
                String currentText = source.getText();
                String newText = currentText.substring(0, caretPos) + "\\n" + currentText.substring(caretPos);
                source.setText(newText);
                source.positionCaret(caretPos + 2);
            }
        };

        startTemplateField.setOnKeyPressed(enterHandler);
        endTemplateField.setOnKeyPressed(enterHandler);
    }
}
