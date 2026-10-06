package com.mhtextractor.gui.controller;

import com.mhtextractor.app.MhtImportPlanner;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.util.UnitProgressAggregator;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;

import java.io.File;
import java.util.List;

/**
 * 追踪演练宿主（{@code -Dagent.exit.delay=<ms>}；native 可达性元数据采集用）。
 *
 * <p>步骤 6（需求 C3 / 决策 E）从 {@link FeatureExercise} 拆分：演练与 GUI 自动化断言曾同驻一类，
 * 现拆成「{@link FeatureExercise} = 可观察结果的 GUI acceptance」+「本类 = 追踪演练」+
 * 「业务用例断言 = 各业务包 JUnit 测试」。本类方法体自 FeatureExercise 原行号
 * 277–658 / 1618–1633 <b>逐字搬移</b>，驱动行为不变。</p>
 *
 * <p>入口：{@code com.mhtextractor.gui.acceptance.TestMain} 读 {@code -Dagent.exit.delay} 后
 * 构造本类并调用 {@link #exerciseAllFeatures()}；与 {@code -Dtest.mode=true}（自动化回归）互为独立分支。</p>
 *
 * <p>⚠️ 演练文案的国际化口径与 FeatureExercise 同：夹具数据走 {@code test.*} bundle 键；
 * 只落 {@code logs/test-results.txt} 的断言消息不在此列（本类不写该文件）。</p>
 */
@lombok.extern.slf4j.Slf4j
public final class AgentExercise extends FeatureExerciseSupport {

    public AgentExercise(MainController mc) {
        super(mc);
    }

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
    ParamFormPane textDialog = openSidePanel(false, ParamScope.GLOBAL);
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
    if (lastPanelCommit != null) {
        this.view.applyConfirmedParams(false, lastPanelCommit);
    }
    Thread.sleep(200);

    // Phase 3: 图片参数弹窗演练（highSpeedMemoryCheck 跳过：勾选会改变提取路径——高速内存预加载，非演练范围；
    //   会话 19 起该确认已是窗口内非阻塞卡片，"模态 Alert 阻塞 agent" 已不是跳过理由）
    textModeToggle().setSelected(false);
    imageModeToggle().setSelected(true);
    Thread.sleep(200);
    ParamFormPane imageDialog = openSidePanel(true, ParamScope.GLOBAL);
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
    if (lastPanelCommit != null) {
        this.view.applyConfirmedParams(true, lastPanelCommit);
    }
    Thread.sleep(200);

    // Phase 4: 配置下拉框（弹窗内，不触发保存/加载的模态输入框）
    ParamFormPane comboDialog = openSidePanel(true, ParamScope.GLOBAL);
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
                ParamFormPane groupDialog = openSidePanel(false, new ParamScope.GroupTarget(g1));
                Thread.sleep(300);
                groupDialog.enableTitleExtractCheck.setSelected(true);
                groupDialog.enableTemplateCheck.setSelected(false);
                groupDialog.confirmButton.fire();
                Thread.sleep(300);
                if (lastPanelCommit != null) {
                    this.view.applyGroupParams(g1, false, lastPanelCommit);
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
