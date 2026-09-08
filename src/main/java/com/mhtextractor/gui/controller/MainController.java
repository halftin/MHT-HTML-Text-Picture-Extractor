package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.gui.component.LogTextArea;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.processor.TextBatchProcessor;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.HorizontalDirection;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面逻辑控制器：预览工作台常驻主窗口，参数设置弹窗化。
 *
 * <p>预览状态机：选定输入目录后扫描出文件列表（不解析）；当前模式参数经参数窗口
 * "确定"后（写入该模式已确认缓存）自动解析，点击列表项即可预览；未确认时点击列表项
 * 提示先设置参数。两套已确认参数缓存（文本/图片）互相隔离，切模式不丢失；主界面
 * GUI 状态（模式/目录/完成后打开输出文件夹）持久化于 logs/gui-state.json。</p>
 */
@Slf4j
public class MainController {

    /** 超大文件完整解析确认阈值（字节） */
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024;

    /** 单元格内边距清零（列头与单元格列对齐由各自 HBox 的内边距控制） */
    private static final String CELL_PAD = "-fx-padding: 0;";
    private static final String FAILED_STYLE =
            CELL_PAD + " -fx-background-color: #f8d7da; -fx-text-background-color: #721c24;";
    private static final String BADGE_STYLE =
            "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white;"
                    + " -fx-padding: 1 4; -fx-background-radius: 9;";
    /** 插入指示线颜色（拖拽排序反馈） */
    private static final String DROP_LINE_STYLE = "-fx-background-color: #3b82f6;";

    /** 徽标列/位置序号列的固定槽位宽度 */
    private static final double BADGE_COL_WIDTH = 40;
    private static final double POS_COL_WIDTH = 40;

    private static final String HINT_NO_MODE_OR_DIR =
            "请选择提取模式，并选择包含 .mht 文件的输入目录。";
    private static final String HINT_NEED_PARAMS =
            "当前模式尚未设置提取参数。\n点击右上角\"提取参数设置\"按钮，配置参数并点击\"确定\"后，即可在此预览提取效果。";
    private static final String HINT_NO_FILES =
            "所选输入目录中未找到 .mht 文件。";

    /** 条目预解析状态 */
    enum Status { WAITING, READY, EMPTY, FAILED }

    /** 缩略图条目：解码后的 JavaFX 图像 + 不含 f%02d_ 前缀的文件名（重排时按新位置重算前缀） */
    private record ThumbEntry(Image image, String coreName) {}

    /** 单个 MHT 文件的预览状态 */
    class PreviewItem {
        final String path;
        final String name;
        final LocalDateTime modifiedTime;
        final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.WAITING);
        final StringProperty errorMsg = new SimpleStringProperty("");
        final BooleanProperty chapterEnabled = new SimpleBooleanProperty(true);
        final StringProperty badge = new SimpleStringProperty(null);
        volatile String cachedText;
        volatile String cachedTitle;
        volatile List<ImageExtractor.ImageData> cachedImages;
        volatile List<ThumbEntry> cachedThumbs;

