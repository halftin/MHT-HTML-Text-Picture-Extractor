package com.mhtextractor.gui.controller;

import com.mhtextractor.app.Texts;
import com.mhtextractor.uicore.PreviewRow;
import javafx.application.Platform;
import javafx.geometry.HorizontalDirection;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;

/**
 * {@code MainView.fxml} 的装配（P6-B）：把 {@code MainController.initialize()} 原来那段
 * <b>112 行</b>的装配序列按职责拆成 5 个 {@code install(...)}，控制器只留调用序列。
 *
 * <h2>为什么单独一个类</h2>
 * <p>装配序列是"节点 ↔ 控制器行为"的接线，本身不承载业务规则；留在控制器里会让
 * "只减不增"的行数闸门永远卡在这一段。搬出来之后控制器侧只剩 5 行调用
 * （{@code MainController.initialize()}），顺序即语义 —— 所以调用顺序在控制器里显式列出，
 * 不在本类里再包一层 {@code installAll()}。</p>
 *
 * <h2>与 {@code MainController} 的协作方式（口径说明）</h2>
 * <p>本类是<b>同包生产协作类</b>，与 {@code PreviewListCell} 完全同一口径：它要用的
 * {@code MainController} 成员保持<b>包级可见</b>且<b>不带</b>
 * {@code /* test-support *&#47;} 标记 —— 那个标记的语义是"<b>为测试夹具</b>放宽的封装让步"
 * （见 {@code ArchitectureTest.testSupportSurfaceDoesNotGrow} 的"标记的语义边界"），
 * 为生产协作放宽不属于它。反过来，本类<b>不得</b>被测试代码拉去当状态面入口。</p>
 *
 * <h2>行为等价性</h2>
 * <p>本类的方法体是 {@code initialize()} 原代码的<b>逐行搬运</b>（只把 {@code x} 写成
 * {@code mc.x} / {@code refs.x()}），注释与顺序都没动 —— 包括"哪些监听器必须挂在哪一步之前"
 * 这类隐性顺序（例如 {@code restoreGuiState()} 必须早于 {@code updateModeUi()}，
 * 否则无状态文件时的初始禁用态会被覆盖）。</p>
 */
final class MainViewInstaller {

    /** 被装配的控制器（同包协作；短名与测试源集驱动 {@code AcceptanceDriver.View.mc} 一致）。 */
    private final MainController mc;
    /** 42 个 {@code fx:id} 的节点引用（{@code initialize()} 期间由命名空间构造）。 */
    private final MainViewRefs refs;

    MainViewInstaller(MainController mc, MainViewRefs refs) {
        this.mc = mc;
        this.refs = refs;
    }

    /** 1. 外壳标签：完成后打开开关的标签位置 + 章节列头提示。 */
    void installShellLabels() {
        refs.extractCheckedOnlyCheck().setLabelPosition(HorizontalDirection.RIGHT);
        refs.openFolderAfterCompleteCheck().setLabelPosition(HorizontalDirection.RIGHT);
        refs.badgeHeaderLabel().setTooltip(new Tooltip(Texts.get("list.chapterNoTooltip")));
    }

