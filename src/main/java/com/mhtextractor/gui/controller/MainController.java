package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.ConfigManager;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.TextBatchProcessor;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.HorizontalDirection;
import javafx.scene.control.*;
import atlantafx.base.controls.ToggleSwitch;
import lombok.extern.slf4j.Slf4j;
import com.mhtextractor.gui.component.LogTextArea;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面逻辑控制器
 */
@Slf4j
public class MainController {

    // ===== 顶部控制栏 =====
    @FXML private ToggleButton textModeToggle;
    @FXML private ToggleButton imageModeToggle;
    @FXML private TextField inputFolderField;
    @FXML private TextField outputFolderField;
    @FXML private Button browseInputButton;
    @FXML private Button browseOutputButton;

    // ===== 文本提取配置 =====
    @FXML private Tab textConfigTab;
    @FXML private javafx.scene.control.TitledPane textTitlePane;
    @FXML private javafx.scene.control.TitledPane textContentLimitPane;
    @FXML private javafx.scene.control.TitledPane textTemplatePane;
    @FXML private javafx.scene.control.TitledPane textOutputModePane;
    @FXML private ToggleSwitch enableLimitCheck;
    @FXML private TextField startMarkerField;
    @FXML private TextField endMarkerField;
    @FXML private ToggleSwitch useRegexCheck;
    @FXML private ToggleSwitch enableTemplateCheck;
    @FXML private TextField startTemplateField;
    @FXML private TextField endTemplateField;
    @FXML private ToggleSwitch addChapterNumberCheck;
    @FXML private ToggleSwitch enableTitleExtractCheck;
    @FXML private TextField titleStartMarkerField;
    @FXML private TextField titleEndMarkerField;
    @FXML private ToggleSwitch titleUseRegexCheck;
    @FXML private ToggleSwitch separateFilesCheck;
    @FXML private ToggleSwitch autoFixEncodingCheck;

    // ===== 图片提取配置 =====
    @FXML private Tab imageConfigTab;
    @FXML private javafx.scene.control.TitledPane imageNamePane;
    @FXML private javafx.scene.control.TitledPane imageRegionPane;
    @FXML private javafx.scene.control.TitledPane imageProcessPane;
    @FXML private javafx.scene.control.TitledPane imageOutputModePane;
    @FXML private javafx.scene.control.TitledPane imagePerformancePane;
    @FXML private ToggleSwitch imageNameExtractCheck;
    @FXML private TextField imageNameStartField;
    @FXML private TextField imageNameEndField;
    @FXML private ToggleSwitch imageRegionExtractCheck;
    @FXML private TextField imageRegionStartField;
    @FXML private TextField imageRegionEndField;
    @FXML private ToggleSwitch imageRenameCheck;
    @FXML private ToggleSwitch imageZipCheck;
    @FXML private ToggleSwitch imageCbzCheck;
    @FXML private ToggleSwitch singleOutputCheck;
    @FXML private ToggleSwitch highSpeedMemoryCheck;

    // ===== 底部操作区 =====
    @FXML private TabPane configTabPane;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private ComboBox<String> configComboBox;
    @FXML private LogTextArea logTextArea;
    @FXML private ToggleSwitch openFolderAfterCompleteCheck;

    private final ConfigManager configManager = new ConfigManager();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Task<BatchProcessor.ProcessStats> currentTask;
    private DoubleProperty smoothProgress;
    private boolean isApplyingConfig = false;