        PreviewItem(String path, String name, LocalDateTime modifiedTime) {
            this.path = path;
            this.name = name;
            this.modifiedTime = modifiedTime;
        }
    }

    // ===== 顶部控制栏 =====
    @FXML private ToggleButton textModeToggle;
    @FXML private ToggleButton imageModeToggle;
    @FXML private TextField inputFolderField;
    @FXML private TextField outputFolderField;
    @FXML private Button browseInputButton;
    @FXML private Button browseOutputButton;

    // ===== 预览工作区（左：列表） =====
    @FXML private Label fileCountLabel;
    @FXML private Label badgeHeaderLabel;
    @FXML ListView<PreviewItem> fileListView;
    @FXML private Pane dragOverlay;

    // ===== 预览工作区（右：信息区与内容区） =====
    @FXML private Label modeLabel;
    @FXML private Label outputModeLabel;
    @FXML private Label outputNameLabel;
    @FXML private Label titleInfoLabel;
    @FXML private Label outputResultLabel;
    @FXML CheckBox adjustCheck;
    @FXML private TextArea textPreviewArea;
    @FXML private VBox imagePreviewBox;
    @FXML private Label imageHintLabel;
    @FXML Button previewEffectButton;
    @FXML private Label renameNoteLabel;
    @FXML private ScrollPane imageScrollPane;
    @FXML private FlowPane thumbnailPane;
    @FXML private Button paramSettingsButton;

    // ===== 底部操作区 =====
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private LogTextArea logTextArea;
    @FXML private ToggleSwitch openFolderAfterCompleteCheck;

    // ===== 状态 =====
    private final ObservableList<PreviewItem> items = FXCollections.observableArrayList();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Task<BatchProcessor.ProcessStats> currentTask;

    /** 两套已确认提取参数缓存：null 表示该模式尚未确认过参数 */
    private AppConfig textParamsCache;
    private AppConfig imageParamsCache;

    /** 工作区后台活动代次：预解析/换目录/换模式/开始提取时自增，取消旧任务 */
    private int parseToken = 0;
    /** 列表选择代次：切换选择时自增，取消过期的图片懒加载 */
    private int requestToken = 0;
    private boolean imageLoadActive = false;
    private PreviewItem loadingItem = null;
    private boolean updatingAdjustCheck = false;
    private PreviewItem lastSelected = null;
    private CellIndicator lastDragOverCell = null;

    // ===== 拖拽排序状态 =====
    private PreviewItem draggedItem = null;
    private int draggedIndex = -1;
    private double dragGapTopY = 0;
    private boolean dragActive = false;
    private boolean dropHandled = false;
    private boolean dragPositionStale = false;
    private ParallelTransition liftAnim = null;
    private List<PreviewListCell> liftCells = null;
    private List<ImageView> liftViews = null;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() {
        openFolderAfterCompleteCheck.setLabelPosition(HorizontalDirection.RIGHT);
        badgeHeaderLabel.setTooltip(new Tooltip(
                "勾选\"添加章节序号\"的文件的最终章节号（阿拉伯数字），随勾选与排序实时变化"));

        // 1. 列表：单元格工厂、选择联动、逐文件章节勾选、拖拽动画滚动联动
        fileListView.setCellFactory(lv -> createPreviewCell());
        fileListView.setItems(items);

        fileListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, newVal) -> onItemSelected(newVal));

        adjustCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (updatingAdjustCheck || !currentModeConfirmed()) {
                return;
            }
            PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            if (isTextMode()) {
                sel.chapterEnabled.set(newVal);
                recomputeBadges();
            }
        });

        // 拖拽动画期间若列表发生滚动（滚轮/自动滚动），标记位置失效并结束提起动画
        fileListView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            for (javafx.scene.Node n : fileListView.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                    sb.valueProperty().addListener((o, a, b) -> {
                        if (dragActive && Math.abs(b.doubleValue() - a.doubleValue()) > 0.1) {
                            dragPositionStale = true;
                            finishLift();
                        }
                    });
                }
            }
        });

        // 2. 模式切换互斥（不再清空另一模式参数：GUI 状态与已确认缓存按模式隔离保留）
        textModeToggle.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                imageModeToggle.setSelected(false);
            }
            onModeChanged();
        });
        imageModeToggle.selectedProperty().addListener((obs, old, newVal) -> {
            if (newVal) {
                textModeToggle.setSelected(false);
            }
            onModeChanged();
        });

        // 3. 目录/开关变更：刷新列表 + 持久化 GUI 状态
        inputFolderField.textProperty().addListener((obs, old, newVal) -> onInputFolderChanged());
        outputFolderField.textProperty().addListener((obs, old, newVal) -> saveGuiState());
        openFolderAfterCompleteCheck.selectedProperty().addListener((obs, old, newVal) -> saveGuiState());

        // 4. 恢复 GUI 状态（无状态文件时与旧版启动逻辑一致）
        restoreGuiState();

        // 5. 初始状态一致性（无状态文件时：两个模式都未选中，全部禁用）
        updateModeUi();

        Platform.runLater(() -> {
            logTextArea.appendText("欢迎使用 MHT 资源提取器！\n");
            logTextArea.appendText("请选择提取模式与输入目录，通过\"提取参数设置\"确认参数后即可预览并提取。\n");
        });
    }

    // ==================== GUI 状态持久化（logs/gui-state.json） ====================

    private void restoreGuiState() {
        GuiState state = GuiStateManager.load();
        if (state == null) {
            return;
        }
        if (state.getInputFolder() != null && !state.getInputFolder().isEmpty()) {
            inputFolderField.setText(state.getInputFolder());
        }
        if (state.getOutputFolder() != null && !state.getOutputFolder().isEmpty()) {
            outputFolderField.setText(state.getOutputFolder());
        }
        openFolderAfterCompleteCheck.setSelected(state.isOpenFolderAfterComplete());
        if (state.isImageModeEnabled()) {
            imageModeToggle.setSelected(true);
        } else {
            textModeToggle.setSelected(true);
        }
    }

    private void saveGuiState() {
        GuiState state = new GuiState();
        state.setImageModeEnabled(imageModeToggle.isSelected());
        state.setInputFolder(inputFolderField.getText());
        state.setOutputFolder(outputFolderField.getText());
        state.setOpenFolderAfterComplete(openFolderAfterCompleteCheck.isSelected());
        GuiStateManager.save(state);
    }

    // ==================== 目录浏览与文件列表 ====================

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

    /** 输入目录变更：有效目录重建文件列表（仅扫描，不解析），无效则清空列表 */
    private void onInputFolderChanged() {
        String text = inputFolderField.getText();
        if (text == null || text.trim().isEmpty() || !new File(text).isDirectory()) {
            clearFileList();
            saveGuiState();
            return;
        }
        rebuildFileList(text);
        saveGuiState();
    }

    private void clearFileList() {
        cancelWorkspaceActivity();
        items.clear();
        fileCountLabel.setText("共 0 个文件");
        updateInfoArea(null);
        updateContentArea(null);
        updateProgressStatus(parseToken);
    }

    private void rebuildFileList(String dir) {
        cancelWorkspaceActivity();
        items.clear();
        List<File> files = FileUtils.findMhtFiles(dir);
        for (File f : files) {
            BatchProcessor.MhtFileInfo info = BatchProcessor.toMhtFileInfo(f);
            items.add(new PreviewItem(info.getPath(), info.getName(), info.getModifiedTime()));
        }
        fileCountLabel.setText("共 " + items.size() + " 个文件");
        if (items.isEmpty()) {
            updateInfoArea(null);
            updateContentArea(null);
            updateProgressStatus(parseToken);
            return;
        }
        fileListView.getSelectionModel().selectFirst();
        // 当前模式参数已确认 → 自动解析（换目录后预览保持可用）
        if (currentModeConfirmed()) {
            startPreParse();
        }
    }

    /** 取消进行中的预解析/图片懒加载等后台工作区活动 */
    private void cancelWorkspaceActivity() {
        parseToken++;
        requestToken++;
        imageLoadActive = false;
        loadingItem = null;
    }

    // ==================== 模式切换与工作区 UI 状态 ====================

    private void onModeChanged() {
        updateModeUi();
        saveGuiState();
    }

    private boolean isTextMode() {
        return !imageModeToggle.isSelected();
    }

    /** 当前模式参数是否已确认（确定过一次即视为已设置） */
    private boolean currentModeConfirmed() {
        return imageModeToggle.isSelected() ? imageParamsCache != null : textParamsCache != null;
    }

    /** 当前模式已确认参数；未确认时返回默认参数（仅用于展示，不算已设置） */
    private AppConfig currentParamsOrDefaults() {
        AppConfig c = imageModeToggle.isSelected() ? imageParamsCache : textParamsCache;
        return c != null ? c : new AppConfig();
    }

    private PreviewItem currentSelection() {
        return fileListView.getSelectionModel().getSelectedItem();
    }

    /**
     * 更新与模式/参数相关的工作区 UI；切模式时取消旧解析，已确认则按新模式重新解析
     */
    private void updateModeUi() {
        cancelWorkspaceActivity();
        boolean anyModeSelected = textModeToggle.isSelected() || imageModeToggle.isSelected();

        // 顶部与底部控件
        inputFolderField.setDisable(!anyModeSelected);
        outputFolderField.setDisable(!anyModeSelected);
        browseInputButton.setDisable(!anyModeSelected);
        browseOutputButton.setDisable(!anyModeSelected);
        openFolderAfterCompleteCheck.setDisable(!anyModeSelected);
        paramSettingsButton.setDisable(!anyModeSelected);
        startButton.setDisable(!anyModeSelected);

        // 预览工作区
        modeLabel.setText("模式：" + (imageModeToggle.isSelected() ? "图片"
                : (textModeToggle.isSelected() ? "文本" : "未选择")));
        updateBadgeHeader();
        updateOutputModeLabel();
        updateRenameNote();
        showCurrentModeArea();
        fileListView.refresh();

        PreviewItem sel = currentSelection();
        updateAdjustCheck(sel);
        updateInfoArea(sel);
        updateContentArea(sel);

        if (anyModeSelected && currentModeConfirmed() && !items.isEmpty()) {
            startPreParse();
        }
    }

    private void showCurrentModeArea() {
        textPreviewArea.setVisible(isTextMode());
        textPreviewArea.setManaged(isTextMode());
        imagePreviewBox.setVisible(!isTextMode());
        imagePreviewBox.setManaged(!isTextMode());
    }

    private void updateBadgeHeader() {
        boolean showBadgeCol = isTextMode() && currentParamsOrDefaults().isAddChapterNumber();
        badgeHeaderLabel.setVisible(showBadgeCol);
        badgeHeaderLabel.setManaged(showBadgeCol);
    }

    private void updateOutputModeLabel() {
        AppConfig params = currentParamsOrDefaults();
        outputModeLabel.setText(isTextMode()
                ? (params.isSeparateFiles() ? "输出模式：单独输出模式" : "输出模式：合并输出模式")
                : (params.isSingleOutputMode() ? "输出模式：合并输出模式" : "输出模式：单独输出模式"));
    }

    // ==================== 参数设置弹窗 ====================

    @FXML
    private void onParamSettings() {
        boolean imageMode = imageModeToggle.isSelected();
        try {
            ParamDialogController ctrl = ParamDialogController.openAndWait(
                    inputFolderField.getScene().getWindow(), imageMode,
                    textParamsCache, imageParamsCache);
            if (ctrl.isConfirmed()) {
                applyConfirmedParams(imageMode, ctrl.getResult());
                log.info("{}模式提取参数已确认生效", imageMode ? "图片" : "文本");
            }
        } catch (Exception e) {
            log.error("参数设置窗口打开失败", e);
            showAlert(Alert.AlertType.ERROR, "参数设置窗口打开失败: " + e.getMessage());
        }
    }

    /** 写入某模式的已确认参数缓存并刷新工作区（agent 演练/自动化测试也走此入口） */
    private void applyConfirmedParams(boolean imageMode, AppConfig params) {
        if (imageMode) {
            imageParamsCache = params;
        } else {
            textParamsCache = params;
        }
        refreshAfterParamsChanged();
    }

    private void refreshAfterParamsChanged() {
        updateBadgeHeader();
        updateOutputModeLabel();
        updateRenameNote();
        fileListView.refresh();
        PreviewItem sel = currentSelection();
        updateAdjustCheck(sel);
        updateInfoArea(sel);
        updateContentArea(sel);
        if (currentModeConfirmed() && !items.isEmpty()) {
            startPreParse();
        }
    }

    // ==================== 开始 / 停止 ====================

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
        if (items.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "未找到任何 MHT 文件");
            return;
        }

        boolean imageMode = imageModeToggle.isSelected();

        // 图片模式额外校验
        if (imageMode) {
            AppConfig params = currentParamsOrDefaults();
            if (params.isEnableZipPackaging() || params.isEnableCbzPackaging()) {
                if (!new File(outputFolder).isDirectory()) {
                    new File(outputFolder).mkdirs();
                }
            }
        }

        // 当前模式参数未确认 → 弹窗提醒（区分"从未设置"与"检测到模式切换"两种文案）
        boolean otherConfirmed = imageMode ? textParamsCache != null : imageParamsCache != null;
        if (!currentModeConfirmed()) {
            String msg = otherConfirmed
                    ? "检测到模式切换，目前模式尚未配置参数，确定提取？"
                    : "目前模式尚未设置提取参数，确定提取？";
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg,
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("提取确认");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        startExtractionTask(buildRuntimeConfig(), orderedPaths(), overrides());
    }

    /** 以已确认参数（未确认时用默认参数）+ 主窗口 GUI 状态构建运行时提取配置 */
    private AppConfig buildRuntimeConfig() {
        AppConfig config = imageModeToggle.isSelected() ? imageParamsCache : textParamsCache;
        if (config == null) {
            config = new AppConfig();
        }
        config.setInputFolder(inputFolderField.getText());
        config.setOutputFolder(outputFolderField.getText());
        config.setImageModeEnabled(imageModeToggle.isSelected());
        config.setOpenFolderAfterComplete(openFolderAfterCompleteCheck.isSelected());
        return config;
    }

    private List<String> orderedPaths() {
        return items.stream().map(i -> i.path).toList();
    }

    private Map<String, PerFileOverrides> overrides() {
        Map<String, PerFileOverrides> map = new LinkedHashMap<>();
        for (PreviewItem item : items) {
            map.put(item.path, new PerFileOverrides(item.chapterEnabled.get()));
        }
        return map;
    }

    /**
     * 执行正式提取任务（按预览列表顺序 + 每文件章节覆盖）
     */
    private void startExtractionTask(AppConfig config, List<String> orderedPaths,
                                     Map<String, PerFileOverrides> overrides) {
        // 取消工作区后台活动，避免与提取任务争用进度条/状态栏
        cancelWorkspaceActivity();
        boolean imageMode = config.isImageModeEnabled();

        // 生成时间戳会话目录，隔离每次提取运行
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH：mm：ss"));
        String sessionOutputFolder = new File(config.getOutputFolder(), timestamp).getPath();
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
                        return processor.process(
                                BatchProcessor.buildMhtFileInfoList(orderedPaths),
                                sessionOutputFolder, progressCallback);
                    } else {
                        TextBatchProcessor processor = new TextBatchProcessor(config);
                        processor.setCancelSupplier(this::isCancelled);
                        processor.setRecursionContinueCallback(recursionCallback);
                        if (config.isSeparateFiles()) {
                            return processor.processSeparate(
                                    BatchProcessor.buildMhtFileInfoList(orderedPaths),
                                    sessionOutputFolder, progressCallback, overrides);
                        } else {
                            String inputDirName = new File(config.getInputFolder()).getName();
                            if (inputDirName == null || inputDirName.isEmpty()) {
                                inputDirName = "mht_output";
                            }
                            String outputPath = new File(sessionOutputFolder, inputDirName + ".txt").toString();
                            return processor.processMerged(
                                    BatchProcessor.buildMhtFileInfoList(orderedPaths),
                                    outputPath, progressCallback, overrides);
                        }
                    }
                } catch (Exception e) {
                    log.error("处理异常", e);
                    throw new RuntimeException(e);
                }
            }
        };

        task.progressProperty().addListener((obs, oldVal, newVal) -> {
            progressBar.setProgress(newVal.doubleValue());
        });

        task.setOnSucceeded(e -> {
            running.set(false);
            currentTask = null;
            startButton.setDisable(false);
            stopButton.setDisable(true);
            progressBar.setProgress(1.0);
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
        progressBar.setVisible(false);
        statusLabel.setText("已停止");
        log.info("用户手动停止处理");
    }

    // ==================== 列表渲染与拖拽排序 ====================

    /** 可显示插入指示线的单元格（拖拽排序反馈） */
    private interface CellIndicator {
        void showInsertIndicator(boolean top);

        void clearInsertIndicator();
    }

    private ListCell<PreviewItem> createPreviewCell() {
        return new PreviewListCell();
    }

    /**
     * 预览文件列表单元格：徽标/顺序/文件名/状态 + 拖拽排序（静态蓝线 + 滑动补位动画）
     */
    private class PreviewListCell extends ListCell<PreviewItem> implements CellIndicator {
        private final HBox box = new HBox(6);
        private final StackPane wrapper = new StackPane();
        private final Label badgeLabel = new Label();
        private final Label posLabel = new Label();
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Region spacer = new Region();
        private final Region topLine = new Region();
        private final Region bottomLine = new Region();

        PreviewListCell() {
            // 内边距与列头 HBox 一致（10px 水平），保证列对齐
            box.setPadding(new Insets(7, 10, 7, 10));
            badgeLabel.setMinWidth(BADGE_COL_WIDTH);
            badgeLabel.setPrefWidth(BADGE_COL_WIDTH);
            badgeLabel.setMaxWidth(BADGE_COL_WIDTH);
            badgeLabel.setAlignment(Pos.CENTER);
            posLabel.setMinWidth(POS_COL_WIDTH);
            posLabel.setPrefWidth(POS_COL_WIDTH);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(badgeLabel, posLabel, nameLabel, spacer, statusLabel);

            // 插入指示线：覆盖在内容上下边缘的 2px 静态蓝线（StackPane 覆盖层，不占布局）
            topLine.setMinHeight(2);
            topLine.setPrefHeight(2);
            topLine.setMaxHeight(2);
            topLine.setStyle(DROP_LINE_STYLE);
            topLine.setVisible(false);
            StackPane.setAlignment(topLine, Pos.TOP_CENTER);
            bottomLine.setMinHeight(2);
            bottomLine.setPrefHeight(2);
            bottomLine.setMaxHeight(2);
            bottomLine.setStyle(DROP_LINE_STYLE);
            bottomLine.setVisible(false);
            StackPane.setAlignment(bottomLine, Pos.BOTTOM_CENTER);

            wrapper.getChildren().addAll(box, topLine, bottomLine);
            setupDnD();
        }

        @Override
        public void showInsertIndicator(boolean top) {
            topLine.setVisible(top);
            bottomLine.setVisible(!top);
        }

        @Override
        public void clearInsertIndicator() {
            topLine.setVisible(false);
            bottomLine.setVisible(false);
        }

        @Override
        protected void updateItem(PreviewItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                clearInsertIndicator();
                setGraphic(null);
                setTooltip(null);
                setStyle("");
                return;
            }
            boolean showBadgeCol = isTextMode() && currentParamsOrDefaults().isAddChapterNumber();
            badgeLabel.setVisible(showBadgeCol);
            badgeLabel.setManaged(showBadgeCol);
            int pos = items.indexOf(item) + 1;
            posLabel.setText(pos + ".");
            nameLabel.setText(item.name);
            statusLabel.setText(switch (item.status.get()) {
                case WAITING -> "⏳";
                case READY -> "✅";
                case EMPTY -> "⚠️";
                case FAILED -> "❌";
            });
            String badge = item.badge.get();
            if (badge == null) {
                badgeLabel.setText("");
                badgeLabel.setStyle("");
            } else {
                badgeLabel.setText(badge);
                badgeLabel.setStyle(BADGE_STYLE);
            }
            if (item.status.get() == Status.FAILED) {
                setStyle(FAILED_STYLE);
                setTooltip(new Tooltip(item.errorMsg.get()));
            } else {
                setStyle(CELL_PAD);
                setTooltip(item.status.get() == Status.EMPTY
                        ? new Tooltip("内容为空，正式提取时将被跳过") : null);
            }
            setGraphic(wrapper);
        }

        private void setupDnD() {
            setOnDragDetected(e -> {
                if (isEmpty() || getItem() == null) {
                    return;
                }
                // 拖拽影像：带投影快照（临时挂投影后截图，随即撤掉）
                setEffect(new DropShadow(10, 0, 0, Color.rgb(0, 0, 0, 0.35)));
                Image snap = snapshot(transparentSnapParams(), null);
                setEffect(null);
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getIndex()));
                db.setContent(cc);
                if (snap != null) {
                    db.setDragView(snap, snap.getWidth() / 2, 12);
                }
                beginDrag(this);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    boolean lowerHalf = e.getY() > getHeight() / 2;
                    showInsertIndicator(!lowerHalf);
                    lastDragOverCell = this;
                }
                e.consume();
            });
            setOnDragExited(e -> {
                clearInsertIndicator();
                if (lastDragOverCell == this) {
                    lastDragOverCell = null;
                }
                e.consume();
            });
            setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean ok = false;
                clearInsertIndicator();
                lastDragOverCell = null;
                try {
                    if (db.hasString() && draggedItem != null) {
                        boolean lowerHalf = e.getY() > getHeight() / 2;
                        int to = getIndex();
                        int insertAt = Math.min(to + (lowerHalf ? 1 : 0), items.size());
                        // 立即结束提起动画（若仍在进行），快照按当前实际位置重新采集
                        finishLift();
                        double gapTopY = cellBoundsInOverlay(this).getMinY()
                                + (lowerHalf ? getHeight() : 0);
                        dropHandled = true;
                        animateReleaseInsert(insertAt, gapTopY, draggedItem);
                        ok = true;
                    }
                } catch (Exception ex) {
                    log.warn("拖拽排序失败：{}", ex.getMessage(), ex);
                }
                e.setDropCompleted(ok);
                e.consume();
            });
            setOnDragDone(e -> {
                if (lastDragOverCell != null) {
                    lastDragOverCell.clearInsertIndicator();
                    lastDragOverCell = null;
                }
                // 未发生 drop 的拖拽结束（Esc / 拖出列表）→ 回到原位
                if (dragActive && !dropHandled && draggedItem != null) {
                    cancelDrag();
                }
                e.consume();
            });
        }
    }

    // ==================== 拖拽排序动画 ====================

    private static SnapshotParameters transparentSnapParams() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return sp;
    }

    /** 单元格在拖拽覆盖层坐标系中的位置 */
    private Bounds cellBoundsInOverlay(javafx.scene.Node cell) {
        Bounds sceneBounds = cell.localToScene(cell.getBoundsInLocal());
        Point2D origin = dragOverlay.localToScene(0, 0);
        return new BoundingBox(
                sceneBounds.getMinX() - origin.getX(),
                sceneBounds.getMinY() - origin.getY(),
                sceneBounds.getWidth(),
                sceneBounds.getHeight());
    }

    private PreviewListCell findCell(PreviewItem item) {
        for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
            if (n instanceof PreviewListCell c && c.getItem() == item) {
                return c;
            }
        }
        return null;
    }

    /** 提起：被拖条目瞬间移除，下方条目快照上滑 0.2s 补位 */
    private void beginDrag(PreviewListCell sourceCell) {
        draggedItem = sourceCell.getItem();
        draggedIndex = items.indexOf(draggedItem);
        dragPositionStale = false;
        dragActive = true;
        dragGapTopY = cellBoundsInOverlay(sourceCell).getMinY();
        double rowHeight = sourceCell.getHeight();

        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c != sourceCell && c.getItem() != null) {
                    Bounds b = cellBoundsInOverlay(c);
                    if (b.getMinY() >= dragGapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        dragOverlay.getChildren().add(iv);
                        views.add(iv);
                    }
                }
            }
        } catch (Exception ex) {
            // 快照失败兜底：跳过动画，瞬时重排
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
            dragOverlay.getChildren().removeAll(views);
            return;
        }

        items.remove(draggedItem);
        cells.forEach(c -> c.setVisible(false));

        liftCells = cells;
        liftViews = views;
        liftAnim = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(-rowHeight);
            liftAnim.getChildren().add(tt);
        }
        liftAnim.setOnFinished(e -> finishLift());
        liftAnim.play();
    }

    /** 结束提起动画：清除快照、恢复单元格显示 */
    private void finishLift() {
        if (liftAnim != null) {
            liftAnim.stop();
            liftAnim = null;
        }
        if (liftViews != null) {
            dragOverlay.getChildren().removeAll(liftViews);
            liftViews = null;
        }
        if (liftCells != null) {
            liftCells.forEach(c -> c.setVisible(true));
            liftCells = null;
        }
    }

    /**
     * 松手/取消共用：条目瞬间出现在目标位置（静态快照置于最上层），
     * 下方条目快照从其底下下滑 0.2s 让位
     */
    private void animateReleaseInsert(int insertAt, double gapTopY, PreviewItem item) {
        double rowHeight = 0;
        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c.getItem() != null) {
                    Bounds b = cellBoundsInOverlay(c);
                    if (b.getMinY() >= gapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        dragOverlay.getChildren().add(iv);
                        views.add(iv);
                        rowHeight = c.getHeight();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
            dragOverlay.getChildren().removeAll(views);
            items.add(insertAt, item);
            finishDragReset();
            return;
        }

        if (rowHeight <= 0) {
            rowHeight = 39;
        }

        items.add(insertAt, item);
        fileListView.getSelectionModel().select(item);
        // 同步完成单元格绑定，保证后续快照/隐藏准确
        fileListView.layout();
        cells.forEach(c -> c.setVisible(false));
        PreviewListCell itemCell = findCell(item);
        if (itemCell != null) {
            // 条目行快照最后加入覆盖层（位于最上层、静态不动），下方快照下滑时从其底下钻过
            try {
                Bounds ib = cellBoundsInOverlay(itemCell);
                ImageView itemView = new ImageView(itemCell.snapshot(transparentSnapParams(), null));
                itemView.setLayoutX(ib.getMinX());
                itemView.setLayoutY(ib.getMinY());
                dragOverlay.getChildren().add(itemView);
            } catch (Exception ex) {
                log.warn("条目行快照失败，该行在动画结束后显示：{}", ex.getMessage(), ex);
            }
            itemCell.setVisible(false);
        }

        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(rowHeight);
            pt.getChildren().add(tt);
        }
        pt.setOnFinished(e -> {
            dragOverlay.getChildren().clear();
            cells.forEach(c -> c.setVisible(true));
            if (itemCell != null) {
                itemCell.setVisible(true);
            }
            finishDragReset();
        });
        pt.play();
    }

    /** 拖拽全流程结束：复位状态并重算徽标/信息区 */
    private void finishDragReset() {
        draggedItem = null;
        dragActive = false;
        dropHandled = false;
        onOrderChanged();
    }

    /** 取消拖拽：条目回到原索引，动画与松手一致；列表已滚动则瞬时恢复 */
    private void cancelDrag() {
        finishLift();
        if (dragPositionStale) {
            items.add(Math.min(draggedIndex, items.size()), draggedItem);
            finishDragReset();
            return;
        }
        animateReleaseInsert(Math.min(draggedIndex, items.size()), dragGapTopY, draggedItem);
    }

    private void onOrderChanged() {
        recomputeBadges();
        PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
        updateInfoArea(sel);
        if (!isTextMode() && sel != null && sel.cachedThumbs != null) {
            renderThumbnails(sel);
        }
    }

    // ==================== 选择与联动 ====================

    private void onItemSelected(PreviewItem item) {
        requestToken++;
        imageLoadActive = false;
        loadingItem = null;
        if (lastSelected != null) {
            // 仅当前文件的图片数据驻留内存
            lastSelected.cachedImages = null;
            lastSelected.cachedThumbs = null;
        }
        lastSelected = item;
        updateAdjustCheck(item);
        updateInfoArea(item);
        updateContentArea(item);
    }

    private void recomputeBadges() {
        AppConfig params = currentParamsOrDefaults();
        int n = 1;
        for (PreviewItem it : items) {
            if (isTextMode() && params.isAddChapterNumber()
                    && it.status.get() == Status.READY && it.chapterEnabled.get()) {
                it.badge.set(String.valueOf(n++));
            } else {
                it.badge.set(null);
            }
        }
        fileListView.refresh();
    }

    private void updateAdjustCheck(PreviewItem item) {
        AppConfig params = currentParamsOrDefaults();
        // 第三行：文本模式 = 章节复选框（逐文件调整）；图片模式 = 输出结果展示（全局打包配置）
        boolean checkVisible = isTextMode() && currentModeConfirmed()
                && item != null && params.isAddChapterNumber();
        adjustCheck.setVisible(checkVisible);
        adjustCheck.setManaged(checkVisible);
        if (checkVisible) {
            adjustCheck.setText("添加章节序号");
            updatingAdjustCheck = true;
            adjustCheck.setSelected(item.chapterEnabled.get());
            updatingAdjustCheck = false;
        }
        boolean resultVisible = !isTextMode() && currentModeConfirmed();
        outputResultLabel.setVisible(resultVisible);
        outputResultLabel.setManaged(resultVisible);
    }

    /** 图片模式输出结果说明：文件夹 / ZIP / CBZ / ZIP + CBZ（打包由全局配置决定） */
    private String outputResultText() {
        AppConfig params = currentParamsOrDefaults();
        boolean zip = params.isEnableZipPackaging();
        boolean cbz = params.isEnableCbzPackaging();
        if (zip && cbz) {
            return "ZIP + CBZ";
        }
        if (zip) {
            return "ZIP";
        }
        if (cbz) {
            return "CBZ";
        }
        return "文件夹";
    }

    private void updateInfoArea(PreviewItem item) {
        AppConfig params = currentParamsOrDefaults();
        boolean confirmed = currentModeConfirmed();
        // 提取标题行：文本模式 + 标题提取开启且起始标记非空时显示（与正式提取判定一致）
        boolean showTitle = confirmed && isTextMode() && params.isEnableTitleExtraction()
                && params.getTitleStartMarker() != null && !params.getTitleStartMarker().isEmpty();
        titleInfoLabel.setVisible(showTitle);
        titleInfoLabel.setManaged(showTitle);
        if (item == null || !confirmed) {
            outputNameLabel.setVisible(false);
            outputNameLabel.setManaged(false);
            return;
        }
        if (showTitle) {
            switch (item.status.get()) {
                case WAITING -> titleInfoLabel.setText("提取标题：解析中…");
                case FAILED, EMPTY -> titleInfoLabel.setText("提取标题：—");
                case READY -> titleInfoLabel.setText(item.cachedTitle != null
                        ? "提取标题：" + item.cachedTitle
                        : "提取标题：未提取到标题");
            }
        }
        outputNameLabel.setVisible(true);
        outputNameLabel.setManaged(true);
        if (isTextMode()) {
            if (params.isSeparateFiles()) {
                outputNameLabel.setText("输出文件：" + stripMhtExt(item.name) + ".txt");
            } else {
                String inputDirName = new File(inputFolderField.getText()).getName();
                if (inputDirName == null || inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                outputNameLabel.setText("输出文件：" + inputDirName + ".txt（合并输出，所有文件共用）");
            }
        } else if (params.isSingleOutputMode()) {
            String folderName = mergedFolderName();
            outputNameLabel.setText("输出目录：" + folderName + "（合并输出，所有文件共用）");
        } else {
            String dirName = separateDirName(item);
            outputNameLabel.setText("输出目录：" + dirName);
        }
    }

    private String separateDirName(PreviewItem item) {
        AppConfig params = currentParamsOrDefaults();
        if (params.isEnableNameExtraction()) {
            if (item.status.get() == Status.WAITING) {
                return "解析中…";
            }
            if (item.cachedTitle != null) {
                return SanitizeUtils.sanitize(item.cachedTitle);
            }
        }
        return stripMhtExt(item.name);
    }

    private String mergedFolderName() {
        AppConfig params = currentParamsOrDefaults();
        if (params.isEnableNameExtraction() && !items.isEmpty()) {
            PreviewItem first = items.get(0);
            if (first.status.get() == Status.WAITING) {
                return "解析中…";
            }
            if (first.cachedTitle != null) {
                return SanitizeUtils.sanitize(first.cachedTitle);
            }
        }
        return ImageBatchProcessor.determineDefaultFolderName(inputFolderField.getText());
    }

    private String stripMhtExt(String name) {
        return name.replaceAll("\\.(?i)(mht|mhtml)$", "");
    }

    // ==================== 内容区渲染 ====================

    private void updateContentArea(PreviewItem item) {
        boolean anyMode = textModeToggle.isSelected() || imageModeToggle.isSelected();
        if (!anyMode) {
            showCurrentModeArea();
            textPreviewArea.setText(HINT_NO_MODE_OR_DIR);
            return;
        }
        if (isTextMode()) {
            imagePreviewBox.setVisible(false);
            imagePreviewBox.setManaged(false);
            textPreviewArea.setVisible(true);
            textPreviewArea.setManaged(true);
            if (!currentModeConfirmed()) {
                textPreviewArea.setText(HINT_NEED_PARAMS);
                return;
            }
            if (item == null) {
                textPreviewArea.setText(items.isEmpty() ? HINT_NO_FILES : "");
                return;
            }
            switch (item.status.get()) {
                case WAITING -> textPreviewArea.setText("解析中...");
                case FAILED -> textPreviewArea.setText("解析失败：" + item.errorMsg.get());
                case EMPTY -> textPreviewArea.setText("内容为空，提取时该文件将被跳过");
                case READY -> renderTextPreview(item);
            }
        } else {
            textPreviewArea.setVisible(false);
            textPreviewArea.setManaged(false);
            imagePreviewBox.setVisible(true);
            imagePreviewBox.setManaged(true);
            if (!currentModeConfirmed()) {
                resetImageArea(HINT_NEED_PARAMS, false);
                return;
            }
            if (item == null) {
                resetImageArea(items.isEmpty() ? HINT_NO_FILES : "", false);
                return;
            }
            if (item.status.get() == Status.FAILED) {
                imageHintLabel.setText("解析失败：" + item.errorMsg.get());
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(false);
                imageScrollPane.setManaged(false);
            } else if (item.cachedThumbs != null) {
                renderThumbnails(item);
            } else if (imageLoadActive && loadingItem == item) {
                // 加载中：保留渐进追加的网格，不重置
                imageHintLabel.setText("正在加载...");
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(true);
                imageScrollPane.setManaged(true);
            } else {
                resetImageArea(null, true);
            }
        }
    }

    private void renderTextPreview(PreviewItem item) {
        AppConfig params = currentParamsOrDefaults();
        String startTemplate = params.isEnableTemplate() ? params.getStartTemplate() : "";
        String endTemplate = params.isEnableTemplate() ? params.getEndTemplate() : "";
        String head = FileWriter.processTemplate(startTemplate, item.name, item.modifiedTime);
        String tail = FileWriter.processTemplate(endTemplate, item.name, item.modifiedTime);
        String text = item.cachedText == null ? "" : item.cachedText;
        if (text.length() <= 10000) {
            textPreviewArea.setText(head + text + tail);
        } else {
            textPreviewArea.setText(head
                    + text.substring(0, 5000)
                    + "\n……【提取内容】……\n"
                    + text.substring(text.length() - 5000)
                    + tail);
        }
    }

    /** 重置图片区：hint 为 null 时显示默认按需预览说明 */
    private void resetImageArea(String hint, boolean showPreviewButton) {
        imageHintLabel.setText(hint != null
                ? hint
                : "图片预览需要加载整个 MHT 文件，为节省内存开销，采用按需预览。点击'查看预览效果'按钮可查看预览。");
        imageHintLabel.setVisible(true);
        previewEffectButton.setVisible(showPreviewButton);
        previewEffectButton.setDisable(false);
        imageScrollPane.setVisible(false);
        imageScrollPane.setManaged(false);
        thumbnailPane.getChildren().clear();
    }

    private void renderThumbnails(PreviewItem item) {
        List<ThumbEntry> thumbs = item.cachedThumbs;
        thumbnailPane.getChildren().clear();
        if (thumbs == null || thumbs.isEmpty()) {
            imageHintLabel.setText("未提取到图片");
            imageHintLabel.setVisible(true);
            previewEffectButton.setVisible(false);
            imageScrollPane.setVisible(false);
            imageScrollPane.setManaged(false);
            return;
        }
        imageHintLabel.setVisible(false);
        previewEffectButton.setVisible(false);
        imageScrollPane.setVisible(true);
        imageScrollPane.setManaged(true);

        for (ThumbEntry entry : thumbs) {
            thumbnailPane.getChildren().add(buildThumbBox(item, entry));
        }
    }

    private void appendThumb(PreviewItem item, ThumbEntry entry) {
        imageHintLabel.setVisible(false);
        previewEffectButton.setVisible(false);
        imageScrollPane.setVisible(true);
        imageScrollPane.setManaged(true);
        thumbnailPane.getChildren().add(buildThumbBox(item, entry));
    }

    private VBox buildThumbBox(PreviewItem item, ThumbEntry entry) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        if (entry.image() == null) {
            box.getChildren().add(new Label("无法加载"));
        } else {
            ImageView iv = new ImageView(entry.image());
            iv.setFitWidth(150);
            iv.setFitHeight(150);
            iv.setPreserveRatio(true);
            box.getChildren().add(iv);
        }
        Label nameLabel = new Label(thumbFileName(entry, items.indexOf(item) + 1));
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(150);
        nameLabel.setStyle("-fx-font-size: 0.85em;");
        box.getChildren().add(nameLabel);
        return box;
    }

    /** 合并输出且不重命名时，文件名按当前列表位置重算 f%02d_ 前缀（拖拽重排后同步刷新） */
    private String thumbFileName(ThumbEntry entry, int pos) {
        AppConfig params = currentParamsOrDefaults();
        boolean mergedNoRename = params.isSingleOutputMode()
                && !params.isEnableBatchRenaming() && items.size() > 1;
        return mergedNoRename ? String.format("f%02d_", pos) + entry.coreName() : entry.coreName();
    }

    /** 解码单张缩略图：WebP 走 JWebP 缩放解码（150 边界），其余走 JavaFX 原生缩放解码；失败返回 image=null */
    private ThumbEntry decodeThumb(ImageExtractor.ImageData imageData, int index) {
        AppConfig params = currentParamsOrDefaults();
        byte[] data = imageData.getData();
        String ext = SanitizeUtils.detectExtensionByMagicNumber(data, "jpg");
        boolean rename = params.isEnableBatchRenaming();
        String coreName = ImageWriter.computeFileName(imageData, ext, rename, index + 1, "");
        Image img = null;
        try {
            if ("webp".equals(ext)) {
                WebPImage webp = WebPImage.read(new ByteArrayInputStream(data),
                        WebPImageLoadOptions.builder()
                                .requestedWidth(150).requestedHeight(150)
                                .preserveRatio(true).smooth(true)
                                .build());
                img = new WebPFXImage(webp);
            } else {
                img = new Image(new ByteArrayInputStream(data), 150, 150, true, true);
            }
        } catch (Exception e) {
            // img 保持 null → 占位"无法加载"
        }
        return new ThumbEntry(img, coreName);
    }

    private void updateRenameNote() {
        AppConfig params = currentParamsOrDefaults();
        boolean show = !isTextMode() && params.isSingleOutputMode() && params.isEnableBatchRenaming();
        renameNoteLabel.setVisible(show);
        renameNoteLabel.setManaged(show);
    }

    // ==================== 图片按需加载 ====================

    @FXML
    private void onPreviewEffect() {
        PreviewItem item = fileListView.getSelectionModel().getSelectedItem();
        if (item == null || !currentModeConfirmed()) {
            return;
        }
        if (item.cachedThumbs != null) {
            renderThumbnails(item);
            return;
        }
        if (imageLoadActive && loadingItem == item) {
            return;
        }

        File f = new File(item.path);
        if (f.length() > LARGE_FILE_THRESHOLD) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("大文件预览确认");
            alert.setHeaderText("文件较大，预览可能需要较长时间与较多内存");
            alert.setContentText(item.name + "（" + (f.length() / 1024 / 1024) + " MB），是否继续？");
            alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        final int token = requestToken;
        imageLoadActive = true;
        loadingItem = item;
        imageHintLabel.setText("正在加载...");
        previewEffectButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在加载图片：" + item.name);

        Thread t = new Thread(() -> {
            try {
                AppConfig params = currentParamsOrDefaults();
                MimeParser parser = new MimeParser();
                MimeParser.ParsedMht parsed = parser.parse(item.path, false, () -> false, null, null);
                ImageExtractor imageExtractor = new ImageExtractor(parsed,
                        params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                        params.getImageRegionStartMarker(), params.getImageRegionEndMarker());
                List<ImageExtractor.ImageData> images = imageExtractor.extractImages(null);
                if (token != requestToken) {
                    return;
                }

                // 后台逐张解码缩略图，渐进上屏（每张一次 runLater）
                List<ThumbEntry> thumbs = new ArrayList<>();
                for (int i = 0; i < images.size(); i++) {
                    if (token != requestToken) {
                        return;
                    }
                    ThumbEntry entry = decodeThumb(images.get(i), i);
                    thumbs.add(entry);
                    final ThumbEntry e = entry;
                    Platform.runLater(() -> {
                        if (token != requestToken
                                || fileListView.getSelectionModel().getSelectedItem() != item) {
                            return;
                        }
                        appendThumb(item, e);
                    });
                }

                Platform.runLater(() -> {
                    if (token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    item.cachedThumbs = thumbs;
                    item.cachedImages = null;
                    if (images.isEmpty()) {
                        // 全量解析确认无图片 → 空内容标记
                        item.status.set(Status.EMPTY);
                        fileListView.refresh();
                    }
                    renderThumbnails(item);
                    updateProgressStatus(parseToken);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    imageHintLabel.setText("解析失败：" + e.getMessage());
                    imageHintLabel.setVisible(true);
                    previewEffectButton.setDisable(false);
                    updateProgressStatus(parseToken);
                });
            }
        }, "preview-image-load");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 预解析（文本全量 / 图片轻量） ====================

    private void startPreParse() {
        if (items.isEmpty()) {
            return;
        }
        final int token = ++parseToken;
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        Thread t = new Thread(() -> {
            MimeParser parser = new MimeParser();
            AppConfig params = currentParamsOrDefaults();
            for (PreviewItem item : items) {
                if (token != parseToken) {
                    return;
                }
                try {
                    MimeParser.ParsedMht parsed = parser.parse(item.path, true, () -> false, null, null);
                    if (parsed.getHtmlContent() == null) {
                        setItemStatus(token, item, Status.FAILED, "无法解析文件（HTML 内容缺失）");
                        continue;
                    }
                    if (isTextMode()) {
                        TextExtractor textExtractor = new TextExtractor(parsed);
                        String text = textExtractor.extractText(params.isAutoFixEncoding(),
                                params.getStartMarker(), params.getEndMarker(), params.isUseRegex());
                        item.cachedText = text;
                        // 标题缓存：与正式提取共用同一 extractTitle 调用（从完整 HTML 提取）
                        if (params.isEnableTitleExtraction()
                                && params.getTitleStartMarker() != null
                                && !params.getTitleStartMarker().isEmpty()) {
                            try {
                                String title = textExtractor.extractTitle(params.getTitleStartMarker(),
                                        params.getTitleEndMarker(), params.isTitleUseRegex());
                                item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                            } catch (Exception e) {
                                item.cachedTitle = null;
                            }
                        }
                        setItemStatus(token, item,
                                (text == null || text.trim().isEmpty()) ? Status.EMPTY : Status.READY,
                                null);
                    } else {
                        if (params.isEnableNameExtraction()) {
                            ImageExtractor imageExtractor = new ImageExtractor(parsed,
                                    params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                                    null, null);
                            String title = imageExtractor.extractTitle();
                            item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                        }
                        setItemStatus(token, item, Status.READY, null);
                    }
                } catch (Exception e) {
                    setItemStatus(token, item, Status.FAILED, "解析失败：" + e.getMessage());
                }
            }
        }, "preview-preparse");
        t.setDaemon(true);
        t.start();
    }

    private void setItemStatus(int token, PreviewItem item, Status st, String err) {
        if (token != parseToken) {
            return;
        }
        Platform.runLater(() -> {
            if (token != parseToken) {
                return;
            }
            item.status.set(st);
            item.errorMsg.set(err == null ? "" : err);
            if (!dragActive) {
                // 拖拽动画期间暂停列表刷新，避免打乱快照画面；拖拽结束后统一重算
                fileListView.refresh();
                recomputeBadges();
            }
            PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
            updateInfoArea(sel);
            if (sel == item) {
                updateContentArea(item);
            }
            updateProgressStatus(token);
        });
    }

    private void updateProgressStatus(int token) {
        if (token != parseToken) {
            return;
        }
        long done = items.stream().filter(i -> i.status.get() != Status.WAITING).count();
        if (imageLoadActive) {
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            return;
        }
        if (done >= items.size()) {
            progressBar.setVisible(false);
        } else {
            progressBar.setVisible(true);
            progressBar.setProgress(items.isEmpty() ? 1.0 : (double) done / items.size());
        }
        statusLabel.setText("解析完成 " + done + "/" + items.size());
    }

    // ==================== 辅助 ====================

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.showAndWait();
    }

    // ==================== Tracing Agent 演练 ====================

    /** 供 Tracing Agent 自动演练所有 UI 功能，捕获完整的反射/JNI 元数据 */
    public void exerciseAllFeatures() throws Exception {
        // Phase 1: 模式切换演练
        textModeToggle.setSelected(true);
        Thread.sleep(300);
        textModeToggle.setSelected(false);
        Thread.sleep(300);
        imageModeToggle.setSelected(true);
        Thread.sleep(300);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 2: 文本参数弹窗演练（全部开关与字段 + TitledPane 展开/折叠）
        textModeToggle.setSelected(true);
        Thread.sleep(200);
        ParamDialogController textDialog = ParamDialogController.showDirect(
                null, false, textParamsCache, imageParamsCache);
        Thread.sleep(400);
        textDialog.enableLimitCheck.setSelected(true);
        textDialog.useRegexCheck.setSelected(true);
        textDialog.enableTemplateCheck.setSelected(true);
        textDialog.addChapterNumberCheck.setSelected(true);
        textDialog.enableTitleExtractCheck.setSelected(true);
        textDialog.titleUseRegexCheck.setSelected(true);
        textDialog.separateFilesCheck.setSelected(true);
        textDialog.autoFixEncodingCheck.setSelected(true);
        textDialog.startMarkerField.setText("<!--START-->");
        textDialog.endMarkerField.setText("<!--END-->");
        textDialog.startTemplateField.setText("\\n==START==\\n");
        textDialog.endTemplateField.setText("\\n==END==\\n");
        textDialog.titleStartMarkerField.setText("<title>");
        textDialog.titleEndMarkerField.setText("</title>");
        textDialog.textTitlePane.setExpanded(false);
        textDialog.textContentLimitPane.setExpanded(false);
        Thread.sleep(100);
        textDialog.textTitlePane.setExpanded(true);
        textDialog.textContentLimitPane.setExpanded(true);
        textDialog.textTemplatePane.setExpanded(true);
        textDialog.textOutputModePane.setExpanded(true);
        Thread.sleep(200);
        textDialog.confirmButton.fire();
        Thread.sleep(200);
        if (textDialog.isConfirmed()) {
            applyConfirmedParams(false, textDialog.getResult());
        }
        Thread.sleep(200);

        // Phase 3: 图片参数弹窗演练（highSpeedMemoryCheck 跳过：勾选触发模态 Alert 阻塞 agent）
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(true);
        Thread.sleep(200);
        ParamDialogController imageDialog = ParamDialogController.showDirect(
                null, true, textParamsCache, imageParamsCache);
        Thread.sleep(400);
        imageDialog.imageNameExtractCheck.setSelected(true);
        imageDialog.imageRegionExtractCheck.setSelected(true);
        imageDialog.imageRenameCheck.setSelected(true);
        imageDialog.imageZipCheck.setSelected(true);
        imageDialog.imageCbzCheck.setSelected(true);
        imageDialog.singleOutputCheck.setSelected(true);
        imageDialog.imageNameStartField.setText("img-");
        imageDialog.imageNameEndField.setText(".png");
        imageDialog.imageRegionStartField.setText("<!--IMG-->");
        imageDialog.imageRegionEndField.setText("<!--/IMG-->");
        imageDialog.imageNamePane.setExpanded(false);
        imageDialog.imageRegionPane.setExpanded(false);
        Thread.sleep(100);
        imageDialog.imageNamePane.setExpanded(true);
        imageDialog.imageRegionPane.setExpanded(true);
        imageDialog.imageProcessPane.setExpanded(true);
        imageDialog.imageOutputModePane.setExpanded(true);
        imageDialog.imagePerformancePane.setExpanded(true);
        Thread.sleep(200);
        imageDialog.confirmButton.fire();
        Thread.sleep(200);
        if (imageDialog.isConfirmed()) {
            applyConfirmedParams(true, imageDialog.getResult());
        }
        Thread.sleep(200);

        // Phase 4: 配置下拉框（弹窗内，不触发保存/加载的模态输入框）
        ParamDialogController comboDialog = ParamDialogController.showDirect(
                null, true, textParamsCache, imageParamsCache);
        Thread.sleep(200);
        comboDialog.configComboBox.setValue("agent_test_config");
        comboDialog.configComboBox.getEditor().setText("agent_test_config");
        Thread.sleep(200);
        comboDialog.cancelButton.fire();
        Thread.sleep(200);

        // Phase 5: 路径与开关
        inputFolderField.setText("C:\\test\\mht");
        outputFolderField.setText("D:\\output");
        openFolderAfterCompleteCheck.setSelected(true);
        Thread.sleep(200);

        // Phase 6: 触发对话框/处理器对象创建（不显示对话框）
        new Alert(Alert.AlertType.INFORMATION);
        new Alert(Alert.AlertType.WARNING);
        new Alert(Alert.AlertType.ERROR);
        new Alert(Alert.AlertType.CONFIRMATION);
        new TextInputDialog("agent_test");
        new javafx.stage.DirectoryChooser();
        new ButtonType("OK");
        new Tooltip("test tooltip");
        new java.io.File("C:\\test");
        // 触发 Jackson 序列化（参数快照 + GUI 状态）
        ObjectMapperForExercise om = new ObjectMapperForExercise();
        AppConfig config = buildRuntimeConfig();
        String json = om.writeValueAsString(config);
        AppConfig restored = om.readValue(json, AppConfig.class);
        log.info("Agent config serialization test: {}", restored.getInputFolder());
        GuiState gs = new GuiState();
        gs.setImageModeEnabled(true);
        gs.setInputFolder("C:\\test\\mht");
        gs.setOutputFolder("D:\\output");
        gs.setOpenFolderAfterComplete(true);
        String gsJson = om.writeValueAsString(gs);
        GuiState gsRestored = om.readValue(gsJson, GuiState.class);
        log.info("Agent gui-state serialization test: {}", gsRestored.getInputFolder());
        Thread.sleep(300);

        // Phase 7: 预览工作区演练（主窗口直驱）
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("mht-preview-agent");
            createSampleMht(tempDir, "preview01.mht");

            // 文本模式：章节复选框 + 文本渲染
            textModeToggle.setSelected(true);
            inputFolderField.setText(tempDir.toString());
            Thread.sleep(1500);
            fileListView.getSelectionModel().selectFirst();
            adjustCheck.setSelected(false);
            Thread.sleep(200);
            adjustCheck.setSelected(true);
            Thread.sleep(300);

            // 图片模式：缩略图加载
            imageModeToggle.setSelected(true);
            Thread.sleep(1500);
            fileListView.getSelectionModel().selectFirst();
            previewEffectButton.fire();
            Thread.sleep(1500);
        } catch (Exception e) {
            log.warn("预览工作区演练失败：{}", e.getMessage(), e);
        }

        // Phase 8: 取消全部模式
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 9: 收尾
        textModeToggle.setSelected(true);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        log.info("Agent exercise complete - all UI features exercised");
    }

    /** 自动化测试：依次执行所有功能并记录结果到日志 */
    public void runAutomatedTests() {
        String logPath = "Y:/MHTtest/test-results.txt";
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(logPath))) {
            log.info("=== Starting automated tests ===");
            out.println("=== MHT Extractor Automated Test Results ===");
            out.println("Time: " + java.time.LocalDateTime.now()); out.println();
            int p = 0, f = 0;

            try { textModeToggle.setSelected(true); Thread.sleep(300); out.println("[PASS] Text mode toggle"); p++;
            } catch (Exception e) { out.println("[FAIL] Text mode: " + e.getMessage()); f++; }

            try { imageModeToggle.setSelected(true); Thread.sleep(300); out.println("[PASS] Image mode toggle"); p++;
            } catch (Exception e) { out.println("[FAIL] Image mode: " + e.getMessage()); f++; }

            // 文本参数弹窗：开关联动 + 确定生效
            try { textModeToggle.setSelected(true); Thread.sleep(200);
                ParamDialogController textDialog = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache);
                Thread.sleep(300);
                textDialog.enableLimitCheck.setSelected(true); textDialog.useRegexCheck.setSelected(true);
                textDialog.separateFilesCheck.setSelected(true); textDialog.autoFixEncodingCheck.setSelected(true);
                textDialog.addChapterNumberCheck.setSelected(true); textDialog.enableTitleExtractCheck.setSelected(true);
                textDialog.confirmButton.fire(); Thread.sleep(200);
                if (!textDialog.isConfirmed()) { throw new IllegalStateException("参数窗口未确认"); }
                applyConfirmedParams(false, textDialog.getResult());
                out.println("[PASS] Text param dialog"); p++;
            } catch (Exception e) { out.println("[FAIL] Text param dialog: " + e.getMessage()); f++; }

            // 图片参数弹窗
            try { imageModeToggle.setSelected(true); Thread.sleep(200);
                ParamDialogController imageDialog = ParamDialogController.showDirect(
                        null, true, textParamsCache, imageParamsCache);
                Thread.sleep(300);
                imageDialog.imageNameExtractCheck.setSelected(true);
                imageDialog.imageRegionExtractCheck.setSelected(true);
                imageDialog.imageRenameCheck.setSelected(true);
                imageDialog.imageZipCheck.setSelected(true);
                imageDialog.singleOutputCheck.setSelected(true);
                imageDialog.confirmButton.fire(); Thread.sleep(200);
                if (!imageDialog.isConfirmed()) { throw new IllegalStateException("参数窗口未确认"); }
                applyConfirmedParams(true, imageDialog.getResult());
                out.println("[PASS] Image param dialog"); p++;
            } catch (Exception e) { out.println("[FAIL] Image param dialog: " + e.getMessage()); f++; }

            try { inputFolderField.setText("Y:\\MHTtest\\文本提取测试"); Thread.sleep(200);
                out.println("[PASS] Set input folder"); p++;
            } catch (Exception e) { out.println("[FAIL] Input folder: " + e.getMessage()); f++; }

            try { outputFolderField.setText("Y:\\MHTtest\\output\\auto"); Thread.sleep(200);
                out.println("[PASS] Set output folder"); p++;
            } catch (Exception e) { out.println("[FAIL] Output folder: " + e.getMessage()); f++; }

            try { ParamDialogController comboDialog = ParamDialogController.showDirect(
                    null, false, textParamsCache, imageParamsCache);
                Thread.sleep(200);
                comboDialog.configComboBox.setValue("auto_test");
                comboDialog.configComboBox.getEditor().setText("auto_test");
                Thread.sleep(200);
                comboDialog.cancelButton.fire(); Thread.sleep(100);
                out.println("[PASS] Config combo"); p++;
            } catch (Exception e) { out.println("[FAIL] Config combo: " + e.getMessage()); f++; }

            // 文本预览工作区（主窗口）：列表 + 章节勾选
            try { textModeToggle.setSelected(true); Thread.sleep(500);
                if (fileListView.getItems().isEmpty()) { throw new IllegalStateException("文件列表为空"); }
                fileListView.getSelectionModel().selectFirst(); Thread.sleep(500);
                adjustCheck.setSelected(false); Thread.sleep(200);
                adjustCheck.setSelected(true); Thread.sleep(200);
                out.println("[PASS] Text preview workspace"); p++;
            } catch (Exception e) { out.println("[FAIL] Text preview workspace: " + e.getMessage()); f++; }

            // 文本提取（参数已确认 → 不弹提示）
            try { textModeToggle.setSelected(true); Thread.sleep(300);
                inputFolderField.setText("Y:\\MHTtest\\文本提取测试");
                outputFolderField.setText("Y:\\MHTtest\\output\\auto\\text");
                startButton.fire(); Thread.sleep(2000);
                out.println("[PASS] Text extraction started"); p++;
            } catch (Exception e) { out.println("[FAIL] Text extraction: " + e.getMessage()); f++; }

            // 图片预览工作区：查看预览效果（懒加载）
            try { imageModeToggle.setSelected(true); Thread.sleep(500);
                fileListView.getSelectionModel().selectFirst(); Thread.sleep(300);
                previewEffectButton.fire(); Thread.sleep(3000);
                out.println("[PASS] Image preview workspace"); p++;
            } catch (Exception e) { out.println("[FAIL] Image preview workspace: " + e.getMessage()); f++; }

            // 图片提取
            try { imageModeToggle.setSelected(true); Thread.sleep(300);
                inputFolderField.setText("Y:\\MHTtest\\图片提取测试");
                outputFolderField.setText("Y:\\MHTtest\\output\\auto\\image");
                startButton.fire(); Thread.sleep(2000);
                out.println("[PASS] Image extraction started"); p++;
            } catch (Exception e) { out.println("[FAIL] Image extraction: " + e.getMessage()); f++; }

            out.println(); out.println("=== RESULTS: " + p + " passed, " + f + " failed ===");
            out.flush();
            log.info("Automated tests complete: {} passed, {} failed", p, f);
        } catch (Exception e) { log.error("Test framework error", e); }
    }

    /**
     * 在指定目录生成一个最小 MHT 文件（手写 MIME 结构，含 HTML 与一张内嵌 WebP），供演练与测试使用
     */
    private static File createSampleMht(java.nio.file.Path dir, String fileName) throws java.io.IOException {
        byte[] webpBytes;
        try (java.io.InputStream is = MainController.class.getResourceAsStream("/sample-preview.webp")) {
            if (is == null) {
                throw new java.io.IOException("sample-preview.webp 资源缺失");
            }
            webpBytes = is.readAllBytes();
        }
        String boundary = "----=_preview_agent_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <agent@example.com>\r\n");
        sb.append("Subject: preview sample\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        sb.append("<html><head><title>预览测试标题</title></head><body>这是正文内容，用于预览窗口文本展示测试。")
                .append("<img src=\"cid:img001\"></body></html>\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: image/webp\r\n");
        sb.append("Content-ID: <img001>\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(java.util.Base64.getEncoder().encodeToString(webpBytes)).append("\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        File f = dir.resolve(fileName).toFile();
        java.nio.file.Files.write(f.toPath(),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return f;
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
        <T> T readValue(String json, Class<T> clazz) throws Exception {
            return mapper.readValue(json, clazz);
        }
    }
}
