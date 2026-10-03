package com.mhtextractor.gui.controller;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.gui.component.LogTextArea;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.processor.TextBatchProcessor;
import com.mhtextractor.util.ChangeListener;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.HtmlExportBuilder;
import com.mhtextractor.util.IncrementalImport;
import com.mhtextractor.util.MhtImportPlanner;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SelectionState;
import com.mhtextractor.util.ThumbnailPixels;
import com.mhtextractor.util.UndoManager;
import com.mhtextractor.util.UnitProgressAggregator;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI 功能演练与自动化回归的宿主（会话 18 · E0.6 从 {@code MainController} 外迁）。
 *
 * <p><b>为什么外迁</b>：这约 1100 行演练/测试代码此前住在生产类里，直接读写 {@code MainController}
 * 的 private 状态 —— 构成"自锁效应"：任何降耦合的重构都会先打碎这些代码，抬高重构成本
 * （需求文档 §四 A-2）。外迁后生产类不再含测试脚手架。</p>
 *
 * <p><b>本类为什么不需要继承 MainController</b>：实测外迁块内 <b>零</b> {@code this.} 引用、
 * 不读任何实例字段 —— 它完全通过控制器公开/包级 API 驱动 GUI。这本身就是"驱动面"存在的证据，
 * 也正是 Layer B（无头驱动 API）的雏形。</p>
 *
 * <p><b>与 MainController 的耦合（P7 起）</b>：本类<b>不再</b>依赖 {@code MainController} 的包级成员。
 * P7 之前它靠 44 处 {@code test-support} 标记的包级成员（可变状态 + 查询 + 内部方法）驱动 GUI；
 * 那些标记已全部摘除（{@code ArchitectureTest.testSupportSurfaceDoesNotGrow} 的上限随之归零），
 * 本类改为经 <b>{@link com.mhtextractor.gui.acceptance.MainViewTestBridge}</b>（测试源集的反射桥）
 * 访问同一批成员 —— 断言一条没少，生产类的封装全部收回。</p>
 *
 * <p><b>入口</b>：测试源集的 {@code com.mhtextractor.gui.acceptance.TestMain} 的
 * {@code -Dagent.exit.delay}（演练，调 {@code exerciseAllFeatures()}）与
 * {@code -Dtest.mode=true}（自动化回归，调 {@code runAutomatedTests()}）两个特殊模式。
 * P7 之前这两个开关由生产类 {@code App} 读取，现已从生产启动代码里彻底移除。</p>
 *
 * <p><b>⚠️ 本类里的中文分两类，处理方式不同（2026-09-27 明确）</b>：
 * <ul>
 *   <li><b>夹具数据</b>（导入来源名、组名、生成的 MHT 正文与标题、资源缺失提示）—— 它们会被
 *       {@code MainController} 打进<b>界面日志区</b>与预览区，属用户可见文案，因此一律取
 *       {@code test.*} bundle 键（{@code test.autoReimport} / {@code test.autoGroup} /
 *       {@code test.demoGroup} / {@code test.autoButtonState} / {@code test.previewHtml} /
 *       {@code test.samplePreviewMissing}）。曾漏接线，导致英文运行的日志里出现
 *       "导入开始：来源=自动化重复导入"。</li>
 *   <li><b>断言失败消息</b>（{@code "组名编辑失败"} 等）—— 只写 {@code logs/test-results.txt}，
 *       <b>从不进日志区</b>，属测试诊断而非产品文案，故保持中文、不入 bundle
 *       （与 CLAUDE.md「测试/演练代码不国际化」一致）。</li>
 * </ul>
 * 判定口径：<b>这段文字会不会出现在用户看得见的地方</b>；会 → 走 bundle。</p>
 */
@lombok.extern.slf4j.Slf4j
public final class FeatureExercise {

    /** 被驱动的控制器实例（组合而非继承：不改动 MainController 的构造器可见性） */
    private final MainController mc;

    /**
     * P7（测试面收口）后，本类访问 {@code MainController} 内部状态与方法的<b>唯一</b>通道。
     *
     * <p>此前这些成员为"同包测试夹具"从 {@code private} 放宽成包级（共 44 处
     * {@code /* test-support *&#47;} 标记）。P7 把可见性全部收回，测试侧改为经
     * {@link com.mhtextractor.gui.acceptance.MainViewTestBridge} 反射访问 ——
     * 生产控制器不再为测试开放状态面，而 31 项 UI 断言覆盖的行为一字未改。</p>
     */
    private final com.mhtextractor.gui.acceptance.MainViewTestBridge.View view;

    public FeatureExercise(MainController mc) {
        this.mc = mc;
        this.view = new com.mhtextractor.gui.acceptance.MainViewTestBridge.View(mc);
    }

    // ===== P7：控件读取全部走 view（反射桥），不再要求 @FXML 字段放宽可见性 =====

    private javafx.scene.control.ListView<Object> listView() {
        return view.fileListView();
    }

    private javafx.scene.control.CheckBox adjustCheck() {
        return view.adjustCheck();
    }

    private javafx.scene.control.Button previewEffectButton() {
        return view.previewEffectButton();
    }

    // ===== 按 fx:id 定位节点（步骤 1 阶段 C；P7 起不再要求 @FXML 字段有可见性） =====
    //
    // 历史：直接引用 mc.<fxmlField> 要求该字段在 MainController 中保持包级可见（即必须带
    // test-support 标记）。改用 Node.lookup("#fx:id") 后字段可收回 private，暴露面随之下降。
    // 实测（.claude-work 探针）：42 个 fx:id 中 41 个可解析，唯一例外 modeToggleGroup 是
    // ToggleGroup 而非 Node —— 它不在本类使用；且 lookup 对 visible=false / managed=false /
    // disabled=true 的节点同样有效。
    // P7：唯一改动是取 Scene 的那一步改走 listView()（经反射桥拿 ListView），
    // 定位本身仍是 lookup("#fx:id")，语义与实测结论都不变。

    /** 按 fx:id 定位节点。找不到即抛异常（不静默返回 null，避免把"节点没了"伪装成断言失败）。 */
    private javafx.scene.Node node(String fxId) {
        // MainController 是普通 FXML 控制器（不是 Node），本身没有 getScene()；
        // 借一个必然挂进场景图的控件取 Scene。见 docs/handoff/H21.md §四。
        javafx.scene.Scene sc = listView().getScene();
        if (sc == null) {
            throw new IllegalStateException("场景尚未建立，无法按 fx:id 定位节点: " + fxId);
        }
        javafx.scene.Node n = sc.getRoot().lookup("#" + fxId);
        if (n == null) {
            throw new IllegalStateException("按 fx:id 未找到节点: #" + fxId);
        }
        return n;
    }

    /** 需要具体控件类型时用这个：把 lookup 结果按类型校验后返回。 */
    private <T> T node(String fxId, Class<T> type) {
        javafx.scene.Node n = node(fxId);
        if (!type.isInstance(n)) {
            throw new IllegalStateException("#" + fxId + " 期望 " + type.getSimpleName()
                    + "，实际 " + n.getClass().getName());
        }
        return type.cast(n);
    }

