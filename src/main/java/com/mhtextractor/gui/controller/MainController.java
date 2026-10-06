package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractionPlanService;
import com.mhtextractor.app.MhtImportPlanner;
import com.mhtextractor.app.PreviewService;
import com.mhtextractor.app.PreviewSession;
import com.mhtextractor.app.SessionStateService;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.app.WorkspaceImportService;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.app.WorkspaceStructure;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.WorkspaceFxAdapter;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.app.ExtractionStats;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.app.UndoManager;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 主界面逻辑控制器：预览工作台常驻主窗口，参数设置弹窗化。
 *
 * <p>预览状态机：选定输入目录后增量导入文件列表（按绝对路径去重、新文件追加末尾，不解析）；当前模式参数经参数窗口
 * "确定"后（写入该模式已确认缓存）自动解析，点击列表项即可预览；未确认时点击列表项
 * 提示先设置参数。两套已确认参数缓存（文本/图片）互相隔离，切模式不丢失；主界面
 * GUI 状态（模式/目录/完成后打开输出文件夹）持久化于 logs/gui-state.json。</p>
 */
@Slf4j
public class MainController implements ImportSessionCoordinator.ImportUi {

    /* 单元格内边距清零（列头与单元格列对齐由各自 HBox 的内边距控制） */
    /* 预解析失败样式（提取结果状态需求拍板：浅黄；2026-09-25 用户反馈改为接近 Windows/WinUI 警告黄 #FFF9AE） */
    /* 选中态 = 基础色混合 40% 浅灰 #bebebe（拍板 2026-09-22，与组行选中切换同机制） */
    /* 提取结果背景画值（提取结果状态需求拍板，配色修订 2026-09-21、2026-09-25）：浅红=失败/跳过；标准绿=成功；分割色=成功+警告（左1/3红右2/3绿）。
       2026-09-25 用户反馈：浅红改 Windows 错误红 #E81123（白字）；警告分割行右侧绿加深 #2E7D32 以承载白字 */
    /* 进度条活动样式类（含各色）与"提取结果 → 样式/颜色"的映射统一在 uicore.ExtractOutcomeStyles */

    /* 8.5 条目背景画值（配色修订 2026-09-21）：深蓝 = 生效参数为全局参数（白字）；浅蓝 = 生效参数为组/条目级独立参数 */
    /* 组条目行样式（配色修订 2026-09-21）：标准黑色底、白色文字；行高大幅缩小为比文字稍高 */
    /* 组条目行选中样式（配色修订 2026-09-21）：黑灰底（#444444），与未选中黑色明显区分 */

    /* 组行左内边距 2px：内容朝左顶格（成员行维持 10px 列对齐） */
    /* 组内成员行内边距（拍板）：行高略低于顶层项目行（顶层 ROW_PAD 34px → 组内约 30px） */
    /* 插入指示线颜色（拖拽排序反馈） */
    /* 组行"入组/合并"整行高亮（琥珀色底+边框，与成员行蓝色插入线视觉区分，6.6 视觉细节） */
    /* 组行落点三区比例：上下边缘为行间空隙（顶层/组间落点），中间带为整行（入组末尾/两组合并） */
    /* 组条目行目标高度（px）：比文字稍高（拍板） */
    /* 组行复选框槽位宽度（px）：仅容纳图形，复选框与内容之间不留空白（拍板） */

    /** 徽标列/位置序号列/复选框列的固定槽位宽度 */

    private static final String HINT_NO_MODE_OR_DIR = I18n.get("preview.hintSelectMode");
    private static final String HINT_NEED_PARAMS = I18n.get("preview.hintParamsUnset");
    private static final String HINT_NO_FILES = I18n.get("preview.hintNoMht");

    /**
     * 输出单元规划的字段视图（路径/来源目录/移除标记，阶段 8）。
     * 实现已下沉 {@code app.ExtractionPlanService}（P3-3b-3）；本包级常量仅维持
     * {@code AcceptanceDriver.itemView()} 的直访契约（测试访问面清单条目不变）。
     */
    static final ExtractionPlanService.ItemView ITEM_VIEW = ExtractionPlanService.itemView(false);

    /**
     * FXML 加载器（P6-B）：由<b>加载方</b>经 {@code setControllerFactory} 注入
     * （{@code App.start()} 与测试源集的 {@code gui.acceptance.TestMain} 逐行等价）。
     *
     * <p>它是 {@link #initialize()} 里构造 {@link MainViewRefs} 的<b>唯一来源</b>：
     * {@code FXMLLoader.loadImpl} 的顺序是"先往 namespace 写 location/resources → 解析 XML
     * （每个 {@code fx:id} 元素构造完成时写进<b>同一张</b> namespace）→ injectFields →
     * 反射调用控制器的 {@code initialize()}"，因此 {@code initialize()} 期间 namespace 已完整
     * （运行期证据见 {@code tools/probes/MainViewRefsProbe.java}）。</p>
     */
    private final FXMLLoader fxmlLoader;

    /**
     * 42 个 {@code fx:id} 的节点引用（P6-B：取代原先 42 个 {@code @FXML} 节点字段）。
     * 在 {@link #initialize()} 里由命名空间构造 —— <b>缺失即抛异常并指名是哪个 {@code fx:id}</b>，
     * 不会留到运行期变成 NPE（见 {@link MainViewRefs#from(java.util.Map)}）。
     *
     * <p>包级可见（不是 {@code private}）是<b>为生产协作</b>：{@link MainViewInstaller} 这个同包
     * 生产类要读这一批节点（C2 起 {@link PreviewListCell} 已不直接读 refs，改经
     * {@link ListInteractionController} / {@link MainViewActions}），与已有先例
     * （{@code ws} / {@code items} / {@code listInteraction} / {@code effectiveForObject}）同一口径 ——
     * 见 {@code ArchitectureTest} 的"标记的语义边界"：{@code /* test-support *&#47;} 标记的是
     * "<b>为测试夹具</b>放宽的封装让步"，为生产类放宽<b>不带</b>标记（本轮 0 标记保持 0）。</p>
     */
    MainViewRefs refs;

    /**
     * 控制器由 {@code FXMLLoader} 的 controller factory 构造 —— <b>唯一构造入口</b>。
     *
     * <p>刻意<b>不</b>提供无参构造：漏了接线会在 {@code load()} 期立刻失败
     * （{@code fx:controller} 拿不到默认构造器），而不是等到某个监听器回调时才 NPE。</p>
     */
    public MainController(FXMLLoader fxmlLoader) {
        this.fxmlLoader = fxmlLoader;
    }

    // ==================== P6-B：为同包生产协作类放宽可见性的成员（口径见下） ====================
    //
    // 装配序列搬到 MainViewInstaller 后，它要读写本类的一部分状态与处理器方法。这些成员是
    // private → 包级（<b>只去掉 private，语义一行未动</b>）：
    //   字段：adapter / contextMenu / ctxClearParams / selectionListened / updatingAdjustCheck /
    //         listInteraction（C2）/ previewCacheLifecycle
    //   方法：createPreviewCell / setupExternalImportDnD / contextActions /
    //         onGroupNameEdited / onGroupNameFocusLost / onModeChanged / onInputFolderChanged /
    //         saveGuiState / restoreGuiState / updateModeUi / updateFileCount / updateListButtons /
    //         onWindowCloseRequest / onSizeAlertStep
    //   （C2：onSelectAllToggled / onSelectionChanged / onItemSelected / recomputeBadges / finishLift
    //    已随列表交互整体外迁到 ListInteractionController —— 本类不再有这些成员）
    // 口径：这是"<b>为生产协作</b>放宽"，与 PreviewListCell 用到的成员完全同类，
    // 因此<b>不打</b>那个 test-support 标记（它的语义是"为测试夹具让步"，见测试内注释）。
    // 测试侧仍然只有一条通道：测试源集的同包直访 AcceptanceDriver（零反射）。

    // ===== 状态 =====
    // 撤销栈（F4）：唯一栈由 app.WorkspaceModel 独占；结构类由模型 undo() 应用，参数 / 拖拽类由本类 popRecord() 后自行应用
    /** 工作区模型（Q4/P3）：**业务数据序的唯一所有者** —— 不再与 GUI 共享可变列表；成功命令会发布 WorkspaceChange */
    final WorkspaceModel ws = new WorkspaceModel();
    /** 模型 → JavaFX 的投影层（Q4/P3）：显示序（ListView 绑定）由它独占维护，数据序只读投影由它提供。
     *  投影<b>时机</b>仍由本类显式给出；"为何不在每次变更上自动投影"见 {@link WorkspaceFxAdapter} 的类 javadoc。 */
    final WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(ws);
    /** 数据序只读投影（adapter.dataOrder() 的别名；同名入口供同包测试驱动读取，已由第 13 条不变式冻结）；唯一写操作 clear() 转发为模型命令 */
    final List<Object> items = adapter.dataOrder();
    /** 显示序：ListView 直接渲染的展平列表，按数据序实时重建（F5 显示序/数据序分离）—— adapter.displayItems() 的只读别名 */
    private final ObservableList<Object> displayItems = adapter.displayItems();

