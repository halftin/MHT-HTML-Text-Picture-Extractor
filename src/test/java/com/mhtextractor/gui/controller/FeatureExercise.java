package com.mhtextractor.gui.controller;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.util.ChangeListener;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.MhtImportPlanner;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.util.ParamScope;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import lombok.extern.slf4j.Slf4j;
import org.glavo.webp.WebPImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GUI 自动化回归宿主（{@code -Dtest.mode=true}）：<b>可观察结果的 GUI acceptance</b>
 * （会话 18 · E0.6 从 {@code MainController} 外迁；步骤 6 按需求 C3 / 决策 E 拆分后，
 * 本类只保留自动化断言矩阵 —— 追踪演练已外迁至 {@link AgentExercise}，共享驱动底座在
 * {@link FeatureExerciseSupport}）。
 *
 * <p><b>与 MainController 的耦合（P7 起）</b>：本类<b>不再</b>依赖 {@code MainController} 的包级成员。
 * P7 之前它靠 44 处 {@code test-support} 标记的包级成员（可变状态 + 查询 + 内部方法）驱动 GUI；
 * 那些标记已全部摘除（{@code ArchitectureTest.testSupportSurfaceDoesNotGrow} 的上限随之归零），
 * 本类改为经 <b>{@link AcceptanceDriver}</b>（测试源集驱动，与本类同包；C3b-1 起 A/B 类
 * 直接访问/派生，C/D 残余反射壳）访问同一批成员 —— 断言一条没少，生产类的封装全部收回。</p>
 *
 * <p><b>入口</b>：测试源集的 {@code com.mhtextractor.gui.acceptance.TestMain} 的
 * {@code -Dtest.mode=true} 分支调 {@link #runAutomatedTests()}，结果写
 * {@code logs/test-results.txt}（{@code === RESULTS: N passed, M failed ===} 契约不变）。
 * P7 之前这两个开关由生产类 {@code App} 读取，现已从生产启动代码里彻底移除。</p>
 *
 * <p><b>步骤 6 拆分口径</b>：本类内嵌的业务级断言已按组外迁至各业务包 JUnit 测试——
 * 导入规划扫描（原统一导入「阶段一」）→ {@code util.MhtImportPlannerTest}；
 * 输出单元规划与参数作用域语义 → {@code util.OutputUnitPlannerTest / util.ParamScopeTest /
 * app.EffectiveParamsServiceTest}；预览缓存 key/命中 → {@code cache.PreviewCacheKeyTest /
 * cache.PreviewCacheStoreTest / app.PreviewServiceTest}。留在本类的都是用户可观察结果的断言
 * （列表项 / 提醒卡 / 按钮态 / 输出文件），断言总数不因拆分而减少。</p>
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
public final class FeatureExercise extends FeatureExerciseSupport {

    public FeatureExercise(MainController mc) {
        super(mc);
    }


// ==================== A2 配额卡场景的观察/驱动辅助（与 tools/probes/NotificationAcceptance 同口径） ====================

/** 等待确认卡出现（配额卡 / 未命名组卡同口径；{@code NotificationHost.hasPendingDecision} 是唯一可观察面） */
private void waitForConfirmCard() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15000;
    while (!com.mhtextractor.gui.component.NotificationHost.hasPendingDecision(statusLabel())
            && System.currentTimeMillis() < deadline) {
        pumpFxEvents();
        Thread.sleep(20);
    }
}

/** 等待配额确认卡出现（A2 场景；超时由调用方断言兜底） */
private void waitForQuotaCard() throws InterruptedException {
    waitForConfirmCard();
}

/** 场景根里的居中模态层（宿主懒装/显式安装都保证场景根是 StackPane） */
private atlantafx.base.controls.ModalPane modalPane() {
    for (javafx.scene.Node n : ((StackPane) listView().getScene().getRoot()).getChildren()) {
        if (n instanceof atlantafx.base.controls.ModalPane mp) {
            return mp;
        }
    }
    throw new IllegalStateException("场景根里找不到 ModalPane");
}

/** 居中卡片本体（确认卡 = {@code Notification}） */
private atlantafx.base.controls.Notification modalContent() {
    javafx.scene.Node content = modalPane().getContent();
    if (content instanceof atlantafx.base.controls.Notification ntf) {
        return ntf;
    }
    throw new IllegalStateException("模态内容不是 Notification: " + content);
}

/** 卡片文案（原始 message 字符串，不受换行/省略号影响） */
private String modalMessage() {
    return modalContent().getMessage();
}

/** 按下卡片的第 index 个主按钮（0 = 确定/继续 → true；1 = 取消/停止 → false） */
private void firePrimary(int index) {
    Button b = modalContent().getPrimaryActions().get(index);
    b.fire();
}

/** 与生产 {@code ImportSessionCoordinator.awaitQuotaConfirm} 相同的拼装口径（同键同参），断言随 locale 走 */
private String quotaCardText(int maxGroups, int maxFiles, int filesKept, int groupsKept) {
    return I18n.get("import.quotaReached", "groups", maxGroups, "files", maxFiles)
            + I18n.get("import.quotaContinue")
            + I18n.get("import.quotaStop", "files", filesKept, "groups", groupsKept);
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
            ParamFormPane textDialog = openSidePanel(false, ParamScope.GLOBAL);
            Thread.sleep(300);
            textDialog.enableLimitCheck.setSelected(true); textDialog.useRegexCheck.setSelected(true);
            textDialog.separateFilesCheck.setSelected(true); textDialog.autoFixEncodingCheck.setSelected(true);
            textDialog.addChapterNumberCheck.setSelected(true); textDialog.enableTitleExtractCheck.setSelected(true);
            textDialog.confirmButton.fire(); Thread.sleep(200);
            if (lastPanelCommit == null) { throw new IllegalStateException("参数窗口未确认"); }
            this.view.applyConfirmedParams(false, lastPanelCommit);
            out.println("[PASS] Text param dialog"); p++;
        } catch (Exception e) { out.println("[FAIL] Text param dialog: " + e.getMessage()); f++; }

        // 图片参数弹窗
        try { imageModeToggle().setSelected(true); Thread.sleep(200);
            ParamFormPane imageDialog = openSidePanel(true, ParamScope.GLOBAL);
            Thread.sleep(300);
            imageDialog.imageNameExtractCheck.setSelected(true);
            imageDialog.imageRegionExtractCheck.setSelected(true);
            imageDialog.imageRenameCheck.setSelected(true);
            imageDialog.imageZipCheck.setSelected(true);
            imageDialog.singleOutputCheck.setSelected(true);
            imageDialog.confirmButton.fire(); Thread.sleep(200);
            if (lastPanelCommit == null) { throw new IllegalStateException("参数窗口未确认"); }
            this.view.applyConfirmedParams(true, lastPanelCommit);
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

            // 步骤 6：本块原「阶段一纯扫描断言」（组批次数 / 非 MHT / 隐藏目录计数）已外迁至
            // util.MhtImportPlannerTest.combinedTreeYieldsFourGroupBatchesWithExactNoiseCounts，
            // 这里只保留 GUI 可观察面：应用导入计划后的列表项与组名（扫描本身仍需真实计划）。
            MhtImportPlanner.Plan plan = MhtImportPlanner.scanner(
                    List.of(root.toString()), MhtImportPlanner.DEFAULT_MAX_DEPTH,
                    MhtImportPlanner.DEFAULT_MAX_GROUPS, MhtImportPlanner.DEFAULT_MAX_FILES).nextRound();

            // 应用（与生产同一实现）
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
            ParamFormPane groupDialog = openSidePanel(false, new ParamScope.GroupTarget(g));
            Thread.sleep(300);
            groupDialog.enableTitleExtractCheck.setSelected(true);
            groupDialog.confirmButton.fire(); Thread.sleep(300);
            if (lastPanelCommit == null) { throw new IllegalStateException("组参数窗口未确认"); }
            this.view.applyGroupParams(g, false, lastPanelCommit); Thread.sleep(300);
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
            ParamFormPane itemDialog = openSidePanel(false,
                    new ParamScope.ItemTarget<>(target, target.textItemParams, target.imageItemParams));
            itemDialog.enableTitleExtractCheck.setSelected(true);
            itemDialog.confirmButton.fire(); Thread.sleep(300);
            if (lastPanelCommit == null) { throw new IllegalStateException("条目参数窗口未确认"); }
            this.view.applyItemParams(target, false, lastPanelCommit);
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
            ParamFormPane itemDlg = openSidePanel(false,
                    new ParamScope.ItemTarget<>(a, a.textItemParams, a.imageItemParams));
            itemDlg.enableTitleExtractCheck.setSelected(true);
            itemDlg.confirmButton.fire(); Thread.sleep(300);
            if (lastPanelCommit == null) { throw new IllegalStateException("条目参数窗口未确认"); }
            this.view.applyItemParams(a, false, lastPanelCommit);
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
            ParamFormPane groupDlg = openSidePanel(false, new ParamScope.GroupTarget(g));
            groupDlg.enableTitleExtractCheck.setSelected(true);
            groupDlg.confirmButton.fire(); Thread.sleep(300);
            if (lastPanelCommit == null) { throw new IllegalStateException("组参数窗口未确认"); }
            this.view.applyGroupParams(g, false, lastPanelCommit);
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

        // 功能一（步骤 3）：未命名组门 —— 上一块留下的手工组（默认名）触发确认卡：
        // ① 卡片列出具体组名；② 取消 → 不提取；③ 确定 → 以默认名提取；④ 用户改名 → 不再提醒
        try { waitForImportDone();
            List<GroupItem<PreviewItem>> unnamed = this.view.topLevelGroups().stream()
                    .filter(g -> g.isDefaultNamed()).toList();
            if (unnamed.size() != 1) {
                throw new IllegalStateException("前置：应恰有 1 个未命名组，实际 " + unnamed.size());
            }
            String unnamedName = unnamed.get(0).getName();
            outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output-gate").getPath()); Thread.sleep(200);
            // ② 卡片出现且列出组名 → 取消 → 不提取
            this.view.extractStats().set(null);
            startButton().fire();
            waitForConfirmCard();
            String card = modalMessage();
            if (!card.equals(I18n.get("group.unnamedConfirm", "names", unnamedName))
                    || !card.contains(unnamedName)) {
                throw new IllegalStateException("未命名组卡文案不符：" + card);
            }
            firePrimary(1);   // 取消
            Thread.sleep(400); pumpFxEvents();
            if (this.view.extractStats().get() != null || this.view.running().get()) {
                throw new IllegalStateException("取消后不应启动提取");
            }
            // ③ 确定 → 以默认名提取（完成判据 = 新一轮 stats 落地；步骤 7 起完成路径会重新启用开始按钮，
            //    既有提取用例的等待循环因此提前退出，此处仍不把按钮状态当判据）
            startButton().fire();
            waitForConfirmCard();
            firePrimary(0);   // 确定
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && this.view.extractStats().get() == null) {
                pumpFxEvents();
            }
            if (this.view.extractStats().get() == null) {
                throw new IllegalStateException("确定后提取未在时限内完成");
            }
            // ④ 用户改名 → 标记清除、卡片不再出现
            listView().getSelectionModel().select(unnamed.get(0)); Thread.sleep(300);
            groupNameField().setText(I18n.get("test.autoGroup")); Thread.sleep(300);
            if (unnamed.get(0).isDefaultNamed()) { throw new IllegalStateException("用户改名后标记未清除"); }
            BatchProcessor.ProcessStats before = this.view.extractStats().get();
            // ③ 的提取禁用过开始按钮，完成路径已会重新启用（步骤 7 终态落地）；此处重算同一派生函数仅作兜底
            this.view.updateStartButtonState();
            startButton().fire();
            Thread.sleep(400); pumpFxEvents();
            if (com.mhtextractor.gui.component.NotificationHost.hasPendingDecision(statusLabel())) {
                throw new IllegalStateException("已改名组不应再弹确认卡");
            }
            long deadline2 = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline2 && this.view.extractStats().get() == before) {
                pumpFxEvents();
            }
            if (this.view.extractStats().get() == before) {
                throw new IllegalStateException("改名后提取未在时限内完成");
            }
            out.println("[PASS] Unnamed group gate (cancel / confirm / renamed)"); p++;
        } catch (Exception e) { out.println("[FAIL] Unnamed group gate: " + e.getMessage()); f++; }

        try { inputFolderField().setText(textDir.toString()); Thread.sleep(200);
            out.println("[PASS] Set input folder"); p++;
        } catch (Exception e) { out.println("[FAIL] Input folder: " + e.getMessage()); f++; }

        try { outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output").getPath()); Thread.sleep(200);
            out.println("[PASS] Set output folder"); p++;
        } catch (Exception e) { out.println("[FAIL] Output folder: " + e.getMessage()); f++; }

        try { ParamFormPane comboDialog = openSidePanel(false, ParamScope.GLOBAL);
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
            // 步骤 7：完成路径终态落地 —— 开始按钮重新启用、停止按钮禁用
            if (startButton().isDisabled()) {
                throw new IllegalStateException("文本提取完成后开始按钮应重新启用（终态落地）");
            }
            if (!stopButton().isDisabled()) {
                throw new IllegalStateException("文本提取完成后停止按钮应禁用（终态落地）");
            }
            out.println("[PASS] Text extraction started"); p++;
        } catch (Exception e) { out.println("[FAIL] Text extraction: " + e.getMessage()); f++; }

        // 图片预览工作区：查看预览效果（懒加载；C1 落盘断言已外迁至 cache/app 的 JUnit，本块保留 C2 内存契约）
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
            // 步骤 6：原「C1 缓存目录三件套 + key 命中」白盒断言已外迁（cache.PreviewCacheKeyTest /
            // cache.PreviewCacheStoreTest / app.PreviewServiceTest 覆盖），本块保留可观察面 C2：
            // 内存只留 meta + 缩略图。
            if (pi.cachedMeta == null || pi.cachedImages != null) {
                throw new IllegalStateException("内存应只留 meta + 缩略图（cachedMeta 非空、cachedImages 为空）");
            }
            out.println("[PASS] Image preview workspace (C2)"); p++;
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
            // 步骤 7：完成路径终态落地 —— 开始按钮重新启用、停止按钮禁用
            if (startButton().isDisabled()) {
                throw new IllegalStateException("图片提取完成后开始按钮应重新启用（终态落地）");
            }
            if (!stopButton().isDisabled()) {
                throw new IllegalStateException("图片提取完成后停止按钮应禁用（终态落地）");
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

        // 步骤 7：取消路径的终态落地 —— 中途取消一次提取后开始按钮重新启用、停止按钮禁用、
        // 运行标记复位；worker 完成后 partial 统计仍照常回写（extractStats 非 null 且含已完成文件）。
        try { textModeToggle().setSelected(true); Thread.sleep(300);
            if (!this.view.dataOrderIsEmpty()) {
                this.view.clearDataOrder();
                this.view.clearUndoStack();
                this.view.refreshAfterListChange();
            }
            waitForImportDone();
            java.nio.file.Path cancelDir = java.nio.file.Files.createTempDirectory("mht-auto-cancel");
            for (int i = 1; i <= 40; i++) {
                createSampleMht(cancelDir, String.format("c%02d.mht", i));
            }
            inputFolderField().setText(cancelDir.toString());
            waitForImportDone();   // 40 个文件统一导入为异步：先排空，避免收尾回调在提取期间重算按钮状态
            outputFolderField().setText(new File(System.getProperty("java.io.tmpdir"),
                    "mht-auto-output-cancel").getPath());
            this.view.extractStats().set(null);
            startButton().fire();
            // 等第一个文件完成（40 文件 → 首个完成的聚合进度 = 0.05，文件完成时 fireProgress 的
            // subProgress = 1.0）再停：保证取消落在提取中途、partial 非空
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && this.view.extractStats().get() == null
                    && progressBar().getProgress() < 0.02) {
                pumpFxEvents();
            }
            if (this.view.extractStats().get() != null) {
                throw new IllegalStateException("提取完成过快，无法测试中途取消");
            }
            if (progressBar().getProgress() < 0.02) {
                throw new IllegalStateException("首个文件未在时限内完成（进度 " + progressBar().getProgress() + "）");
            }
            stopButton().fire();   // 停止 → onStopProcessing + onCancelled 落地；partial 回写等 worker 完成后送达
            long cancelDeadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < cancelDeadline && this.view.extractStats().get() == null) {
                pumpFxEvents();
            }
            pumpFxEvents();
            BatchProcessor.ProcessStats partial = this.view.extractStats().get();
            if (partial == null) {
                throw new IllegalStateException("取消后 worker 未产出 partial 统计（回写未生效）");
            }
            if (partial.getSuccessCount() < 1) {
                throw new IllegalStateException("中途取消应有已完成文件的 partial 成功计数，实际 "
                        + partial.getSuccessCount());
            }
            if (this.view.running().get()) {
                throw new IllegalStateException("取消终态必须复位运行标记");
            }
            if (startButton().isDisabled()) {
                throw new IllegalStateException("取消后开始按钮应重新启用（终态落地）");
            }
            if (!stopButton().isDisabled()) {
                throw new IllegalStateException("取消后停止按钮应禁用（终态落地）");
            }
            out.println("[PASS] Cancel extraction terminal state"); p++;
        } catch (Exception e) { out.println("[FAIL] Cancel extraction: " + e.getMessage()); f++; }

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

        // A2（台账 1.3；决策 F）：GUI 配额确认卡场景 —— 真实 ImportSessionCoordinator（包级注入构造：
        // 派发器 = 真实 Platform::runLater，配额 = 2 组 / 3 文件，不必造 200 个组的夹具）+ 真实
        // MainController 的 ImportUi（confirmQuotaReached → GuiNotifications.quotaConfirm），
        // 在真实 FX 线程上驱动。与无头 ImportSessionCoordinatorQuotaTest 的分工：无头测试用延迟
        // 派发器确定性复现"卡在 apply 落地前构造"的竞态（桩 UI 收卡）；本场景补上"真实卡片渲染 +
        // 真实按钮回答 + 计数含触发轮 + 关窗守卫无残留"这条 GUI 可观察面。
        try { textModeToggle().setSelected(true); Thread.sleep(200);
            if (com.mhtextractor.gui.component.NotificationHost.hasPendingDecision(statusLabel())) {
                throw new IllegalStateException("场景前置失败：存在未答的提醒卡");
            }
            if (!this.view.dataOrderIsEmpty()) {
                this.view.clearDataOrder();
                this.view.clearUndoStack();
                this.view.refreshAfterListChange();
            }
            waitForImportDone();
            java.nio.file.Path qRoot = java.nio.file.Files.createTempDirectory("mht-auto-quota");
            createSampleMht(qRoot, "top1.mht");
            createSampleMht(qRoot, "top2.mht");
            for (String s : List.of("d1", "d2", "d3", "d4")) {
                createSampleMht(java.nio.file.Files.createDirectories(qRoot.resolve(s)), "in.mht");
            }
            ImportSessionCoordinator quotaSessions =
                    new ImportSessionCoordinator(this.view.model(), this.mc, Platform::runLater, 2, 3);
            quotaSessions.start(List.of(qRoot.toString()), I18n.get("test.autoReimport"), false);
            // 卡 1 = 轮 1（顶层 2 文件 + d1）= 3 文件 / 1 组（文件配额打满）；计数必须含触发卡的那一轮
            waitForQuotaCard();
            if (!com.mhtextractor.gui.component.NotificationHost.hasPendingDecision(statusLabel())) {
                throw new IllegalStateException("① 配额卡未出现（NotificationHost 不可观察）");
            }
            if (!modalMessage().equals(quotaCardText(2, 3, 3, 1))) {
                throw new IllegalStateException("② 卡 1 文案不符：\"" + modalMessage() + "\"");
            }
            if (this.view.visibleCount() != 3 || this.view.topLevelGroups().size() != 1) {
                throw new IllegalStateException("卡 1 弹出时模型应为 3 文件/1 组，实际 "
                        + this.view.visibleCount() + "/" + this.view.topLevelGroups().size());
            }
            firePrimary(0);   // 「继续」= true：放行扫描线程扫下一轮
            // 卡 2 = 轮 2 = 5 文件 / 3 组（组配额打满）
            waitForQuotaCard();
            if (!modalMessage().equals(quotaCardText(2, 3, 5, 3))) {
                throw new IllegalStateException("② 卡 2 文案不符：\"" + modalMessage() + "\"");
            }
            if (this.view.visibleCount() != 5 || this.view.topLevelGroups().size() != 3) {
                throw new IllegalStateException("继续后只扫下一轮：模型应为 5 文件/3 组，实际 "
                        + this.view.visibleCount() + "/" + this.view.topLevelGroups().size());
            }
            firePrimary(1);   // 「停止」= false：中止导入，轮 3 永不落地
            waitForImportDone();
            if (this.view.visibleCount() != 5 || this.view.topLevelGroups().size() != 3) {
                throw new IllegalStateException("③ 停止后应保留已导入两轮（5 文件/3 组），实际 "
                        + this.view.visibleCount() + "/" + this.view.topLevelGroups().size());
            }
            if (com.mhtextractor.gui.component.NotificationHost.hasPendingDecision(statusLabel())) {
                throw new IllegalStateException("④ 卡片收尾后仍有未答决定（关窗守卫会受影响）");
            }
            out.println("[PASS] Import quota confirm card (A2)"); p++;
        } catch (Exception e) { out.println("[FAIL] Import quota card: " + e.getMessage()); f++; }

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

}