    private javafx.scene.control.ToggleButton textModeToggle() { return node("textModeToggle", javafx.scene.control.ToggleButton.class); }
    private javafx.scene.control.ToggleButton imageModeToggle() { return node("imageModeToggle", javafx.scene.control.ToggleButton.class); }
    private javafx.scene.control.TextField inputFolderField() { return node("inputFolderField", javafx.scene.control.TextField.class); }
    private javafx.scene.control.TextField outputFolderField() { return node("outputFolderField", javafx.scene.control.TextField.class); }
    private javafx.scene.control.CheckBox selectAllCheck() { return node("selectAllCheck", javafx.scene.control.CheckBox.class); }
    private javafx.scene.control.Button removeButton() { return node("removeButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.Button clearParamsButton() { return node("clearParamsButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.Button undoButton() { return node("undoButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.Button groupButton() { return node("groupButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.Button ungroupButton() { return node("ungroupButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.Button applyGlobalButton() { return node("applyGlobalButton", javafx.scene.control.Button.class); }
    private javafx.scene.control.TextField groupNameField() { return node("groupNameField", javafx.scene.control.TextField.class); }
    private javafx.scene.control.ProgressBar progressBar() { return node("progressBar", javafx.scene.control.ProgressBar.class); }
    private javafx.scene.control.Label statusLabel() { return node("statusLabel", javafx.scene.control.Label.class); }
    private javafx.scene.control.Button startButton() { return node("startButton", javafx.scene.control.Button.class); }
    private atlantafx.base.controls.ToggleSwitch openFolderAfterCompleteCheck() { return node("openFolderAfterCompleteCheck", atlantafx.base.controls.ToggleSwitch.class); }

public void exerciseAllFeatures() throws Exception {
    // Phase 0: 清场——移除启动时 GUI 状态恢复导入的既有文件并重置撤销栈，保证后续演练脚本可预测
    if (!this.view.dataOrderIsEmpty()) {
        this.view.clearDataOrder();
        this.view.clearUndoStack();
        this.view.refreshAfterListChange();
    }
    // 统一导入为异步：等待启动时可能在途的导入收尾，再二次清场（防 applyImportPlan 回填列表）
    waitForImportDone();
    if (!this.view.dataOrderIsEmpty()) {
        this.view.clearDataOrder();
        this.view.clearUndoStack();
        this.view.refreshAfterListChange();
    }

    // Phase 1: 模式切换演练
    textModeToggle().setSelected(true);
    Thread.sleep(300);
    textModeToggle().setSelected(false);
    Thread.sleep(300);
    imageModeToggle().setSelected(true);
    Thread.sleep(300);
    imageModeToggle().setSelected(false);
    Thread.sleep(300);

    // Phase 2: 文本参数弹窗演练（全部开关与字段 + TitledPane 展开/折叠）
    textModeToggle().setSelected(true);
    Thread.sleep(200);
    ParamDialogController textDialog = ParamDialogController.showDirect(
            null, false, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
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
        this.view.applyConfirmedParams(false, textDialog.getResult());
    }
    Thread.sleep(200);

    // Phase 3: 图片参数弹窗演练（highSpeedMemoryCheck 跳过：勾选会改变提取路径——高速内存预加载，非演练范围；
    //   会话 19 起该确认已是窗口内非阻塞卡片，"模态 Alert 阻塞 agent" 已不是跳过理由）
    textModeToggle().setSelected(false);
    imageModeToggle().setSelected(true);
    Thread.sleep(200);
    ParamDialogController imageDialog = ParamDialogController.showDirect(
            null, true, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
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
        this.view.applyConfirmedParams(true, imageDialog.getResult());
    }
    Thread.sleep(200);

    // Phase 4: 配置下拉框（弹窗内，不触发保存/加载的模态输入框）
    ParamDialogController comboDialog = ParamDialogController.showDirect(
            null, true, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
    Thread.sleep(200);
    comboDialog.configComboBox.setValue("agent_test_config");
    comboDialog.configComboBox.getEditor().setText("agent_test_config");
    Thread.sleep(200);
    comboDialog.cancelButton.fire();
    Thread.sleep(200);

    // Phase 5: 路径与开关
    inputFolderField().setText("C:\\test\\mht");
    outputFolderField().setText("D:\\output");
    openFolderAfterCompleteCheck().setSelected(true);
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
    AppConfig config = this.view.buildRuntimeConfig();
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
        textModeToggle().setSelected(true);
        inputFolderField().setText(tempDir.toString());
        Thread.sleep(1500);
        listView().getSelectionModel().selectFirst();
        adjustCheck().setSelected(false);
        Thread.sleep(200);
        adjustCheck().setSelected(true);
        Thread.sleep(300);

        // 图片模式：缩略图加载
        imageModeToggle().setSelected(true);
        Thread.sleep(1500);
        listView().getSelectionModel().selectFirst();
        previewEffectButton().fire();
        Thread.sleep(1500);
    } catch (Exception e) {
        log.warn("预览工作区演练失败：{}", e.getMessage(), e);
    }

    // Phase 8: 增量导入 + 复选框 + 移除 + 撤销（阶段 1–3）
    try {
        java.nio.file.Path importDir = java.nio.file.Files.createTempDirectory("mht-import-agent");
        createSampleMht(importDir, "import01.mht");
        createSampleMht(importDir, "import02.mht");
        textModeToggle().setSelected(true);
        Thread.sleep(200);
        inputFolderField().setText(importDir.toString());
        Thread.sleep(1200);
        // 增量导入：新增第三个文件后再次导入同目录 → 仅新增 1 个（IMPORT 入撤销栈）
        createSampleMht(importDir, "import03.mht");
        this.view.importFromFolder(importDir.toString());
        Thread.sleep(1200);
        // 背景色三态之"无生效参数"（NONE）：临时清空全局缓存走渲染路径后恢复
        AppConfig savedTextCache = this.view.textParamsCache();
        this.view.textParamsCache(null);
        listView().refresh();
        Thread.sleep(200);
        this.view.textParamsCache(savedTextCache);
        listView().refresh();
        Thread.sleep(200);
        // 列头全选/全不选（三态联动）
        selectAllCheck().fire();
        Thread.sleep(200);
        selectAllCheck().fire();
        Thread.sleep(200);
        // 部分勾选 → 移除（不弹确认框）→ 撤销移除 → 撤销两次增量导入
        List<PreviewItem> vis = this.view.allVisibleItems();
        if (!vis.isEmpty()) {
            vis.get(0).setSelected(true);
            Thread.sleep(200);
            removeButton().fire();
            Thread.sleep(300);
            undoButton().fire();
            Thread.sleep(300);
            undoButton().fire();
            Thread.sleep(300);
            undoButton().fire();
            Thread.sleep(300);
        }
    } catch (Exception e) {
        log.warn("阶段 1–3 演练失败：{}", e.getMessage(), e);
    }

    // Phase 9: 合并为组 + 组名编辑 + 组参数 + 拆分组 + 撤销（阶段 4–6）
    try {
        java.nio.file.Path groupDir = java.nio.file.Files.createTempDirectory("mht-group-agent");
        for (int i = 1; i <= 4; i++) {
            createSampleMht(groupDir, "g0" + i + ".mht");
        }
        inputFolderField().setText(groupDir.toString());
        Thread.sleep(1500);
        List<PreviewItem> vis = this.view.allVisibleItems();
        if (vis.size() >= 4) {
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            vis.get(0).setSelected(true);
            vis.get(1).setSelected(true);
            groupButton().fire();
            Thread.sleep(400);
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            List<PreviewItem> tops = this.view.allVisibleItems().stream()
                    .filter(i -> i.parentGroup == null).toList();
            tops.get(0).setSelected(true);
            tops.get(1).setSelected(true);
            groupButton().fire();
            Thread.sleep(400);
            List<GroupItem<PreviewItem>> groups = this.view.topLevelGroups();
            if (groups.size() == 2) {
                GroupItem<PreviewItem> g1 = groups.get(0);
                GroupItem<PreviewItem> g2 = groups.get(1);
                // 折叠/展开动画演练：触发后立即快进完成（演练运行于 FX 线程，sleep 会阻塞 pulse
                // 使 Timeline 无法推进；动画真实推进由临时 JUnit 测试与人工走查验证）
                this.view.toggleGroupExpanded(g1);
                this.view.finishExpandAnim();
                this.view.toggleGroupExpanded(g1);
                this.view.finishExpandAnim();
                Thread.sleep(300);
                // 组名编辑：g2 改成与 g1 重名 → 自动追加序号（R7）
                listView().getSelectionModel().select(g1);
                Thread.sleep(300);
                groupNameField().setText(I18n.get("test.demoGroup"));
                Thread.sleep(300);
                listView().getSelectionModel().select(g2);
                Thread.sleep(300);
                groupNameField().setText(I18n.get("test.demoGroup"));
                Thread.sleep(300);
                // 组参数（文本模式）：g1 确认独立参数 → 深绿背景 + 组内重解析
                ParamDialogController groupDialog = ParamDialogController.showDirect(
                        null, false, this.view.textParamsCache(), this.view.imageParamsCache(),
                        new ParamScope.GroupTarget(g1));
                Thread.sleep(300);
                groupDialog.enableTitleExtractCheck.setSelected(true);
                groupDialog.enableTemplateCheck.setSelected(false);
                groupDialog.confirmButton.fire();
                Thread.sleep(300);
                if (groupDialog.isConfirmed()) {
                    this.view.applyGroupParams(g1, false, groupDialog.getResult());
                }
                listView().refresh();
                Thread.sleep(400);
                // 拆分组 + 撤销拆分（组名/参数恢复）→ 撤销组参数（PARAM_SET）→ 撤销两次建组
                listView().getSelectionModel().select(g1);
                Thread.sleep(200);
                ungroupButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
            }
        }
    } catch (Exception e) {
        log.warn("阶段 4–6 演练失败：{}", e.getMessage(), e);
    }

    // Phase 10: 拖拽（performDrop 程序化驱动，覆盖组内排序/跨组/出组/组行移动/两组合并/非法落点，阶段 7）
    try {
        List<PreviewItem> vis = this.view.allVisibleItems();
        if (vis.size() >= 4) {
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            vis.get(0).setSelected(true);
            vis.get(1).setSelected(true);
            groupButton().fire();
            Thread.sleep(400);
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            List<PreviewItem> tops = this.view.allVisibleItems().stream()
                    .filter(i -> i.parentGroup == null).toList();
            tops.get(0).setSelected(true);
            tops.get(1).setSelected(true);
            groupButton().fire();
            Thread.sleep(400);
            List<GroupItem<PreviewItem>> groups = this.view.topLevelGroups();
            if (groups.size() == 2) {
                GroupItem<PreviewItem> g1 = groups.get(0);
                GroupItem<PreviewItem> g2 = groups.get(1);
                // 组内排序：g1 第二个成员拖到第一个成员之前
                this.view.performDrop(this.view.visibleMembersOf(g1).get(1), this.view.visibleMembersOf(g1).get(0),
                        DropResolver.Zone.TOP);
                this.view.rebuildDisplay();
                Thread.sleep(300);
                // 跨组入组末尾：g1 第一个成员拖到 g2 条目 INTO
                this.view.performDrop(this.view.visibleMembersOf(g1).get(0), g2, DropResolver.Zone.INTO);
                this.view.rebuildDisplay();
                Thread.sleep(300);
                // 出组到顶层：g2 一个成员拖到 g1 组行下方空隙
                this.view.performDrop(this.view.visibleMembersOf(g2).get(0), g1, DropResolver.Zone.BOTTOM);
                this.view.rebuildDisplay();
                Thread.sleep(300);
                // 组行移动：g2 拖到 g1 之前
                this.view.performDrop(g2, g1, DropResolver.Zone.TOP);
                this.view.rebuildDisplay();
                Thread.sleep(300);
                // 两组合并：g2 拖到 g1 条目 INTO
                this.view.performDrop(g2, g1, DropResolver.Zone.INTO);
                this.view.rebuildDisplay();
                Thread.sleep(300);
                // 非法组合：组拖到自身成员行 → 无操作
                this.view.performDrop(g1, this.view.visibleMembersOf(g1).get(0), DropResolver.Zone.TOP);
                this.view.rebuildDisplay();
                Thread.sleep(200);
                // 撤销组合并 → 撤销组行移动 → 撤销出组 → 撤销入组 → 撤销组内排序
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
                undoButton().fire();
                Thread.sleep(300);
            }
        }
    } catch (Exception e) {
        log.warn("阶段 7 演练失败：{}", e.getMessage(), e);
    }

    // Phase 11: 输出单元拆分（阶段 8）：多来源未分组按 sourceFolder 分桶 + 各组独立单元
    try {
        java.nio.file.Path srcDir2 = java.nio.file.Files.createTempDirectory("mht-source2-agent");
        createSampleMht(srcDir2, "s01.mht");
        createSampleMht(srcDir2, "s02.mht");
        this.view.importFromFolder(srcDir2.toString());
        Thread.sleep(1200);
        String sessionDir = new File(System.getProperty("java.io.tmpdir"), "mht-agent-session").getPath();
        List<OutputUnitPlanner.OutputUnit> textUnits = OutputUnitPlanner.plan(
                this.view.dataOrder(), this.view.itemView(), false, this.view.textParamsCache(), this.view.imageParamsCache(), sessionDir);
        log.info("Agent 文本输出单元计划：{}",
                textUnits.stream().map(u -> u.label() + "(" + u.fileCount() + ")").toList());
        List<OutputUnitPlanner.OutputUnit> imageUnits = OutputUnitPlanner.plan(
                this.view.dataOrder(), this.view.itemView(), true, this.view.textParamsCache(), this.view.imageParamsCache(), sessionDir);
        log.info("Agent 图片输出单元计划：{}",
                imageUnits.stream().map(u -> u.label() + "(" + u.fileCount() + ")").toList());
        UnitProgressAggregator aggregator = new UnitProgressAggregator(
                textUnits.stream().map(OutputUnitPlanner.OutputUnit::fileCount).toList());
        log.info("Agent 进度聚合 totalFiles={}", aggregator.totalFiles());
        Thread.sleep(200);
    } catch (Exception e) {
        log.warn("阶段 8 演练失败：{}", e.getMessage(), e);
    }

    // Phase 12: 取消全部模式
    textModeToggle().setSelected(false);
    imageModeToggle().setSelected(false);
    Thread.sleep(300);

    // Phase 13: 收尾
    textModeToggle().setSelected(true);
    imageModeToggle().setSelected(false);
    Thread.sleep(300);

    // Phase 14: 导入相关（统一导入改造）：高亮样式类 + 盘符根目录判定（不真正扫描盘符）
    this.view.updateImportDropHighlight(true);
    Thread.sleep(100);
    this.view.clearImportDropHighlight();
    MhtImportPlanner.findDriveRoots(List.of("D:\\", "C:\\test"));
    MhtImportPlanner.scanner(List.of("C:\\test"), MhtImportPlanner.DEFAULT_MAX_DEPTH,
            MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES);
    Thread.sleep(100);

    log.info("Agent exercise complete - all UI features exercised");
}

/** 测试专用：在 FX 线程上排空事件队列（进入嵌套事件循环，处理完当前已排队回调后返回）。
 *  runAutomatedTests 本身运行在 FX 线程，普通 sleep 会阻塞事件回调（如提取 onSucceeded）无法执行 */
private void pumpFxEvents() {
    Object key = new Object();
    Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
    Platform.enterNestedEventLoop(key);
}

/** 测试专用：等待在途的统一导入收尾（统一导入为后台异步，断言列表状态前必须先排空） */
private void waitForImportDone() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15000;
    while (this.view.isImporting() && System.currentTimeMillis() < deadline) {
        pumpFxEvents();
        Thread.sleep(20);
    }
}

/** 自动化测试：依次执行所有功能并记录结果到文件（test.mode=true 触发）。
 *  返回 true = 至少 1 项断言执行且 0 失败；false = 框架级错误或存在失败断言（供 App 映射为进程退出码）。
 *  结果写 logs/test-results.txt：logs 目录由本方法自行创建（不再依赖 logback 启动时创建 logs 的副作用——
 *  曾实测：logs 建不出来时 FileWriter 抛 FileNotFoundException，被末尾 catch 吞掉后方法"正常"返回，
 *  App 侧 success=true → exit 0，~2.4 秒静默退出、零产物零堆栈（假绿）。 */
public boolean runAutomatedTests() {
    java.io.File resultsFile = new java.io.File("logs", "test-results.txt");
    try {
        java.nio.file.Files.createDirectories(resultsFile.getParentFile().toPath());
    } catch (Exception e) {
        System.err.println("[test-mode] FATAL: cannot create logs dir: " + e);
        return false;
    }
    System.err.println("[test-mode] runAutomatedTests starting; results -> " + resultsFile.getAbsolutePath());
    int p = 0, f = 0;
    try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(resultsFile))) {
        log.info("=== Starting automated tests ===");
        out.println("=== MHT Extractor Automated Test Results ===");
        out.println("Time: " + java.time.LocalDateTime.now()); out.println();

        // 清场：移除启动时 GUI 状态恢复导入的既有文件并重置撤销栈（保证断言可预测）
        if (!this.view.dataOrderIsEmpty()) {
            this.view.clearDataOrder();
            this.view.clearUndoStack();
            this.view.refreshAfterListChange();
        }
        // 统一导入为异步：等待启动时可能在途的导入收尾，再二次清场（防 applyImportPlan 回填列表）
        waitForImportDone();
        if (!this.view.dataOrderIsEmpty()) {
            this.view.clearDataOrder();
            this.view.clearUndoStack();
            this.view.refreshAfterListChange();
        }

        // 临时样例目录（不依赖固定测试数据路径）
        java.nio.file.Path textDir = java.nio.file.Files.createTempDirectory("mht-auto-text");
        java.nio.file.Path imageDir = java.nio.file.Files.createTempDirectory("mht-auto-image");
        createSampleMht(textDir, "t01.mht");
        createSampleMht(textDir, "t02.mht");
        createSampleMht(imageDir, "i01.mht");
        createSampleMht(imageDir, "i02.mht");

        try { textModeToggle().setSelected(true); Thread.sleep(300); out.println("[PASS] Text mode toggle"); p++;
        } catch (Exception e) { out.println("[FAIL] Text mode: " + e.getMessage()); f++; }

        try { imageModeToggle().setSelected(true); Thread.sleep(300); out.println("[PASS] Image mode toggle"); p++;
        } catch (Exception e) { out.println("[FAIL] Image mode: " + e.getMessage()); f++; }

        // 文本参数弹窗：开关联动 + 确定生效
        try { textModeToggle().setSelected(true); Thread.sleep(200);
            ParamDialogController textDialog = ParamDialogController.showDirect(
                    null, false, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
            Thread.sleep(300);
            textDialog.enableLimitCheck.setSelected(true); textDialog.useRegexCheck.setSelected(true);
            textDialog.separateFilesCheck.setSelected(true); textDialog.autoFixEncodingCheck.setSelected(true);
            textDialog.addChapterNumberCheck.setSelected(true); textDialog.enableTitleExtractCheck.setSelected(true);
            textDialog.confirmButton.fire(); Thread.sleep(200);
            if (!textDialog.isConfirmed()) { throw new IllegalStateException("参数窗口未确认"); }
            this.view.applyConfirmedParams(false, textDialog.getResult());
            out.println("[PASS] Text param dialog"); p++;
        } catch (Exception e) { out.println("[FAIL] Text param dialog: " + e.getMessage()); f++; }

        // 图片参数弹窗
        try { imageModeToggle().setSelected(true); Thread.sleep(200);
            ParamDialogController imageDialog = ParamDialogController.showDirect(
                    null, true, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
            Thread.sleep(300);
            imageDialog.imageNameExtractCheck.setSelected(true);
            imageDialog.imageRegionExtractCheck.setSelected(true);
            imageDialog.imageRenameCheck.setSelected(true);
            imageDialog.imageZipCheck.setSelected(true);
            imageDialog.singleOutputCheck.setSelected(true);
            imageDialog.confirmButton.fire(); Thread.sleep(200);
            if (!imageDialog.isConfirmed()) { throw new IllegalStateException("参数窗口未确认"); }
            this.view.applyConfirmedParams(true, imageDialog.getResult());
            out.println("[PASS] Image param dialog"); p++;
        } catch (Exception e) { out.println("[FAIL] Image param dialog: " + e.getMessage()); f++; }

        // 增量导入：先导入 2 个，追加 1 个后再次导入 → 仅新增 1 个
        try { textModeToggle().setSelected(true); Thread.sleep(200);
            inputFolderField().setText(textDir.toString()); Thread.sleep(1200);
            waitForImportDone();
            long before = this.view.visibleCount();
            createSampleMht(textDir, "t03.mht");
            this.view.importFromFolder(textDir.toString()); Thread.sleep(500);
            waitForImportDone();
            if (this.view.visibleCount() != before + 1) {
                throw new IllegalStateException("增量导入数量不符：" + before + " -> " + this.view.visibleCount());
            }
            out.println("[PASS] Incremental import"); p++;
        } catch (Exception e) { out.println("[FAIL] Incremental import: " + e.getMessage()); f++; }

        // 撤销导入后同目录可重新导入（回归：撤销导入曾清空列表且无法重新导入）
        try { long before = this.view.visibleCount();
            undoButton().fire(); Thread.sleep(400);
            if (this.view.visibleCount() != before - 1) {
                throw new IllegalStateException("撤销导入数量不符：" + before + " -> " + this.view.visibleCount());
            }
            this.view.importFromFolder(textDir.toString()); Thread.sleep(500);
            waitForImportDone();
            if (this.view.visibleCount() != before) {
                throw new IllegalStateException("撤销导入后重新导入失败：" + this.view.visibleCount());
            }
            out.println("[PASS] Undo import + re-import"); p++;
        } catch (Exception e) { out.println("[FAIL] Undo import re-import: " + e.getMessage()); f++; }

        // 统一导入：递归 + 分层建组 + 根层平铺 + 组名唯一化 + 隐藏目录跳过
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mht-auto-import");
            java.nio.file.Path sub1 = java.nio.file.Files.createDirectories(root.resolve("sub1"));
            java.nio.file.Path sub2 = java.nio.file.Files.createDirectories(root.resolve("sub2"));
            java.nio.file.Path sub2deep = java.nio.file.Files.createDirectories(sub2.resolve("sub2"));  // 同名深层
            java.nio.file.Path hidden = java.nio.file.Files.createDirectories(root.resolve(".hidden"));
            java.nio.file.Path emptyLayer = java.nio.file.Files.createDirectories(root.resolve("noMhtHere"));
            java.nio.file.Path deeper = java.nio.file.Files.createDirectories(emptyLayer.resolve("deeper"));

            createSampleMht(root, "r1.mht");            // 根层 → 顶层平铺
            createSampleMht(root, "r2.mht");            // 根层
            createSampleMht(sub1, "s1.mht");            // 组 sub1
            createSampleMht(sub2, "s2.mht");            // 组 sub2
            createSampleMht(sub2deep, "s3.mht");        // 组 sub2(2)（同名 → 唯一化）
            createSampleMht(hidden, "h1.mht");          // 应跳过
            createSampleMht(deeper, "d1.mht");          // 组 deeper（noMhtHere 本层无 MHT → 不建组）
            java.nio.file.Files.writeString(root.resolve("note.txt"), "x");  // 非 MHT → 忽略

            int before = (int) this.view.visibleCount();
            int groupsBefore = this.view.topLevelGroups().size();

            // 阶段一：纯扫描断言（不走 GUI 事件）
            MhtImportPlanner.Scanner sc = MhtImportPlanner.scanner(
                    List.of(root.toString()), MhtImportPlanner.DEFAULT_MAX_DEPTH,
                    MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES);
            MhtImportPlanner.Plan plan = sc.nextRound();
            long groupBatches = plan.batches().stream()
                    .filter(b -> b instanceof MhtImportPlanner.GroupBatch).count();
            if (groupBatches != 4) throw new IllegalStateException("组批次数不符：" + groupBatches);   // sub1,sub2,sub2(2),deeper
            if (plan.ignoredNonMht() != 1) throw new IllegalStateException("非 MHT 计数不符");
            if (plan.hiddenDirs() != 1) throw new IllegalStateException("隐藏目录计数不符");

            // 阶段二：应用（与生产同一实现）
            this.view.applyImportPlan(plan); Thread.sleep(600);
            if (this.view.visibleCount() != before + 6) throw new IllegalStateException("导入总数不符：" + this.view.visibleCount()); // 2 根 + 4 组内
            if (this.view.topLevelGroups().size() != groupsBefore + 4) throw new IllegalStateException("组数不符");
            boolean noMhtGroup = this.view.topLevelGroups().stream().anyMatch(g -> "noMhtHere".equals(g.getName()));
            if (noMhtGroup) throw new IllegalStateException("本层无 MHT 的目录不应建组");
            if (this.view.topLevelGroups().stream().noneMatch(g -> "sub2(2)".equals(g.getName()))) {
                throw new IllegalStateException("同名文件夹组名未唯一化");
            }
            out.println("[PASS] Unified import: recursion + layering + dedupe naming"); p++;
        } catch (Exception e) { out.println("[FAIL] Unified import: " + e.getMessage()); f++; }

        // 统一导入：一次撤销撤整批（含自动创建的组）
        try {
            int itemsBefore = (int) this.view.visibleCount();
            int groupsBefore = this.view.topLevelGroups().size();
            undoButton().fire(); Thread.sleep(600);
            if (this.view.visibleCount() != itemsBefore - 6) throw new IllegalStateException("整批撤销项目数不符：" + this.view.visibleCount());
            if (this.view.topLevelGroups().size() != groupsBefore - 4) throw new IllegalStateException("整批撤销未带走自动创建的组");
            out.println("[PASS] Unified import: single undo removes whole batch (items + groups)"); p++;
        } catch (Exception e) { out.println("[FAIL] Unified import undo: " + e.getMessage()); f++; }

        // 统一导入：重复导入同目录无新文件 → 列表不变、不建组、不清既有参数（RISK-1）
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mht-auto-import");
            java.nio.file.Path sub1 = java.nio.file.Files.createDirectories(root.resolve("sub1"));
            createSampleMht(root, "r1.mht");
            createSampleMht(sub1, "s1.mht");
            int itemsBefore = (int) this.view.visibleCount();
            int groupsBefore = this.view.topLevelGroups().size();
            this.view.importByPaths(List.of(root.toString()), I18n.get("test.autoReimport"), false); Thread.sleep(800);
            waitForImportDone();
            if (this.view.visibleCount() != itemsBefore + 2 || this.view.topLevelGroups().size() != groupsBefore + 1) {
                throw new IllegalStateException("首次导入目录失败");
            }
            // 为导入条目设置条目级参数 → 重复导入不得清空（RISK-1 拍板）
            PreviewItem r1 = this.view.allPreviewItems().stream()
                    .filter(i -> i.path.endsWith("r1.mht")).findFirst().orElse(null);
            if (r1 == null) {
                throw new IllegalStateException("未找到导入条目");
            }
            r1.textItemParams = new AppConfig();
            this.view.importByPaths(List.of(root.toString()), I18n.get("test.autoReimport"), false); Thread.sleep(800);
            waitForImportDone();
            if (this.view.visibleCount() != itemsBefore + 2 || this.view.topLevelGroups().size() != groupsBefore + 1) {
                throw new IllegalStateException("重复导入不应改变列表");
            }
            if (r1.textItemParams == null) {
                throw new IllegalStateException("重复导入清空了既有条目级参数");
            }
            // 清场：撤销本次导入（后续测试段假设列表为 3 个顶层项目、无组）
            undoButton().fire(); Thread.sleep(600);
            out.println("[PASS] Unified import: re-import is a no-op"); p++;
        } catch (Exception e) { out.println("[FAIL] Unified import re-import: " + e.getMessage()); f++; }

        // 按钮状态：导入期间禁用 + 收尾恢复
        try {
            this.view.importPending(this.view.newImportSession(List.of(), I18n.get("test.autoButtonState"), false));
            this.view.updateStartButtonState();
            if (!startButton().isDisabled()) throw new IllegalStateException("导入期间开始提取应禁用");
            this.view.importPending(null);
            this.view.updateStartButtonState();
            boolean anyMode = textModeToggle().isSelected() || imageModeToggle().isSelected();
            if (startButton().isDisabled() != (!anyMode || this.view.visibleCount() == 0)) {
                throw new IllegalStateException("导入结束后开始按钮状态未按派生规则恢复");
            }
            out.println("[PASS] Import disables/restores start button"); p++;
        } catch (Exception e) { out.println("[FAIL] Import button state: " + e.getMessage()); f++; }

        // 拖拽高亮：硬复位
        try {
            this.view.importDragDepth(3); this.view.updateImportDropHighlight(true);
            this.view.clearImportDropHighlight();
            if (listView().getStyleClass().contains("list-import-hover")) {
                throw new IllegalStateException("拖拽高亮未清除");
            }
            out.println("[PASS] Drag-drop import highlight reset"); p++;
        } catch (Exception e) { out.println("[FAIL] Drag-drop highlight: " + e.getMessage()); f++; }

        // 复选框列头全选/全不选（三态联动）
        try { selectAllCheck().fire(); Thread.sleep(200);
            long sel = this.view.allVisibleItems().stream().filter(i -> i.selected).count();
            if (sel != this.view.visibleCount()) { throw new IllegalStateException("全选未生效: " + sel); }
            selectAllCheck().fire(); Thread.sleep(200);
            sel = this.view.allVisibleItems().stream().filter(i -> i.selected).count();
            if (sel != 0) { throw new IllegalStateException("全不选未生效: " + sel); }
            out.println("[PASS] Select-all checkbox tri-state"); p++;
        } catch (Exception e) { out.println("[FAIL] Select-all checkbox: " + e.getMessage()); f++; }

        // 移除 + 撤销（部分勾选不弹确认框）
        try { List<PreviewItem> vis = this.view.allVisibleItems();
            vis.get(0).setSelected(true); Thread.sleep(200);
            removeButton().fire(); Thread.sleep(300);
            if (vis.get(0).removed != true) { throw new IllegalStateException("移除未生效"); }
            undoButton().fire(); Thread.sleep(300);
            if (vis.get(0).removed) { throw new IllegalStateException("撤销移除未生效"); }
            out.println("[PASS] Remove + undo"); p++;
        } catch (Exception e) { out.println("[FAIL] Remove + undo: " + e.getMessage()); f++; }

        // 合并为组 + 组名编辑 + 组参数 + 拆分组 + 撤销
        try { List<PreviewItem> vis = this.view.allVisibleItems();
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            vis.get(0).setSelected(true); vis.get(1).setSelected(true);
            groupButton().fire(); Thread.sleep(400);
            List<GroupItem<PreviewItem>> groups = this.view.topLevelGroups();
            if (groups.size() != 1) { throw new IllegalStateException("建组失败: " + groups.size()); }
            GroupItem<PreviewItem> g = groups.get(0);
            listView().getSelectionModel().select(g); Thread.sleep(300);
            String autoGroup = I18n.get("test.autoGroup");
            groupNameField().setText(autoGroup); Thread.sleep(300);
            if (!autoGroup.equals(g.getName())) { throw new IllegalStateException("组名编辑失败"); }
            // 折叠/展开动画（触发后立即快进验证终态；FX 线程 sleep 会阻塞 pulse 动画无法推进）
            this.view.toggleGroupExpanded(g);
            this.view.finishExpandAnim();
            if (g.isExpanded()) { throw new IllegalStateException("折叠动画未生效"); }
            this.view.toggleGroupExpanded(g);
            this.view.finishExpandAnim();
            if (!g.isExpanded()) { throw new IllegalStateException("展开动画未生效"); }
            Thread.sleep(300);
            ParamDialogController groupDialog = ParamDialogController.showDirect(
                    null, false, this.view.textParamsCache(), this.view.imageParamsCache(), new ParamScope.GroupTarget(g));
            Thread.sleep(300);
            groupDialog.enableTitleExtractCheck.setSelected(true);
            groupDialog.confirmButton.fire(); Thread.sleep(300);
            if (!groupDialog.isConfirmed()) { throw new IllegalStateException("组参数窗口未确认"); }
            this.view.applyGroupParams(g, false, groupDialog.getResult()); Thread.sleep(300);
            if (g.getTextParamsCache() == null) { throw new IllegalStateException("组参数未写入"); }
            listView().getSelectionModel().select(g); Thread.sleep(200);
            ungroupButton().fire(); Thread.sleep(300);
            if (this.view.topLevelGroups().size() != 0) { throw new IllegalStateException("拆分组失败"); }
            undoButton().fire(); Thread.sleep(300);
            if (this.view.topLevelGroups().size() != 1 || !autoGroup.equals(this.view.topLevelGroups().get(0).getName())) {
                throw new IllegalStateException("撤销拆分失败（组名/参数未恢复）");
            }
            undoButton().fire(); Thread.sleep(300);   // 撤销组参数（PARAM_SET）
            if (g.getTextParamsCache() != null) {
                throw new IllegalStateException("撤销组参数失败");
            }
            out.println("[PASS] Group + rename + group params + ungroup + undo chain"); p++;
        } catch (Exception e) { out.println("[FAIL] Group ops: " + e.getMessage()); f++; }

        // 拖拽（performDrop 程序化驱动）+ 撤销；第二组用单项目建组（R9）
        try { undoButton().fire(); Thread.sleep(300);   // 撤销上一段建组，成员回顶层
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            vis.get(0).setSelected(true); vis.get(1).setSelected(true);
            groupButton().fire(); Thread.sleep(400);
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            List<PreviewItem> tops = this.view.allVisibleItems().stream()
                    .filter(i -> i.parentGroup == null).toList();
            tops.get(0).setSelected(true);
            groupButton().fire(); Thread.sleep(400);
            List<GroupItem<PreviewItem>> groups = this.view.topLevelGroups();
            if (groups.size() != 2) { throw new IllegalStateException("建第二组失败: " + groups.size()); }
            GroupItem<PreviewItem> g1 = groups.get(0); GroupItem<PreviewItem> g2 = groups.get(1);
            this.view.performDrop(this.view.visibleMembersOf(g1).get(1), this.view.visibleMembersOf(g1).get(0),
                    DropResolver.Zone.TOP); this.view.rebuildDisplay(); Thread.sleep(200);
            this.view.performDrop(this.view.visibleMembersOf(g1).get(0), g2, DropResolver.Zone.INTO);
            this.view.rebuildDisplay(); Thread.sleep(200);
            this.view.performDrop(g2, g1, DropResolver.Zone.INTO);
            this.view.rebuildDisplay(); Thread.sleep(200);
            if (this.view.topLevelGroups().size() != 1) { throw new IllegalStateException("两组合并失败"); }
            undoButton().fire(); Thread.sleep(300);   // 撤销组合并
            undoButton().fire(); Thread.sleep(300);   // 撤销入组
            undoButton().fire(); Thread.sleep(300);   // 撤销组内排序
            if (this.view.topLevelGroups().size() != 2) { throw new IllegalStateException("撤销拖拽失败"); }
            out.println("[PASS] Drag-drop (reorder/cross-group/merge) + undo"); p++;
        } catch (Exception e) { out.println("[FAIL] Drag-drop: " + e.getMessage()); f++; }

        // 条目独立参数 + 应用到全局 + 撤销链（条目独立参数需求）
        try {
            undoButton().fire(); Thread.sleep(300);   // 撤销建组（第二组）
            undoButton().fire(); Thread.sleep(300);   // 撤销建组（第一组）
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() < 2) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
            PreviewItem target = vis.get(0);
            ParamDialogController itemDialog = ParamDialogController.showDirect(
                    null, false, this.view.textParamsCache(), this.view.imageParamsCache(),
                    new ParamScope.ItemTarget<>(target, target.textItemParams, target.imageItemParams));
            itemDialog.enableTitleExtractCheck.setSelected(true);
            itemDialog.confirmButton.fire(); Thread.sleep(300);
            if (!itemDialog.isConfirmed()) { throw new IllegalStateException("条目参数窗口未确认"); }
            this.view.applyItemParams(target, false, itemDialog.getResult());
            if (target.textItemParams == null) { throw new IllegalStateException("条目参数未写入"); }
            listView().getSelectionModel().select(target); Thread.sleep(200);
            if (applyGlobalButton().isDisabled()) { throw new IllegalStateException("应用到全局按钮未启用"); }
            AppConfig globalBeforeApply = this.view.textParamsCache();
            applyGlobalButton().fire(); Thread.sleep(300);
            if (target.textItemParams != null
                    || this.view.allVisibleItems().stream().anyMatch(i -> i.textItemParams != null)) {
                throw new IllegalStateException("应用到全局未清空独立参数");
            }
            undoButton().fire(); Thread.sleep(300);   // 撤销应用到全局
            if (target.textItemParams == null || this.view.textParamsCache() != globalBeforeApply) {
                throw new IllegalStateException("撤销应用到全局未恢复");
            }
            undoButton().fire(); Thread.sleep(300);   // 撤销条目参数
            if (target.textItemParams != null) {
                throw new IllegalStateException("撤销条目参数未恢复");
            }
            out.println("[PASS] Item params + apply-global + undo chain"); p++;
        } catch (Exception e) { out.println("[FAIL] Item params undo chain: " + e.getMessage()); f++; }

        // 清除参数（清除参数功能）：按钮/菜单禁用态 + 条目清除 + 撤销恢复（拍板 Q1-Q3）
        try {
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
            textModeToggle().setSelected(true); Thread.sleep(300);
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            this.view.updateListButtons();
            PreviewItem a = vis.get(0), b = vis.get(1);
            if (!clearParamsButton().isDisabled()) { throw new IllegalStateException("无独立参数时按钮应禁用"); }
            ParamDialogController itemDlg = ParamDialogController.showDirect(
                    null, false, this.view.textParamsCache(), this.view.imageParamsCache(),
                    new ParamScope.ItemTarget<>(a, a.textItemParams, a.imageItemParams));
            itemDlg.enableTitleExtractCheck.setSelected(true);
            itemDlg.confirmButton.fire(); Thread.sleep(300);
            if (!itemDlg.isConfirmed()) { throw new IllegalStateException("条目参数窗口未确认"); }
            this.view.applyItemParams(a, false, itemDlg.getResult());
            if (a.textItemParams == null) { throw new IllegalStateException("条目参数未写入"); }
            listView().getSelectionModel().select(a); Thread.sleep(200);
            if (clearParamsButton().isDisabled()) { throw new IllegalStateException("选中带参数条目时按钮应启用"); }
            listView().getSelectionModel().select(b); this.view.updateListButtons();
            if (!clearParamsButton().isDisabled()) { throw new IllegalStateException("选中无参数条目时按钮应禁用"); }
            a.setSelected(true); b.setSelected(true); this.view.updateListButtons();
            if (clearParamsButton().isDisabled()) { throw new IllegalStateException("勾选集合含参数目标时按钮应启用"); }
            clearParamsButton().fire(); Thread.sleep(300);
            if (a.textItemParams != null) { throw new IllegalStateException("清除条目参数未生效"); }
            if (!clearParamsButton().isDisabled()) { throw new IllegalStateException("清除后按钮应禁用"); }
            this.view.allVisibleItems().forEach(i -> i.setSelected(false)); this.view.updateListButtons();
            undoButton().fire(); Thread.sleep(300);
            if (a.textItemParams == null) { throw new IllegalStateException("撤销清除参数未恢复"); }
            // 菜单启用态：空白/无参数禁用、带参数启用（启用看右键目标）
            this.view.updateContextMenuState(null);
            if (!this.view.ctxClearParams().isDisable()) { throw new IllegalStateException("空白区清除参数应禁用"); }
            this.view.updateContextMenuState(a);
            if (this.view.ctxClearParams().isDisable()) { throw new IllegalStateException("带参数条目清除参数应启用"); }
            this.view.updateContextMenuState(b);
            if (!this.view.ctxClearParams().isDisable()) { throw new IllegalStateException("无参数条目清除参数应禁用"); }
            out.println("[PASS] Clear params (item scope + enable matrix)"); p++;
        } catch (Exception e) { out.println("[FAIL] Clear params item: " + e.getMessage()); f++; }

        // 清除参数组语义：组内成员/组条目 → 清整组；混合多选（组内+组外）→ 都清；一次撤销全部恢复
        try {
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
            this.view.allVisibleItems().forEach(i -> i.setSelected(false));
            PreviewItem a = vis.get(0), b = vis.get(1), c = vis.get(2);
            b.setSelected(true); c.setSelected(true);
            groupButton().fire(); Thread.sleep(400);
            List<GroupItem<PreviewItem>> groups = this.view.topLevelGroups();
            if (groups.size() != 1) { throw new IllegalStateException("建组失败: " + groups.size()); }
            GroupItem<PreviewItem> g = groups.get(0);
            ParamDialogController groupDlg = ParamDialogController.showDirect(
                    null, false, this.view.textParamsCache(), this.view.imageParamsCache(), new ParamScope.GroupTarget(g));
            groupDlg.enableTitleExtractCheck.setSelected(true);
            groupDlg.confirmButton.fire(); Thread.sleep(300);
            if (!groupDlg.isConfirmed()) { throw new IllegalStateException("组参数窗口未确认"); }
            this.view.applyGroupParams(g, false, groupDlg.getResult());
            if (g.getTextParamsCache() == null) { throw new IllegalStateException("组参数未写入"); }
            // a（组外带参数）+ b（组内成员）混合多选 → 清 a 条目参数 + 清整组 g 参数（同组去重）
            a.setSelected(true); b.setSelected(true); this.view.updateListButtons();
            if (clearParamsButton().isDisabled()) { throw new IllegalStateException("混合多选按钮应启用"); }
            clearParamsButton().fire(); Thread.sleep(300);
            if (a.textItemParams != null) { throw new IllegalStateException("组外条目参数未清除"); }
            if (g.getTextParamsCache() != null) { throw new IllegalStateException("组参数未清除"); }
            undoButton().fire(); Thread.sleep(300);
            if (a.textItemParams == null || g.getTextParamsCache() == null) {
                throw new IllegalStateException("撤销混合清除未全部恢复");
            }
            // 清场恢复：撤销组参数、建组、条目参数，回到本段前状态
            undoButton().fire(); Thread.sleep(300);
            undoButton().fire(); Thread.sleep(300);
            undoButton().fire(); Thread.sleep(300);
            if (this.view.topLevelGroups().size() != 0 || a.textItemParams != null) {
                throw new IllegalStateException("清除参数测试段清场失败");
            }
            out.println("[PASS] Clear params (group + mixed multi-select + single undo)"); p++;
        } catch (Exception e) { out.println("[FAIL] Clear params group: " + e.getMessage()); f++; }

        // 进度条口径（修复：独立参数场景无参数条目导致进度条卡中间）——只统计有生效参数条目
        try {
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() < 2) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
            textModeToggle().setSelected(true); Thread.sleep(300);
            this.view.cancelWorkspaceActivity();   // 作废在途解析代次，保证断言确定性
            AppConfig savedGlobal = this.view.textParamsCache();
            for (PreviewItem it : this.view.allPreviewItems()) {
                it.textItemParams = null;
                it.status = PreviewItem.Status.WAITING;
            }
            PreviewItem p0 = vis.get(0);
            this.view.textParamsCache(null);               // 全局未确认
            p0.textItemParams = new AppConfig();  // 仅该条目有独立参数
            this.view.updateProgressStatus(this.view.parseToken());
            if (!progressBar().isVisible() || Math.abs(progressBar().getProgress()) > 0.001) {
                throw new IllegalStateException("无参数条目不应参与进度（期望可见且 0/1）");
            }
            if (!progressBar().getStyleClass().contains("progress-preparse")) {
                throw new IllegalStateException("预解析进度条应为浅蓝样式");
            }
            p0.status = PreviewItem.Status.READY;
            this.view.updateProgressStatus(this.view.parseToken());
            if (progressBar().isVisible()) {
                throw new IllegalStateException("有参数条目全部完成后进度条应隐藏");
            }
            if (progressBar().getStyleClass().contains("progress-preparse")) {
                throw new IllegalStateException("预解析完成后应移除浅蓝样式");
            }
            p0.textItemParams = null;
            this.view.updateProgressStatus(this.view.parseToken());
            if (progressBar().isVisible()) {
                throw new IllegalStateException("全部无参数时进度条应隐藏");
            }
            // 相邻修复：提取运行期间进度条/状态栏归提取任务独占，解析回调不得覆盖
            this.view.running().set(true);
            progressBar().setVisible(true);
            progressBar().setProgress(0.5);
            String labelBefore = statusLabel().getText();
            this.view.updateProgressStatus(this.view.parseToken());
            if (Math.abs(progressBar().getProgress() - 0.5) > 0.001
                    || !statusLabel().getText().equals(labelBefore)) {
                this.view.running().set(false);
                throw new IllegalStateException("提取期间解析回调不应覆盖进度条/状态栏");
            }
            this.view.running().set(false);
            // 恢复全局参数并重解析
            this.view.textParamsCache(savedGlobal);
            this.view.refreshAfterParamsChanged();
            Thread.sleep(500);
            out.println("[PASS] Progress bar counts only effective-param items"); p++;
        } catch (Exception e) { out.println("[FAIL] Progress bar param-scope: " + e.getMessage()); f++; }

        // 双击 toggle 勾选（回归：CLICKED 事件按键判定须用 getButton，isPrimaryButtonDown 恒为 false）
        try {
            List<PreviewItem> vis = this.view.allVisibleItems();
            PreviewItem dt = vis.get(0);
            boolean before = dt.selected;
            javafx.scene.Node targetCell = this.view.findCellNode(dt);
            if (targetCell == null) { throw new IllegalStateException("未找到条目 cell"); }
            javafx.scene.input.MouseEvent click2 = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 2,
                    false, false, false, false, false, false, false,
                    false, false, false, null);
            targetCell.fireEvent(click2); Thread.sleep(300);
            if (dt.selected == before) {
                throw new IllegalStateException("双击未切换勾选状态");
            }
            // 快速连续双击：第二次手势的 click 序列为 3、4（多击窗口内递增）→ 奇数跳过、偶数 toggle
            javafx.scene.input.MouseEvent click3 = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 3,
                    false, false, false, false, false, false, false,
                    false, false, false, null);
            targetCell.fireEvent(click3); Thread.sleep(200);
            if (dt.selected == before) {
                throw new IllegalStateException("奇数次 click 不应 toggle");
            }
            javafx.scene.input.MouseEvent click4 = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 4,
                    false, false, false, false, false, false, false,
                    false, false, false, null);
            targetCell.fireEvent(click4); Thread.sleep(300);
            if (dt.selected != before) {
                throw new IllegalStateException("偶数次 click 应 toggle");
            }
            out.println("[PASS] Double-click toggle (rapid sequence)"); p++;
        } catch (Exception e) { out.println("[FAIL] Double-click toggle: " + e.getMessage()); f++; }