    /** 工作区后台活动代次：预解析/换目录/换模式/开始提取时自增，取消旧任务 */
    int parseToken = 0;
    /** 列表选择代次：切换选择时自增，取消过期的图片懒加载 */
    private int requestToken = 0;
    /** 预览会话缓存（需求 2 会话 9）：存储层 + 四条生命周期规则；root = <java.io.tmpdir>/mht-extractor-preview（P6/P8，与 HtmlExportBuilder 的 mht-extractor-view 并列） */
    final PreviewSession previewCacheLifecycle = PreviewSession.create();
    /**
     * 工作区缓存失效服务（E2.2）：把原先散落在 12 处的"规则 (a)/(c)"调用收敛为具名操作，
     * 并成为 {@code com.mhtextractor.app}（无头应用层）的第一块砖。零 JavaFX 依赖。
     */
    private final com.mhtextractor.app.WorkspaceParamService workspaceParamService =
            new com.mhtextractor.app.WorkspaceParamService(previewCacheLifecycle.lifecycle());
    /**
     * 预览服务（步骤 2）：把「算缓存键 → 查缓存 → 未命中则解析+抽图+原子落盘 → 组装缩略图元数据」
     * 这条<b>业务链路</b>从本控制器搬进无头应用层（{@code com.mhtextractor.app.PreviewService}）。
     * 控制器此后只负责：起线程、解码像素、上屏、弹窗、进度。
     */
    private final com.mhtextractor.app.PreviewService previewService =
            new com.mhtextractor.app.PreviewService(previewCacheLifecycle.lifecycle());
    /**
     * 生效参数服务（R8-①）：把「生效参数是什么」「一组目标是否统一 / 是否混合」「是否存在独立参数」
     * 收敛为唯一实现点，本控制器与 {@code PreviewListCell} 只调用。服务本身无状态，模式与两个全局
     * 参数缓存每次经 {@link #effectiveContext()} 传入（缓存可被确认/应用到全局/撤销整体替换）。
     */
    private final com.mhtextractor.app.EffectiveParamsService effectiveParamsService =
            new com.mhtextractor.app.EffectiveParamsService();
    /**
     * 提取业务服务（Q3 / P2）：逐单元调用 processor、跨单元进度聚合、统计累计、取消传播、
     * 单元失败不中断整批 —— 全部搬进无头应用层（{@code com.mhtextractor.app.WorkspaceExtractionService}）。
     */
    private final WorkspaceExtractionService extractionService =
            new WorkspaceExtractionService(previewCacheLifecycle.lifecycle());
    /**
     * 提取任务协调器（Q3 / P2；步骤 2 起兼作提取启动编排收口）：JavaFX {@code Task} 生命周期与
     * UI 回调的宿主，{@link ExtractionTaskCoordinator#begin()} 从 {@code onStartProcessing} 接下
     * deny 链 → R1.6 参数未确认卡 → 顺序门 → 启动的整条编排。
     * <b>partial 统计（原 {@code lastExtractStats}）、运行标记（原 {@code running}）与在途任务
     * （原 {@code currentTask}）三个提取状态字段都已归它持有</b>——本类不再有第二份可变 holder：
     * 写入只发生在协调器一处（服务返回值落一次），取消回调只读它。测试支撑访问经
     * {@link #extractStats()} 与 {@link #running()}；驱动面（{@code AcceptanceDriver}）直访
     * 本字段读 {@code buildRuntimeConfig()}（与 {@code refs} 同一口径：为协作放宽可见性，
     * 不带 test-support 标记）。
     */
    final ExtractionTaskCoordinator extractionCoordinator =
            new ExtractionTaskCoordinator(extractionService, new ExtractionTaskCoordinator.Host() {
                @Override public boolean isAnimating() { return groupCommand.isAnimating(); }
                @Override public String inputFolder() { return refs.inputFolderField().getText(); }
                @Override public String outputFolder() { return refs.outputFolderField().getText(); }
                @Override public int itemCount() { return items.size(); }
                @Override public long visibleCount() { return MainController.this.visibleCount(); }
                @Override public boolean imageMode() { return refs.imageModeToggle().isSelected(); }
                @Override public boolean openFolderAfterComplete() { return refs.openFolderAfterCompleteCheck().isSelected(); }
                @Override public AppConfig textParamsCache() { return paramDomain.textParamsCache(); }
                @Override public AppConfig imageParamsCache() { return paramDomain.imageParamsCache(); }
                @Override public boolean currentModeConfirmed() { return MainController.this.currentModeConfirmed(); }
                @Override public boolean otherModeConfirmed() {
                    return refs.imageModeToggle().isSelected() ? paramDomain.textParamsCache() != null : paramDomain.imageParamsCache() != null;
                }
                @Override public AppConfig currentModeParamsOrDefaults() { return MainController.this.currentParamsOrDefaults(); }
                @Override public List<Object> dataOrder() { return items; }
                @Override public boolean extractCheckedOnly() { return refs.extractCheckedOnlyCheck().isSelected(); }
                @Override public int visibleCheckedCount() {
                    return (int) MainController.this.allVisibleItems().stream().filter(i -> i.selected).count();
                }
                @Override public void clearExtractOutcomes(List<PreviewItem> items) { infoPresenter.clearExtractOutcomes(items); }
                @Override public void setExtractionUiLocked(boolean locked) {
                    boolean anyMode = refs.textModeToggle().isSelected() || refs.imageModeToggle().isSelected();
                    refs.extractCheckedOnlyCheck().setDisable(locked || !anyMode);
                    refs.fileListView().refresh();
                }
                @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
                @Override public void cancelWorkspaceActivity() { MainController.this.cancelWorkspaceActivity(); }
                @Override public Label statusLabel() { return refs.statusLabel(); }
                @Override public void setStatusText(String text) { refs.statusLabel().setText(text); }
                @Override public void setProgressBarActivity(String styleClass) { MainController.this.setProgressBarActivity(styleClass); }
                @Override public void applyExtractResults(ExtractionStats stats) { infoPresenter.applyExtractResults(stats); }
                @Override public ProgressBar progressBar() { return refs.progressBar(); }
                @Override public Button startButton() { return refs.startButton(); }
                @Override public Button stopButton() { return refs.stopButton(); }
            }, () -> this.paramDomain.updateStartButtonState());
    boolean updatingAdjustCheck = false;

    // ===== 分组状态（F5） =====
    // 组编号计数器已归一到 WorkspaceModel.nextGroupNo()（手工建组与自动建组共用，避免重号）——
    // 控制器不再持有 groupNoCounter。
    /** 正在编辑组名的组（信息区第二行输入框挂接对象） */
    private GroupItem<PreviewItem> nameEditingGroup = null;
    /** 最近一次有效组名（输入框失焦时空名恢复用） */
    private String lastValidGroupName = "";
    /** 组名输入框程序回写期间的抑制标记（防监听递归） */
    private boolean updatingGroupName = false;
    /** 正在编辑提取标题的条目（信息区「提取标题」输入框挂接对象） */
    private PreviewItem titleEditingItem = null;
    /** 标题输入框程序回写期间的抑制标记（防监听递归） */
    private boolean updatingTitle = false;
    /** 已挂勾选监听的项目集合（Identity 去重，防止重复挂接） */
    final Set<PreviewItem> selectionListened =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // ==================== 统一导入会话 ====================
    /** 当前导入会话：非 null = 导入进行中（用于禁用开始提取 + 恢复判定） */
    ImportSession importPending = null;
    /** 外部导入拖拽进行中（与内部排序通道互斥；hasFiles() 判定） */
    private boolean importDragOver = false;
    /** 拖拽目标嵌套深度计数（DRAG_ENTERED_TARGET/DRAG_EXITED_TARGET 成对计数） */
    int importDragDepth = 0;
    /** 外部导入拖拽的列表高亮样式类 */
    private static final String STYLE_LIST_IMPORT_HOVER = "list-import-hover";

    /** 导入会话（不可变） */
    record ImportSession(List<String> addresses, String sourceDesc, boolean fromDrag) {}

    /**
     * 导入会话协调器（Q2/P1）：后台线程生命周期、轮次派发、配额确认等待、模型命令、列表刷新与唯一收尾都在它里面。
     * 导入的<b>规则</b>（地址规范化、轮次去重、组名计算、累计计数）在无头
     * {@link com.mhtextractor.app.WorkspaceImportService} —— 原先散在本类的 7 个累计字段
     * （{@code importAccumulated} / {@code importGroupCount} 与 5 个扫描计数器）随规则一起搬进该服务的会话对象。
     */
    private final ImportSessionCoordinator importSessions = new ImportSessionCoordinator(ws, this);