    @FXML
    public void initialize() {
        // 统一设置 ToggleSwitch 标签在右侧（开关在左）
        ToggleSwitch[] toggles = {enableLimitCheck, useRegexCheck, enableTemplateCheck,
                addChapterNumberCheck, enableTitleExtractCheck, titleUseRegexCheck,
                separateFilesCheck, autoFixEncodingCheck, imageNameExtractCheck,
                imageRegionExtractCheck, imageRenameCheck, imageZipCheck,
                imageCbzCheck, singleOutputCheck, highSpeedMemoryCheck,
                openFolderAfterCompleteCheck};
        for (ToggleSwitch t : toggles) {
            t.setLabelPosition(HorizontalDirection.RIGHT);
        }

        // 1. 初始禁用所有输入控件（等待用户选择模式）
        inputFolderField.setDisable(true);
        outputFolderField.setDisable(true);
        browseInputButton.setDisable(true);
        browseOutputButton.setDisable(true);
        startButton.setDisable(true);
        separateFilesCheck.setDisable(true);
        openFolderAfterCompleteCheck.setDisable(true);

        // 2. 文本模式 — 内容限定字段联动
        enableLimitCheck.selectedProperty().addListener((obs, old, newVal) -> toggleLimitSettings());
        // 文本模式 — 自定义模板字段联动
        enableTemplateCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTemplateSettings());
        // 文本模式 — 标题提取字段联动
        enableTitleExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleTitleSettings());
        addChapterNumberCheck.selectedProperty().addListener((obs, old, newVal) -> updateTitleExtractTooltip());

        // 高速内存模式警告（ToggleSwitch 不支持 onAction，改用属性监听）
        highSpeedMemoryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                onHighSpeedMemoryChanged();
            }
        });

        setupTemplateFieldEnterKeyHandler();

        // 3. 图片模式 — 名称提取字段联动
        imageNameExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageNameSettings());
        // 图片模式 — 区域限定字段联动
        imageRegionExtractCheck.selectedProperty().addListener((obs, old, newVal) -> toggleImageRegionSettings());

        // 4. 模式切换互斥
        textModeToggle.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                imageModeToggle.setSelected(false);
                configTabPane.getSelectionModel().select(textConfigTab);
            } else {
                // 取消文本模式时清空文本配置
                clearTextModeConfig();
            }
            updateAllUIStates();
        });
        imageModeToggle.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                textModeToggle.setSelected(false);
                configTabPane.getSelectionModel().select(imageConfigTab);
            } else {
                // 取消图片模式时清空图片配置
                clearImageModeConfig();
            }
            updateAllUIStates();
        });

        // 5. 初始状态：两个模式都未选中
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(false);

        // 6. 初始折叠并禁用所有配置区域
        disableAndCollapseAllPanes();

        // 加载配置列表
        refreshConfigList();

        Platform.runLater(() -> {
            logTextArea.appendText("欢迎使用 MHT 资源提取器！\n");
            logTextArea.appendText("请选择提取模式，然后选择 MHT 文件所在文件夹进行提取。\n");
        });
    }
    /**
     * 为模板输入框设置回车键处理：按下回车时插入 \n 字符串
     */
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

    // ===== 目录浏览 =====

    @FXML
    private void onBrowseInput() {
        String currentPath = inputFolderField.getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                inputFolderField.getScene().getWindow(),
                "选择输入目录",
                currentPath);
        if (dir != null) {
            inputFolderField.setText(dir);
            autoSetOutputPath(dir);
        }
    }

    @FXML
    private void onBrowseOutput() {
        String currentPath = outputFolderField.getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                outputFolderField.getScene().getWindow(),
                "选择输出目录",
                currentPath);
        if (dir != null) {
            outputFolderField.setText(dir);
        }
    }

    /**
     * 根据输入目录自动设置输出目录为项目根目录下的"输出文件"文件夹
     */
    private void autoSetOutputPath(String inputPath) {
        String projectDir = System.getProperty("user.dir");
        String outputBaseDir = projectDir + File.separator + "输出文件";

        File outDirFile = new File(outputBaseDir);
        if (!outDirFile.exists()) {
            outDirFile.mkdirs();
        }

        outputFolderField.setText(outputBaseDir);
    }

    // ===== 开始处理 =====

    @FXML
    private void onStartProcessing() {
        String inputFolder = inputFolderField.getText();
        String outputFolder = outputFolderField.getText();

        if (inputFolder == null || inputFolder.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "请提供有效的输入目录");
            return;
        }
        if (outputFolder == null || outputFolder.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "请提供有效的输出目录");
            return;
        }
        if (!new File(inputFolder).isDirectory()) {
            showAlert(Alert.AlertType.ERROR, "输入目录不存在");
            return;
        }

        // 预统计文件数量
        var mhtFiles = com.mhtextractor.util.FileUtils.findMhtFiles(inputFolder);
        int totalFiles = mhtFiles.size();
        if (totalFiles == 0) {
            showAlert(Alert.AlertType.WARNING, "未找到任何 MHT 文件");
            return;
        }

        boolean imageMode = imageModeToggle.isSelected();

        // 图片模式额外校验
        if (imageMode) {
            if (imageZipCheck.isSelected() || imageCbzCheck.isSelected()) {
                if (!new File(outputFolder).isDirectory()) {
                    new File(outputFolder).mkdirs();
                }
            }
        }

        // 生成时间戳会话目录，隔离每次提取运行
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH：mm：ss"));
        String sessionOutputFolder = new File(outputFolder, timestamp).getPath();
        if (!new File(sessionOutputFolder).mkdirs()) {
            log.warn("会话输出目录创建失败或已存在：{}", sessionOutputFolder);
        }
        log.info("会话输出目录：{}", sessionOutputFolder);

        running.set(true);
        startButton.setDisable(true);
        stopButton.setDisable(false);
        progressBar.setVisible(true);
        progressBar.setProgress(0);

        Task<BatchProcessor.ProcessStats> task = new Task<>() {
            @Override
            protected BatchProcessor.ProcessStats call() {
                try {
                    AppConfig config = buildConfig();

                    // 进度回调：每处理完一个文件时更新进度
                    BatchProcessor.ProgressCallback progressCallback = (current, total, fileName, subProgress) -> {
                        double overall = (current - 1 + subProgress) / total;
                        updateProgress(overall, 1.0);
                    };

                    // 递归深度超限回调：弹窗询问用户是否继续
                    java.util.function.Supplier<Boolean> recursionCallback = () -> {
                        CompletableFuture<Boolean> future = new CompletableFuture<>();
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("递归深度警告");
                            alert.setHeaderText("MIME 树递归深度已超过 100 层");
                            alert.setContentText("该文件可能已损坏或结构异常。\n\n" +
                                    "选「是」：继续解析（后续不再提醒）\n" +
                                    "选「否」：跳过此文件，保留已提取的内容");
                            alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
                            ButtonType result = alert.showAndWait().orElse(ButtonType.NO);
                            future.complete(result == ButtonType.YES);
                        });
                        return future.join();
                    };

                    if (imageMode) {
                        ImageBatchProcessor processor = new ImageBatchProcessor(config);
                        processor.setCancelSupplier(this::isCancelled);
                        processor.setRecursionContinueCallback(recursionCallback);
                        return processor.process(inputFolder, sessionOutputFolder, progressCallback);
                    } else {
                        TextBatchProcessor processor = new TextBatchProcessor(config);
                        processor.setCancelSupplier(this::isCancelled);
                        processor.setRecursionContinueCallback(recursionCallback);
                        if (config.isSeparateFiles()) {
                            return processor.processSeparate(inputFolder, sessionOutputFolder, progressCallback);
                        } else {
                            String inputDirName = new File(inputFolder).getName();
                            if (inputDirName == null || inputDirName.isEmpty()) {
                                inputDirName = "mht_output";
                            }
                            String outputPath = new File(sessionOutputFolder, inputDirName + ".txt").toString();
                            return processor.processMerged(inputFolder, outputPath, progressCallback);
                        }
                    }
                } catch (Exception e) {
                    log.error("处理异常", e);
                    throw new RuntimeException(e);
                }
            }
        };

        // 使用平滑进度条动画替代直接绑定
        smoothProgress = JavaFxUtil.createSmoothProgressProperty();
        progressBar.progressProperty().bind(smoothProgress);

        // 监听 Task 进度变化，平滑过渡到新值
        task.progressProperty().addListener((obs, oldVal, newVal) -> {
            JavaFxUtil.setSmoothProgress(smoothProgress, newVal.doubleValue());
        });

        task.setOnSucceeded(e -> {
            running.set(false);
            currentTask = null;
            startButton.setDisable(false);
            stopButton.setDisable(true);
            smoothProgress.set(1.0);
            progressBar.progressProperty().unbind();
            JavaFxUtil.stopSmoothProgress(smoothProgress);
            statusLabel.setText("处理完成");
            BatchProcessor.ProcessStats stats = task.getValue();
            if (stats != null) {
                statusLabel.setText(stats.getSummaryLine());
            }
            // 提取完成后打开会话输出目录
            if (openFolderAfterCompleteCheck.isSelected()) {
                File folder = new File(sessionOutputFolder);
                if (folder.exists()) {
                    try {
                        Runtime.getRuntime().exec("explorer.exe " + folder.getAbsolutePath());
                    } catch (Exception ex) {
                        log.error("打开文件夹失败", ex);
                    }
                }
            }
        });

        task.setOnFailed(e -> {
            running.set(false);
            currentTask = null;
            startButton.setDisable(false);
            stopButton.setDisable(true);
            progressBar.progressProperty().unbind();
            JavaFxUtil.stopSmoothProgress(smoothProgress);
            progressBar.setVisible(false);
            statusLabel.setText("处理异常");
            Throwable ex = task.getException();
            log.error("处理异常", ex);
            showAlert(Alert.AlertType.ERROR, "处理异常: " + ex.getMessage());
        });

        currentTask = task;
        new Thread(task).start();
    }

    @FXML
    private void onStopProcessing() {
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        running.set(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        progressBar.progressProperty().unbind();
        JavaFxUtil.stopSmoothProgress(smoothProgress);
        progressBar.setVisible(false);
        statusLabel.setText("已停止");
        log.info("用户手动停止处理");
    }

    // ===== 事件处理 =====

    /**
     * 高速内存模式确认弹窗
     */
    private void onHighSpeedMemoryChanged() {
        if (highSpeedMemoryCheck.isSelected()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("高速内存模式");
            alert.setHeaderText("确认开启高速内存模式？");
            alert.setContentText("该模式会预加载所有 MHT 文件到内存，\n需要约原始文件总大小 4 倍的空闲内存。\n\n如果内存不足，请使用默认的流式模式。");
            alert.showAndWait().ifPresent(response -> {
                if (response != ButtonType.OK) {
                    highSpeedMemoryCheck.setSelected(false);
                }
            });
        }
    }

    // ===== 配置管理 =====

    @FXML
    private void onSaveConfig() {
        TextInputDialog dialog = new TextInputDialog("my_config");
        dialog.setTitle("保存配置");
        dialog.setHeaderText("输入配置名称");
        dialog.showAndWait().ifPresent(name -> {
            try {
                AppConfig config = buildConfig();
                configManager.saveConfig(name, config);
                refreshConfigList();
                configComboBox.getSelectionModel().select(name);
                log.info("配置已保存：{}", name);
            } catch (Exception e) {
                log.error("保存配置失败", e);
                showAlert(Alert.AlertType.ERROR, "保存配置失败: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onLoadConfig() {
        String selected = configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "请选择要加载的配置");
            return;
        }
        try {
            AppConfig config = configManager.loadConfig(selected);
            applyConfig(config);
            log.info("配置已加载：{}", selected);
        } catch (Exception e) {
            log.error("加载配置失败", e);
            showAlert(Alert.AlertType.ERROR, "加载配置失败: " + e.getMessage());
        }
    }

    @FXML
    private void onDeleteConfig() {
        String selected = configComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "请选择要删除的配置");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "确定删除配置 \"" + selected + "\"？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    configManager.deleteConfig(selected);
                    refreshConfigList();
                    log.info("配置已删除：{}", selected);
                } catch (Exception e) {
                    log.error("删除配置失败", e);
                    showAlert(Alert.AlertType.ERROR, "删除配置失败: " + e.getMessage());
                }
            }
        });
    }

    private void refreshConfigList() {
        List<String> names = configManager.getConfigNames();
        configComboBox.setItems(FXCollections.observableArrayList(names));
    }

    // ===== 从 UI 构建 AppConfig =====

    private AppConfig buildConfig() {
        AppConfig config = new AppConfig();
        config.setInputFolder(inputFolderField.getText());
        config.setOutputFolder(outputFolderField.getText());

        // 文本模式
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

        // 图片模式
        config.setImageModeEnabled(imageModeToggle.isSelected());
        config.setEnableNameExtraction(imageNameExtractCheck.isSelected());
        config.setImageNameStartMarker(imageNameStartField.getText());
        config.setImageNameEndMarker(imageNameEndField.getText());
        config.setEnableRegionExtraction(imageRegionExtractCheck.isSelected());
        config.setImageRegionStartMarker(imageRegionStartField.getText());
        config.setImageRegionEndMarker(imageRegionEndField.getText());
        config.setEnableBatchRenaming(imageRenameCheck.isSelected());
        config.setEnableZipPackaging(imageZipCheck.isSelected());
        config.setEnableCbzPackaging(imageCbzCheck.isSelected());
        config.setSingleOutputMode(singleOutputCheck.isSelected());
        config.setHighSpeedMemoryMode(highSpeedMemoryCheck.isSelected());
        config.setOpenFolderAfterComplete(openFolderAfterCompleteCheck.isSelected());

        return config;
    }

    // ===== 从 AppConfig 填充 UI =====

    private void applyConfig(AppConfig config) {
        if (config.getInputFolder() != null) inputFolderField.setText(config.getInputFolder());
        if (config.getOutputFolder() != null) outputFolderField.setText(config.getOutputFolder());

        separateFilesCheck.setSelected(config.isSeparateFiles());
        enableLimitCheck.setSelected(config.isEnableContentLimit());
        if (config.getStartMarker() != null) startMarkerField.setText(config.getStartMarker());
        if (config.getEndMarker() != null) endMarkerField.setText(config.getEndMarker());
        useRegexCheck.setSelected(config.isUseRegex());
        enableTemplateCheck.setSelected(config.isEnableTemplate());
        if (config.getStartTemplate() != null) startTemplateField.setText(config.getStartTemplate());
        if (config.getEndTemplate() != null) endTemplateField.setText(config.getEndTemplate());
        addChapterNumberCheck.setSelected(config.isAddChapterNumber());
        enableTitleExtractCheck.setSelected(config.isEnableTitleExtraction());
        if (config.getTitleStartMarker() != null) titleStartMarkerField.setText(config.getTitleStartMarker());
        if (config.getTitleEndMarker() != null) titleEndMarkerField.setText(config.getTitleEndMarker());
        titleUseRegexCheck.setSelected(config.isTitleUseRegex());
        autoFixEncodingCheck.setSelected(config.isAutoFixEncoding());

        imageModeToggle.setSelected(config.isImageModeEnabled());
        if (config.isImageModeEnabled()) {
            textModeToggle.setSelected(false);
            configTabPane.getSelectionModel().select(imageConfigTab);
        } else {

            textModeToggle.setSelected(true);
            imageModeToggle.setSelected(false);
            configTabPane.getSelectionModel().select(textConfigTab);
        }

        imageNameExtractCheck.setSelected(config.isEnableNameExtraction());
        if (config.getImageNameStartMarker() != null) imageNameStartField.setText(config.getImageNameStartMarker());
        if (config.getImageNameEndMarker() != null) imageNameEndField.setText(config.getImageNameEndMarker());
        imageRegionExtractCheck.setSelected(config.isEnableRegionExtraction());
        if (config.getImageRegionStartMarker() != null) imageRegionStartField.setText(config.getImageRegionStartMarker());
        if (config.getImageRegionEndMarker() != null) imageRegionEndField.setText(config.getImageRegionEndMarker());
        imageRenameCheck.setSelected(config.isEnableBatchRenaming());
        imageZipCheck.setSelected(config.isEnableZipPackaging());
        imageCbzCheck.setSelected(config.isEnableCbzPackaging());
        singleOutputCheck.setSelected(config.isSingleOutputMode());
        highSpeedMemoryCheck.setSelected(config.isHighSpeedMemoryMode());
        openFolderAfterCompleteCheck.setSelected(config.isOpenFolderAfterComplete());

        // 导入配置后刷新联动状态，确保子控件启用/禁用与配置值匹配
        updateAllUIStates();
    }

    // ==================== 子控件条件禁用联动 ====================

    /**
     * 内容限定字段联动：未勾选时禁用标记输入框并清空
     */
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

    /**
     * 自定义模板字段联动：未勾选时禁用模板输入框并恢复默认值
     */
    private void toggleTemplateSettings() {
        boolean enabled = enableTemplateCheck.isSelected();
        startTemplateField.setDisable(!enabled);
        endTemplateField.setDisable(!enabled);
        if (!enabled) {
            startTemplateField.setText("\\n========== START ==========\\n");
            endTemplateField.setText("\\n========== END ==========\\n");
        }
    }

    /**
     * 标题提取字段联动：未勾选时禁用标记输入框并清空
     */
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

    /**
     * 更新标题提取 CheckBox 的 tooltip，反映章节序号+标题的组合状态
     */
    private void updateTitleExtractTooltip() {
        boolean hasChapterNumber = addChapterNumberCheck.isSelected();
        boolean enableExtract = enableTitleExtractCheck.isSelected();
        String tooltip;
        if (enableExtract) {
            tooltip = hasChapterNumber ? "模式：序号 + 标题" : "模式：仅标题";
        } else {
            tooltip = "已禁用";
        }
        enableTitleExtractCheck.setTooltip(new Tooltip(tooltip));
    }

    /**
     * 图片名称提取字段联动：未勾选时禁用标记输入框并清空
     */
    private void toggleImageNameSettings() {
        boolean enabled = imageNameExtractCheck.isSelected();
        imageNameStartField.setDisable(!enabled);
        imageNameEndField.setDisable(!enabled);
        if (!enabled) {
            imageNameStartField.clear();
            imageNameEndField.clear();
        }
    }

    /**
     * 图片区域限定字段联动：未勾选时禁用标记输入框并清空
     */
    private void toggleImageRegionSettings() {
        boolean enabled = imageRegionExtractCheck.isSelected();
        imageRegionStartField.setDisable(!enabled);
        imageRegionEndField.setDisable(!enabled);
        if (!enabled) {
            imageRegionStartField.clear();
            imageRegionEndField.clear();
        }
    }

    // ==================== 全局 UI 状态管理 ====================

    /**
     * 更新所有 UI 组件的启用/禁用状态
     */
    private void updateAllUIStates() {
        boolean anyModeSelected = textModeToggle.isSelected() || imageModeToggle.isSelected();
        boolean textModeSelected = textModeToggle.isSelected();
        boolean imageModeSelected = imageModeToggle.isSelected();

        // 输入输出控件
        inputFolderField.setDisable(!anyModeSelected);
        outputFolderField.setDisable(!anyModeSelected);
        browseInputButton.setDisable(!anyModeSelected);
        browseOutputButton.setDisable(!anyModeSelected);
        separateFilesCheck.setDisable(!anyModeSelected);
        openFolderAfterCompleteCheck.setDisable(!anyModeSelected);
        startButton.setDisable(!anyModeSelected);

        // 根据模式控制 TitledPane 的展开/折叠和启用/禁用
        if (textModeSelected) {
            // 文本模式：启用并展开文本配置区域，禁用并折叠图片配置区域
            enableAndExpandTextPanes();
            disableAndCollapseImagePanes();

            // 触发子控件联动
            toggleLimitSettings();
            toggleTemplateSettings();
            toggleTitleSettings();
        } else if (imageModeSelected) {
            // 图片模式：启用并展开图片配置区域，禁用并折叠文本配置区域
            disableAndCollapseTextPanes();
            enableAndExpandImagePanes();

            // 触发子控件联动
            toggleImageNameSettings();
            toggleImageRegionSettings();
        } else {
            // 未选择模式：禁用并折叠所有配置区域
            disableAndCollapseAllPanes();
        }
    }

    // ==================== TitledPane 展开/折叠与启用/禁用控制 ====================

    /**
     * 禁用并折叠所有配置区域
     */
    private void disableAndCollapseAllPanes() {
        disableAndCollapseTextPanes();
        disableAndCollapseImagePanes();
    }

    /**
     * 禁用并折叠文本配置区域
     */
    private void disableAndCollapseTextPanes() {
        if (textTitlePane != null) {
            textTitlePane.setDisable(true);
            textTitlePane.setExpanded(false);
        }
        if (textContentLimitPane != null) {
            textContentLimitPane.setDisable(true);
            textContentLimitPane.setExpanded(false);
        }
        if (textTemplatePane != null) {
            textTemplatePane.setDisable(true);
            textTemplatePane.setExpanded(false);
        }
        if (textOutputModePane != null) {
            textOutputModePane.setDisable(true);
            textOutputModePane.setExpanded(false);
        }
    }

    /**
     * 启用并展开文本配置区域
     */
    private void enableAndExpandTextPanes() {
        if (textTitlePane != null) {
            textTitlePane.setDisable(false);
            textTitlePane.setExpanded(true);
        }
        if (textContentLimitPane != null) {
            textContentLimitPane.setDisable(false);
            textContentLimitPane.setExpanded(true);
        }
        if (textTemplatePane != null) {
            textTemplatePane.setDisable(false);
            textTemplatePane.setExpanded(true);
        }
        if (textOutputModePane != null) {
            textOutputModePane.setDisable(false);
            textOutputModePane.setExpanded(true);
        }
    }

    /**
     * 禁用并折叠图片配置区域
     */
    private void disableAndCollapseImagePanes() {
        if (imageNamePane != null) {
            imageNamePane.setDisable(true);
            imageNamePane.setExpanded(false);
        }
        if (imageRegionPane != null) {
            imageRegionPane.setDisable(true);
            imageRegionPane.setExpanded(false);
        }
        if (imageProcessPane != null) {
            imageProcessPane.setDisable(true);
            imageProcessPane.setExpanded(false);
        }
        if (imageOutputModePane != null) {
            imageOutputModePane.setDisable(true);
            imageOutputModePane.setExpanded(false);
        }
        if (imagePerformancePane != null) {
            imagePerformancePane.setDisable(true);
            imagePerformancePane.setExpanded(false);
        }
    }

    /**
     * 启用并展开图片配置区域
     */
    private void enableAndExpandImagePanes() {
        if (imageNamePane != null) {
            imageNamePane.setDisable(false);
            imageNamePane.setExpanded(true);
        }
        if (imageRegionPane != null) {
            imageRegionPane.setDisable(false);
            imageRegionPane.setExpanded(true);
        }
        if (imageProcessPane != null) {
            imageProcessPane.setDisable(false);
            imageProcessPane.setExpanded(true);
        }
        if (imageOutputModePane != null) {
            imageOutputModePane.setDisable(false);
            imageOutputModePane.setExpanded(true);
        }
        if (imagePerformancePane != null) {
            imagePerformancePane.setDisable(false);
            imagePerformancePane.setExpanded(true);
        }
    }


    // ==================== 清空模式配置 ====================

    /**
     * 清空文本模式的配置
     */
    private void clearTextModeConfig() {
        enableLimitCheck.setSelected(false);
        startMarkerField.clear();
        endMarkerField.clear();
        useRegexCheck.setSelected(false);

        enableTemplateCheck.setSelected(false);
        startTemplateField.setText("\\n========== START ==========\\n");
        endTemplateField.setText("\\n========== END ==========\\n");

        addChapterNumberCheck.setSelected(false);
        enableTitleExtractCheck.setSelected(false);
        titleStartMarkerField.clear();
        titleEndMarkerField.clear();
        titleUseRegexCheck.setSelected(false);

        separateFilesCheck.setSelected(false);
        autoFixEncodingCheck.setSelected(false);
    }

    /**
     * 清空图片模式的配置
     */
    private void clearImageModeConfig() {
        imageNameExtractCheck.setSelected(false);
        imageNameStartField.clear();
        imageNameEndField.clear();

        imageRegionExtractCheck.setSelected(false);
        imageRegionStartField.clear();
        imageRegionEndField.clear();

        imageRenameCheck.setSelected(false);
        imageZipCheck.setSelected(false);
        imageCbzCheck.setSelected(false);

        singleOutputCheck.setSelected(false);
        highSpeedMemoryCheck.setSelected(false);
    }

    // ===== 辅助方法 =====

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.showAndWait();
    }

    // Agent exercise code removed — use build-native.cmd for agent runs
    private void __agent_exercise_removed() throws Exception {
        // Phase 1: Toggle text mode on/off
        textModeToggle.setSelected(true);
        Thread.sleep(300);
        textModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 2: Toggle image mode on/off
        imageModeToggle.setSelected(true);
        Thread.sleep(300);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 3: Text mode - all ToggleSwitches and fields
        textModeToggle.setSelected(true);
        Thread.sleep(200);
        enableLimitCheck.setSelected(true);
        useRegexCheck.setSelected(true);
        enableTemplateCheck.setSelected(true);
        addChapterNumberCheck.setSelected(true);
        enableTitleExtractCheck.setSelected(true);
        titleUseRegexCheck.setSelected(true);
        separateFilesCheck.setSelected(true);
        autoFixEncodingCheck.setSelected(true);
        startMarkerField.setText("<!--START-->");
        endMarkerField.setText("<!--END-->");
        startTemplateField.setText("\\n==START==\\n");
        endTemplateField.setText("\\n==END==\\n");
        titleStartMarkerField.setText("<title>");
        titleEndMarkerField.setText("</title>");
        Thread.sleep(200);

        // Collapse and expand TitledPanes
        textTitlePane.setExpanded(false);
        textContentLimitPane.setExpanded(false);
        Thread.sleep(100);
        textTitlePane.setExpanded(true);
        textContentLimitPane.setExpanded(true);
        textTemplatePane.setExpanded(true);
        textOutputModePane.setExpanded(true);
        Thread.sleep(200);

        // Phase 4: Image mode - all ToggleSwitches and fields
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(true);
        Thread.sleep(200);
        imageNameExtractCheck.setSelected(true);
        imageRegionExtractCheck.setSelected(true);
        imageRenameCheck.setSelected(true);
        imageZipCheck.setSelected(true);
        imageCbzCheck.setSelected(true);
        singleOutputCheck.setSelected(true);
        highSpeedMemoryCheck.setSelected(true);
        imageNameStartField.setText("img-");
        imageNameEndField.setText(".png");
        imageRegionStartField.setText("<!--IMG-->");
        imageRegionEndField.setText("<!--/IMG-->");
        Thread.sleep(200);
        toggleImageNameSettings();
        toggleImageRegionSettings();
        Thread.sleep(200);

        // Phase 5: Config combo box
        configComboBox.setValue("agent_test_config");
        configComboBox.getEditor().setText("agent_test_config");
        Thread.sleep(200);

        // Phase 6: Fill paths
        inputFolderField.setText("C:\\test\\mht");
        outputFolderField.setText("D:\\output");
        openFolderAfterCompleteCheck.setSelected(true);
        Thread.sleep(200);

        // Phase 7: Trigger dialog/processor object creation (without showing dialogs)
        // These constructors trigger reflection registration for Dialog classes
        new Alert(Alert.AlertType.INFORMATION);
        new Alert(Alert.AlertType.WARNING);
        new Alert(Alert.AlertType.ERROR);
        new Alert(Alert.AlertType.CONFIRMATION);
        new TextInputDialog("agent_test");
        new javafx.stage.DirectoryChooser();
        new ButtonType("OK");
        new Tooltip("test tooltip");
        new java.io.File("C:\\test");
        // Trigger Jackson serialization
        ObjectMapperForExercise om = new ObjectMapperForExercise();
        AppConfig config = buildConfig();
        String json = om.writeValueAsString(config);
        AppConfig restored = om.readValue(json, AppConfig.class);
        log.info("Agent config serialization test: {}", restored.getInputFolder());
        Thread.sleep(300);

        // Phase 8: Deselect all modes
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(false);
        disableAndCollapseAllPanes();
        Thread.sleep(300);

        // Phase 9: High speed memory toggle (triggers Alert)
        textModeToggle.setSelected(true);
        imageModeToggle.setSelected(false);
        highSpeedMemoryCheck.setSelected(true); // triggers onHighSpeedMemoryChanged
        Thread.sleep(300);
        highSpeedMemoryCheck.setSelected(false);

        log.info("Agent exercise complete - all UI features exercised");
    }

    /** Minimal Jackson ObjectMapper wrapper for agent exercise */
    private static class ObjectMapperForExercise {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;
        ObjectMapperForExercise() {
            mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
        String writeValueAsString(Object value) throws Exception {
            return mapper.writeValueAsString(value);
        }
        AppConfig readValue(String json, Class<AppConfig> clazz) throws Exception {
            return mapper.readValue(json, clazz);
        }
    }
}