        // 输出单元拆分：组 + 未分组（撤销链后状态已变化，此处自行建组构造确定状态）
        try { String sessionDir = new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-session").getPath();
            List<PreviewItem> vis = this.view.allVisibleItems();
            if (vis.size() >= 3) {
                this.view.allVisibleItems().forEach(i -> i.setSelected(false));
                vis.get(0).setSelected(true);
                vis.get(1).setSelected(true);
                groupButton().fire(); Thread.sleep(400);
            }
            List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                    this.view.dataOrder(), this.view.itemView(), false, this.view.textParamsCache(), this.view.imageParamsCache(), sessionDir);
            if (units.size() < 2) { throw new IllegalStateException("输出单元数异常: " + units.size()); }
            out.println("[PASS] Output unit plan (" + units.size() + " units)"); p++;
        } catch (Exception e) { out.println("[FAIL] Output unit plan: " + e.getMessage()); f++; }

        try { inputFolderField().setText(textDir.toString()); Thread.sleep(200);
            out.println("[PASS] Set input folder"); p++;
        } catch (Exception e) { out.println("[FAIL] Input folder: " + e.getMessage()); f++; }

        try { outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output").getPath()); Thread.sleep(200);
            out.println("[PASS] Set output folder"); p++;
        } catch (Exception e) { out.println("[FAIL] Output folder: " + e.getMessage()); f++; }

        try { ParamDialogController comboDialog = ParamDialogController.showDirect(
                null, false, this.view.textParamsCache(), this.view.imageParamsCache(), ParamScope.GLOBAL);
            Thread.sleep(200);
            comboDialog.configComboBox.setValue("auto_test");
            comboDialog.configComboBox.getEditor().setText("auto_test");
            Thread.sleep(200);
            comboDialog.cancelButton.fire(); Thread.sleep(100);
            out.println("[PASS] Config combo"); p++;
        } catch (Exception e) { out.println("[FAIL] Config combo: " + e.getMessage()); f++; }

        // 文本预览工作区（主窗口）：列表 + 章节勾选
        try { textModeToggle().setSelected(true); Thread.sleep(500);
            if (listView().getItems().isEmpty()) { throw new IllegalStateException("文件列表为空"); }
            listView().getSelectionModel().selectFirst(); Thread.sleep(500);
            adjustCheck().setSelected(false); Thread.sleep(200);
            adjustCheck().setSelected(true); Thread.sleep(200);
            out.println("[PASS] Text preview workspace"); p++;
        } catch (Exception e) { out.println("[FAIL] Text preview workspace: " + e.getMessage()); f++; }

        // 文本提取（参数已确认 → 不弹提示；断言单元合并后 fileResults 完整，供回写背景色）
        try { textModeToggle().setSelected(true); Thread.sleep(300);
            inputFolderField().setText(textDir.toString());
            waitForImportDone();   // 统一导入为异步：先排空（本例为全量已导入 no-op），避免收尾回调在提取期间重算按钮状态
            outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output-text").getPath());
            startButton().fire();
            // 提取回调（onSucceeded 等）在 FX 线程事件队列中，须嵌套泵排空后再断言
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline
                    && (this.view.extractStats().get() == null || startButton().isDisabled())) {
                pumpFxEvents();
            }
            BatchProcessor.ProcessStats merged = this.view.extractStats().get();
            if (merged == null || merged.getFileResults().size() != this.view.visibleCount()
                    || !merged.getFileResults().stream()
                            .allMatch(r -> r.outcome() == ExtractOutcome.SUCCESS)) {
                throw new IllegalStateException("单元合并后 fileResults 缺失或状态异常："
                        + (merged == null ? "null" : merged.getFileResults().size()));
            }
            // 进度条配色拍板：全部成功 → 满格绿色
            if (!progressBar().isVisible() || Math.abs(progressBar().getProgress() - 1.0) > 0.001
                    || !progressBar().getStyleClass().contains("progress-extract-success")) {
                throw new IllegalStateException("全部成功后进度条应满格且为绿色");
            }
            out.println("[PASS] Text extraction started"); p++;
        } catch (Exception e) { out.println("[FAIL] Text extraction: " + e.getMessage()); f++; }

        // 图片预览工作区：查看预览效果（懒加载 + 需求 2 C1 预览落盘断言）
        try { imageModeToggle().setSelected(true); Thread.sleep(500);
            PreviewItem pvTarget = this.view.allVisibleItems().stream()
                    .filter(i -> "t01.mht".equals(i.name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("找不到 t01.mht"));
            listView().getSelectionModel().select(pvTarget); Thread.sleep(300);
            previewEffectButton().fire();
            long previewDeadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < previewDeadline && this.view.imageLoadActive()) {
                pumpFxEvents();
            }
            Thread.sleep(200); pumpFxEvents();
            Object selObj = listView().getSelectionModel().getSelectedItem();
            if (!(selObj instanceof PreviewItem pi) || pi.cachedThumbs == null
                    || pi.cachedThumbs.isEmpty()) {
                throw new IllegalStateException("预览后应有缩略图（来自磁盘图片解码）");
            }
            PreviewItem previewed = (PreviewItem) selObj;
            // C1：缓存目录含 meta.json + images/ + .complete；C2：内存只留 meta + 缩略图
            AppConfig pvParams = this.view.effectiveForObject(pi);
            if (pvParams == null) {
                throw new IllegalStateException("预览条目无生效参数");
            }
            PreviewCacheKey pvKey = PreviewCacheKey.compute(Path.of(pi.path),
                    pvParams.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
            if (this.view.previewCacheLifecycle().store().load(pvKey).isEmpty()) {
                throw new IllegalStateException("预览后缓存应命中（meta.json/images/.complete）");
            }
            if (pi.cachedMeta == null || pi.cachedImages != null) {
                throw new IllegalStateException("内存应只留 meta + 缩略图（cachedMeta 非空、cachedImages 为空）");
            }
            out.println("[PASS] Image preview workspace (C1/C2)"); p++;
        } catch (Exception e) { out.println("[FAIL] Image preview workspace: " + e.getMessage()); f++; }

        // 图片提取（含损坏文件 → 满格条应转为浅红，进度条配色拍板）
        try { imageModeToggle().setSelected(true); Thread.sleep(300);
            java.nio.file.Files.write(imageDir.resolve("bad.mht"),
                    "garbage-not-a-mht".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            inputFolderField().setText(imageDir.toString());
            waitForImportDone();   // 统一导入为异步：bad.mht 须完成导入后才开始提取，否则无失败条目
            outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output-image").getPath());
            startButton().fire();
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline
                    && (this.view.extractStats().get() == null || startButton().isDisabled())) {
                pumpFxEvents();
            }
            if (this.view.extractStats().get() == null) {
                throw new IllegalStateException("图片提取未在时限内完成");
            }
            if (!progressBar().isVisible() || Math.abs(progressBar().getProgress() - 1.0) > 0.001
                    || !progressBar().getStyleClass().contains("progress-extract-failed")) {
                throw new IllegalStateException("存在失败条目时满格条应为浅红");
            }
            out.println("[PASS] Image extraction started"); p++;
        } catch (Exception e) { out.println("[FAIL] Image extraction: " + e.getMessage()); f++; }

        // 预览缓存生命周期（需求 2 会话 9）：参数失效 C3 / 移除清理 C7 / 退出清理 C8
        try { imageModeToggle().setSelected(true); Thread.sleep(300);
            PreviewItem target = this.view.allVisibleItems().stream()
                    .filter(i -> "i01.mht".equals(i.name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("找不到 i01.mht"));
            // C3：全局参数变化 → 全部缓存目录删除（含此前图片预览块落盘的目录）
            AppConfig modified = new AppConfig();
            modified.setImageNameStartMarker("__auto_test_changed__");
            this.view.applyConfirmedParams(true, modified); Thread.sleep(300);
            if (!this.view.previewCacheLifecycle().store().listKeys().isEmpty()) {
                throw new IllegalStateException("参数变化后缓存应全部删除（C3）");
            }
            // 预览 i01 → 落盘恰 1 个目录
            listView().getSelectionModel().select(target); Thread.sleep(200);
            previewEffectButton().fire();
            long lcDeadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < lcDeadline && this.view.imageLoadActive()) {
                pumpFxEvents();
            }
            Thread.sleep(200); pumpFxEvents();
            if (this.view.previewCacheLifecycle().store().listKeys().size() != 1) {
                throw new IllegalStateException("预览后应恰有 1 个缓存目录，实际 "
                        + this.view.previewCacheLifecycle().store().listKeys().size());
            }
            // C7：移除项目 → 其缓存目录删除
            this.view.removeItems(List.of(target)); Thread.sleep(200);
            if (!this.view.previewCacheLifecycle().store().listKeys().isEmpty()) {
                throw new IllegalStateException("移除项目后其缓存应被删除（C7）");
            }
            // C8：退出清空（test.mode 跳过弹窗只清空，关窗处理器同样逻辑）
            this.view.previewCacheLifecycle().onShutdown(() -> { });
            java.nio.file.Path cacheRoot = this.view.previewCacheLifecycle().store().root();
            if (java.nio.file.Files.isDirectory(cacheRoot)
                    && java.nio.file.Files.list(cacheRoot).findAny().isPresent()) {
                throw new IllegalStateException("退出清空后缓存根目录应为空（C8）");
            }
            out.println("[PASS] Preview cache lifecycle (C3/C7/C8)"); p++;
        } catch (Exception e) { out.println("[FAIL] Preview cache lifecycle: " + e.getMessage()); f++; }
        // 兜底清场（无论成败）：自动化测试经 System.exit 退出不走关窗处理器，避免缓存残留跨会话
        try { this.view.previewCacheLifecycle().onShutdown(() -> { }); } catch (Exception ignored) { }

        // GUI 状态回写 locale（H13 行为变更 2 修复锁定）：saveGuiState 不得把 locale 清成 null
        try {
            this.view.saveGuiState();
            String savedLocale = GuiStateManager.load().getLocale();
            if (savedLocale == null || savedLocale.isBlank() || I18n.parseLocale(savedLocale) == null) {
                throw new IllegalStateException("saveGuiState 未回写有效 locale，实际: " + savedLocale);
            }
            out.println("[PASS] GuiState locale persisted after save"); p++;
        } catch (Exception e) { out.println("[FAIL] GuiState locale: " + e.getMessage()); f++; }

        // 导入 → 移除 → 同目录再导入：被移除的项目必须能加回来。
        // 背景（本项目真实 bug，从导入功能落地起就存在）：F2 移除是"隐藏式移除"——只把 removed 置真，
        // 对象仍留在数据序里（为了撤销）。因此导入去重的比对集合必须是"可见项"（allVisibleItems）；
        // 一旦误用 allPreviewItems（其 javadoc 明写"含已移除项"），被移除文件的绝对路径仍被视作
        // "已导入"，整批被静默跳过（只打一条 debug 日志），用户在界面上再也加不回来、也看不到原因。
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mht-reimport-after-remove");
            createSampleMht(root, "k1.mht");
            createSampleMht(root, "k2.mht");
            int itemsBefore = (int) this.view.visibleCount();
            this.view.importByPaths(List.of(root.toString()), I18n.get("test.autoReimport"), false);
            Thread.sleep(800);
            waitForImportDone();
            if (this.view.visibleCount() != itemsBefore + 2) {
                throw new IllegalStateException("首次导入目录失败，可见数=" + this.view.visibleCount());
            }
            List<PreviewItem> imported = new ArrayList<>(this.view.allVisibleItems().stream()
                    .filter(i -> i.path.contains("mht-reimport-after-remove")).toList());
            if (imported.size() != 2) {
                throw new IllegalStateException("未找到本次导入的 2 个条目，实际 " + imported.size());
            }
            this.view.removeItems(imported);
            Thread.sleep(300);
            if (this.view.visibleCount() != itemsBefore) {
                throw new IllegalStateException("移除后可见数不符，可见数=" + this.view.visibleCount());
            }
            this.view.importByPaths(List.of(root.toString()), I18n.get("test.autoReimport"), false);
            Thread.sleep(800);
            waitForImportDone();
            if (this.view.visibleCount() != itemsBefore + 2) {
                throw new IllegalStateException("被移除的项目无法通过同目录再加回来，可见数="
                        + this.view.visibleCount() + "（应为 " + (itemsBefore + 2) + "）");
            }
            out.println("[PASS] Re-import after remove restores items"); p++;
        } catch (Exception e) { out.println("[FAIL] Re-import after remove: " + e.getMessage()); f++; }

        out.println(); out.println("=== RESULTS: " + p + " passed, " + f + " failed ===");
        out.flush();
        log.info("Automated tests complete: {} passed, {} failed", p, f);
    } catch (Exception e) {
        log.error("Test framework error", e);
        System.err.println("[test-mode] FATAL: test framework error: " + e);
        e.printStackTrace();
        return false;
    }
    // 退出码口径：断言全绿且至少跑了 1 项（防"什么都没跑"的假绿）
    System.err.println("=== RESULTS: " + p + " passed, " + f + " failed ===");
    return f == 0 && p > 0;
}

/**
 * 在指定目录生成一个最小 MHT 文件（手写 MIME 结构，含 HTML 与一张内嵌 WebP），供演练与测试使用
 */
private File createSampleMht(java.nio.file.Path dir, String fileName) throws java.io.IOException {
    byte[] webpBytes;
    try (java.io.InputStream is = MainController.class.getResourceAsStream("/sample-preview.webp")) {
        if (is == null) {
            throw new java.io.IOException(I18n.get("test.samplePreviewMissing"));
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
    sb.append(I18n.get("test.previewHtml"))
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
