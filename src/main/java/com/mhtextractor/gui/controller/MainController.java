package com.mhtextractor.gui.controller;

import com.mhtextractor.app.ExtractionPreconditions;
import com.mhtextractor.app.PreviewService;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.app.WorkspaceImportService;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.config.GuiStateManager;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.gui.component.ExtractOutcomeStyles;
import com.mhtextractor.gui.component.GuiNotifications;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.WorkspaceFxAdapter;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.MhtImportPlanner;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SourceKinds;
import com.mhtextractor.util.UndoManager;
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
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
    /* 进度条活动样式类（含各色）与"提取结果 → 样式/颜色"的映射统一在 gui.component.ExtractOutcomeStyles */

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

    /** 输出单元规划的字段视图（路径/来源目录/移除标记，阶段 8） */
    static final OutputUnitPlanner.ItemView<PreviewItem> ITEM_VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(PreviewItem item) {
            return item.path;
        }

        @Override
        public String sourceFolder(PreviewItem item) {
            return item.sourceFolder;
        }

        @Override
        public boolean removed(PreviewItem item) {
            return item.removed;
        }

        @Override
        public AppConfig textItemParams(PreviewItem item) {
            return item.textItemParams;
        }

        @Override
        public AppConfig imageItemParams(PreviewItem item) {
            return item.imageItemParams;
        }
    };

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
    // 测试侧仍然只有一条通道：测试源集的 MainViewTestBridge（反射）。

    // ===== 状态 =====
    // 撤销栈（F4）：唯一栈由 app.WorkspaceModel 独占；结构类由模型 undo() 应用，参数 / 拖拽类由本类 popRecord() 后自行应用
    /** 工作区模型（Q4/P3）：**业务数据序的唯一所有者** —— 不再与 GUI 共享可变列表；成功命令会发布 WorkspaceChange */
    final WorkspaceModel ws = new WorkspaceModel();
    /** 模型 → JavaFX 的投影层（Q4/P3）：显示序（ListView 绑定）由它独占维护，数据序只读投影由它提供。
     *  投影<b>时机</b>仍由本类显式给出；"为何不在每次变更上自动投影"见 {@link WorkspaceFxAdapter} 的类 javadoc。 */
    final WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(ws);
    /** 数据序只读投影：保留同名入口给 FeatureExercise（P7 才改造）；唯一写操作 clear() 转发为模型命令 */
    final List<Object> items = adapter.dataOrder();
    /** 显示序：ListView 直接渲染的展平列表，按数据序实时重建（F5 显示序/数据序分离）—— adapter.displayItems() 的只读别名 */
    private final ObservableList<Object> displayItems = adapter.displayItems();

    /** 两套已确认提取参数缓存：null 表示该模式尚未确认过参数 */
    AppConfig textParamsCache;
    AppConfig imageParamsCache;

    /** 工作区后台活动代次：预解析/换目录/换模式/开始提取时自增，取消旧任务 */
    int parseToken = 0;
    /** 列表选择代次：切换选择时自增，取消过期的图片懒加载 */
    private int requestToken = 0;
    /** 预览会话缓存（需求 2 会话 9）：存储层 + 四条生命周期规则；root = <java.io.tmpdir>/mht-extractor-preview（P6/P8，与 HtmlExportBuilder 的 mht-extractor-view 并列） */
    final PreviewCacheLifecycle previewCacheLifecycle = new PreviewCacheLifecycle(
            new PreviewCacheStore(Paths.get(System.getProperty("java.io.tmpdir"), "mht-extractor-preview")));
    /**
     * 工作区缓存失效服务（E2.2）：把原先散落在 12 处的"规则 (a)/(c)"调用收敛为具名操作，
     * 并成为 {@code com.mhtextractor.app}（无头应用层）的第一块砖。零 JavaFX 依赖。
     */
    private final com.mhtextractor.app.WorkspaceParamService workspaceParamService =
            new com.mhtextractor.app.WorkspaceParamService(previewCacheLifecycle);
    /**
     * 预览服务（步骤 2）：把「算缓存键 → 查缓存 → 未命中则解析+抽图+原子落盘 → 组装缩略图元数据」
     * 这条<b>业务链路</b>从本控制器搬进无头应用层（{@code com.mhtextractor.app.PreviewService}）。
     * 控制器此后只负责：起线程、解码像素、上屏、弹窗、进度。
     */
    private final com.mhtextractor.app.PreviewService previewService =
            new com.mhtextractor.app.PreviewService(previewCacheLifecycle);
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
            new WorkspaceExtractionService(previewCacheLifecycle);
    /**
     * 提取任务协调器（Q3 / P2）：JavaFX {@code Task} 生命周期与 UI 回调的宿主。
     * <b>partial 统计（原 {@code lastExtractStats}）、运行标记（原 {@code running}）与在途任务
     * （原 {@code currentTask}）三个提取状态字段都已归它持有</b>——本类不再有第二份可变 holder：
     * 写入只发生在协调器一处（服务返回值落一次），取消回调只读它。测试支撑访问经
     * {@link #extractStats()} 与 {@link #running()}。
     */
    private final ExtractionTaskCoordinator extractionCoordinator =
            new ExtractionTaskCoordinator(extractionService);
    /** 退出清空已完成：二次 close 放行（规则 d） */
    private volatile boolean shutdownCacheCleared = false;
    /** 正在清空缓存（FX 线程独占）：挡住清空期间的重复关窗请求，避免开两份清理 */
    private boolean shutdownInProgress = false;
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
        @Override public PreviewCacheLifecycle cacheLifecycle() { return previewCacheLifecycle; }
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
            return ParamScope.effectiveImageParams(item.parentGroup, item.imageItemParams, imageParamsCache);
        }
        @Override public PreviewService previewService() { return previewService; }
        @Override public PreviewCacheLifecycle cacheLifecycle() { return previewCacheLifecycle; }
        @Override public PreviewRenderer renderer() { return previewRenderer; }
        @Override public ProgressBar progressBar() { return refs.progressBar(); }
        @Override public Label statusLabel() { return refs.statusLabel(); }
        @Override public Label imageHintLabel() { return refs.imageHintLabel(); }
        @Override public Button previewEffectButton() { return refs.previewEffectButton(); }
        @Override public boolean extractionRunning() { return running().get(); }
        @Override public void setProgressBarActivity(String styleClass) { MainController.this.setProgressBarActivity(styleClass); }
        @Override public void updateProgressStatus(int token) { MainController.this.updateProgressStatus(token); }
        @Override public void refreshList() { refs.fileListView().refresh(); }
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
        @Override public Object animLockedSelection() { return groupCommand.animLockedSelection(); }
        @Override public boolean isCurrentModeConfirmed() { return currentModeConfirmed(); }
        @Override public void cancelPreviewLoad() { requestToken++; previewLoader.cancel(); }
        @Override public void updateListButtons() { MainController.this.updateListButtons(); }
        @Override public void updateInfoArea(Object sel) { MainController.this.updateInfoArea(sel); }
        @Override public void updateContentArea(Object sel) { MainController.this.updateContentArea(sel); }
        @Override public void updateAdjustCheck(Object sel) { MainController.this.updateAdjustCheck(sel); }
        @Override public void updateProgressStatus() { MainController.this.updateProgressStatus(parseToken); }
        @Override public void renderThumbnails(PreviewItem item) { previewRenderer.renderThumbnails(item); }
        @Override public void startPreParseFor(List<PreviewItem> items) { MainController.this.startPreParseFor(items); }
        @Override public void clearPreviewCache(PreviewItem item) { MainController.this.clearPreviewCache(item); }
        @Override public void toggleGroupExpanded(GroupItem<PreviewItem> group) { groupCommand.toggleGroupExpanded(group); }
    });
    /** Q9a：FeatureExercise 按<b>字段访问</b>读它的 isDisable()，故保留同名字段指向新类里那一个 */
    MenuItem ctxClearParams;
    /** Q10：参数剪贴板（R7.1-R7.7）—— 复制快照 + 批量应用编排；路由与应用本体都是下方那两个方法 */
    private final ParamClipboard paramClipboard = new ParamClipboard(ws::push, this::paramScopeTarget, this::applyTargetParams);

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

    /** 规则 (d)：关窗清空（§2.2/§2.6(6)）。有缓存 → 无按钮等待卡片 → 停后台活动 → 等在途写入（≤2s）→ 清空 → 清完自动关窗；
     *  无缓存 → 直接放行。只清 PreviewCacheStore 的 root，绝不触碰 java.io.tmpdir 整体。
     *
     *  <p><b>P7：本方法不再读 {@code test.mode}</b>。此前有一条"自动化不等卡片"的旁路分支，使生产启动代码
     *  与测试开关耦合；现在无论谁来关窗都走同一条生产路径。GUI 自动化改由测试源集的
     *  {@code gui.acceptance.TestMain} 承载，收尾用 {@code Platform.exit()+System.exit()}，
     *  不经过关窗处理器，因此那条旁路对它本来也不起作用。</p> */
    void onWindowCloseRequest(WindowEvent e) {
        if (shutdownCacheCleared) {
            return;                                    // 已清空 → 放行关闭
        }
        if (NotificationHost.hasPendingDecision(refs.statusLabel())) {
            e.consume();                               // 有未回答的确认卡：不放行（旧模态 Alert 本就关不掉主窗）
            return;
        }
        if (shutdownInProgress) {
            e.consume();                               // 正在清空 → 继续等，重复的关窗请求一律挡住
            return;
        }
        if (!workspaceParamService.hasCachedEntries()) {
            return;                                    // 无缓存 → 放行（不显示卡片）
        }
        e.consume();
        shutdownInProgress = true;
        cancelWorkspaceActivity();                     // 仍在 FX 线程调用：与旧实现同线程，保持 token 可见性语义
        Stage stage = (Stage) e.getSource();
        // 会话 19：无按钮等待卡片 —— 清空期间不可交互（persistent），清完自动收卡片并自动关窗，无需点确定
        GuiNotifications.cacheClearing(refs.statusLabel(), previewCacheLifecycle.store().root(),
                this::clearPreviewCache, stage::close);
    }

    /** 关窗收尾（后台线程）：等在途写入结束 → 清空缓存（cancelWorkspaceActivity 已由调用方在 FX 线程先行调用） */
    private void clearPreviewCache() {
        previewLoader.awaitInFlight(2000);
        previewCacheLifecycle.onShutdown(() -> { });
        shutdownCacheCleared = true;
    }

    // ==================== GUI 状态持久化（logs/gui-state.json） ====================

    void restoreGuiState() {
        GuiState state = GuiStateManager.load();
        if (state == null) {
            return;
        }
        if (state.getInputFolder() != null && !state.getInputFolder().isEmpty()) {
            refs.inputFolderField().setText(state.getInputFolder());
        }
        if (state.getOutputFolder() != null && !state.getOutputFolder().isEmpty()) {
            refs.outputFolderField().setText(state.getOutputFolder());
        }
        refs.openFolderAfterCompleteCheck().setSelected(state.isOpenFolderAfterComplete());
        if (state.isImageModeEnabled()) {
            refs.imageModeToggle().setSelected(true);
        } else {
            refs.textModeToggle().setSelected(true);
        }
    }

    void saveGuiState() {
        GuiState state = new GuiState();
        state.setImageModeEnabled(refs.imageModeToggle().isSelected());
        state.setInputFolder(refs.inputFolderField().getText());
        state.setOutputFolder(refs.outputFolderField().getText());
        state.setOpenFolderAfterComplete(refs.openFolderAfterCompleteCheck().isSelected());
        Locale loc = I18n.currentLocale();
        state.setLocale(loc.getLanguage() + (loc.getCountry().isEmpty() ? "" : "_" + loc.getCountry()));
        GuiStateManager.save(state);
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
        updateStartButtonState();
    }

    /** 会话收尾：清位并刷新开始按钮（★ 必须走派生更新，不裸 setDisable） */
    @Override
    public void onImportFinished() {
        importPending = null;
        updateStartButtonState();
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
        startPreParseFor(items);
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

    /** 日志文案里的模式词（图片/文本 → Image/Text），供 log.* 模板的 {0} 占位符使用 */
    private static String modeWord(boolean imageMode) {
        return I18n.get(imageMode ? "mode.image" : "mode.text");
    }

    /** 当前模式参数是否已确认（确定过一次即视为已设置） */
    private boolean currentModeConfirmed() {
        return refs.imageModeToggle().isSelected() ? imageParamsCache != null : textParamsCache != null;
    }

    /** 当前模式已确认参数；未确认时返回默认参数（仅用于展示，不算已设置） */
    private AppConfig currentParamsOrDefaults() {
        AppConfig c = refs.imageModeToggle().isSelected() ? imageParamsCache : textParamsCache;
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

    /** 生效参数判定的解析上下文（唯一构造点）：当前模式 + 两个全局已确认参数缓存 */
    private com.mhtextractor.app.EffectiveParamsService.Context effectiveContext() {
        return new com.mhtextractor.app.EffectiveParamsService.Context(
                refs.imageModeToggle().isSelected(), textParamsCache, imageParamsCache);
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
        refs.paramSettingsButton().setDisable(!anyModeSelected);
        updateStartButtonState();
        // 清除参数拍板：按钮启用状态随当前模式刷新（同时修复"应用到全局"按钮切模式后陈旧态）
        updateListButtons();

        // 预览工作区
        refs.modeLabel().setText(I18n.get("mode.labelPrefix") + (refs.imageModeToggle().isSelected() ? I18n.get("mode.image")
                : (refs.textModeToggle().isSelected() ? I18n.get("mode.text") : I18n.get("mode.none"))));
        Object sel = currentSelection();
        updateBadgeHeader();
        updateOutputModeLabel(sel);
        updateRenameNote(sel);
        showCurrentModeArea();
        refs.fileListView().refresh();
        updateAdjustCheck(sel);
        updateInfoArea(sel);
        updateContentArea(sel);

        if (anyModeSelected && anyVisibleItemHasEffectiveParams() && visibleCount() > 0) {
            startPreParse();
        }
        // 方案A：切模式后同步进度条状态（新模式无参数条目 → 隐藏；否则显示新模式解析进度）
        updateProgressStatus(parseToken);
    }

    private void showCurrentModeArea() {
        refs.textPreviewArea().setVisible(isTextMode());
        refs.textPreviewArea().setManaged(isTextMode());
        refs.imagePreviewBox().setVisible(!isTextMode());
        refs.imagePreviewBox().setManaged(!isTextMode());
    }

    private void updateBadgeHeader() {
        boolean showBadgeCol = isTextMode() && anyVisibleItemHasChapterNumbers();
        refs.badgeHeaderLabel().setVisible(showBadgeCol);
        refs.badgeHeaderLabel().setManaged(showBadgeCol);
    }

    /** 任一有效项目的生效参数开启章节序号（组参数可独立开启，列头按需显示） */
    private boolean anyVisibleItemHasChapterNumbers() {
        for (PreviewItem it : allVisibleItems()) {
            AppConfig eff = effectiveForObject(it);
            if (eff != null && eff.isAddChapterNumber()) {
                return true;
            }
        }
        return false;
    }

    /** 输出模式标签按当前选中对象的生效参数显示（组条目→该组生效参数；项目→其生效参数；无选中→全局） */
    private void updateOutputModeLabel(Object sel) {
        AppConfig params = effectiveForObject(sel);
        if (params == null) {
            params = currentParamsOrDefaults();
        }
        refs.outputModeLabel().setText(isTextMode()
                ? (params.isSeparateFiles() ? I18n.get("extract.outputModeSeparate") : I18n.get("extract.outputModeMerged"))
                : (params.isSingleOutputMode() ? I18n.get("extract.outputModeMerged") : I18n.get("extract.outputModeSeparate")));
    }

    // ==================== 参数设置弹窗 ====================

    /** 主窗口参数按钮路由（条目独立参数需求拍板）：选中组条目 → 该组参数；
     *  选中组内成员 → 其所在组参数；选中顶层项目 → 该条目的条目级参数；未选中 → 全局参数 */
    @FXML
    private void onParamSettings() {
        if (groupCommand.isAnimating()) {
            return;
        }
        openParamDialog(paramScopeTarget(currentSelection()));
    }

    /** 选择对象 → 参数作用域目标（R7.4 复用点）："设置参数"按钮与"应用到所选"共用这一份路由 */
    private ParamScope.Target paramScopeTarget(Object sel) {
        return switch (sel) {
            case GroupItem<?> g -> new ParamScope.GroupTarget(g);
            case PreviewItem it when it.parentGroup != null -> new ParamScope.GroupTarget(it.parentGroup);
            case PreviewItem it -> new ParamScope.ItemTarget<>(it, it.textItemParams, it.imageItemParams);
            case null, default -> ParamScope.GLOBAL;
        };
    }

    /** 确认路径的应用本体（R7.4 唯一实现点）：组 → 组参数；条目 → 条目参数；其余 → 全局参数 */
    private void applyTargetParams(ParamScope.Target target, boolean imageMode, AppConfig params) {
        switch (target) {
            case ParamScope.GroupTarget(GroupItem<?> g) -> applyGroupParams(ListInteractionController.asPreviewGroup(g), imageMode, params);
            case ParamScope.ItemTarget<?> it -> applyItemParams((PreviewItem) it.item(), imageMode, params);
            default -> {
                applyConfirmedParams(imageMode, params);
                log.info(I18n.msg("log.maincontroller.15", modeWord(imageMode)));
            }
        }
    }

    /** 打开参数设置侧边栏并应用确认结果（按钮行与右键菜单共用；空白区域菜单项强制传全局目标） */
    private void openParamDialog(ParamScope.Target target) {
        boolean imageMode = refs.imageModeToggle().isSelected();
        try {
            // Q6a：非模态覆盖式侧边栏取代模态窗口。面板按窗口缓存（ParamSidePanel.of），
            // 复用同一个 ParamFormPane 实例 ⇒ "收起再展开同一目标"保留草稿（R1.5）。
            ParamSidePanel panel = ParamSidePanel.of(refs.statusLabel());
            // Q6b/R1.7：草稿变了（改字段 / 提交 / 切目标丢弃）就重算开始按钮 —— 判定仍只有
            // ParamFormPane.isDirty() 一处，这里只接线（每次 show 都重设，幂等）。
            panel.setDirtyListener(this::updateStartButtonState);
            panel.show(target, imageMode, textParamsCache, imageParamsCache,
                    params -> applyTargetParams(target, imageMode, params));
            updateStartButtonState();   // 本次 show 可能什么都没变（同目标重开 / 取消切换）⇒ 兜底重算
        } catch (Exception e) {
            log.error(I18n.msg("log.maincontroller.16"), e);
            GuiNotifications.paramDialogOpenFailed(refs.statusLabel());
        }
    }

    /** 写入某条目的条目级独立参数（条目独立参数需求）：不写全局，仅清缓存重解析该条目；入撤销栈（PARAM_SET） */
    void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? item.imageItemParams : item.textItemParams;
        if (imageMode) {
            item.imageItemParams = params;
        } else {
            item.textItemParams = params;
        }
        paramClipboard.pushParamUndo(imageMode, item.path, null, before);
        clearExtractOutcomes(List.of(item));
        clearPreviewCache(item);
        workspaceParamService.paramsChanged(imageMode, item);   // 规则 (a)：参数变化 → 删该项目缓存目录
        startPreParseFor(List.of(item));
        log.info(I18n.msg("log.maincontroller.17", item.name, modeWord(imageMode)));
        refs.fileListView().refresh();
        updateListButtons();
        updateInfoArea(item);
        updateExtractBanner(item);
    }

    /** 写入某模式的已确认参数缓存并刷新工作区（agent 演练/自动化测试也走此入口）；入撤销栈（PARAM_SET） */
    void applyConfirmedParams(boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? imageParamsCache : textParamsCache;
        if (imageMode) {
            imageParamsCache = params;
        } else {
            textParamsCache = params;
        }
        paramClipboard.pushParamUndo(imageMode, null, null, before);
        // 拍板：全局参数重新确认 → 全部条目旧提取结果色失效
        clearExtractOutcomes(allPreviewItems());
        // 规则 (a)：全局参数变化 → 全部条目缓存目录删除（保守全删：有独立参数的条目删除也只是下次预览重建，语义安全）
        workspaceParamService.paramsChanged(imageMode, allPreviewItems());
        refreshAfterParamsChanged();
        updateExtractBanner(currentSelection());
    }

    /** 写入某组的已确认参数缓存（R8）：参数作用域仅限预览解析与显示，提取管线按组参数输出属阶段 8 */
    void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? group.getImageParamsCache() : group.getTextParamsCache();
        if (imageMode) {
            group.setImageParamsCache(params);
        } else {
            group.setTextParamsCache(params);
        }
        log.info(I18n.msg("log.maincontroller.18", group.getName(), modeWord(imageMode)));
        // 入撤销栈（PARAM_SET，组参数设置可撤销）
        paramClipboard.pushParamUndo(imageMode, null, group.getGroupNo(), before);
        // 拍板：组参数重新确认 → 该组成员旧提取结果色失效
        List<PreviewItem> members = group.getMembers().stream().filter(m -> !m.removed).toList();
        clearExtractOutcomes(members);
        // 规则 (a)：组参数变化 → 该组成员缓存目录删除
        workspaceParamService.paramsChanged(imageMode, members);
        refreshAfterTargetedParamsChange(members);
        updateListButtons();
        updateExtractBanner(currentSelection());
    }

    // ==================== 右键菜单（右键菜单功能需求；Q9a 外迁后只剩回调面） ====================

    /**
     * 右键菜单的宿主回调面（Q9a）：5 个查询 + 12 个动作转发。
     *
     * <p><b>动作实现刻意不搬</b>：建组 / 拆组 / 移除 / 清参数 / 应用全局 / 打开目录 / 查看源码都要动模型、
     * 缓存与 UI 状态，实现留在本类，{@link FileListContextMenu} 只转发"点了哪一项、目标是哪个对象"。</p>
     *
     * <p><b>模型类型判定留在本类</b>：{@code targetType} / {@code isLiveTarget} 是搬前
     * {@code updateContextMenuState} 头四行与 {@code pickItemAt} 里那一句 {@code it.removed} 的逐字搬移，
     * 于是菜单类零模型依赖（只认 {@link FileListContextMenu.TargetType} 这个四布尔快照）。
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
            @Override public FileListContextMenu.TargetType targetType(Object target) {
                return switch (target) {
                    case null -> FileListContextMenu.TargetType.BLANK;
                    case GroupItem<?> g -> FileListContextMenu.TargetType.GROUP;
                    case PreviewItem it when it.parentGroup != null -> FileListContextMenu.TargetType.MEMBER;
                    case PreviewItem it -> FileListContextMenu.TargetType.TOP_ITEM;
                    default -> new FileListContextMenu.TargetType(false, false, false, false);
                };
            }
            @Override public boolean isLiveTarget(Object item) {
                return !(item instanceof PreviewItem it && it.removed);
            }
            @Override public FileListContextMenu.ParamsInfo paramsInfo(Object target) {
                return paramClipboard.paramsInfo(resolveParamTargets(target), hasIndependentParams(target),
                        effectiveParamsService, effectiveContext());
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
            @Override public void removeGroup(Object target) { onContextRemoveGroup(target); }
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
            openParamDialog(ParamScope.GLOBAL);
        } else {
            onParamSettings();
        }
    }

    /** 移除组（Q3 拍板：每次必弹确认；与按钮行整组移除共用同一路径，G2 统一） */
    private void onContextRemoveGroup(Object target) {
        if (groupCommand.isAnimating()) {
            return;
        }
        GroupItem<PreviewItem> group = null;
        if (target instanceof GroupItem<?> g) {
            group = ListInteractionController.asPreviewGroup(g);
        } else if (target instanceof PreviewItem it && it.parentGroup != null) {
            group = it.parentGroup;
        }
        if (group != null) {
            removeGroupWithConfirm(group);
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
        applyGlobalParamsTo(target);
    }

    /** 按钮行"应用到全局"：目标 = 当前选中条目 */
    @FXML
    private void onApplyGlobalClicked() {
        if (groupCommand.isAnimating()) {
            return;
        }
        Object sel = currentSelection();
        if (hasIndependentParams(sel)) {
            applyGlobalParamsTo(sel);
        }
    }

    /** 拍板：目标生效参数集写入当前模式全局缓存，清空当前模式所有独立参数（条目级 + 组，含目标自身），
     *  全部条目继承新全局；另一模式独立参数不受影响。入撤销栈（APPLY_GLOBAL，含应用前完整参数快照） */
    private void applyGlobalParamsTo(Object target) {
        AppConfig params = effectiveForObject(target);
        if (params == null) {
            return;
        }
        boolean imageMode = refs.imageModeToggle().isSelected();
        // 撤销快照（拍板 2026-09-22）：应用前的全局参数 + 全部独立参数，撤销可完整回退
        AppConfig globalBefore = imageMode ? imageParamsCache : textParamsCache;
        Map<String, AppConfig> itemBefore = new HashMap<>();
        for (PreviewItem it : allPreviewItems()) {
            itemBefore.put(it.path, imageMode ? it.imageItemParams : it.textItemParams);
        }
        Map<Integer, AppConfig> groupBefore = new HashMap<>();
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                groupBefore.put(g.getGroupNo(),
                        imageMode ? g.getImageParamsCache() : g.getTextParamsCache());
            }
        }
        if (imageMode) {
            imageParamsCache = params;
        } else {
            textParamsCache = params;
        }
        for (PreviewItem it : allPreviewItems()) {
            if (imageMode) {
                it.imageItemParams = null;
            } else {
                it.textItemParams = null;
            }
        }
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                if (imageMode) {
                    g.setImageParamsCache(null);
                } else {
                    g.setTextParamsCache(null);
                }
            }
        }
        ws.push(new UndoManager.Record<>(UndoManager.RecordType.APPLY_GLOBAL, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                imageMode, globalBefore, itemBefore, groupBefore, false, null, null, null,
                false, null, null));
        clearExtractOutcomes(allPreviewItems());
        // 规则 (a)：全局参数写入 + 全部独立参数清空 → 全部条目生效参数变化 → 删除全部缓存目录
        workspaceParamService.paramsChanged(imageMode, allPreviewItems());
        log.info(I18n.msg("log.maincontroller.27", modeWord(imageMode)));
        refreshAfterParamsChanged();
        updateListButtons();
        updateExtractBanner(currentSelection());
    }

    // ==================== 清除参数（清除参数功能） ====================

    /** 按钮行"清除参数"：目标 = 勾选集合（若有）否则单选，与"移除"一致 */
    @FXML
    private void onClearParamsClicked() {
        if (groupCommand.isAnimating()) {
            return;
        }
        clearParamsForTargets(resolveParamTargets(currentSelection()));
    }

    /** 右键菜单"清除参数"：动作逻辑与按钮一致（勾选优先 → 右键目标；启用看右键目标） */
    private void onContextClearParams(Object target) {
        if (groupCommand.isAnimating()) {
            return;
        }
        clearParamsForTargets(resolveParamTargets(target));
    }

    // ==================== 参数目标解析 / 清除参数 / 复制与应用（Q10） ====================

    /** 勾选集合优先 → 回退对象（与"移除"的勾选优先拍板一致）；复制/应用与清除参数共用同一"所选"口径 */
    private List<Object> resolveParamTargets(Object fallback) {
        List<PreviewItem> checked = allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        if (!checked.isEmpty()) {
            return new ArrayList<>(checked);
        }
        return fallback == null ? new ArrayList<>() : new ArrayList<>(List.of(fallback));
    }

    /** 右键「复制参数」（R7.2 的禁用理由写在菜单项文本里；R7.7 只存可持久化提取参数快照） */
    private void onContextCopyParams(Object target) {
        List<Object> targets = resolveParamTargets(target);
        if (!targets.isEmpty()) paramClipboard.copy(effectiveForObject(targets.get(0)));
    }

    /** 右键「应用到所选」（R7.4/R7.5）：逐目标复用确认路径的应用函数；整批只压一条 PARAM_SET 记录 */
    private void onContextApplyToSelection(Object target) {
        if (groupCommand.isAnimating() || paramClipboard.isEmpty()) return;
        paramClipboard.applyToSelection(resolveParamTargets(target), refs.imageModeToggle().isSelected());
    }

    /**
     * 清除参数（拍板 Q1-Q4）：只清当前模式。组外条目 → 清条目级参数；组内成员/组条目 → 清整组组参数
     * （按组去重）；无参数目标自然跳过；一次操作一条 CLEAR_PARAMS 撤销记录（before 快照），一次撤销全部恢复。
     */
    private void clearParamsForTargets(List<Object> rawTargets) {
        boolean imageMode = refs.imageModeToggle().isSelected();
        List<PreviewItem> topItems = new ArrayList<>();
        Set<GroupItem<PreviewItem>> groups = new LinkedHashSet<>();
        for (Object o : rawTargets) {
            if (o instanceof PreviewItem it && !it.removed) {
                if (it.parentGroup != null) {
                    if ((imageMode ? it.parentGroup.getImageParamsCache()
                                   : it.parentGroup.getTextParamsCache()) != null) {
                        groups.add(it.parentGroup);
                    }
                } else if ((imageMode ? it.imageItemParams : it.textItemParams) != null) {
                    topItems.add(it);
                }
            } else if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> grp = ListInteractionController.asPreviewGroup(g);
                if ((imageMode ? grp.getImageParamsCache() : grp.getTextParamsCache()) != null) {
                    groups.add(grp);
                }
            }
        }
        if (topItems.isEmpty() && groups.isEmpty()) {
            return;
        }
        // 撤销快照：清除前该模式参数（只收录本次实际清除的目标）
        Map<String, AppConfig> itemBefore = new HashMap<>();
        for (PreviewItem it : topItems) {
            itemBefore.put(it.path, imageMode ? it.imageItemParams : it.textItemParams);
        }
        Map<Integer, AppConfig> groupBefore = new HashMap<>();
        for (GroupItem<PreviewItem> grp : groups) {
            groupBefore.put(grp.getGroupNo(),
                    imageMode ? grp.getImageParamsCache() : grp.getTextParamsCache());
        }
        if (imageMode) {
            for (PreviewItem it : topItems) {
                it.imageItemParams = null;
            }
            for (GroupItem<PreviewItem> grp : groups) {
                grp.setImageParamsCache(null);
            }
        } else {
            for (PreviewItem it : topItems) {
                it.textItemParams = null;
            }
            for (GroupItem<PreviewItem> grp : groups) {
                grp.setTextParamsCache(null);
            }
        }
        ws.push(new UndoManager.Record<>(UndoManager.RecordType.CLEAR_PARAMS, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, false, null, null, null,
                imageMode, itemBefore, groupBefore));
        // 受影响条目：清除条目参数的项目 + 清除组参数的各组全部有效成员
        List<PreviewItem> affected = new ArrayList<>(topItems);
        for (GroupItem<PreviewItem> grp : groups) {
            affected.addAll(grp.getMembers().stream().filter(m -> !m.removed).toList());
        }
        // 拍板：参数变更 → 受影响条目旧提取结果色失效
        clearExtractOutcomes(affected);
        // 规则 (a)：清除参数也是参数变化（生效参数回落组/全局）→ 受影响条目缓存目录删除
        workspaceParamService.paramsChanged(imageMode, affected);
        log.info(I18n.msg("log.maincontroller.28", topItems.size(), groups.size(), modeWord(imageMode)));
        refreshAfterTargetedParamsChange(affected);
        updateListButtons();
        updateExtractBanner(currentSelection());
    }

    // ==================== 提取结果回写与 banner（提取结果状态需求） ====================

    /** 提取结果一次性回写（拍板：提取结束/取消后统一回写，非逐文件实时更新）：
     *  按路径匹配条目写入结果字段，列表一次刷新，选中条目 banner 同步 */
    private void applyExtractResults(BatchProcessor.ProcessStats stats) {
        Map<String, FileResult> byPath = new HashMap<>();
        for (FileResult r : stats.getFileResults()) {
            byPath.put(r.path(), r);
        }
        for (PreviewItem it : allPreviewItems()) {
            FileResult r = byPath.get(it.path);
            if (r != null) {
                it.extractOutcome = r.outcome();
                it.extractDetail = buildExtractDetail(r);
            }
        }
        refs.fileListView().refresh();
        updateExtractBanner(currentSelection());
        log.info(I18n.msg("log.maincontroller.29", byPath.size()));
    }

    /** 结果详情拼接：失败/跳过原因 + 警告，分号分隔；无内容返回 null */
    private String buildExtractDetail(FileResult r) {
        List<String> parts = new ArrayList<>();
        if (r.message() != null && !r.message().isEmpty()) {
            parts.add(r.message());
        }
        parts.addAll(r.warnings());
        return parts.isEmpty() ? null : String.join(I18n.get("list.itemSeparator"), parts);
    }

    /** 提取结果色清除（拍板）：重新确认参数后旧结果失效，回到参数色/解析状态色 */
    private void clearExtractOutcomes(List<PreviewItem> targets) {
        for (PreviewItem it : targets) {
            it.extractOutcome = ExtractOutcome.NONE;
            it.extractDetail = null;
        }
    }

    /** 预览区顶部 banner（拍板）：选中条目结果含红（失败/跳过/成功+警告）时显示具体情况；
     *  无提取结果时，解析失败（黄色）条目显示解析失败原因 */
    private void updateExtractBanner(Object sel) {
        if (sel instanceof PreviewItem it && it.extractOutcome != ExtractOutcome.NONE
                && it.extractDetail != null && !it.extractDetail.isEmpty()) {
            String prefix = switch (it.extractOutcome) {
                case FAILED -> I18n.get("extract.statusFailed");
                case SKIPPED -> I18n.get("extract.statusSkipped");
                case SUCCESS_WARNED -> I18n.get("extract.statusWarned");
                default -> null;
            };
            if (prefix != null) {
                refs.extractResultBanner().setText(prefix + I18n.get("list.colon") + it.extractDetail);
                refs.extractResultBanner().setVisible(true);
                refs.extractResultBanner().setManaged(true);
                return;
            }
        }
        if (sel instanceof PreviewItem it && it.status == PreviewItem.Status.FAILED) {
            refs.extractResultBanner().setText(I18n.get("extract.parseFailed") + it.errorMsg);
            refs.extractResultBanner().setVisible(true);
            refs.extractResultBanner().setManaged(true);
            return;
        }
        refs.extractResultBanner().setVisible(false);
        refs.extractResultBanner().setManaged(false);
    }

    void refreshAfterParamsChanged() {
        Object sel = currentSelection();
        updateBadgeHeader();
        updateOutputModeLabel(sel);
        updateRenameNote(sel);
        refs.fileListView().refresh();
        updateAdjustCheck(sel);
        updateInfoArea(sel);
        updateContentArea(sel);
        if (currentModeConfirmed() && visibleCount() > 0) {
            startPreParse();
        }
    }

    /**
     * 局部参数变更后刷新（R8 泛化，清除参数功能复用）：受影响条目旧缓存失效、按新生效参数重解析。
     * 先取消在途后台活动（parseToken +1）作废旧代次在途解析结果；其它项目被代次打断的
     * 在途解析以同一新代次补偿续跑（只补仍处等待中的项，不重新解析已完成项，不误伤其它项目）。
     */
    private void refreshAfterTargetedParamsChange(List<PreviewItem> affected) {
        cancelWorkspaceActivity();
        for (PreviewItem m : affected) {
            m.cachedText = null;
            m.cachedTitle = null;
            m.cachedImages = null;
            m.cachedThumbs = null;
            m.status = PreviewItem.Status.WAITING;
        }
        Object sel = currentSelection();
        updateBadgeHeader();
        updateOutputModeLabel(sel);
        updateRenameNote(sel);
        listInteraction.recomputeBadges();
        refs.fileListView().refresh();
        updateAdjustCheck(sel);
        updateInfoArea(sel);
        updateContentArea(sel);
        updateProgressStatus(parseToken);
        if (!affected.isEmpty()) {
            preParseItems(new ArrayList<>(affected), parseToken);
        }
        List<PreviewItem> interrupted = allVisibleItems().stream()
                .filter(i -> i.status == PreviewItem.Status.WAITING && !affected.contains(i)).toList();
        if (!interrupted.isEmpty()) {
            preParseItems(new ArrayList<>(interrupted), parseToken);
        }
    }

    // ==================== 开始 / 停止 ====================

    @FXML
    private void onStartProcessing() {
        if (groupCommand.isAnimating()) {
            return;
        }
        String inputFolder = refs.inputFolderField().getText();
        String outputFolder = refs.outputFolderField().getText();

        // 会话 18 · E4-3：前置校验**规则**（含"该用 WARNING 还是 ERROR"）已抽到
        // ExtractionPreconditions（零 JavaFX、有独立单测）。
        // 逐条委托以保持原判定**顺序**；级别由规则给出，UI 只负责展示。
        // 刻意不调用聚合形态 validate(Facts)：那会把顺序与级别压平，属行为变更。
        var denial = ExtractionPreconditions.deny(ExtractionPreconditions.blankInput(inputFolder));
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.blankOutput(outputFolder));
        }
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.inputNotDirectory(inputFolder));
        }
        if (denial == null) {
            denial = ExtractionPreconditions.deny(ExtractionPreconditions.noExtractableItems(
                    items.size(), (int) visibleCount()));
        }
        if (denial != null) {
            // 会话 19：ERROR 走居中错误卡片（必须确认），WARNING 走右上角 toast（非阻塞）
            GuiNotifications.preconditionDenied(refs.statusLabel(), denial);
            return;
        }

        boolean imageMode = refs.imageModeToggle().isSelected();

        // 图片模式额外校验
        if (imageMode) {
            AppConfig params = currentParamsOrDefaults();
            if (params.isEnableZipPackaging() || params.isEnableCbzPackaging()) {
                File outRoot = new File(outputFolder);
                if (!outRoot.isDirectory() && !outRoot.mkdirs() && !outRoot.isDirectory()) {
                    log.warn(I18n.msg("log.maincontroller.30", outputFolder));
                }
            }
        }

        // 当前模式参数未确认 → 提醒（区分"从未设置"与"检测到模式切换"两种文案；会话 19：回调式确认）
        boolean otherConfirmed = imageMode ? textParamsCache != null : imageParamsCache != null;
        if (!currentModeConfirmed()) {
            String msg = otherConfirmed
                    ? I18n.get("extract.confirmModeSwitch")
                    : I18n.get("extract.confirmParamsUnset");
            GuiNotifications.paramTargetConfirm(refs.statusLabel(), msg, ok -> {
                if (ok) {
                    startExtractionTask();
                }
            });
            return;
        }

        startExtractionTask();
    }

    /** 以已确认参数（未确认时用默认参数）+ 主窗口 GUI 状态构建运行时提取配置 */
    AppConfig buildRuntimeConfig() {
        AppConfig config = refs.imageModeToggle().isSelected() ? imageParamsCache : textParamsCache;
        if (config == null) {
            config = new AppConfig();
        }
        config.setInputFolder(refs.inputFolderField().getText());
        config.setOutputFolder(refs.outputFolderField().getText());
        config.setImageModeEnabled(refs.imageModeToggle().isSelected());
        config.setOpenFolderAfterComplete(refs.openFolderAfterCompleteCheck().isSelected());
        return config;
    }

    private Map<String, PerFileOverrides> overrides() {
        Map<String, PerFileOverrides> map = new LinkedHashMap<>();
        for (PreviewItem item : allVisibleItems()) {
            map.put(item.path, new PerFileOverrides(item.chapterEnabled));
        }
        return map;
    }

    /**
     * 执行正式提取任务（6.4/6.8：按输出单元拆分批次——未分组按来源 + 各组；
     * 进度跨单元聚合、取消传播、stats 汇总、单元失败不中断整体）
     */
    private void startExtractionTask() {
        // 取消工作区后台活动，避免与提取任务争用进度条/状态栏
        cancelWorkspaceActivity();
        boolean imageMode = refs.imageModeToggle().isSelected();

        // 生成时间戳会话目录，隔离每次提取运行
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(I18n.get("params.datetimeFormat")));
        String sessionOutputFolder = new File(refs.outputFolderField().getText(), timestamp).getPath();
        if (!new File(sessionOutputFolder).mkdirs()) {
            log.warn(I18n.msg("log.maincontroller.31", sessionOutputFolder));
        }
        log.info(I18n.msg("log.maincontroller.32", sessionOutputFolder));

        // 构建输出单元计划（FX 线程快照；单元参数补充运行时 GUI 字段）
        List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                items, ITEM_VIEW, imageMode, textParamsCache, imageParamsCache, sessionOutputFolder);
        for (OutputUnitPlanner.OutputUnit unit : units) {
            AppConfig params = unit.getParams();
            params.setInputFolder(refs.inputFolderField().getText());
            params.setOutputFolder(refs.outputFolderField().getText());
            params.setImageModeEnabled(imageMode);
            params.setOpenFolderAfterComplete(refs.openFolderAfterCompleteCheck().isSelected());
        }
        log.info(I18n.msg("log.maincontroller.33", units.stream()
                .map(u -> I18n.get("extract.groupFileCount", "group", u.label(),
                        "count", u.fileCount())).toList()));
        Map<String, PerFileOverrides> overrides = overrides();

        extractionCoordinator.run(
                new WorkspaceExtractionService.ExtractionRequest(sessionOutputFolder, units, overrides, imageMode,
                        recursionDecision()),
                refs.statusLabel(),
                extractionUi(sessionOutputFolder, refs.openFolderAfterCompleteCheck().isSelected()));
    }

    /**
     * 递归深度超限回调：窗口内居中确认（会话 19）。等待方是提取后台线程，不冻结界面 ——
     * {@code Platform.runLater} 把确认卡排到 FX 线程，本线程 {@code future.join()} 等结果。
     */
    private java.util.function.Supplier<Boolean> recursionDecision() {
        return () -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            String msg = I18n.get("alert.recursionDepthHeader") + "\n"
                    + I18n.get("alert.recursionDepthContent")
                    + I18n.get("alert.recursionDepthContinue")
                    + I18n.get("alert.recursionDepthSkip");
            Platform.runLater(() -> GuiNotifications.recursionDepthConfirm(refs.statusLabel(), msg, future::complete));
            return future.join();
        };
    }

    /** 提取任务的界面收尾动作（Q3 / P2）：只有控件状态与列表回写，与搬迁前三个回调逐条对应。 */
    private ExtractionTaskCoordinator.ExtractionUi extractionUi(String sessionOutputFolder,
                                                               boolean openFolderAfterComplete) {
        return new ExtractionTaskCoordinator.ExtractionUi() {
            @Override
            public File sessionOutputFolder() {
                return new File(sessionOutputFolder);
            }

            @Override
            public boolean openFolderAfterComplete() {
                return openFolderAfterComplete;
            }

            @Override
            public void setProgress(double value) {
                refs.progressBar().setProgress(value);
            }

            @Override
            public void setProgressBarOutcome(ExtractionTaskCoordinator.ExtractOutcomeStyle style) {
                setProgressBarActivity(switch (style) {
                    case FAILED -> ExtractOutcomeStyles.PROGRESS_EXTRACT_FAILED;
                    case WARNED -> ExtractOutcomeStyles.PROGRESS_EXTRACT_WARNED;
                    case SUCCESS -> ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS;
                });
            }

            @Override
            public void extractionStarted() {
                refs.startButton().setDisable(true);
                refs.stopButton().setDisable(false);
                refs.progressBar().setVisible(true);
                refs.progressBar().setProgress(0);
                setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_EXTRACT_SUCCESS);   // 提取中 = 开始提取按钮绿
            }

            @Override
            public void applyResults(BatchProcessor.ProcessStats stats) {
                applyExtractResults(stats);
            }

            @Override
            public void setStatusText(String text) {
                refs.statusLabel().setText(text);
            }
        };
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
    AtomicReference<BatchProcessor.ProcessStats> extractStats() {
        return extractionCoordinator.lastExtractStats;
    }

    @FXML
    private void onStopProcessing() {
        extractionCoordinator.cancel();
        extractionCoordinator.running.set(false);
        refs.startButton().setDisable(false);
        refs.stopButton().setDisable(true);
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

    /** 清空预览缓存并回到待解析（生效参数切换后重新预解析，阶段 5 机制）。只清内存态；磁盘缓存由 PreviewCacheLifecycle 四条规则管理 */
    private void clearPreviewCache(PreviewItem it) {
        it.cachedText = null;
        it.cachedTitle = null;
        it.cachedImages = null;
        it.cachedThumbs = null;
        it.cachedMeta = null;
        it.status = PreviewItem.Status.WAITING;
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

    /** 开始按钮可用性的唯一派生函数 —— 所有禁用/恢复都必须经由此函数 */
    void updateStartButtonState() {
        boolean anyMode = refs.textModeToggle().isSelected() || refs.imageModeToggle().isSelected();
        // R1.7：有未提交的参数草稿时不许开始提取（判定唯一来源是 ParamFormPane.isDirty()，
        // 由 ParamSidePanel.setDirtyListener 在编辑/提交/切目标时把本方法叫回来）。
        refs.startButton().setDisable(!anyMode || visibleCount() == 0 || isImporting()
                || ParamSidePanel.isDirty(refs.statusLabel()));
    }

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
        updateStartButtonState();
        listInteraction.refreshSelectionUi();
        listInteraction.recomputeBadges();
        // 方案A：无条件刷新——全部无参数时 updateProgressStatus 会隐藏进度条且不动状态栏
        updateProgressStatus(parseToken);
        Object selAfter = refs.fileListView().getSelectionModel().getSelectedItem();
        updateInfoArea(selAfter);
        updateContentArea(selAfter);
    }

    @FXML
    private void onRemoveClicked() {
        if (groupCommand.isAnimating()) {
            return;
        }
        // R5：有复选框勾选时按勾选集合移除（优先于当前选中项，含组内成员）
        List<PreviewItem> targets = allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
        // D7：无勾选且选中组条目 → 整组移除（组名/参数随组对象保留在撤销记录中）
        if (targets.isEmpty() && sel instanceof GroupItem<?> g) {
            // G2（拍板）：整组移除一律必弹确认（R6"仅列表将空时确认"的有意例外），按钮行与右键菜单共用
            removeGroupWithConfirm(ListInteractionController.asPreviewGroup(g));
            return;
        }
        if (targets.isEmpty()) {
            if (sel instanceof PreviewItem it && !it.removed) {
                targets = List.of(it);
            } else {
                return;
            }
        }
        if (targets.size() >= visibleCount()) {
            // R13 提醒文案：全选含折叠组成员时提示（会话 19：回调式居中确认取代 showAndWait）
            final List<PreviewItem> pending = targets;
            String msg = I18n.get("list.removeAllConfirm", "count", pending.size())
                    + collapsedGroupHint(pending);
            GuiNotifications.removeAllConfirm(refs.statusLabel(), msg, ok -> {
                if (ok) {
                    removeSelected(pending);
                }
            });
            return;
        }
        removeSelected(targets);
    }

    /** 移除收尾（原确认框之后的同步段）：执行移除 + 日志 + "列表已空"提醒 */
    private void removeSelected(List<PreviewItem> targets) {
        removeItems(targets);
        log.info(I18n.msg("log.maincontroller.46", targets.size(), visibleCount()));
        if (visibleCount() == 0) {
            GuiNotifications.listEmpty(refs.statusLabel());
        }
    }

    /** 整组移除确认 + 执行（G2 拍板：按钮行与右键菜单统一每次必弹确认；文案区分"列表将空"与折叠提示） */
    private void removeGroupWithConfirm(GroupItem<PreviewItem> group) {
        int memberCount = visibleMemberCount(group);
        String collapsedHint = group.isExpanded()
                ? ""
                : I18n.get("group.removeNoteCollapsed");
        String msg = memberCount >= visibleCount()
                ? I18n.get("group.removeConfirm", "count", memberCount) + collapsedHint
                : I18n.get("group.removeConfirmSimple", "count", memberCount) + collapsedHint;
        GuiNotifications.removeGroupConfirm(refs.statusLabel(), msg, ok -> {
            if (ok) {
                removeGroup(group);
                log.info(I18n.msg("log.maincontroller.47", group.getName(), memberCount, visibleCount()));
                if (visibleCount() == 0) {
                    GuiNotifications.listEmpty(refs.statusLabel());
                }
            }
        });
    }

    /** 隐藏式移除：结构变更（removed 标记 + 空组自动销毁 D11）与撤销记录由 {@link WorkspaceModel#removeItems} 负责；
     *  本类只做缓存规则 (c) 与选择/刷新收尾 */
    private void removeItems(List<PreviewItem> targets) {
        Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
        int selIndex = displayItems.indexOf(sel);
        ws.removeItems(targets);                              // 结构 + 撤销记录（模型是唯一实现）
        // 规则 (c)：从列表移除 → 删该项目缓存目录
        workspaceParamService.itemsRemoved(targets);
        // 被选中项若已被移除（含"所在组被销毁"）→ 选最近的可见项
        boolean selRemoved = sel != null
                && ((sel instanceof PreviewItem it && targets.contains(it))
                || (sel instanceof GroupItem<?> g && items.stream().noneMatch(o -> o == g)));
        if (selRemoved) {
            selectNearestVisible(selIndex);
        }
        refreshAfterListChange();
    }

    /** 整组移除（D7）：结构变更与撤销记录由 {@link WorkspaceModel#removeGroup} 负责；本类只做缓存规则 (c) 与选择/刷新收尾 */
    private void removeGroup(GroupItem<PreviewItem> group) {
        Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
        int selIndex = displayItems.indexOf(sel);
        List<PreviewItem> members = new ArrayList<>(group.getMembers());
        ws.removeGroup(group);                                // 结构 + 撤销记录（模型是唯一实现）
        // 规则 (c)：整组移除 → 删组内成员缓存目录
        workspaceParamService.itemsRemoved(members);
        if (sel == group) {
            selectNearestVisible(selIndex);
        }
        refreshAfterListChange();
    }

    /** R13 确认框文案：待移除项目含折叠组成员时提示（折叠组内项目不可见，避免用户误以为未被选中） */
    private String collapsedGroupHint(List<PreviewItem> targets) {
        List<String> parts = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> group = ListInteractionController.asPreviewGroup(g);
                if (!group.isExpanded()) {
                    long cnt = group.getMembers().stream()
                            .filter(m -> !m.removed && targets.contains(m)).count();
                    if (cnt > 0) {
                        parts.add(group.getName() + I18n.get("group.itemCount", "count", cnt));
                    }
                }
            }
        }
        return parts.isEmpty()
                ? ""
                : I18n.get("group.removeNoteHidden",
                        "names", String.join(I18n.get("list.nameSeparator"), parts));
    }

    @FXML
    private void onUndoClicked() {
        if (groupCommand.isAnimating() || !ws.canUndo()) {
            return;
        }
        UndoManager.Record<PreviewItem> record = ws.popRecord();
        Object selectAfter = null;
        // 结构恢复类操作（REORDER/MERGE_GROUPS）：恢复后生效参数可能变化，受影响项目重新预解析
        List<PreviewItem> reparseAfter = List.of();
        if (record != null) {
            switch (record.type()) {
                case REMOVE, REMOVE_GROUP -> {
                    // 结构恢复（removed 复位 + 被销毁的空组/整组按原顶层索引插回）由模型负责
                    ws.applyUndo(record);
                    for (PreviewItem it : record.items()) {
                        it.setSelected(false);      // 选中态属 UI 职责，模型不管
                    }
                    log.info(I18n.msg("log.maincontroller.48", record.items().size(), record.destroyedGroups().size()));
                }
                case IMPORT -> {
                    Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
                    int selIndex = displayItems.indexOf(sel);
                    boolean selRemoved = sel != null && record.items().contains(sel);
                    // 撤销导入 = 这批项目被移除：先做 UI 侧标记（removed 供致空组判定），
                    // 结构摘除（从组内与顶层两处 + 致空组销毁）交由模型——原先本类手写了等价的两段
                    for (PreviewItem it : record.items()) {
                        it.removed = true;
                        it.setSelected(false);
                    }
                    // 规则 (c)：撤销导入 = 从列表移除这批项目 → 删其缓存目录
                    workspaceParamService.itemsRemoved(record.items());
                    ws.applyUndo(record);
                    if (selRemoved) {
                        selectNearestVisible(selIndex);
                    }
                    log.info(I18n.msg("log.maincontroller.49", record.items().size(), record.groups().size()));
                }
                case GROUP -> {
                    // 撤销建组：结构恢复（组解散、成员回原位置）由模型负责
                    ws.applyUndo(record);
                    record.items().stream()
                            .filter(i -> !i.removed).findFirst().ifPresent(firstVisible -> refs.fileListView().getSelectionModel().select(firstVisible));
                    log.info(I18n.msg("log.maincontroller.50", record.group().getName()));
                }
                case REORDER, MERGE_GROUPS -> {
                    // 拖拽类：结构恢复（顶层顺序 / 组位置 / 组内顺序，组对象复用）自 Q0/P0 起由模型负责；
                    // 本类只做缓存失效与重解析收尾（缓存规则不得由 GUI 直调，见不变式）。
                    ws.applyUndo(record);
                    reparseAfter = new ArrayList<>();
                    for (PreviewItem it : ws.applyUndoAffected()) {
                        clearPreviewCache(it);
                        reparseAfter.add(it);
                    }
                    log.info(I18n.msg("log.maincontroller.51", I18n.get(record.type() == UndoManager.RecordType.REORDER
                        ? "reorder.dragSort" : "reorder.groupMerge")));
                }
                case UNGROUP -> {
                    // F6：组重建（成员回组、组按原索引插回、条目级参数清除）由模型负责
                    ws.applyUndo(record);
                    selectAfter = record.group();
                    log.info(I18n.msg("log.maincontroller.52", record.group().getName(), record.items().size()));
                }
                case PARAM_SET -> {
                    // 条目独立参数需求拍板：设置参数类操作（条目/组/全局）可撤销，恢复设置前的参数
                    boolean im = record.paramImageMode();
                    if (!record.clearItemParamsBefore().isEmpty() || !record.clearGroupParamsBefore().isEmpty()) {
                        // Q10/R7.5 批量「应用到所选」：一条记录携带全部目标的 before 快照，
                        // 恢复与 CLEAR_PARAMS 撤销共用同一份实现（不另写一套）
                        restoreParamSnapshots(im, record.clearItemParamsBefore(), record.clearGroupParamsBefore());
                    } else if (record.paramTargetPath() != null) {
                        for (PreviewItem it : allPreviewItems()) {
                            if (it.path.equals(record.paramTargetPath())) {
                                if (im) {
                                    it.imageItemParams = record.paramBefore();
                                } else {
                                    it.textItemParams = record.paramBefore();
                                }
                                clearExtractOutcomes(List.of(it));
                                clearPreviewCache(it);
                                // 规则 (a)：撤销恢复旧参数 = 生效参数变化 → 删该项目缓存目录
                                workspaceParamService.paramsChanged(im, it);
                                startPreParseFor(List.of(it));
                                break;
                            }
                        }
                    } else if (record.paramTargetGroupNo() != null) {
                        for (Object o : items) {
                            if (o instanceof GroupItem<?> g
                                    && g.getGroupNo() == record.paramTargetGroupNo()) {
                                GroupItem<PreviewItem> grp = ListInteractionController.asPreviewGroup(g);
                                if (im) {
                                    grp.setImageParamsCache(record.paramBefore());
                                } else {
                                    grp.setTextParamsCache(record.paramBefore());
                                }
                                List<PreviewItem> members = grp.getMembers().stream()
                                        .filter(m -> !m.removed).toList();
                                clearExtractOutcomes(members);
                                for (PreviewItem m : members) {
                                    clearPreviewCache(m);
                                }
                                // 规则 (a)：撤销恢复组参数 → 成员生效参数变化 → 删其缓存目录
                                workspaceParamService.paramsChanged(im, members);
                                startPreParseFor(members);
                                break;
                            }
                        }
                    } else {
                        if (im) {
                            imageParamsCache = record.paramBefore();
                        } else {
                            textParamsCache = record.paramBefore();
                        }
                        clearExtractOutcomes(allPreviewItems());
                        // 规则 (a)：撤销恢复全局参数 → 全部条目生效参数可能变化 → 保守全删
                        workspaceParamService.paramsChanged(im, allPreviewItems());
                        refreshAfterParamsChanged();
                    }
                    refs.fileListView().refresh();
                    updateInfoArea(currentSelection());
                    updateExtractBanner(currentSelection());
                    updateListButtons();
                    log.info(I18n.msg("log.maincontroller.53", modeWord(im)));
                }
                case CLEAR_PARAMS -> {
                    // 清除参数拍板 Q4：按 before 快照一次恢复全部被清除的条目/组参数，并重走联动刷新
                    boolean im = record.clearImageMode();
                    Map<Integer, AppConfig> groupBefore = record.clearGroupParamsBefore();
                    int restoredItems = restoreParamSnapshots(im, record.clearItemParamsBefore(), groupBefore);
                    log.info(I18n.msg("log.maincontroller.54", modeWord(im), restoredItems, groupBefore.size()));
                }
                case APPLY_GLOBAL -> {
                    // 条目独立参数需求拍板：恢复应用前的全局参数与全部独立参数快照
                    boolean im = record.applyGlobalImageMode();
                    if (im) {
                        imageParamsCache = record.applyGlobalBefore();
                    } else {
                        textParamsCache = record.applyGlobalBefore();
                    }
                    Map<String, AppConfig> itemBefore = record.applyGlobalItemParamsBefore();
                    for (PreviewItem it : allPreviewItems()) {
                        if (itemBefore.containsKey(it.path)) {
                            AppConfig p = itemBefore.get(it.path);
                            if (im) {
                                it.imageItemParams = p;
                            } else {
                                it.textItemParams = p;
                            }
                        }
                    }
                    Map<Integer, AppConfig> groupBefore = record.applyGlobalGroupParamsBefore();
                    for (Object o : items) {
                        if (o instanceof GroupItem<?> g) {
                            if (groupBefore.containsKey(g.getGroupNo())) {
                                AppConfig p = groupBefore.get(g.getGroupNo());
                                if (im) {
                                    g.setImageParamsCache(p);
                                } else {
                                    g.setTextParamsCache(p);
                                }
                            }
                        }
                    }
                    clearExtractOutcomes(allPreviewItems());
                    // 规则 (a)：撤销应用到全局 → 全部条目生效参数可能变化 → 保守全删
                    workspaceParamService.paramsChanged(im, allPreviewItems());
                    refreshAfterParamsChanged();
                    updateListButtons();
                    updateExtractBanner(currentSelection());
                    log.info(I18n.msg("log.maincontroller.55", modeWord(im)));
                }
            }
        }
        refreshAfterListChange();
        if (selectAfter != null && displayItems.contains(selectAfter)) {
            refs.fileListView().getSelectionModel().select(selectAfter);
        }
        // 父组指针已在 refreshAfterListChange 重建，此时按恢复后的生效参数重解析受影响项目
        if (!reparseAfter.isEmpty() && currentModeConfirmed()) {
            startPreParseFor(reparseAfter);
        }
    }

    /** 按 (path | groupNo → AppConfig) 快照恢复参数并重走联动刷新与缓存失效（清除参数 / 批量应用共用） */
    private int restoreParamSnapshots(boolean im, Map<String, AppConfig> itemBefore,
                                      Map<Integer, AppConfig> groupBefore) {
        List<PreviewItem> restoredItems = new ArrayList<>();
        for (PreviewItem it : allPreviewItems()) {
            if (itemBefore.containsKey(it.path)) {
                if (im) it.imageItemParams = itemBefore.get(it.path);
                else it.textItemParams = itemBefore.get(it.path);
                restoredItems.add(it);
            }
        }
        List<PreviewItem> affected = new ArrayList<>(restoredItems);
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> grp = ListInteractionController.asPreviewGroup(g);
                if (groupBefore.containsKey(grp.getGroupNo())) {
                    if (im) grp.setImageParamsCache(groupBefore.get(grp.getGroupNo()));
                    else grp.setTextParamsCache(groupBefore.get(grp.getGroupNo()));
                    affected.addAll(grp.getMembers().stream()
                            .filter(m -> !m.removed).toList());
                }
            }
        }
        clearExtractOutcomes(affected);
        // 规则 (a)：撤销恢复旧参数 = 生效参数变化 → 删受影响条目缓存目录（经 WorkspaceParamService 收口）
        workspaceParamService.paramsChanged(im, affected);
        refreshAfterTargetedParamsChange(affected);
        updateListButtons();
        updateExtractBanner(currentSelection());
        return restoredItems.size();
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
            refs.groupNameField().setText(g.getName());
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
        String unique = GroupStructure.uniqueGroupName(trimmed, otherGroupNames(g));
        if (!unique.equals(trimmed)) {
            log.info(I18n.msg("log.maincontroller.09", trimmed, unique));
            updatingGroupName = true;
            refs.groupNameField().setText(unique);
            updatingGroupName = false;
        }
        g.setName(unique);
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

    private void updateInfoArea(Object item) {
        updateExtractBanner(item);
        // 组条目：第二行（原"输出文件："位置）变为"输出组文件名："+ 可编辑输入框（F5 6.3）
        if (item instanceof GroupItem<?> g) {
            refs.titleInfoLabel().setVisible(false);
            refs.titleInfoLabel().setManaged(false);
            refs.outputNameLabel().setVisible(false);
            refs.outputNameLabel().setManaged(false);
            refs.groupNameBox().setVisible(true);
            refs.groupNameBox().setManaged(true);
            attachGroupNameEditor(ListInteractionController.asPreviewGroup(g));
            return;
        }
        AppConfig params = effectiveForObject(item);
        boolean confirmed = params != null;
        if (params == null) {
            params = new AppConfig();
        }
        nameEditingGroup = null;
        refs.groupNameBox().setVisible(false);
        refs.groupNameBox().setManaged(false);
        // 提取标题行：文本模式 + 标题提取开启且起始标记非空时显示（与正式提取判定一致，按该条目生效参数）
        boolean showTitle = confirmed && isTextMode() && params.isEnableTitleExtraction()
                && params.getTitleStartMarker() != null && !params.getTitleStartMarker().isEmpty();
        refs.titleInfoLabel().setVisible(showTitle);
        refs.titleInfoLabel().setManaged(showTitle);
        if (!(item instanceof PreviewItem it) || !confirmed) {
            refs.outputNameLabel().setVisible(false);
            refs.outputNameLabel().setManaged(false);
            return;
        }
        if (showTitle) {
            switch (it.status) {
                case WAITING -> refs.titleInfoLabel().setText(I18n.get("preview.titleParsing"));
                case FAILED, EMPTY -> refs.titleInfoLabel().setText(I18n.get("preview.titleNone"));
                case READY -> refs.titleInfoLabel().setText(it.cachedTitle != null
                        ? I18n.get("preview.titlePrefix") + it.cachedTitle
                        : I18n.get("preview.titleNotFound"));
            }
        }
        refs.outputNameLabel().setVisible(true);
        refs.outputNameLabel().setManaged(true);
        if (isTextMode()) {
            if (params.isSeparateFiles()) {
                refs.outputNameLabel().setText(I18n.get("preview.outputFilePrefix") + SourceKinds.stripKnownExtension(it.name) + ".txt");
            } else {
                String inputDirName = new File(refs.inputFolderField().getText()).getName();
                if (inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                refs.outputNameLabel().setText(I18n.get("preview.outputFilePrefix") + inputDirName + ".txt"
                        + I18n.get("preview.outputFileTxtSuffix"));
            }
        } else if (params.isSingleOutputMode()) {
            String folderName = mergedFolderName(it);
            refs.outputNameLabel().setText(I18n.get("preview.outputDirPrefix") + folderName
                    + I18n.get("preview.outputDirMergedNote"));
        } else {
            String dirName = separateDirName(it);
            refs.outputNameLabel().setText(I18n.get("preview.outputDirPrefix") + dirName);
        }
    }

    private String separateDirName(PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        if (params != null && params.isEnableNameExtraction()) {
            if (item.status == PreviewItem.Status.WAITING) {
                return I18n.get("preview.parsing");
            }
            if (item.cachedTitle != null) {
                return SanitizeUtils.sanitize(item.cachedTitle);
            }
        }
        return SourceKinds.stripKnownExtension(item.name);
    }

    private String mergedFolderName(PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        if (params != null && params.isEnableNameExtraction()) {
            PreviewItem first = allVisibleItems().stream().findFirst().orElse(null);
            if (first != null) {
                if (first.status == PreviewItem.Status.WAITING) {
                    return I18n.get("preview.parsing");
                }
                if (first.cachedTitle != null) {
                    return SanitizeUtils.sanitize(first.cachedTitle);
                }
            }
        }
        return ImageBatchProcessor.determineDefaultFolderName(refs.inputFolderField().getText());
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
            GroupItem<PreviewItem> group = ListInteractionController.asPreviewGroup(g);
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
        AppConfig global = imageMode ? imageParamsCache : textParamsCache;
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

    // ==================== 预解析（文本全量 / 图片轻量） ====================

    private void startPreParse() {
        List<PreviewItem> targets = allVisibleItems();
        if (targets.isEmpty()) {
            return;
        }
        // 提取运行期间进度条归提取任务独占（相邻修复），预解析照常跑但不动进度条
        if (!running().get()) {
            refs.progressBar().setVisible(true);
            refs.progressBar().setProgress(0);
            setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_PREPARSE);
        }
        // 快照迭代目标，避免解析期间列表被增量导入/移除修改导致并发异常
        preParseItems(new ArrayList<>(targets), ++parseToken);
    }

    /** 增量导入专用：仅预解析新增项目，沿用当前代次（不打断进行中的全量解析） */
    private void startPreParseFor(List<PreviewItem> targets) {
        preParseItems(new ArrayList<>(targets), parseToken);
    }

    private void preParseItems(List<PreviewItem> targets, int token) {
        if (targets.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            for (PreviewItem item : targets) {
                if (token != parseToken) {
                    return;
                }
                // 每条目按生效参数解析（6.5）：组参数已设置用组参数，否则继承全局；无生效参数留待参数确认后解析
                AppConfig params = effectiveForObject(item);
                if (params == null) {
                    continue;
                }
                try {
                    var parser = SourceParsers.forPath(item.path);
                    MimeParser.ParsedMht parsed = parser.parse(item.path, true, () -> false, null, null);   // 来源由扩展名选
                    if (parsed.getHtmlContent() == null) {
                        setItemStatus(token, item, PreviewItem.Status.FAILED, I18n.get("preview.unparseable"));
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
                                (text == null || text.trim().isEmpty()) ? PreviewItem.Status.EMPTY : PreviewItem.Status.READY,
                                null);
                    } else {
                        if (params.isEnableNameExtraction()) {
                            ImageExtractor imageExtractor = new ImageExtractor(parsed,
                                    params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                                    null, null);
                            String title = imageExtractor.extractTitle();
                            item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                        }
                        setItemStatus(token, item, PreviewItem.Status.READY, null);
                    }
                } catch (Exception e) {
                    setItemStatus(token, item, PreviewItem.Status.FAILED, I18n.get("preview.parseFailed") + e.getMessage());
                }
            }
        }, "preview-preparse");
        t.setDaemon(true);
        t.start();
    }

    private void setItemStatus(int token, PreviewItem item, PreviewItem.Status st, String err) {
        if (token != parseToken) {
            return;
        }
        Platform.runLater(() -> {
            if (token != parseToken) {
                return;
            }
            item.status = st;
            item.errorMsg = err == null ? "" : err;
            if (item.removed) {
                // 已移除项：状态已记录（撤销恢复后可见），跳过 UI 刷新
                return;
            }
            if (!listInteraction.isDragActive() && !groupCommand.isAnimating()) {
                // 拖拽/折叠动画期间暂停列表刷新，避免打乱动画画面；动画结束后统一重算
                refs.fileListView().refresh();
                listInteraction.recomputeBadges();
            }
            Object sel = refs.fileListView().getSelectionModel().getSelectedItem();
            updateInfoArea(sel);
            if (sel == item) {
                updateContentArea(item);
            }
            updateProgressStatus(token);
        });
    }

    void updateProgressStatus(int token) {
        if (token != parseToken) {
            return;
        }
        // 相邻修复（拍板 2026-09-24）：提取运行期间进度条/状态栏归提取任务独占，
        // 预解析完成等回调一律不更新（避免覆盖提取进度显示）
        if (running().get()) {
            return;
        }
        if (previewLoader.isActive()) {
            refs.progressBar().setVisible(true);
            refs.progressBar().setProgress(-1);
            return;
        }
        // 拍板 2026-09-24（方案A）：只统计有生效参数的条目。无参数条目由 preParseItems 跳过
        // （留待参数确认后解析），不参与进度，否则独立参数场景下进度条会卡在中间。
        List<PreviewItem> vis = allVisibleItems().stream()
                .filter(i -> effectiveForObject(i) != null).toList();
        long total = vis.size();
        long done = vis.stream().filter(i -> i.status != PreviewItem.Status.WAITING).count();
        if (total == 0) {
            refs.progressBar().setVisible(false);   // 全部无生效参数：不预解析，不动状态栏文案
            setProgressBarActivity(null);
            return;
        }
        if (done >= total) {
            refs.progressBar().setVisible(false);
            setProgressBarActivity(null);
        } else {
            refs.progressBar().setVisible(true);
            refs.progressBar().setProgress((double) done / total);
            setProgressBarActivity(ExtractOutcomeStyles.PROGRESS_PREPARSE);
        }
        refs.statusLabel().setText(I18n.get("preview.parseDone") + done + "/" + total);
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
        long stepGiB = step * PreviewCacheLifecycle.SIZE_ALERT_STEP_BYTES / (1024L * 1024 * 1024);
        double actualGiB = previewCacheLifecycle.lastObservedTotalBytes() / (1024.0 * 1024 * 1024);
        String message = I18n.get("cache.sizeAlert",
                        "step", stepGiB, "actual", String.format("%.1f", actualGiB),
                        "dir", previewCacheLifecycle.store().root())
                + I18n.get("cache.sizeAlertNote");
        GuiNotifications.cacheStepWarning(refs.statusLabel(), message);
    }
}