    // ===== 右键菜单（13 项 = 9 动作 + 4 分隔线；Q9a 已整体外迁到 FileListContextMenu） =====
    /** 右键菜单：菜单项所有权 / 启用规则 / 动作转发 / 显示生命周期都在新类；本类只提供回调面 */
    FileListContextMenu contextMenu;
    /** C1a：源查看/浏览器动作已外迁到 {@link SourceViewActions}（含浏览器查看页生成并发闸）；本类只保留转发面 */
    private final SourceViewActions sourceViewActions = new SourceViewActions(new SourceViewActions.Host() {
        @Override
        public Node anchor() {
            return refs.statusLabel();
        }

        @Override
        public void setStatusText(String text) {
            refs.statusLabel().setText(text);
        }
    });
    /** 预览渲染协作者（C1b）：文本预览 / 缩略图呈现 / 单击缩略图打开缓存图片从本类原样外迁，
     *  宿主能力经 {@link PreviewRenderer.Host} 收窄；文案键、日志键（log.maincontroller.64/65/66）、
     *  线程名（open-cached-image）与 {@code FileOpener} 两级兜底全部保持不变。 */
    private final PreviewRenderer previewRenderer = new PreviewRenderer(new PreviewRenderer.Host() {
        @Override public TextArea textPreviewArea() { return refs.textPreviewArea(); }
        @Override public Label imageHintLabel() { return refs.imageHintLabel(); }
        @Override public ScrollPane imageScrollPane() { return refs.imageScrollPane(); }
        @Override public FlowPane thumbnailPane() { return refs.thumbnailPane(); }
        @Override public Button previewEffectButton() { return refs.previewEffectButton(); }
        @Override public AppConfig effectiveParams(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public int visibleCount() { return (int) MainController.this.visibleCount(); }
        @Override public int unitIndex(Object item) { return (int) MainController.this.unitIndex((PreviewItem) item); }
        @Override public PreviewSession session() { return previewCacheLifecycle; }
    });
    /** 预览按需加载编排协作者（C1c）：原 {@code onPreviewEffect()} 主体原样外迁到 {@link PreviewLoader}，
     *  宿主能力经 {@link PreviewLoader.Host} 收窄；文案键、日志键（log.maincontroller.61/62/63）、
     *  线程名（preview-image-load / preview-cache-stale-delete）与代次语义全部保持不变。 */
    final PreviewLoader previewLoader = new PreviewLoader(new PreviewLoader.Host() {
        @Override public int requestToken() { return requestToken; }
        @Override public int parseToken() { return parseToken; }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public AppConfig effectiveParams(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public AppConfig effectiveImageParams(PreviewItem item) {
            return ParamScope.effectiveImageParams(item.parentGroup, item.imageItemParams, paramDomain.imageParamsCache());
        }
        @Override public PreviewService previewService() { return previewService; }
        @Override public PreviewRenderer renderer() { return previewRenderer; }
        @Override public ProgressBar progressBar() { return refs.progressBar(); }
        @Override public Label statusLabel() { return refs.statusLabel(); }
        @Override public Label imageHintLabel() { return refs.imageHintLabel(); }
        @Override public Button previewEffectButton() { return refs.previewEffectButton(); }
        @Override public boolean extractionRunning() { return running().get(); }
        @Override public void setProgressBarActivity(String styleClass) { MainController.this.setProgressBarActivity(styleClass); }
        @Override public void updateProgressStatus(int token) { preparseController.updateProgressStatus(token); }
        @Override public void refreshList() { refs.fileListView().refresh(); }
    });
    /**
     * 预解析编排协作者（C5）：全量/增量预解析、条目状态回写与预解析进度从本类原样外迁到
     * {@link PreparseController}；宿主能力经 {@link PreparseController.Host} 收窄为具名方法
     * （本匿名类逐个转发到下方保留成员）。文案键、线程名（preview-preparse）、代次语义
     * （parseToken 留在本类）与进度条样式不变。包级字段：测试源集 AcceptanceDriver 经
     * {@code mc.preparseController.updateProgressStatus(token)} 驱动。
     */
    final PreparseController preparseController = new PreparseController(new PreparseController.Host() {
        @Override public int parseToken() { return parseToken; }
        @Override public int nextParseToken() { return ++parseToken; }
        @Override public boolean extractionRunning() { return running().get(); }
        @Override public boolean isPreviewLoading() { return previewLoader.isActive(); }
        @Override public boolean isDragActive() { return listInteraction.isDragActive(); }
        @Override public boolean isAnimating() { return groupCommand.isAnimating(); }
        @Override public boolean isTextMode() { return MainController.this.isTextMode(); }
        @Override public AppConfig effectiveParams(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public ProgressBar progressBar() { return refs.progressBar(); }
        @Override public Label statusLabel() { return refs.statusLabel(); }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public void refreshList() { refs.fileListView().refresh(); }
        @Override public void recomputeBadges() { listInteraction.recomputeBadges(); }
        @Override public void updateInfoArea(Object sel) { infoPresenter.updateInfoArea(sel); }
        @Override public void updateContentArea(Object sel) { MainController.this.updateContentArea(sel); }
        @Override public void setProgressBarActivity(String styleClass) { MainController.this.setProgressBarActivity(styleClass); }
    });
    /**
     * 工作区信息呈现协作者（C5）：模式摘要、选中信息区与提取结果展示从本类原样外迁到
     * {@link WorkspaceInfoPresenter}；宿主能力经 {@link WorkspaceInfoPresenter.Host} 收窄为具名方法
     * （本匿名类逐个转发到下方保留成员）。文案键、日志键（log.maincontroller.29）、
     * 组名编辑框接线与提取结果 banner 语义不变。
     */
    private final WorkspaceInfoPresenter infoPresenter = new WorkspaceInfoPresenter(new WorkspaceInfoPresenter.Host() {
        @Override public Label titleInfoLabel() { return refs.titleInfoLabel(); }
        @Override public Label outputNameLabel() { return refs.outputNameLabel(); }
        @Override public HBox groupNameBox() { return refs.groupNameBox(); }
        @Override public Label badgeHeaderLabel() { return refs.badgeHeaderLabel(); }
        @Override public Label outputModeLabel() { return refs.outputModeLabel(); }
        @Override public Label extractResultBanner() { return refs.extractResultBanner(); }
        @Override public TextField inputFolderField() { return refs.inputFolderField(); }
        @Override public AppConfig effectiveParams(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public AppConfig currentParamsOrDefaults() { return MainController.this.currentParamsOrDefaults(); }
        @Override public boolean isTextMode() { return MainController.this.isTextMode(); }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public List<PreviewItem> allPreviewItems() { return MainController.this.allPreviewItems(); }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public void refreshList() { refs.fileListView().refresh(); }
        @Override public void attachGroupNameEditor(GroupItem<PreviewItem> group) { MainController.this.attachGroupNameEditor(group); }
        @Override public void clearNameEditingGroup() { nameEditingGroup = null; }
        @Override public HBox titleEditBox() { return refs.titleEditBox(); }
        @Override public TextField titleField() { return refs.titleField(); }
        @Override public void attachTitleEditor(PreviewItem item) { MainController.this.attachTitleEditor(item); }
        @Override public void clearTitleEditingItem() { MainController.this.clearTitleEditingItem(); }
    });
    /**
     * 列表交互协作者（C2）：列表选择 / 勾选联动 / 拖拽生命周期 / 章节徽标重算从本类原样外迁到
     * {@link ListInteractionController}；宿主能力经 {@link ListInteractionController.Host} 收窄为具名方法
     * （本匿名类逐个转发到下方保留成员）。文案键、日志键（log.maincontroller.42/43/45）、
     * 动画时序与选择语义不变。
     */
    final ListInteractionController listInteraction = new ListInteractionController(new ListInteractionController.Host() {
        @Override public ListView<Object> fileListView() { return refs.fileListView(); }
        @Override public CheckBox selectAllCheck() { return refs.selectAllCheck(); }
        @Override public javafx.scene.layout.Pane dragOverlay() { return refs.dragOverlay(); }
        @Override public List<Object> dataOrder() { return items; }
        @Override public ObservableList<Object> displayItems() { return displayItems; }
        @Override public void hideRow(Object item) { adapter.hideRow(item); }
        @Override public WorkspaceModel.DropResult applyResolvedDrop(DropResolver.Operation op) { return ws.applyResolvedDrop(op); }
        @Override public void rebuildDisplay() { MainController.this.rebuildDisplay(); }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public void selectRow(Object row) { refs.fileListView().getSelectionModel().select(row); }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public boolean isTextMode() { return MainController.this.isTextMode(); }
        @Override public AppConfig effectiveForObject(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public ParamScope.Status effectiveStatus(Object item) { return MainController.this.effectiveStatus(item); }
        @Override public int visibleMemberCount(GroupItem<PreviewItem> group) { return MainController.this.visibleMemberCount(group); }
        @Override public long unitIndex(PreviewItem item) { return MainController.this.unitIndex(item); }
        @Override public boolean isListAnimating() { return groupCommand.isAnimating(); }
        @Override public boolean extractionRunning() { return MainController.this.running().get(); }
        @Override public Object animLockedSelection() { return groupCommand.animLockedSelection(); }
        @Override public boolean isCurrentModeConfirmed() { return currentModeConfirmed(); }
        @Override public void cancelPreviewLoad() { requestToken++; previewLoader.cancel(); }
        @Override public void updateListButtons() { MainController.this.updateListButtons(); }
        @Override public void updateInfoArea(Object sel) { infoPresenter.updateInfoArea(sel); }
        @Override public void updateContentArea(Object sel) { MainController.this.updateContentArea(sel); }
        @Override public void updateAdjustCheck(Object sel) { MainController.this.updateAdjustCheck(sel); }
        @Override public void updateProgressStatus() { preparseController.updateProgressStatus(parseToken); }
        @Override public void renderThumbnails(PreviewItem item) { previewRenderer.renderThumbnails(item); }
        @Override public void startPreParseFor(List<PreviewItem> items) { preparseController.startPreParseFor(items); }
        @Override public void clearPreviewCache(PreviewItem item) { item.resetPreviewState(); }
        @Override public void toggleGroupExpanded(GroupItem<PreviewItem> group) { groupCommand.toggleGroupExpanded(group); }
    });
    /**
     * 工作区生命周期协作者（C4）：移除命令 / 结构类撤销 / 关窗清缓存从本类原样外迁到
     * {@link WorkspaceLifecycleController}；宿主能力经 {@link WorkspaceLifecycleController.Host} 收窄为具名方法
     * （本匿名类逐个转发到下方保留成员）。文案键、日志键（log.maincontroller.46..55）、确认卡片、
     * 撤销记录语义、缓存规则时机与关窗守卫不变。
     */
    private final WorkspaceLifecycleController workspaceLifecycle =
            new WorkspaceLifecycleController(new WorkspaceLifecycleController.Host() {
        @Override public ListView<Object> fileListView() { return refs.fileListView(); }
        @Override public Node anchor() { return refs.statusLabel(); }
        @Override public List<Object> dataOrder() { return items; }
        @Override public ObservableList<Object> displayItems() { return displayItems; }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public void selectRow(Object row) { refs.fileListView().getSelectionModel().select(row); }
        @Override public void selectNearestVisible(int i) { MainController.this.selectNearestVisible(i); }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public int visibleCount() { return (int) MainController.this.visibleCount(); }
        @Override public int visibleMemberCount(GroupItem<PreviewItem> group) { return MainController.this.visibleMemberCount(group); }
        @Override public boolean isAnimating() { return groupCommand.isAnimating(); }
        @Override public void removeModelItems(List<PreviewItem> targets) { ws.removeItems(targets); }
        @Override public void removeModelGroup(GroupItem<PreviewItem> group) { ws.removeGroup(group); }
        @Override public boolean canUndo() { return ws.canUndo(); }
        @Override public UndoManager.Record<PreviewItem> popRecord() { return ws.popRecord(); }
        @Override public void applyUndo(UndoManager.Record<PreviewItem> record) { ws.applyUndo(record); }
        @Override public List<PreviewItem> applyUndoAffected() { return ws.applyUndoAffected(); }
        @Override public void applyParamUndo(UndoManager.Record<PreviewItem> record) { paramDomain.applyParamUndo(record); }
        @Override public void cacheRulesForRemovedItems(Iterable<PreviewItem> items) { workspaceParamService.itemsRemoved(items); }
        @Override public void clearItemPreviewState(PreviewItem item) { item.resetPreviewState(); }
        @Override public void refreshAfterListChange() { MainController.this.refreshAfterListChange(); }
        @Override public void startPreParseFor(List<PreviewItem> items) { preparseController.startPreParseFor(items); }
        @Override public boolean currentModeConfirmed() { return MainController.this.currentModeConfirmed(); }
        @Override public boolean hasCachedEntries() { return workspaceParamService.hasCachedEntries(); }
        @Override public void cancelWorkspaceActivity() { MainController.this.cancelWorkspaceActivity(); }
        @Override public void awaitPreviewInFlight(long millis) { previewLoader.awaitInFlight(millis); }
        @Override public Path cacheRoot() { return previewCacheLifecycle.store().root(); }
        @Override public void onShutdownClearCache() { workspaceParamService.clearAllCaches(); }
    });
    /** Q9a：测试驱动按<b>字段访问</b>读它的 isDisable()（已由第 13 条不变式冻结），故保留同名字段指向新类里那一个 */
    MenuItem ctxClearParams;
    /**
     * 参数域协作者（步骤 1）：「两套已确认参数缓存 + 参数应用 + 参数撤销 + 参数变更后的派生刷新」
     * 从本类原样外迁到 {@link ParamDomainController}；宿主能力经 {@link ParamDomainController.Host}
     * 收窄为具名方法（本匿名类逐个转发到下方保留成员）。文案键、日志键（log.maincontroller.15..18/
     * 27/28/53..55）、撤销三分支语义、缓存失效（规则 (a) 经 WorkspaceParamService）时机与
     * R1.7 开始按钮派生规则不变。包级字段：测试源集 AcceptanceDriver 经 {@code mc.paramDomain}
     * 同包直访（读两套缓存 / 驱动参数应用与派生刷新）。
     */
    final ParamDomainController paramDomain = new ParamDomainController(new ParamDomainController.Host() {
        @Override public ListView<Object> fileListView() { return refs.fileListView(); }
        @Override public Node anchor() { return refs.statusLabel(); }
        @Override public Button startButton() { return refs.startButton(); }
        @Override public boolean imageMode() { return refs.imageModeToggle().isSelected(); }
        @Override public boolean anyModeSelected() { return refs.textModeToggle().isSelected() || refs.imageModeToggle().isSelected(); }
        @Override public Object currentSelection() { return MainController.this.currentSelection(); }
        @Override public List<Object> dataOrder() { return items; }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public List<PreviewItem> allPreviewItems() { return MainController.this.allPreviewItems(); }
        @Override public AppConfig effectiveForObject(Object item) { return MainController.this.effectiveForObject(item); }
        @Override public boolean currentModeConfirmed() { return MainController.this.currentModeConfirmed(); }
        @Override public long visibleCount() { return MainController.this.visibleCount(); }
        @Override public boolean isImporting() { return MainController.this.isImporting(); }
        @Override public void pushUndo(UndoManager.Record<PreviewItem> record) { ws.push(record); }
        @Override public void pushParamUndo(boolean imageMode, String path, Integer groupNo, AppConfig before) { paramClipboard.pushParamUndo(imageMode, path, groupNo, before); }
        @Override public void paramsChanged(boolean imageMode, PreviewItem item) { workspaceParamService.paramsChanged(imageMode, item); }
        @Override public void paramsChanged(boolean imageMode, Iterable<PreviewItem> items) { workspaceParamService.paramsChanged(imageMode, items); }
        @Override public void clearPreviewCache(PreviewItem item) { item.resetPreviewState(); }
        @Override public void clearExtractOutcomes(List<PreviewItem> items) { infoPresenter.clearExtractOutcomes(items); }
        @Override public void updateBadgeHeader() { infoPresenter.updateBadgeHeader(); }
        @Override public void updateOutputModeLabel(Object sel) { infoPresenter.updateOutputModeLabel(sel); }
        @Override public void updateRenameNote(Object sel) { MainController.this.updateRenameNote(sel); }
        @Override public void updateAdjustCheck(Object sel) { MainController.this.updateAdjustCheck(sel); }
        @Override public void updateInfoArea(Object sel) { infoPresenter.updateInfoArea(sel); }
        @Override public void updateContentArea(Object sel) { MainController.this.updateContentArea(sel); }
        @Override public void updateExtractBanner(Object sel) { infoPresenter.updateExtractBanner(sel); }
        @Override public void updateListButtons() { MainController.this.updateListButtons(); }
        @Override public void recomputeBadges() { listInteraction.recomputeBadges(); }
        @Override public void cancelWorkspaceActivity() { MainController.this.cancelWorkspaceActivity(); }
        @Override public void startPreParseFor(List<PreviewItem> items) { preparseController.startPreParseFor(items); }
        @Override public void startPreParse() { preparseController.startPreParse(); }
        @Override public void updateProgressStatus() { preparseController.updateProgressStatus(parseToken); }
    });
    /** Q10：参数剪贴板（R7.1-R7.7）—— 复制快照 + 批量应用编排；路由与应用本体注入参数域协作者的那两份实现 */
    private final com.mhtextractor.app.ParamClipboard paramClipboard = com.mhtextractor.app.ParamClipboardFactory.create(ws::push, paramDomain::paramScopeTarget, paramDomain::applyTargetParams);

    /**
     * 分组命令协作者（C3a）：分组/拆组命令与折叠/展开动画状态从本类原样外迁到
     * {@link GroupCommandController}；宿主能力经 {@link GroupCommandController.Host} 收窄为具名方法
     * （本匿名类逐个转发到下方保留成员）。文案键、日志键（log.maincontroller.56/57/58/59）、
     * 动画时长与几何快照语义不变。
     */
    final GroupCommandController groupCommand = new GroupCommandController(new GroupCommandController.Host() {
        @Override public ListView<Object> fileListView() { return refs.fileListView(); }
        @Override public javafx.scene.layout.Pane dragOverlay() { return refs.dragOverlay(); }
        @Override public javafx.scene.control.TextField groupNameField() { return refs.groupNameField(); }
        @Override public List<Object> dataOrder() { return items; }
        @Override public int indexInDataOrder(Object item) { return items.indexOf(item); }
        @Override public ObservableList<Object> displayItems() { return displayItems; }
        @Override public GroupItem<PreviewItem> createGroup(List<PreviewItem> targets) { return ws.group(targets, I18n.get("core.groupDefault")); }
        @Override public List<PreviewItem> ungroupGroup(GroupItem<PreviewItem> group) { return ws.ungroup(group); }
        @Override public List<PreviewItem> allVisibleItems() { return MainController.this.allVisibleItems(); }
        @Override public Object currentSelection() { return refs.fileListView().getSelectionModel().getSelectedItem(); }
        @Override public void selectRow(Object row) { refs.fileListView().getSelectionModel().select(row); }
        @Override public void selectNearestVisible(int i) { MainController.this.selectNearestVisible(i); }
        @Override public boolean isDragActive() { return listInteraction.isDragActive(); }
        @Override public void refreshAfterListChange() { MainController.this.refreshAfterListChange(); }
        @Override public void rebuildDisplay() { MainController.this.rebuildDisplay(); }
        @Override public void registerGroupNameRefresh(GroupItem<PreviewItem> group) { MainController.this.registerGroupNameRefresh(group); }
        @Override public void setCheckedSilently(List<PreviewItem> targets, boolean value) { listInteraction.setCheckedSilently(targets, value); }
    });

    // ==================== 初始化 ====================

    /**
     * 装配序列（P6-B）：控制器只留调用序列，装配本体在 {@link MainViewInstaller}。
     *
     * <p>顺序即语义（与 P6-B 之前的 {@code initialize()} 逐行一致）：外壳标签 → 列表接线 →
     * 模式/目录与 GUI 状态恢复 → 预览缓存 hook → 欢迎语。两条隐性约束就藏在这个顺序里 ——
     * ① 状态恢复必须早于初始一致性刷新；② 关窗清空处理器只能在 scene/window 装配好之后挂上。</p>
     *
     * <p>{@code refs} 在这一行构造：{@code FXMLLoader} 保证 {@code getNamespace()} 期间已完整，
     * 缺任何一个 {@code fx:id} 都会在这里立刻抛异常并指名（而不是运行期 NPE）。</p>
     */
    @FXML
    public void initialize() {
        refs = MainViewRefs.from(fxmlLoader.getNamespace());
        MainViewInstaller installer = new MainViewInstaller(this, refs);
        installer.installShellLabels();
        installer.installFileList();
        installer.installModeAndFolders();
        installer.installPreviewCacheHooks();
        installer.installWelcomeLog();
    }

    /** 关窗（规则 d）：本体已外迁到 {@link WorkspaceLifecycleController}（C4），此处仅作转发壳（MainViewInstaller 挂接点） */
    void onWindowCloseRequest(WindowEvent e) {
        workspaceLifecycle.onWindowCloseRequest(e);
    }

    // ==================== GUI 状态持久化（logs/gui-state.json） ====================

    void restoreGuiState() {
        SessionStateService.Session state = SessionStateService.load();
        if (state == null) {
            return;
        }
        if (state.inputFolder() != null && !state.inputFolder().isEmpty()) {
            refs.inputFolderField().setText(state.inputFolder());
        }
        if (state.outputFolder() != null && !state.outputFolder().isEmpty()) {
            refs.outputFolderField().setText(state.outputFolder());
        }
        refs.openFolderAfterCompleteCheck().setSelected(state.openFolderAfterComplete());
        refs.extractCheckedOnlyCheck().setSelected(state.extractCheckedOnly());
        if (state.imageModeEnabled()) {
            refs.imageModeToggle().setSelected(true);
        } else {
            refs.textModeToggle().setSelected(true);
        }
    }

    void saveGuiState() {
        Locale loc = I18n.currentLocale();
        SessionStateService.save(new SessionStateService.Session(
                refs.inputFolderField().getText(),
                refs.outputFolderField().getText(),
                refs.openFolderAfterCompleteCheck().isSelected(),
                refs.extractCheckedOnlyCheck().isSelected(),
                refs.imageModeToggle().isSelected(),
                loc.getLanguage() + (loc.getCountry().isEmpty() ? "" : "_" + loc.getCountry())));
    }

    // ==================== 目录浏览与文件列表 ====================

    @FXML
    private void onBrowseInput() {
        String currentPath = refs.inputFolderField().getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                refs.inputFolderField().getScene().getWindow(),
                I18n.get("dir.chooseInput"),
                currentPath);
        if (dir != null) {
            boolean sameDir = dir.equals(refs.inputFolderField().getText());
            refs.inputFolderField().setText(dir);
            // 重选同一目录不触发 textProperty 变化：显式触发导入（如撤销导入后再导回同一目录）
            if (sameDir) {
                onInputFolderChanged();
            }
            autoSetOutputPath();
        }
    }

    @FXML
    private void onBrowseOutput() {
        String currentPath = refs.outputFolderField().getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                refs.outputFolderField().getScene().getWindow(),
                I18n.get("dir.chooseOutput"),
                currentPath);
        if (dir != null) {
            refs.outputFolderField().setText(dir);
        }
    }

    /**
     * 根据输入目录自动设置输出目录为项目根目录下的"输出文件"文件夹
     */
    private void autoSetOutputPath() {
        String projectDir = System.getProperty("user.dir");
        String outputBaseDir = projectDir + File.separator + I18n.get("core.defaultOutputName");

        File outDirFile = new File(outputBaseDir);
        if (!outDirFile.isDirectory() && !outDirFile.mkdirs() && !outDirFile.isDirectory()) {
            log.warn(I18n.msg("log.maincontroller.01", outputBaseDir));
        }

        refs.outputFolderField().setText(outputBaseDir);
    }

    /** 输入目录变更：作为导入源增量合并（F3）——已有项目保持不变，新文件按绝对路径去重后追加末尾 */
    void onInputFolderChanged() {
        String text = refs.inputFolderField().getText();
        if (text == null || text.trim().isEmpty()) {
            saveGuiState();
            return;
        }
        if (!new File(text).isDirectory()) {
            log.warn(I18n.msg("log.maincontroller.02", text));
            saveGuiState();
            return;
        }
        importFromFolder(text);
        saveGuiState();
    }

    /** 增量导入：统一入口（BC-1：改为递归扫描 + 自动建组） */
    private void importFromFolder(String dir) {
        importByPaths(List.of(dir), I18n.get("dir.label", "path", dir), false);
    }

    // ==================== 统一导入（拖拽 + 浏览共用入口） ====================

    /** 是否正在导入 */
    boolean isImporting() {
        return importPending != null;
    }

    /**
     * 统一导入入口：来源只接受地址字段。
     * 拖拽路径（fromDrag=true）在盘符根目录时弹确认框（BC-8）；浏览路径不弹。
     *
     * <p>本方法只留"拦截 + 确认展示 + 日志产出"；规范化/去重/建组/累计的规则在
     * {@link com.mhtextractor.app.WorkspaceImportService}，会话生命周期在 {@link ImportSessionCoordinator}。</p>
     */
    void importByPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        if (isImporting()) {
            log.warn(I18n.msg("log.maincontroller.03"));
            return;
        }
        // 地址规范化（RISK-6）：祖先/后代包含关系合并为祖先，防重复扫描与重复批次
        WorkspaceImportService.NormalizeResult normalized = importSessions.normalize(addresses);
        // 规范化日志（.06 重复 / .07 被祖先覆盖）：规则在 app 层回传有序事件，文案留在 GUI 层。
        // ★ 必须在 .04 之前逐条产出 —— 原实现是"规范化过程中打日志，返回后才打 .04"。
        for (WorkspaceImportService.DroppedAddress dropped : normalized.dropped()) {
            if (dropped.ancestor() == null) {
                log.info(I18n.msg("log.maincontroller.06", dropped.address()));
            } else {
                log.info(I18n.msg("log.maincontroller.07", dropped.address(), dropped.ancestor()));
            }
        }
        if (normalized.kept().isEmpty()) {
            return;
        }
        log.info(I18n.msg("log.maincontroller.04", sourceDesc, normalized.kept().size()));

        // BC-8：仅拖拽来源 + 盘符根目录 → 确认（会话 19：窗口内居中确认卡片，回调式取代 showAndWait）
        if (fromDrag) {
            List<String> roots = MhtImportPlanner.findDriveRoots(normalized.kept());
            if (!roots.isEmpty()) {
                String msg = I18n.get("import.rootDirConfirmMsg") + String.join("\n", roots)
                        + I18n.get("import.rootDirConfirmNote");
                GuiNotifications.driveRootConfirm(refs.statusLabel(), msg, ok -> {
                    if (ok) {
                        importSessions.start(normalized.kept(), sourceDesc, fromDrag);
                    } else {
                        log.info(I18n.msg("log.maincontroller.05", roots));
                    }
                });
                return;
            }
        }

        importSessions.start(normalized.kept(), sourceDesc, fromDrag);
    }

    /**
     * 应用一个计划（一批或一轮）；FX 线程；生产与自动化测试共用（<b>签名与语义一字不变</b>：
     * GUI 自动化 {@code FeatureExercise} 用它程序化驱动一整轮导入）。
     * 规则（去重/条目构造/累计/组名）在 {@link com.mhtextractor.app.WorkspaceImportService}，
     * 模型命令与刷新在 {@link ImportSessionCoordinator}。
     */
    void applyImportPlan(MhtImportPlanner.Plan plan) {
        importSessions.applyPlan(plan);
    }

    // ==================== 导入协调器的 GUI 回调（ImportSessionCoordinator.ImportUi） ====================

    /** 会话开始：置位"导入中"并刷新开始按钮（C-1：禁用开始提取） */
    @Override
    public void onImportStarted(List<String> addresses, String sourceDesc, boolean fromDrag) {
        importPending = new ImportSession(addresses, sourceDesc, fromDrag);
        paramDomain.updateStartButtonState();
    }

    /** 会话收尾：清位并刷新开始按钮（★ 必须走派生更新，不裸 setDisable） */
    @Override
    public void onImportFinished() {
        importPending = null;
        paramDomain.updateStartButtonState();
    }

    /** 列表可见项快照：路径（导入去重口径 = 可见项，<b>不得</b>含已移除项）+ 数量 */
    @Override
    public VisibleSnapshot visibleSnapshot() {
        List<PreviewItem> visible = allVisibleItems();
        return new VisibleSnapshot(visible.stream().map(i -> i.path).toList(), visible.size());
    }

    @Override
    public boolean hasConfirmedParams() {
        return currentModeConfirmed();
    }

    /** 收尾统一预解析（RISK-7：导入期间抑制，收尾跑一次，避免 O(n²)） */
    @Override
    public void startPreParse(List<PreviewItem> items) {
        preparseController.startPreParseFor(items);
    }

    @Override
    public void refreshList() {
        refreshAfterListChange();
    }

    @Override
    public void attachGroupNameRefresh(GroupItem<PreviewItem> group) {
        registerGroupNameRefresh(group);
    }

    @Override
    public void selectFirstRow() {
        refs.fileListView().getSelectionModel().selectFirst();
    }

    @Override
    public void confirmQuotaReached(String message, Consumer<Boolean> answer) {
        GuiNotifications.quotaConfirm(refs.statusLabel(), message, answer);
    }

    @Override
    public void showImportFailed() {
        GuiNotifications.importFailed(refs.statusLabel());
    }

    /** 组名刷新监听注册（手动/自动建组共用；F8/RISK-11） */
    private void registerGroupNameRefresh(GroupItem<PreviewItem> group) {
        // 会话 18 · E1.1：GroupItem 已去 JavaFX 依赖，原 nameProperty().addListener(...)
        // 改为等价的自研 ChangeListener（同步回调，语义一致）
        group.addNameChangeListener(refs.fileListView()::refresh);
    }

    /** 外部导入拖拽落点高亮（去抖：状态未变化不重复操作样式类） */
    void updateImportDropHighlight(boolean on) {
        if (on == importDragOver) {
            return;
        }
        importDragOver = on;
        if (on) {
            refs.fileListView().getStyleClass().add(STYLE_LIST_IMPORT_HOVER);
        } else {
            refs.fileListView().getStyleClass().remove(STYLE_LIST_IMPORT_HOVER);
        }
    }

    /** 硬复位（深度计数归零 + 清除高亮）：dragDropped/dragDone 无条件调用 */
    void clearImportDropHighlight() {
        importDragDepth = 0;
        updateImportDropHighlight(false);
    }

    /** 外部文件/文件夹拖入的 ListView 级处理器（接受点唯一在 ListView；单元格放行分支见 setupDnD） */
    void setupExternalImportDnD() {
        refs.fileListView().setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !listInteraction.isDragActive()) {
                e.acceptTransferModes(TransferMode.COPY);     // 接受点唯一：ListView
                updateImportDropHighlight(true);
            }
        });
        refs.fileListView().setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && !listInteraction.isDragActive()) {
                importDragDepth++;
                updateImportDropHighlight(true);
            }
        });
        refs.fileListView().setOnDragExited(_ -> clearImportDropHighlight());
        refs.fileListView().setOnDragDropped(this::onExternalFilesDropped);
        refs.fileListView().setOnDragDone(_ -> clearImportDropHighlight());
    }

    private void onExternalFilesDropped(DragEvent e) {
        if (listInteraction.isDragActive()) {
            e.setDropCompleted(false);
            e.consume();
            return;
        }
        boolean ok = false;
        try {
            List<File> dropped = e.getDragboard().getFiles();
            if (dropped != null && !dropped.isEmpty()) {
                List<String> addresses = dropped.stream().map(File::getAbsolutePath).toList();
                // ★ 拖放回调在 FX 线程；导入确认（盘符根目录）现在是窗口内回调式卡片，
                //   仍延后到 runLater，避免在 dragDropped 分发过程中开弹层干扰 DnD 状态机。
                Platform.runLater(() -> importByPaths(addresses, I18n.get("import.dragDrop"), true));
                ok = true;
            }
        } catch (Exception ex) {
            log.warn(I18n.msg("log.maincontroller.14", ex.getMessage()), ex);
        } finally {
            clearImportDropHighlight();
        }
        e.setDropCompleted(ok);
        e.consume();
    }

    /** 取消进行中的预解析/图片懒加载等后台工作区活动 */
    void cancelWorkspaceActivity() {
        parseToken++;
        requestToken++;
        previewLoader.cancel();
    }

    // ==================== 模式切换与工作区 UI 状态 ====================

    void onModeChanged() {
        updateModeUi();
        saveGuiState();
    }

    boolean isTextMode() {
        return !refs.imageModeToggle().isSelected();
    }

    /** 当前模式参数是否已确认（确定过一次即视为已设置） */
    private boolean currentModeConfirmed() {
        return refs.imageModeToggle().isSelected() ? paramDomain.imageParamsCache() != null : paramDomain.textParamsCache() != null;
    }

    /** 当前模式已确认参数；未确认时返回默认参数（仅用于展示，不算已设置） */
    private AppConfig currentParamsOrDefaults() {
        AppConfig c = refs.imageModeToggle().isSelected() ? paramDomain.imageParamsCache() : paramDomain.textParamsCache();
        return c != null ? c : new AppConfig();
    }

    /** 当前模式下某条目的生效参数（三层覆盖，条目独立参数需求拍板）：组参数 > 条目级参数 > 全局；均未确认 → null。
     *  解析唯一实现在 {@code app.EffectiveParamsService}（图片模式仍经 ParamScope.effectiveImageParams，与提取路径一致）
     *  上下文为快照：模式 + 两个全局已确认参数缓存（缓存可被确认/应用到全局/撤销整体替换） */
    AppConfig effectiveForObject(Object o) {
        return effectiveParamsService.effectiveFor(o, effectiveContext());
    }

    /** 生效参数来源三态（NONE/GLOBAL/GROUP）—— 视图层画背景色与白字判定共用同一份来源判定（唯一实现在服务，R8-①） */
    ParamScope.Status effectiveStatus(Object target) {
        return effectiveParamsService.effectiveSourceOf(target, effectiveContext());
    }

    /** 生效参数判定的解析上下文（唯一构造点）：当前模式 + 两个全局已确认参数缓存（现由参数域协作者持有） */
    private com.mhtextractor.app.EffectiveParamsService.Context effectiveContext() {
        return new com.mhtextractor.app.EffectiveParamsService.Context(
                refs.imageModeToggle().isSelected(), paramDomain.textParamsCache(), paramDomain.imageParamsCache());
    }

    /** 任一有效项目存在生效参数（组参数或全局参数已确认）—— 判定收口到服务（R8-①） */
    private boolean anyVisibleItemHasEffectiveParams() {
        return effectiveParamsService.anyHasEffectiveParams(allVisibleItems(), effectiveContext());
    }

    private Object currentSelection() {
        return refs.fileListView().getSelectionModel().getSelectedItem();
    }

    /**
     * 更新与模式/参数相关的工作区 UI；切模式时取消旧解析，已确认则按新模式重新解析
     */
    void updateModeUi() {
        cancelWorkspaceActivity();
        boolean anyModeSelected = refs.textModeToggle().isSelected() || refs.imageModeToggle().isSelected();

        // 顶部与底部控件
        refs.inputFolderField().setDisable(!anyModeSelected);
        refs.outputFolderField().setDisable(!anyModeSelected);
        refs.browseInputButton().setDisable(!anyModeSelected);
        refs.browseOutputButton().setDisable(!anyModeSelected);
        refs.openFolderAfterCompleteCheck().setDisable(!anyModeSelected);
        refs.extractCheckedOnlyCheck().setDisable(!anyModeSelected);
        refs.paramSettingsButton().setDisable(!anyModeSelected);
        paramDomain.updateStartButtonState();
        // 清除参数拍板：按钮启用状态随当前模式刷新（同时修复"应用到全局"按钮切模式后陈旧态）
        updateListButtons();

        // 预览工作区
        refs.modeLabel().setText(I18n.get("mode.labelPrefix") + (refs.imageModeToggle().isSelected() ? I18n.get("mode.image")
                : (refs.textModeToggle().isSelected() ? I18n.get("mode.text") : I18n.get("mode.none"))));
        Object sel = currentSelection();
        infoPresenter.updateBadgeHeader();
        infoPresenter.updateOutputModeLabel(sel);
        updateRenameNote(sel);
        showCurrentModeArea();
        refs.fileListView().refresh();
        updateAdjustCheck(sel);
        infoPresenter.updateInfoArea(sel);
        updateContentArea(sel);

        if (anyModeSelected && anyVisibleItemHasEffectiveParams() && visibleCount() > 0) {
            preparseController.startPreParse();
        }
        // 方案A：切模式后同步进度条状态（新模式无参数条目 → 隐藏；否则显示新模式解析进度）
        preparseController.updateProgressStatus(parseToken);
    }

    private void showCurrentModeArea() {
        refs.textPreviewArea().setVisible(isTextMode());
        refs.textPreviewArea().setManaged(isTextMode());
        refs.imagePreviewBox().setVisible(!isTextMode());
        refs.imagePreviewBox().setManaged(!isTextMode());
    }

    // ==================== 参数设置弹窗（步骤 1：入口留本类作薄转发，应用/撤销/刷新本体在 ParamDomainController） ====================

    /** 主窗口参数按钮路由（条目独立参数需求拍板）：选中组条目 → 该组参数；
     *  选中组内成员 → 其所在组参数；选中顶层项目 → 该条目的条目级参数；未选中 → 全局参数 */
    @FXML
    private void onParamSettings() {
        if (groupCommand.isAnimating()) {
            return;
        }
        paramDomain.openParamDialog(paramDomain.paramScopeTarget(currentSelection()));
    }

    /** 参数应用/撤销/派生化刷新的唯一实现已外迁到 {@link ParamDomainController}（步骤 1）；本类只留上述薄入口。 */

    // ==================== 右键菜单（右键菜单功能需求；Q9a 外迁后只剩回调面） ====================

    /**
     * 右键菜单的宿主回调面（Q9a）：5 个查询 + 12 个动作转发。
     *
     * <p><b>动作实现刻意不搬</b>：建组 / 拆组 / 移除 / 清参数 / 应用全局 / 打开目录 / 查看源码都要动模型、
     * 缓存与 UI 状态，实现留在本类，{@link FileListContextMenu} 只转发"点了哪一项、目标是哪个对象"。</p>
     *
     * <p><b>模型类型判定留在本类</b>：{@code targetType} / {@code isLiveTarget} 是搬前
     * {@code updateContextMenuState} 头四行与 {@code pickItemAt} 里那一句 {@code it.removed} 的逐字搬移，
     * 于是菜单类零模型依赖（只认 {@link com.mhtextractor.uicore.FileMenuModel.TargetType} 这个四布尔快照）。
     * 四布尔而非枚举是<b>有意</b>的：原实现允许"非空但既不是条目也不是组"的目标（四标记全 false），
     * 那与空白区域并不等价。</p>
     *
     * <p>{@code visibleCount} 与 {@code hasIndependentParams} 经 {@code MainController.this} 限定，
     * 否则会递归到匿名类自己的同名方法。</p>
     */
    FileListContextMenu.Actions contextActions() {
        return new FileListContextMenu.Actions() {
            @Override public boolean isDragActive() { return listInteraction.isDragActive(); }
            @Override public long visibleCount() { return MainController.this.visibleCount(); }
            @Override public void selectRow(Object target) { refs.fileListView().getSelectionModel().select(target); }
            @Override public com.mhtextractor.uicore.FileMenuModel.TargetType targetType(Object target) {
                return switch (target) {
                    case null -> com.mhtextractor.uicore.FileMenuModel.TargetType.BLANK;
                    case GroupItem<?> g -> com.mhtextractor.uicore.FileMenuModel.TargetType.GROUP;
                    case PreviewItem it when it.parentGroup != null -> com.mhtextractor.uicore.FileMenuModel.TargetType.MEMBER;
                    case PreviewItem it -> com.mhtextractor.uicore.FileMenuModel.TargetType.TOP_ITEM;
                    default -> new com.mhtextractor.uicore.FileMenuModel.TargetType(false, false, false, false);
                };
            }
            @Override public boolean isLiveTarget(Object item) {
                return !(item instanceof PreviewItem it && it.removed);
            }
            @Override public com.mhtextractor.app.ParamsInfo paramsInfo(Object target) {
                List<Object> targets = paramDomain.resolveParamTargets(target);
                ParamDomainController.TargetCounts counts = ParamDomainController.countTargets(targets);
                return paramClipboard.paramsInfo(targets, hasIndependentParams(target),
                        effectiveParamsService, effectiveContext(),
                        counts.topLevelCount(), counts.distinctGroupCount());
            }
            @Override public void select(Object target) { onContextSelect(target); }
            @Override public void selectAll(Object target) { onContextSelectAll(target); }
            @Override public void openFolder(Object target) { onContextOpenFolder(target); }
            @Override public void openInBrowser(Object target) { sourceViewActions.openInBrowser(target); }
            @Override public void viewSource(Object target) { sourceViewActions.viewSource(target); }
            @Override public void params(Object target) { onContextParams(target); }
            @Override public void clearParams(Object target) { onContextClearParams(target); }
            @Override public void applyGlobal(Object target) { onContextApplyGlobal(target); }
            @Override public void copyParams(Object target) { onContextCopyParams(target); }
            @Override public void applyToSelection(Object target) { onContextApplyToSelection(target); }
            @Override public void group() { onGroupClicked(); }
            @Override public void ungroup() { onUngroupClicked(); }
            @Override public void remove() { onRemoveClicked(); }
            @Override public void removeGroup(Object target) { workspaceLifecycle.onContextRemoveGroup(target); }
        };
    }

    /** 菜单项禁用状态按右键目标类型刷新（Q9a：规则本体已搬到 {@link FileListContextMenu#updateState}） */
    void updateContextMenuState(Object target) {
        contextMenu.updateState(target);
    }

    /** 选中（拍板 Q4）：项目条目勾选自身；组条目整组勾选（强制 true，不做 toggle）。目标 = 右键那一项 */
    private void onContextSelect(Object target) {
        if (groupCommand.isAnimating() || target == null) {
            return;
        }
        List<PreviewItem> targets;
        if (target instanceof PreviewItem it && !it.removed) {
            targets = List.of(it);
        } else if (target instanceof GroupItem<?> g) {
            targets = g.getMembers().stream()
                    .filter(m -> m instanceof PreviewItem pi && !pi.removed)
                    .map(m -> (PreviewItem) m)
                    .toList();
        } else {
            targets = List.of();
        }
        listInteraction.setCheckedSilently(targets, true);
        listInteraction.refreshSelectionUi();
        updateListButtons();
    }

    /** 全选（拍板 Q4/Q14 强制全选不回退）：组内成员/组条目 → 该组全部成员；顶层项目/空白 → 整个列表（含折叠组成员） */
    private void onContextSelectAll(Object target) {
        if (groupCommand.isAnimating()) {
            return;
        }
        List<PreviewItem> targets;
        if (target instanceof GroupItem<?> g) {
            targets = visibleMembersOf(g);
        } else if (target instanceof PreviewItem it && it.parentGroup != null) {
            targets = visibleMembersOf(it.parentGroup);
        } else {
            targets = allVisibleItems();
        }
        listInteraction.setCheckedSilently(targets, true);
        listInteraction.refreshSelectionUi();
        updateListButtons();
    }

    /** 打开所在文件夹（全新能力 5.2）：explorer /select 定位；Q15 拍板——文件缺失时不定位直接打开所在目录 */
    private void onContextOpenFolder(Object target) {
        if (target instanceof PreviewItem it && !it.removed) {
            openContainingFolder(it);
        }
    }

    private void openContainingFolder(PreviewItem it) {
        File f = new File(it.path);
        File dir = f.getParentFile();
        try {
            if (f.exists()) {
                // 提权进程直接启动 explorer.exe 会被 UIPI 拦截（DDE 无法路由到非提权 shell）。
                // 必须整条命令作为单个参数传给 cmd /c（分参数传会被 ProcessBuilder 二次引号包裹拆坏，
                // 已实测验证：分参数=静默失败，单参数=窗口打开且文件选中）；路径整体引号包裹防空格截断
                new ProcessBuilder("cmd", "/c",
                        "start \"\" explorer /select,\"" + f.getAbsolutePath() + "\"").start();
            } else if (dir != null && dir.exists()) {
                log.warn(I18n.msg("log.maincontroller.19", it.path));
                // 步骤 7：去掉 AWT Desktop.open 改用 explorer.exe（native 对 AWT 支持有限，
                // CLAUDE.md 有同一处决策记录，native 元数据也未注册 awt 条目）。
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
            } else {
                log.warn(I18n.msg("log.maincontroller.20", dir == null ? it.path : dir.getAbsolutePath()));
                GuiNotifications.fileDirMissing(refs.statusLabel(), it.path);
            }
        } catch (Exception ex) {
            log.error(I18n.msg("log.maincontroller.21"), ex);
            GuiNotifications.openFolderFailed(refs.statusLabel());
        }
    }

    /** 设置提取参数（拍板 Q7/G6）：空白区域 → 强制全局参数；其余复用选中项路由（Q8 已选中该行） */
    private void onContextParams(Object target) {
        if (groupCommand.isAnimating()) {
            return;
        }
        if (target == null) {
            paramDomain.openParamDialog(ParamScope.GLOBAL);
        } else {
            onParamSettings();
        }
    }

    // ==================== 应用到全局（条目独立参数需求） ====================

    /** 目标生效参数来源是否为独立参数（条目级或组参数；拍板：仅此状态"应用到全局"可用）—— 判定收口到服务（R8-①） */
    private boolean hasIndependentParams(Object target) {
        return effectiveParamsService.hasIndependentParams(target, effectiveContext());
    }

    /** 右键菜单"应用到全局"：目标 = 右键点击的条目 */
    private void onContextApplyGlobal(Object target) {
        if (groupCommand.isAnimating() || !hasIndependentParams(target)) {
            return;
        }
        paramDomain.applyGlobalParamsTo(target);
    }

    /** 按钮行"应用到全局"：目标 = 当前选中条目 */
    @FXML
    private void onApplyGlobalClicked() {
        if (groupCommand.isAnimating()) {
            return;
        }
        Object sel = currentSelection();
        if (hasIndependentParams(sel)) {
            paramDomain.applyGlobalParamsTo(sel);
        }
    }

    /** 拍板：目标生效参数集写入当前模式全局缓存，清空当前模式所有独立参数（条目级 + 组，含目标自身），
     *  全部条目继承新全局；另一模式独立参数不受影响。入撤销栈（APPLY_GLOBAL，含应用前完整参数快照）
     *  —— 本体已外迁到 {@link ParamDomainController#applyGlobalParamsTo}（步骤 1），此处仅入口守卫。 */

    // ==================== 清除参数（清除参数功能） ====================

    /** 按钮行"清除参数"：目标 = 勾选集合（若有）否则单选，与"移除"一致 */
    @FXML
    private void onClearParamsClicked() {
        if (groupCommand.isAnimating()) {
            return;
        }
        paramDomain.clearParamsForTargets(paramDomain.resolveParamTargets(currentSelection()));
    }

    /** 右键菜单"清除参数"：动作逻辑与按钮一致（勾选优先 → 右键目标；启用看右键目标） */
    private void onContextClearParams(Object target) {
        if (groupCommand.isAnimating()) {
            return;
        }
        paramDomain.clearParamsForTargets(paramDomain.resolveParamTargets(target));
    }

    // ==================== 参数目标解析 / 清除参数 / 复制与应用（Q10） ====================

    /** 右键「复制参数」（R7.2 的禁用理由写在菜单项文本里；R7.7 只存可持久化提取参数快照） */
    private void onContextCopyParams(Object target) {
        List<Object> targets = paramDomain.resolveParamTargets(target);
        if (!targets.isEmpty()) paramClipboard.copy(effectiveForObject(targets.get(0)));
    }

    /** 右键「应用到所选」（R7.4/R7.5）：逐目标复用确认路径的应用函数；整批只压一条 PARAM_SET 记录 */
    private void onContextApplyToSelection(Object target) {
        if (groupCommand.isAnimating() || paramClipboard.isEmpty()) return;
        paramClipboard.applyToSelection(paramDomain.resolveParamTargets(target), refs.imageModeToggle().isSelected());
    }

    /** 参数目标解析 / 清除参数 / 参数变更后的派生刷新已外迁到 {@link ParamDomainController}（步骤 1）；
     *  上方两个右键入口只经其 resolveParamTargets 薄转发。 */

    // ==================== 开始 / 停止 ====================

    @FXML
    private void onStartProcessing() {
        // deny 链 → 图片模式额外校验 → R1.6 参数未确认卡 → 顺序门 → 启动：
        // 编排本体已整体外迁到 ExtractionTaskCoordinator.begin()（步骤 2，台账 1.1-②），
        // 此处仅 FXML 入口薄转发。顺序、日志键与文案键一律不变。
        extractionCoordinator.begin();
    }

    /**
     * 单次提取的运行标记（测试支撑）：投影到协调器持有的唯一实例 ——
     * 解析回调据此让出进度条 / 状态栏，语义与搬迁前 {@code running} 字段一致。
     */
    AtomicBoolean running() {
        return extractionCoordinator.running;
    }

    /**
     * 最近一次提取的 partial/完整统计（测试支撑）：<b>只读</b>投影到协调器持有的唯一 holder ——
     * 写入只发生在 {@code ExtractionTaskCoordinator.run} 一处（服务返回值落一次）。
     */
    AtomicReference<ExtractionStats> extractStats() {
        return extractionCoordinator.lastExtractStats;
    }

    @FXML
    private void onStopProcessing() {
        // E1.1：取消 + 复位（running / currentTask / 停止按钮 / 开始按钮派生函数）只有协调器一份实现；
        // 此处只保留非复位 UI 动作。任务线程迟到的 onCancelled 会再落地一次（幂等，见 landTerminalState）。
        extractionCoordinator.cancelAndReset();
        refs.progressBar().setVisible(false);
        setProgressBarActivity(null);
        refs.statusLabel().setText(I18n.get("extract.stopped"));
        log.info(I18n.msg("log.maincontroller.41"));
    }

    // ==================== 列表渲染 ====================

    /** 列表单元格工厂（单元格实现已外迁为顶层类 PreviewListCell，C2 起经窄接口 MainViewActions 依赖列表交互协作者） */
    ListCell<Object> createPreviewCell() {
        return new PreviewListCell(listInteraction);
    }

    /** 顶层组列表（按数据序；供 agent 演练使用）—— 委托模型 */
    private List<GroupItem<PreviewItem>> topLevelGroups() {
        return ws.groups();
    }

    /** 组内有效成员（removed == false；供 agent 演练使用） */
    private List<PreviewItem> visibleMembersOf(GroupItem<?> g) {
        List<PreviewItem> out = new ArrayList<>();
        for (Object m : g.getMembers()) {
            if (m instanceof PreviewItem p && !p.removed) {
                out.add(p);
            }
        }
        return out;
    }

    // ==================== 显示序 / 数据序（F5） ====================

    /** 数据模型中的所有项目（含组内成员与已移除项）—— 委托 {@link WorkspaceModel#allItems()}（本类只读查询） */
    private List<PreviewItem> allPreviewItems() {
        return ws.allItems();
    }

    /** 全部有效项目（removed == false，含组内成员）。按数据序遍历：折叠组的成员不在显示序中，
     *  若按显示序遍历会导致全选/提取名单/进度统计漏掉折叠组成员（冒烟测试发现，阶段 4 遗留） */
    private List<PreviewItem> allVisibleItems() {
        return ws.allItems().stream().filter(i -> !i.removed).toList();
    }

    /** 重建父组指针（显示序重建时同步调用）—— 委托模型，本类不再留第二份实现 */
    private void rebuildParentPointers() {
        ws.rebuildParentPointers();
    }

    /** 按数据序重建显示序（ListView 渲染展平列表）。动画期间的外部重建（换目录/模式切换等）先快进完成动画 */
    void rebuildDisplay() {
        groupCommand.fastForwardOnExternalRebuild();
        rebuildParentPointers();
        adapter.project();
    }

    /** 组内有效成员数（removed == false） */
    int visibleMemberCount(GroupItem<PreviewItem> g) {
        return (int) g.getMembers().stream().filter(m -> !m.removed).count();
    }

    /** 条目在所属单元内的可见序号（0 起）：顶层项目按顶层可见项目计数，组内成员按组内可见成员计数（R10） */
    long unitIndex(PreviewItem item) {
        if (item.parentGroup == null) {
            return items.stream().takeWhile(o -> o != item)
                    .filter(o -> o instanceof PreviewItem it && !it.removed).count();
        }
        GroupItem<PreviewItem> g = item.parentGroup;
        return g.getMembers().stream().takeWhile(m -> m != item)
                .filter(m -> !m.removed).count();
    }

    // ==================== 移除 / 撤销（F2 / F4） ====================

    /** 可见文件数：removed 标记的项目在列表中完全隐藏，不参与任何计数（组条目不计入）—— 委托 {@link WorkspaceModel#visibleCount()} */
    private long visibleCount() {
        return ws.visibleCount();
    }

    /** 显示序中"可见行"判定：有效项目或组条目 */
    private boolean isVisibleRow(Object o) {
        return (o instanceof PreviewItem it && !it.removed) || o instanceof GroupItem<?>;
    }

    void updateFileCount() {
        refs.fileCountLabel().setText(I18n.get("list.fileCount", "count", visibleCount()));
    }

    /* 列表操作按钮可用性：列表为空时移除禁用；撤销栈为空时撤销禁用；合并为组需勾选集合或选中项目；拆分组仅选中组条目时可用（F6） */
    /** 查看HTML源码按钮（浏览器查看HTML需求）：对列表当前选中条目打开源码视图 */
    @FXML
    private void onViewSourceButton() {
        if (groupCommand.isAnimating()) {
            return;
        }
        if (currentSelection() instanceof PreviewItem it && !it.removed) {
            sourceViewActions.viewSource(it);
        }
    }

    void updateListButtons() {
        refs.removeButton().setDisable(visibleCount() == 0);
        refs.undoButton().setDisable(!ws.canUndo());
        boolean anyChecked = allVisibleItems().stream().anyMatch(i -> i.selected);
        Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
        boolean selItem = sel instanceof PreviewItem it && !it.removed;
        refs.groupButton().setDisable(!anyChecked && !selItem);
        refs.ungroupButton().setDisable(!(sel instanceof GroupItem<?>));
        refs.viewSourceButton().setDisable(!selItem);
        // 条目独立参数需求拍板：仅当选中条目生效参数为独立参数时可"应用到全局"
        refs.applyGlobalButton().setDisable(!hasIndependentParams(sel));
        // 清除参数拍板 Q3：勾选集合任一目标带参数（无勾选 → 单选目标）即启用
        boolean anyClearable = anyChecked
                ? allVisibleItems().stream().filter(i -> i.selected)
                        .anyMatch(this::hasIndependentParams)
                : hasIndependentParams(sel);
        refs.clearParamsButton().setDisable(!anyClearable);
    }

    /** 开始按钮可用性的唯一派生函数已外迁到 {@link ParamDomainController#updateStartButtonState}（步骤 1）；
     *  本类的全部调用点已改为经 {@code paramDomain} 转发。 */

    /**
     * 列表结构变化（移除/撤销/导入/建组）后的统一刷新，按指南拆成两段：
     * ① <b>刷新模型投影</b> —— {@code adapter.refresh()}（全量投影 + 按对象钉住选中，
     *    即原 {@code rebuildDisplay()} 加"仍在显示序则重选"那三行，规则逐字未改）；
     * ② <b>更新计数/按钮/选择等 UI 派生状态</b> —— 下面这些自己读模型/控件的派生刷新。
     */
    void refreshAfterListChange() {
        groupCommand.fastForwardOnExternalRebuild();
        rebuildParentPointers();
        adapter.refresh();
        updateFileCount();
        updateListButtons();
        paramDomain.updateStartButtonState();
        listInteraction.refreshSelectionUi();
        listInteraction.recomputeBadges();
        // 方案A：无条件刷新——全部无参数时 updateProgressStatus 会隐藏进度条且不动状态栏
        preparseController.updateProgressStatus(parseToken);
        Object selAfter = refs.fileListView().getSelectionModel().getSelectedItem();
        infoPresenter.updateInfoArea(selAfter);
        updateContentArea(selAfter);
    }

    /** 移除按钮：命令本体已外迁到 {@link WorkspaceLifecycleController}（C4），此处仅作 FXML 入口转发 */
    @FXML
    private void onRemoveClicked() {
        workspaceLifecycle.onRemoveClicked();
    }

    /** 撤销按钮：结构类分支已外迁到 {@link WorkspaceLifecycleController}（C4），此处仅作 FXML 入口转发；
     *  参数类分支（PARAM_SET / CLEAR_PARAMS / APPLY_GLOBAL）经其 Host 整体委派到
     *  {@link ParamDomainController#applyParamUndo}（步骤 1）。 */
    @FXML
    private void onUndoClicked() {
        workspaceLifecycle.onUndoClicked();
    }

    /** 选中最近的可见行（项目或组条目；优先下一行，无则选上一行，都没有则清空选中） */
    private void selectNearestVisible(int fromDisplayIndex) {
        int start = Math.max(0, Math.min(fromDisplayIndex, displayItems.size() - 1));
        for (int i = start; i < displayItems.size(); i++) {
            if (isVisibleRow(displayItems.get(i))) {
                refs.fileListView().getSelectionModel().select(displayItems.get(i));
                return;
            }
        }
        for (int i = start - 1; i >= 0; i--) {
            if (isVisibleRow(displayItems.get(i))) {
                refs.fileListView().getSelectionModel().select(displayItems.get(i));
                return;
            }
        }
        refs.fileListView().getSelectionModel().clearSelection();
    }

    // ==================== 合并为组 / 组操作（F5） ====================

    /** 合并为组（F5）：命令本体已外迁到 {@link GroupCommandController}（C3a），此处仅作 FXML 入口转发 */
    @FXML
    private void onGroupClicked() {
        groupCommand.onGroupClicked();
    }

    /** 拆分组（F6）：命令本体已外迁到 {@link GroupCommandController}（C3a），此处仅作 FXML 入口转发 */
    @FXML
    private void onUngroupClicked() {
        groupCommand.onUngroupClicked();
    }

    // ==================== 组名编辑（F5 6.3） ====================

    /** 选中组条目：信息区第二行挂接组名编辑器（输入框显示组名，编辑实时绑定） */
    private void attachGroupNameEditor(GroupItem<PreviewItem> g) {
        if (nameEditingGroup != g) {
            nameEditingGroup = g;
            lastValidGroupName = g.getName();
            updatingGroupName = true; refs.groupNameField().setText(g.getName()); updatingGroupName = false;   // 程序化回填，不得误走 onGroupNameEdited 提交分支（步骤 3 标记）
        }
    }

    /** 组名输入即生效；重名时自动追加序号直至唯一并日志提示（R7） */
    void onGroupNameEdited(String newVal) {
        GroupItem<PreviewItem> g = nameEditingGroup;
        if (g == null || updatingGroupName) {
            return;
        }
        String trimmed = newVal.trim();
        if (trimmed.isEmpty()) {
            // 编辑中允许临时清空，失焦时恢复最近有效组名
            g.setName("");
            return;
        }
        String unique = WorkspaceStructure.uniqueGroupName(trimmed, otherGroupNames(g));
        if (!unique.equals(trimmed)) {
            log.info(I18n.msg("log.maincontroller.09", trimmed, unique));
            updatingGroupName = true;
            refs.groupNameField().setText(unique);
            updatingGroupName = false;
        }
        g.setName(unique); g.setDefaultNamed(false);   // 用户提交改名：永久退出「未命名组」提醒（步骤 3 · 功能一）
        lastValidGroupName = unique;
    }

    /** 输入框失焦时空组名恢复为最近一次有效组名 */
    void onGroupNameFocusLost() {
        GroupItem<PreviewItem> g = nameEditingGroup;
        if (g == null) {
            return;
        }
        String text = refs.groupNameField().getText();
        if (text == null || text.trim().isEmpty()) {
            updatingGroupName = true;
            refs.groupNameField().setText(lastValidGroupName);
            updatingGroupName = false;
            g.setName(lastValidGroupName);
        }
    }

    // ==================== 提取标题编辑（查看器右侧信息区） ====================

    /** 选中已就绪条目：信息区「提取标题」行挂接可编辑输入框（显示用户编辑值；无则回填提取标题） */
    private void attachTitleEditor(PreviewItem it) {
        if (titleEditingItem != it) {
            titleEditingItem = it;
            // 程序化回填，不得误走 onTitleEdited 提交分支
            updatingTitle = true;
            refs.titleField().setText(it.titleOverride != null ? it.titleOverride : it.cachedTitle);
            updatingTitle = false;
        }
    }

    /** 提取标题输入即生效：trim 后为空恢复"用提取值"，否则写入用户编辑值（最终输出命名优先取它） */
    void onTitleEdited(String newVal) {
        PreviewItem it = titleEditingItem;
        if (it == null || updatingTitle) {
            return;
        }
        String trimmed = newVal == null ? "" : newVal.trim();
        it.titleOverride = trimmed.isEmpty() ? null : trimmed;
    }

    /** 离开标题编辑（换条目 / 组条目 / 未就绪）：切断挂接，避免误写到旧条目 */
    private void clearTitleEditingItem() {
        titleEditingItem = null;
    }

    /** 其它组（排除自身）的组名集合 */
    private List<String> otherGroupNames(GroupItem<PreviewItem> g) {
        List<String> names = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof GroupItem<?> og && og != g) {
                names.add(og.getName());
            }
        }
        return names;
    }

    // ==================== 选择与联动 ====================

    private void updateAdjustCheck(Object item) {
        // 第三行：文本模式 = 章节复选框（逐文件调整）；图片模式 = 输出结果展示（全局打包配置）；组条目两者均不显示
        boolean isItem = item instanceof PreviewItem it && !it.removed;
        AppConfig eff = isItem ? effectiveForObject(item) : null;
        boolean checkVisible = isTextMode() && eff != null && eff.isAddChapterNumber();
        refs.adjustCheck().setVisible(checkVisible);
        refs.adjustCheck().setManaged(checkVisible);
        if (checkVisible) {
            PreviewItem it = (PreviewItem) item;
            refs.adjustCheck().setText(I18n.get("params.addChapterNumbers"));
            updatingAdjustCheck = true;
            refs.adjustCheck().setSelected(it.chapterEnabled);
            updatingAdjustCheck = false;
        }
        boolean resultVisible = !isTextMode() && eff != null;
        refs.outputResultLabel().setVisible(resultVisible);
        refs.outputResultLabel().setManaged(resultVisible);
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
        return I18n.get("extract.unitFolder");
    }

    // ==================== 内容区渲染 ====================

    private void updateContentArea(Object item) {
        boolean anyMode = refs.textModeToggle().isSelected() || refs.imageModeToggle().isSelected();
        if (!anyMode) {
            showCurrentModeArea();
            refs.textPreviewArea().setText(HINT_NO_MODE_OR_DIR);
            return;
        }
        // 组条目：预览区显示组信息与参数状态提示（6.5）
        if (item instanceof GroupItem<?> g) {
            GroupItem<PreviewItem> group = WorkspaceStructure.asPreviewGroup(g);
            String info = I18n.get("preview.groupInfo") + group.getName()
                    + I18n.get("preview.groupItemCount", "count", visibleMemberCount(group))
                    + I18n.get("preview.paramsLabel") + groupParamStatusText(group);
            if (isTextMode()) {
                refs.imagePreviewBox().setVisible(false);
                refs.imagePreviewBox().setManaged(false);
                refs.textPreviewArea().setVisible(true);
                refs.textPreviewArea().setManaged(true);
                refs.textPreviewArea().setText(info);
            } else {
                refs.textPreviewArea().setVisible(false);
                refs.textPreviewArea().setManaged(false);
                refs.imagePreviewBox().setVisible(true);
                refs.imagePreviewBox().setManaged(true);
                previewRenderer.resetImageArea(info, false);
            }
            return;
        }
        PreviewItem it = item instanceof PreviewItem p ? p : null;
        if (isTextMode()) {
            refs.imagePreviewBox().setVisible(false);
            refs.imagePreviewBox().setManaged(false);
            refs.textPreviewArea().setVisible(true);
            refs.textPreviewArea().setManaged(true);
            if (it == null) {
                refs.textPreviewArea().setText(visibleCount() == 0 ? HINT_NO_FILES : "");
                return;
            }
            if (effectiveForObject(it) == null) {
                refs.textPreviewArea().setText(HINT_NEED_PARAMS);
                return;
            }
            switch (it.status) {
                case WAITING -> refs.textPreviewArea().setText(I18n.get("preview.imageParsing"));
                case FAILED -> refs.textPreviewArea().setText(I18n.get("preview.parseFailed") + it.errorMsg);
                case EMPTY -> refs.textPreviewArea().setText(I18n.get("preview.emptyContentShort"));
                case READY -> previewRenderer.renderTextPreview(it);
            }
        } else {
            refs.textPreviewArea().setVisible(false);
            refs.textPreviewArea().setManaged(false);
            refs.imagePreviewBox().setVisible(true);
            refs.imagePreviewBox().setManaged(true);
            if (it == null) {
                previewRenderer.resetImageArea(visibleCount() == 0 ? HINT_NO_FILES : "", false);
                return;
            }
            if (effectiveForObject(it) == null) {
                previewRenderer.resetImageArea(HINT_NEED_PARAMS, false);
                return;
            }
            if (it.status == PreviewItem.Status.FAILED) {
                refs.imageHintLabel().setText(I18n.get("preview.parseFailed") + it.errorMsg);
                refs.imageHintLabel().setVisible(true);
                refs.previewEffectButton().setVisible(true);
                refs.previewEffectButton().setDisable(true);
                refs.imageScrollPane().setVisible(false);
                refs.imageScrollPane().setManaged(false);
            } else if (it.cachedThumbs != null) {
                previewRenderer.renderThumbnails(it);
            } else if (previewLoader.isLoading(it)) {
                // 加载中：保留渐进追加的网格，不重置
                refs.imageHintLabel().setText(I18n.get("preview.imageLoading"));
                refs.imageHintLabel().setVisible(true);
                refs.previewEffectButton().setVisible(true);
                refs.previewEffectButton().setDisable(true);
                refs.imageScrollPane().setVisible(true);
                refs.imageScrollPane().setManaged(true);
            } else {
                previewRenderer.resetImageArea(null, true);
            }
        }
    }

    /** 组参数状态提示（6.5）：组条目选中时预览区显示"该组使用独立参数"或"该组继承全局参数" */
    private String groupParamStatusText(GroupItem<PreviewItem> g) {
        boolean imageMode = refs.imageModeToggle().isSelected();
        AppConfig own = imageMode ? g.getImageParamsCache() : g.getTextParamsCache();
        if (own != null) {
            return I18n.get("group.paramsIndependent");
        }
        AppConfig global = imageMode ? paramDomain.imageParamsCache() : paramDomain.textParamsCache();
        return global != null ? I18n.get("group.paramsInherited") : I18n.get("group.paramsInheritedUnconfirmed");
    }

    private void updateRenameNote(Object sel) {
        AppConfig params = effectiveForObject(sel);
        if (params == null) {
            params = currentParamsOrDefaults();
        }
        boolean show = !isTextMode() && params.isSingleOutputMode() && params.isEnableBatchRenaming();
        refs.renameNoteLabel().setVisible(show);
        refs.renameNoteLabel().setManaged(show);
    }

    // ==================== 图片按需加载 ====================

    @FXML
    private void onPreviewEffect() {
        previewLoader.load();
    }

    /** 进度条活动样式切换（拍板 2026-09-24）：一次只保留一个活动类；null = 默认色 */
    private void setProgressBarActivity(String styleClass) {
        refs.progressBar().getStyleClass().removeAll(ExtractOutcomeStyles.progressActivityStyles());
        if (styleClass != null) {
            refs.progressBar().getStyleClass().add(styleClass);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 会话 12：磁盘占用跨台阶回调（在预览后台线程被 checkSizeAlert 同步调用）。
     * 台阶序号换算 GiB、取当前实际占用后，回 FX 线程弹<b>右上角非阻塞 toast</b>
     * （会话 19：原非模态 Alert 换成应用内提醒；连续跨台阶由 toast 图层自行限流，不再需要"先关再弹"）。
     */
    void onSizeAlertStep(int step) {
        long stepGiB = step * previewCacheLifecycle.sizeAlertStepBytes() / (1024L * 1024 * 1024);
        double actualGiB = previewCacheLifecycle.lastObservedTotalBytes() / (1024.0 * 1024 * 1024);
        String message = I18n.get("cache.sizeAlert",
                        "step", stepGiB, "actual", String.format("%.1f", actualGiB),
                        "dir", previewCacheLifecycle.store().root())
                + I18n.get("cache.sizeAlertNote");
        GuiNotifications.cacheStepWarning(refs.statusLabel(), message);
    }
}