    /** 2. 列表：单元格工厂、投影绑定、外部导入 DnD、右键菜单、勾选/组名/选择/拖拽动画联动。 */
    void installFileList() {
        PreviewColumns.install(refs.fileListView());
        refs.fileListView().setCellFactory(_ -> mc.createPreviewCell());
        mc.adapter.bindListView(refs.fileListView());
        mc.setupExternalImportDnD();

        // 空白/非预期区域松手：不处理 drop（无蓝条、无吸附），走取消路径瞬时恢复原位不消失

        // 右键菜单（右键菜单功能需求）：ListView 级弹出（命中 cell = 条目目标；未命中 = 空白区域）。
        // Q9a：菜单项 / 启用规则 / "点击菜单外即关闭"的显示生命周期已整体搬进 FileListContextMenu，
        // 这里只装配 + 接线回调面，并把 test-support 字段指向新类里那一个菜单项。
        mc.contextMenu = new FileListContextMenu(mc.contextActions());
        mc.contextMenu.install(refs.fileListView());
        mc.ctxClearParams = mc.contextMenu.clearParamsItem();

        // 列头全选 CheckBox 三态联动（F1）：单项勾选变化实时刷新列头与组复选框，新导入项目自动挂监听
        refs.selectAllCheck().setOnAction(_ -> mc.listInteraction.onSelectAllToggled());
        refs.groupNameField().textProperty().addListener((_, _, newVal) -> mc.onGroupNameEdited(newVal));
        refs.groupNameField().focusedProperty().addListener((_, _, newVal) -> {
            if (!newVal) {
                mc.onGroupNameFocusLost();
            }
        });
        // 提取标题可编辑（查看器右侧信息区）：输入即写回条目的 titleOverride；失焦无需处理
        refs.titleField().textProperty().addListener((_, _, newVal) -> mc.onTitleEdited(newVal));
        // 新条目自动挂勾选监听（F1）：数据序所有权移交模型后，挂接面从"列表被 add"改为"模型发布了变更"。
        // 载荷里的条目（ITEMS_ADDED / GROUPED / UNGROUPED / UNDO_APPLIED）逐个挂接；selectionListened 按身份
        // 去重，故"同一对象重复挂接"是 no-op —— 这比迁移前的 ObservableList 监听面只多不少，不产生第二份状态。
        refs.fileListView().getSelectionModel().selectedItemProperty()
                .addListener((_, _, newVal) -> mc.listInteraction.onItemSelected(newVal));

        refs.adjustCheck().selectedProperty().addListener((_, _, newVal) -> {
            if (mc.updatingAdjustCheck) {
                return;
            }
            Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
            if (!(sel instanceof PreviewRow it) || mc.effectiveForObject(it) == null) {
                return;
            }
            if (mc.isTextMode()) {
                it.chapterEnabled = newVal;
                mc.listInteraction.recomputeBadges();
            }
        });

        // 拖拽动画期间若列表发生滚动（滚轮/自动滚动），标记位置失效并结束提起动画
        refs.fileListView().skinProperty().addListener((_, _, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            for (javafx.scene.Node n : refs.fileListView().lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                    sb.valueProperty().addListener((_, a, b) -> {
                        if (mc.listInteraction.isDragActive() && Math.abs(b.doubleValue() - a.doubleValue()) > 0.1) {
                            mc.listInteraction.markDragPositionStale();
                            mc.listInteraction.finishLift();
                        }
                    });
                }
            }
        });
    }

    /** 3. 模式与目录：模式切换监听、目录/开关变更监听、GUI 状态恢复与初始一致性。 */
    void installModeAndFolders() {
        // 模式切换：互斥由 modeToggleGroup 承担，点击已选按钮可取消回到"未选择"态；
        //    不清空另一模式参数：GUI 状态与已确认缓存按模式隔离保留
        refs.modeToggleGroup().selectedToggleProperty().addListener((_, _, _) -> mc.onModeChanged());

        // 目录/开关变更：刷新列表 + 持久化 GUI 状态
        refs.inputFolderField().textProperty().addListener((_, _, _) -> mc.onInputFolderChanged());
        refs.outputFolderField().textProperty().addListener((_, _, _) -> mc.saveGuiState());
        refs.extractCheckedOnlyCheck().selectedProperty().addListener((_, _, _) -> mc.saveGuiState());
        refs.openFolderAfterCompleteCheck().selectedProperty().addListener((_, _, _) -> mc.saveGuiState());

        // 恢复 GUI 状态（无状态文件时与旧版启动逻辑一致）
        mc.restoreGuiState();

        // 初始状态一致性（无状态文件时：两个模式都未选中，全部禁用）
        mc.updateModeUi();
        mc.updateFileCount();
        mc.updateListButtons();
    }

    /** 4. 预览会话缓存：启动扫孤儿 + 10 GiB 台阶提醒 hook + 关窗清空处理器挂接。 */
    void installPreviewCacheHooks() {
        // 预览会话缓存（需求 2 会话 9）：启动扫孤儿（P7 崩溃恢复）；
        //    关窗清空处理器经 scene/window 装配后挂接（initialize 阶段 scene 尚未存在，App.start 稍后装配）

        // 会话 12：磁盘占用提醒 hook（写盘成功且跨 10GiB 台阶时经 checkSizeAlert 触发；
        // 在预览后台线程同步回调，这里换算 GiB 文案后回 FX 线程非模态弹窗）

        refs.fileListView().sceneProperty().addListener((_, _, newScene) -> {
            if (newScene == null) {
                return;
            }
            // ScrollPane content enters the scene when its skin is created; the window may already exist.
            if (newScene.getWindow() instanceof Stage stage) {
                stage.setOnCloseRequest(mc::onWindowCloseRequest);
            }
            newScene.windowProperty().addListener((_, _, newW) -> {
                if (newW instanceof Stage stage) {
                    stage.setOnCloseRequest(mc::onWindowCloseRequest);
                }
            });
        });
    }

    /** 5. 日志区欢迎语（延后一帧，等日志 appender 就位）。 */
    void installWelcomeLog() {
        Platform.runLater(() -> {
            refs.logTextArea().appendText(Texts.get("preview.welcome"));
            refs.logTextArea().appendText(Texts.get("preview.welcomeHint"));
        });
    }
}
