package com.mhtextractor.gui.controller;

import atlantafx.base.controls.ToggleSwitch;
import com.mhtextractor.app.ExtractionPreconditions;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.app.WorkspaceImportService;
import com.mhtextractor.app.WorkspaceModel;
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
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.WorkspaceFxAdapter;
import com.mhtextractor.platform.BrowserLauncher;
import com.mhtextractor.platform.FileOpener;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.ExtractOutcome;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.GroupStructure;
import com.mhtextractor.util.HtmlExportBuilder;
import com.mhtextractor.util.MhtImportPlanner;
import com.mhtextractor.util.OutputUnitPlanner;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SelectionState;
import com.mhtextractor.util.ThumbnailPixels;
import com.mhtextractor.util.UndoManager;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.HorizontalDirection;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

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

    /** 超大文件完整解析确认阈值（字节） */
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024;

    /* 单元格内边距清零（列头与单元格列对齐由各自 HBox 的内边距控制） */
    /* 预解析失败样式（提取结果状态需求拍板：浅黄；2026-09-25 用户反馈改为接近 Windows/WinUI 警告黄 #FFF9AE） */
    /* 选中态 = 基础色混合 40% 浅灰 #bebebe（拍板 2026-09-22，与组行选中切换同机制） */
    /* 提取结果背景画值（提取结果状态需求拍板，配色修订 2026-09-21、2026-09-25）：浅红=失败/跳过；标准绿=成功；分割色=成功+警告（左1/3红右2/3绿）。
       2026-09-25 用户反馈：浅红改 Windows 错误红 #E81123（白字）；警告分割行右侧绿加深 #2E7D32 以承载白字 */
    /** 进度条活动样式类（进度条活动配色拍板 2026-09-24）：预解析=浅蓝；提取成功=绿；有警告=黄；有失败/跳过=浅红 */
    private static final String STYLE_PROGRESS_PREPARSE = "progress-preparse";
    private static final String STYLE_PROGRESS_EXTRACT_SUCCESS = "progress-extract-success";
    private static final String STYLE_PROGRESS_EXTRACT_WARNED = "progress-extract-warned";
    private static final String STYLE_PROGRESS_EXTRACT_FAILED = "progress-extract-failed";

    /* 8.5 条目背景画值（配色修订 2026-09-21）：深蓝 = 生效参数为全局参数（白字）；浅蓝 = 生效参数为组/条目级独立参数 */
    /* 组条目行样式（配色修订 2026-09-21）：标准黑色底、白色文字；行高大幅缩小为比文字稍高 */
    /* 组条目行选中样式（配色修订 2026-09-21）：黑灰底（#444444），与未选中黑色明显区分 */

    /* 组行左内边距 2px：内容朝左顶格（成员行维持 10px 列对齐） */
    /* 组内成员行内边距（拍板）：行高略低于顶层项目行（顶层 ROW_PAD 34px → 组内约 30px） */
    /* 插入指示线颜色（拖拽排序反馈） */
    /* 组行"入组/合并"整行高亮（琥珀色底+边框，与成员行蓝色插入线视觉区分，6.6 视觉细节） */
    /* 组行落点三区比例：上下边缘为行间空隙（顶层/组间落点），中间带为整行（入组末尾/两组合并） */
    /** 折叠/展开动画时长（拍板 200ms；缓动照搬 TitledPaneSkin 的 LINEAR） */
    private static final Duration EXPAND_ANIM_DURATION = Duration.millis(200);
    /** 成员行动画基准高度兜底（px）：ROW_PAD 7×2 + 单行文本 ≈ 34；动画开始时优先实测 */
    private static final double MEMBER_ROW_HEIGHT_FALLBACK = 34.0;
    /* 组条目行目标高度（px）：比文字稍高（拍板） */
    /* 组行复选框槽位宽度（px）：仅容纳图形，复选框与内容之间不留空白（拍板） */

    /** 徽标列/位置序号列/复选框列的固定槽位宽度 */

    private static final String HINT_NO_MODE_OR_DIR = I18n.get("preview.hintSelectMode");
    private static final String HINT_NEED_PARAMS = I18n.get("preview.hintParamsUnset");
    private static final String HINT_NO_FILES = I18n.get("preview.hintNoMht");

    /** 缩略图纯数据 → JavaFX 图像（渲染层唯一的 JavaFX 转换点；失败返回 null 表示无法加载） */
    private static Image toFxImage(ThumbnailData d) {
        int[] px = d.pixels();
        if (!ThumbnailPixels.isUsable(px, d.width(), d.height())) {
            return null;
        }
        try {
            WritableImage img = new WritableImage(d.width(), d.height());
            // stride == width：像素为行优先连续的 ARGB int（打包/解包契约见 ThumbnailPixels）
            img.getPixelWriter().setPixels(0, 0, d.width(), d.height(),
                    PixelFormat.getIntArgbInstance(), px, 0, d.width());
            return img;
        } catch (Exception e) {
            // 与 decodeThumb 一致：失败即视为无法加载（渲染层显示占位）
            return null;
        }
    }

    /** JavaFX 图像 → 缩略图纯数据（后台线程调用；失败返回 pixels=null 的数据对象） */
    private static ThumbnailData toThumbnailData(Image img, String coreName) {
        if (img == null) {
            return new ThumbnailData(coreName, 0, 0, null);
        }
        try {
            int w = (int) Math.round(img.getWidth());
            int h = (int) Math.round(img.getHeight());
            if (w <= 0 || h <= 0) {
                return new ThumbnailData(coreName, 0, 0, null);
            }
            // 经 IntBuffer 接收，再交由纯 Java 的 ThumbnailPixels 打包 —— 该段逻辑有零 JavaFX 单测
            IntBuffer buf = IntBuffer.allocate(w * h);
            img.getPixelReader().getPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), buf, w);
            int[] px = ThumbnailPixels.pack(buf, w, h);
            if (px == null) {
                return new ThumbnailData(coreName, 0, 0, null);
            }
            return new ThumbnailData(coreName, w, h, px);
        } catch (Exception e) {
            return new ThumbnailData(coreName, 0, 0, null);
        }
    }

    /** 输出单元规划的字段视图（路径/来源目录/移除标记，阶段 8） */
    /* test-support */ static final OutputUnitPlanner.ItemView<PreviewItem> ITEM_VIEW = new OutputUnitPlanner.ItemView<>() {
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

    // ===== 顶部控制栏 =====
    @FXML private ToggleGroup modeToggleGroup;
    @FXML private ToggleButton textModeToggle;
    @FXML ToggleButton imageModeToggle;
    @FXML private TextField inputFolderField;
    @FXML private TextField outputFolderField;
    @FXML private Button browseInputButton;
    @FXML private Button browseOutputButton;

    // ===== 预览工作区（左：列表） =====
    @FXML private Label fileCountLabel;
    @FXML private CheckBox selectAllCheck;
    @FXML private Label badgeHeaderLabel;
    @FXML ListView<Object> fileListView;
    @FXML private Pane dragOverlay;
    // ===== 列表按钮行（移除/撤销/合并为组/拆分组；后两者阶段 4/6 启用） =====
    @FXML private Button removeButton;
    @FXML private Button clearParamsButton;
    @FXML private Button undoButton;
    @FXML private Button viewSourceButton;
    @FXML private Button groupButton;
    @FXML private Button ungroupButton;
    @FXML private Button applyGlobalButton;

    // ===== 预览工作区（右：信息区与内容区） =====
    @FXML private Label modeLabel;
    @FXML private Label outputModeLabel;
    @FXML private Label outputNameLabel;
    @FXML private HBox groupNameBox;
    @FXML private TextField groupNameField;
    @FXML private Label titleInfoLabel;
    @FXML private Label outputResultLabel;
    @FXML CheckBox adjustCheck;
    @FXML private TextArea textPreviewArea;
    @FXML private VBox imagePreviewBox;
    @FXML private Label imageHintLabel;
    /** 提取结果提示 banner（提取结果状态需求）：预览区顶部，选中含红条目时显示失败/跳过/警告详情 */
    @FXML private Label extractResultBanner;
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
    @FXML LogTextArea logTextArea;
    @FXML private ToggleSwitch openFolderAfterCompleteCheck;

    // ===== 状态 =====
    // 撤销栈（F4）：唯一栈由 app.WorkspaceModel 独占；结构类由模型 undo() 应用，参数 / 拖拽类由本类 popRecord() 后自行应用
    /** 工作区模型（Q4/P3）：**业务数据序的唯一所有者** —— 不再与 GUI 共享可变列表；成功命令会发布 WorkspaceChange */
    /* test-support */ final WorkspaceModel ws = new WorkspaceModel();
    /** 模型 → JavaFX 的投影层（Q4/P3）：显示序（ListView 绑定）由它独占维护，数据序只读投影由它提供。
     *  投影<b>时机</b>仍由本类显式给出；"为何不在每次变更上自动投影"见 {@link WorkspaceFxAdapter} 的类 javadoc。 */
    private final WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(ws);
    /** 数据序只读投影：保留同名入口给 FeatureExercise（P7 才改造）；唯一写操作 clear() 转发为模型命令 */
    /* test-support */ final List<Object> items = adapter.dataOrder();
    /** 显示序：ListView 直接渲染的展平列表，按数据序实时重建（F5 显示序/数据序分离）—— adapter.displayItems() 的只读别名 */
    private final ObservableList<Object> displayItems = adapter.displayItems();

    /** 两套已确认提取参数缓存：null 表示该模式尚未确认过参数 */
    /* test-support */ AppConfig textParamsCache;
    /* test-support */ AppConfig imageParamsCache;

    /** 工作区后台活动代次：预解析/换目录/换模式/开始提取时自增，取消旧任务 */
    /* test-support */ int parseToken = 0;
    /** 列表选择代次：切换选择时自增，取消过期的图片懒加载 */
    private int requestToken = 0;
    /* test-support */ boolean imageLoadActive = false;
    private PreviewItem loadingItem = null;
    /** 预览会话缓存（需求 2 会话 9）：存储层 + 四条生命周期规则；root = <java.io.tmpdir>/mht-extractor-preview（P6/P8，与 HtmlExportBuilder 的 mht-extractor-view 并列） */
    /* test-support */ final PreviewCacheLifecycle previewCacheLifecycle = new PreviewCacheLifecycle(
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
    /** 在途预览加载线程（规则 d 退出清空时等待其结束用；仅 FX 线程写） */
    private volatile Thread previewLoadThread = null;
    /** 退出清空已完成：二次 close 放行（规则 d） */
    private volatile boolean shutdownCacheCleared = false;
    /** 正在清空缓存（FX 线程独占）：挡住清空期间的重复关窗请求，避免开两份清理 */
    private boolean shutdownInProgress = false;
    private boolean updatingAdjustCheck = false;
    /** 全选/全不选批量写入期间的联动抑制标记（F1） */
    private boolean updatingSelection = false;
    private Object lastSelected = null;
    CellIndicator lastDragOverCell = null;
    /** Shift 区间多选的锚点行（显示序元素：PreviewItem 或 GroupItem）；无锚点时 Shift 点击忽略 */
    private Object selectionAnchor = null;

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
    private final Set<PreviewItem> selectionListened =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // ===== 拖拽排序状态 =====
    /** 被拖对象（PreviewItem 或 GroupItem）：null = 无进行中的拖拽会话 */
    Object draggedObject = null;
    boolean dragActive = false;
    boolean dropHandled = false;
    @Getter
    private boolean dragPositionStale = false;
    private ParallelTransition liftAnim = null;
    private List<PreviewListCell> liftCells = null;
    private List<ImageView> liftViews = null;

    // ==================== 统一导入会话 ====================
    /** 当前导入会话：非 null = 导入进行中（用于禁用开始提取 + 恢复判定） */
    /* test-support */ ImportSession importPending = null;
    /** 外部导入拖拽进行中（与内部排序通道互斥；hasFiles() 判定） */
    private boolean importDragOver = false;
    /** 拖拽目标嵌套深度计数（DRAG_ENTERED_TARGET/DRAG_EXITED_TARGET 成对计数） */
    /* test-support */ int importDragDepth = 0;
    /** 外部导入拖拽的列表高亮样式类 */
    private static final String STYLE_LIST_IMPORT_HOVER = "list-import-hover";

    /** 导入会话（不可变） */
    /* test-support */ record ImportSession(List<String> addresses, String sourceDesc, boolean fromDrag) {}

    /**
     * 导入会话协调器（Q2/P1）：后台线程生命周期、轮次派发、配额确认等待、模型命令、列表刷新与唯一收尾都在它里面。
     * 导入的<b>规则</b>（地址规范化、轮次去重、组名计算、累计计数）在无头
     * {@link com.mhtextractor.app.WorkspaceImportService} —— 原先散在本类的 7 个累计字段
     * （{@code importAccumulated} / {@code importGroupCount} 与 5 个扫描计数器）随规则一起搬进该服务的会话对象。
     */
    private final ImportSessionCoordinator importSessions = new ImportSessionCoordinator(ws, this);

    // ===== 右键菜单（右键菜单功能需求：13 项 = 9 动作 + 4 分隔线） =====
    /** 菜单实例（项禁用状态在弹出前按右键目标类型刷新） */
    private ContextMenu contextMenu;
    /** 右键目标条目（PreviewItem/GroupItem）；null = 空白区域 */
    private Object contextTarget = null;
    private MenuItem ctxSelect;
    private MenuItem ctxSelectAll;
    private MenuItem ctxOpenFolder;
    private MenuItem ctxOpenInBrowser;
    private MenuItem ctxViewSource;
    /** 浏览器查看页生成并发闸（防重复点击并发导出） */
    private final AtomicBoolean browserExporting = new AtomicBoolean(false);
    private MenuItem ctxParams;
    /* test-support */ MenuItem ctxClearParams;
    private MenuItem ctxGroup;
    private MenuItem ctxUngroup;
    private MenuItem ctxRemove;
    private MenuItem ctxRemoveGroup;
    private MenuItem ctxApplyGlobal;
    /** 菜单显示期间的主窗口按压过滤：点击菜单外任意处即关闭（JavaFX autoHide 在本应用窗口组合下不可靠，手动兜底） */
    private final EventHandler<MouseEvent> contextClickAway = _ -> {
        if (contextMenu.isShowing()) {
            contextMenu.hide();
        }
    };

    // ===== 折叠/展开动画状态（方案 B 抽屉式，快照平移手法与拖拽一致） =====
    /** 动画全局锁：动画期间列表项目不可交互（按钮可点但无效果），快速连点折叠箭头直接忽略 */
    boolean listAnimating = false;
    /** 正在动画的组；null = 无动画 */
    @Setter
    @Getter
    private GroupItem<PreviewItem> animatingGroup = null;
    /** true = 展开动画；false = 折叠动画 */
    @Setter
    @Getter
    private boolean animExpanding = false;
    /** 动画 ParallelTransition（200ms ease-out；完成/快进后置 null） */
    private ParallelTransition collapseAnim = null;
    /** 动画中的快照视图（成员行 + 下方行；结束后从覆盖层移除） */
    private List<ImageView> collapseViews = null;
    /** 动画期间被隐藏的真实 cell（结束后恢复可见） */
    private List<PreviewListCell> collapseCells = null;
    /** 动画期间锁定的选中项：列表选择变化被回选至此项 */
    private Object animLockedSelection = null;
    /** 动画自身触发的显示序重建豁免标记（防快进兜底把动画自己的 rebuild 打断） */
    private boolean suppressAnimRebuild = false;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() {
        openFolderAfterCompleteCheck.setLabelPosition(HorizontalDirection.RIGHT);
        badgeHeaderLabel.setTooltip(new Tooltip(I18n.get("list.chapterNoTooltip")));

        // 1. 列表：单元格工厂、选择联动、逐文件章节勾选、拖拽动画滚动联动
        fileListView.setCellFactory(_ -> createPreviewCell());
        adapter.bindListView(fileListView);
        setupExternalImportDnD();

        // 空白/非预期区域松手：不处理 drop（无蓝条、无吸附），走取消路径瞬时恢复原位不消失

        // 右键菜单（右键菜单功能需求）：ListView 级弹出（命中 cell = 条目目标；未命中 = 空白区域）
        buildContextMenu();
        fileListView.setOnContextMenuRequested(this::onContextMenuRequested);

        // 列头全选 CheckBox 三态联动（F1）：单项勾选变化实时刷新列头与组复选框，新导入项目自动挂监听
        selectAllCheck.setOnAction(_ -> onSelectAllToggled());
        groupNameField.textProperty().addListener((_, _, newVal) -> onGroupNameEdited(newVal));
        groupNameField.focusedProperty().addListener((_, _, newVal) -> {
            if (!newVal) {
                onGroupNameFocusLost();
            }
        });
        // 新条目自动挂勾选监听（F1）：数据序所有权移交模型后，挂接面从"列表被 add"改为"模型发布了变更"。
        // 载荷里的条目（ITEMS_ADDED / GROUPED / UNGROUPED / UNDO_APPLIED）逐个挂接；selectionListened 按身份
        // 去重，故"同一对象重复挂接"是 no-op —— 这比迁移前的 ObservableList 监听面只多不少，不产生第二份状态。
        ws.addListener(change -> {
            for (PreviewItem it : change.affected()) {
                if (selectionListened.add(it)) {
                    // 会话 18 · E1.3a：原 it.selected.addListener((obs2,a,b) -> ...)
                    // 改为等价的自研 ChangeListener（同步回调；仅值变化时触发）
                    it.addSelectedChangeListener(this::onSelectionChanged);
                }
            }
        });

        fileListView.getSelectionModel().selectedItemProperty()
                .addListener((_, _, newVal) -> onItemSelected(newVal));

        adjustCheck.selectedProperty().addListener((_, _, newVal) -> {
            if (updatingAdjustCheck) {
                return;
            }
            Object sel = fileListView.getSelectionModel().getSelectedItem();
            if (!(sel instanceof PreviewItem it) || effectiveForObject(it) == null) {
                return;
            }
            if (isTextMode()) {
                it.chapterEnabled = newVal;
                recomputeBadges();
            }
        });

        // 拖拽动画期间若列表发生滚动（滚轮/自动滚动），标记位置失效并结束提起动画
        fileListView.skinProperty().addListener((_, _, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            for (javafx.scene.Node n : fileListView.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                    sb.valueProperty().addListener((_, a, b) -> {
                        if (dragActive && Math.abs(b.doubleValue() - a.doubleValue()) > 0.1) {
                            dragPositionStale = true;
                            finishLift();
                        }
                    });
                }
            }
        });

        // 2. 模式切换：互斥由 modeToggleGroup 承担，点击已选按钮可取消回到"未选择"态；
        //    不清空另一模式参数：GUI 状态与已确认缓存按模式隔离保留
        modeToggleGroup.selectedToggleProperty().addListener((_, _, _) -> onModeChanged());

        // 3. 目录/开关变更：刷新列表 + 持久化 GUI 状态
        inputFolderField.textProperty().addListener((_, _, _) -> onInputFolderChanged());
        outputFolderField.textProperty().addListener((_, _, _) -> saveGuiState());
        openFolderAfterCompleteCheck.selectedProperty().addListener((_, _, _) -> saveGuiState());

        // 4. 恢复 GUI 状态（无状态文件时与旧版启动逻辑一致）
        restoreGuiState();

        // 5. 初始状态一致性（无状态文件时：两个模式都未选中，全部禁用）
        updateModeUi();
        updateFileCount();
        updateListButtons();

        // 6. 预览会话缓存（需求 2 会话 9）：启动扫孤儿（P7 崩溃恢复）；
        //    关窗清空处理器经 scene/window 装配后挂接（initialize 阶段 scene 尚未存在，App.start 稍后装配）
        previewCacheLifecycle.startupCleanup();
        // 会话 12：磁盘占用提醒 hook（写盘成功且跨 10GiB 台阶时经 checkSizeAlert 触发；
        // 在预览后台线程同步回调，这里换算 GiB 文案后回 FX 线程非模态弹窗）
        previewCacheLifecycle.setSizeAlertHook(this::onSizeAlertStep);
        fileListView.sceneProperty().addListener((_, _, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.windowProperty().addListener((_, _, newW) -> {
                if (newW instanceof Stage stage) {
                    stage.setOnCloseRequest(this::onWindowCloseRequest);
                }
            });
        });

        Platform.runLater(() -> {
            logTextArea.appendText(I18n.get("preview.welcome"));
            logTextArea.appendText(I18n.get("preview.welcomeHint"));
        });
    }

    /** 规则 (d)：关窗清空（§2.2/§2.6(6)）。有缓存 → 无按钮等待卡片 → 停后台活动 → 等在途写入（≤2s）→ 清空 → 清完自动关窗；
     *  无缓存 → 直接放行。test.mode 跳过卡片。只清 PreviewCacheStore 的 root，绝不触碰 java.io.tmpdir 整体。 */
    private void onWindowCloseRequest(WindowEvent e) {
        if (shutdownCacheCleared) {
            return;                                    // 已清空 → 放行关闭
        }
        if (NotificationHost.hasPendingDecision(statusLabel)) {
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
        if ("true".equals(System.getProperty("test.mode"))) {
            clearPreviewCache();                       // 自动化不等卡片
            stage.close();
            return;
        }
        // 会话 19：无按钮等待卡片 —— 清空期间不可交互（persistent），清完自动收卡片并自动关窗，无需点确定
        String quote = I18n.get("cache.clearingBeforeExit") + "\n" + previewCacheLifecycle.store().root();
        NotificationHost.busy(statusLabel, I18n.get("cache.clearingTitle"), quote, FontAwesomeSolid.BROOM,
                this::clearPreviewCache, stage::close);
    }

    /** 关窗收尾（后台线程）：等在途写入结束 → 清空缓存（cancelWorkspaceActivity 已由调用方在 FX 线程先行调用） */
    private void clearPreviewCache() {
        Thread t = previewLoadThread;
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        previewCacheLifecycle.onShutdown(() -> { });
        shutdownCacheCleared = true;
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

    /* test-support */ void saveGuiState() {
        GuiState state = new GuiState();
        state.setImageModeEnabled(imageModeToggle.isSelected());
        state.setInputFolder(inputFolderField.getText());
        state.setOutputFolder(outputFolderField.getText());
        state.setOpenFolderAfterComplete(openFolderAfterCompleteCheck.isSelected());
        Locale loc = I18n.currentLocale();
        state.setLocale(loc.getLanguage() + (loc.getCountry().isEmpty() ? "" : "_" + loc.getCountry()));
        GuiStateManager.save(state);
    }

    // ==================== 目录浏览与文件列表 ====================

    @FXML
    private void onBrowseInput() {
        String currentPath = inputFolderField.getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                inputFolderField.getScene().getWindow(),
                I18n.get("dir.chooseInput"),
                currentPath);
        if (dir != null) {
            boolean sameDir = dir.equals(inputFolderField.getText());
            inputFolderField.setText(dir);
            // 重选同一目录不触发 textProperty 变化：显式触发导入（如撤销导入后再导回同一目录）
            if (sameDir) {
                onInputFolderChanged();
            }
            autoSetOutputPath();
        }
    }

    @FXML
    private void onBrowseOutput() {
        String currentPath = outputFolderField.getText();
        String dir = JavaFxUtil.showDirectoryChooser(
                outputFolderField.getScene().getWindow(),
                I18n.get("dir.chooseOutput"),
                currentPath);
        if (dir != null) {
            outputFolderField.setText(dir);
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

        outputFolderField.setText(outputBaseDir);
    }

    /** 输入目录变更：作为导入源增量合并（F3）——已有项目保持不变，新文件按绝对路径去重后追加末尾 */
    private void onInputFolderChanged() {
        String text = inputFolderField.getText();
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
    /* test-support */ void importFromFolder(String dir) {
        importByPaths(List.of(dir), I18n.get("dir.label", "path", dir), false);
    }

    // ==================== 统一导入（拖拽 + 浏览共用入口） ====================

    /** 是否正在导入 */
    /* test-support */ boolean isImporting() {
        return importPending != null;
    }

    /**
     * 统一导入入口：来源只接受地址字段。
     * 拖拽路径（fromDrag=true）在盘符根目录时弹确认框（BC-8）；浏览路径不弹。
     *
     * <p>本方法只留"拦截 + 确认展示 + 日志产出"；规范化/去重/建组/累计的规则在
     * {@link com.mhtextractor.app.WorkspaceImportService}，会话生命周期在 {@link ImportSessionCoordinator}。</p>
     */
    /* test-support */ void importByPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
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
                NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.HDD, ok -> {
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
    /* test-support */ void applyImportPlan(MhtImportPlanner.Plan plan) {
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
        fileListView.getSelectionModel().selectFirst();
    }

    @Override
    public void confirmQuotaReached(String message, Consumer<Boolean> answer) {
        NotificationHost.confirm(statusLabel, message, FontAwesomeSolid.CLIPBOARD_LIST, answer);
    }

    @Override
    public void showImportFailed() {
        NotificationHost.error(statusLabel, ErrorCode.IMPORT_FAILED.message(), FontAwesomeSolid.FILE_IMPORT);
    }

    /** 组名刷新监听注册（手动/自动建组共用；F8/RISK-11） */
    private void registerGroupNameRefresh(GroupItem<PreviewItem> group) {
        // 会话 18 · E1.1：GroupItem 已去 JavaFX 依赖，原 nameProperty().addListener(...)
        // 改为等价的自研 ChangeListener（同步回调，语义一致）
        group.addNameChangeListener(fileListView::refresh);
    }

    /** 外部导入拖拽落点高亮（去抖：状态未变化不重复操作样式类） */
    /* test-support */ void updateImportDropHighlight(boolean on) {
        if (on == importDragOver) {
            return;
        }
        importDragOver = on;
        if (on) {
            fileListView.getStyleClass().add(STYLE_LIST_IMPORT_HOVER);
        } else {
            fileListView.getStyleClass().remove(STYLE_LIST_IMPORT_HOVER);
        }
    }

    /** 硬复位（深度计数归零 + 清除高亮）：dragDropped/dragDone 无条件调用 */
    /* test-support */ void clearImportDropHighlight() {
        importDragDepth = 0;
        updateImportDropHighlight(false);
    }

    /** 外部文件/文件夹拖入的 ListView 级处理器（接受点唯一在 ListView；单元格放行分支见 setupDnD） */
    private void setupExternalImportDnD() {
        fileListView.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !dragActive) {
                e.acceptTransferModes(TransferMode.COPY);     // 接受点唯一：ListView
                updateImportDropHighlight(true);
            }
        });
        fileListView.setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && !dragActive) {
                importDragDepth++;
                updateImportDropHighlight(true);
            }
        });
        fileListView.setOnDragExited(_ -> clearImportDropHighlight());
        fileListView.setOnDragDropped(this::onExternalFilesDropped);
        fileListView.setOnDragDone(_ -> clearImportDropHighlight());
    }

    private void onExternalFilesDropped(DragEvent e) {
        if (dragActive) {
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
    /* test-support */ void cancelWorkspaceActivity() {
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

    boolean isTextMode() {
        return !imageModeToggle.isSelected();
    }

    /** 日志文案里的模式词（图片/文本 → Image/Text），供 log.* 模板的 {0} 占位符使用 */
    private static String modeWord(boolean imageMode) {
        return I18n.get(imageMode ? "mode.image" : "mode.text");
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

    /** 当前模式下某条目的生效参数（三层覆盖，条目独立参数需求拍板）：组参数 > 条目级参数 > 全局；均未确认 → null。
     *  解析唯一实现在 {@code app.EffectiveParamsService}（图片模式仍经 ParamScope.effectiveImageParams，与提取路径一致）
     *  上下文为快照：模式 + 两个全局已确认参数缓存（缓存可被确认/应用到全局/撤销整体替换） */
    /* test-support */ AppConfig effectiveForObject(Object o) {
        return effectiveParamsService.effectiveFor(o, effectiveContext());
    }

    /** 生效参数来源三态（NONE/GLOBAL/GROUP）—— 视图层画背景色与白字判定共用同一份来源判定（唯一实现在服务，R8-①） */
    ParamScope.Status effectiveStatus(Object target) {
        return effectiveParamsService.effectiveSourceOf(target, effectiveContext());
    }

    /** 生效参数判定的解析上下文（唯一构造点）：当前模式 + 两个全局已确认参数缓存 */
    private com.mhtextractor.app.EffectiveParamsService.Context effectiveContext() {
        return new com.mhtextractor.app.EffectiveParamsService.Context(
                imageModeToggle.isSelected(), textParamsCache, imageParamsCache);
    }

    /** 任一有效项目存在生效参数（组参数或全局参数已确认）—— 判定收口到服务（R8-①） */
    private boolean anyVisibleItemHasEffectiveParams() {
        return effectiveParamsService.anyHasEffectiveParams(allVisibleItems(), effectiveContext());
    }

    private Object currentSelection() {
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
        updateStartButtonState();
        // 清除参数拍板：按钮启用状态随当前模式刷新（同时修复"应用到全局"按钮切模式后陈旧态）
        updateListButtons();

        // 预览工作区
        modeLabel.setText(I18n.get("mode.labelPrefix") + (imageModeToggle.isSelected() ? I18n.get("mode.image")
                : (textModeToggle.isSelected() ? I18n.get("mode.text") : I18n.get("mode.none"))));
        Object sel = currentSelection();
        updateBadgeHeader();
        updateOutputModeLabel(sel);
        updateRenameNote(sel);
        showCurrentModeArea();
        fileListView.refresh();
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
        textPreviewArea.setVisible(isTextMode());
        textPreviewArea.setManaged(isTextMode());
        imagePreviewBox.setVisible(!isTextMode());
        imagePreviewBox.setManaged(!isTextMode());
    }

    private void updateBadgeHeader() {
        boolean showBadgeCol = isTextMode() && anyVisibleItemHasChapterNumbers();
        badgeHeaderLabel.setVisible(showBadgeCol);
        badgeHeaderLabel.setManaged(showBadgeCol);
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
        outputModeLabel.setText(isTextMode()
                ? (params.isSeparateFiles() ? I18n.get("extract.outputModeSeparate") : I18n.get("extract.outputModeMerged"))
                : (params.isSingleOutputMode() ? I18n.get("extract.outputModeMerged") : I18n.get("extract.outputModeSeparate")));
    }

    // ==================== 参数设置弹窗 ====================

    /** 主窗口参数按钮路由（条目独立参数需求拍板）：选中组条目 → 该组参数；
     *  选中组内成员 → 其所在组参数；选中顶层项目 → 该条目的条目级参数；未选中 → 全局参数 */
    @FXML
    private void onParamSettings() {
        if (listAnimating) {
            return;
        }
        Object sel = currentSelection();
        ParamScope.Target target = switch (sel) {
            case GroupItem<?> g -> new ParamScope.GroupTarget(g);
            case PreviewItem it when it.parentGroup != null -> new ParamScope.GroupTarget(it.parentGroup);
            case PreviewItem it -> new ParamScope.ItemTarget<>(it, it.textItemParams, it.imageItemParams);
            case null, default -> ParamScope.GLOBAL;
        };
        openParamDialog(target);
    }

    /** 打开参数设置弹窗并应用确认结果（按钮行与右键菜单共用；空白区域菜单项强制传全局目标） */
    @SuppressWarnings("unchecked")
    private void openParamDialog(ParamScope.Target target) {
        boolean imageMode = imageModeToggle.isSelected();
        try {
            ParamDialogController ctrl = ParamDialogController.openAndWait(
                    inputFolderField.getScene().getWindow(), imageMode,
                    textParamsCache, imageParamsCache, target);
            if (ctrl.isConfirmed()) {
                if (target instanceof ParamScope.GroupTarget(GroupItem<?> group)) {
                    applyGroupParams((GroupItem<PreviewItem>) group, imageMode, ctrl.getResult());
                } else if (target instanceof ParamScope.ItemTarget<?> it) {
                    applyItemParams((PreviewItem) it.item(), imageMode, ctrl.getResult());
                } else {
                    applyConfirmedParams(imageMode, ctrl.getResult());
                    log.info(I18n.msg("log.maincontroller.15", modeWord(imageMode)));
                }
            }
        } catch (Exception e) {
            log.error(I18n.msg("log.maincontroller.16"), e);
            NotificationHost.error(statusLabel, ErrorCode.PARAM_DIALOG_OPEN_FAILED.message(), FontAwesomeSolid.SLIDERS_H);
        }
    }

    /** 写入某条目的条目级独立参数（条目独立参数需求）：不写全局，仅清缓存重解析该条目；入撤销栈（PARAM_SET） */
    /* test-support */ void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? item.imageItemParams : item.textItemParams;
        if (imageMode) {
            item.imageItemParams = params;
        } else {
            item.textItemParams = params;
        }
        ws.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, item.path, null, before,
                false, null, null));
        clearExtractOutcomes(List.of(item));
        clearPreviewCache(item);
        workspaceParamService.paramsChanged(imageMode, item);   // 规则 (a)：参数变化 → 删该项目缓存目录
        startPreParseFor(List.of(item));
        log.info(I18n.msg("log.maincontroller.17", item.name, modeWord(imageMode)));
        fileListView.refresh();
        updateListButtons();
        updateInfoArea(item);
        updateExtractBanner(item);
    }

    /** 写入某模式的已确认参数缓存并刷新工作区（agent 演练/自动化测试也走此入口）；入撤销栈（PARAM_SET） */
    /* test-support */ void applyConfirmedParams(boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? imageParamsCache : textParamsCache;
        if (imageMode) {
            imageParamsCache = params;
        } else {
            textParamsCache = params;
        }
        ws.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, null, null, before,
                false, null, null));
        // 拍板：全局参数重新确认 → 全部条目旧提取结果色失效
        clearExtractOutcomes(allPreviewItems());
        // 规则 (a)：全局参数变化 → 全部条目缓存目录删除（保守全删：有独立参数的条目删除也只是下次预览重建，语义安全）
        workspaceParamService.paramsChanged(imageMode, allPreviewItems());
        refreshAfterParamsChanged();
        updateExtractBanner(currentSelection());
    }

    /** 写入某组的已确认参数缓存（R8）：参数作用域仅限预览解析与显示，提取管线按组参数输出属阶段 8 */
    /* test-support */ void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? group.getImageParamsCache() : group.getTextParamsCache();
        if (imageMode) {
            group.setImageParamsCache(params);
        } else {
            group.setTextParamsCache(params);
        }
        log.info(I18n.msg("log.maincontroller.18", group.getName(), modeWord(imageMode)));
        // 入撤销栈（PARAM_SET，组参数设置可撤销）
        ws.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, null, group.getGroupNo(), before,
                false, null, null));
        // 拍板：组参数重新确认 → 该组成员旧提取结果色失效
        List<PreviewItem> members = group.getMembers().stream().filter(m -> !m.removed).toList();
        clearExtractOutcomes(members);
        // 规则 (a)：组参数变化 → 该组成员缓存目录删除
        workspaceParamService.paramsChanged(imageMode, members);
        refreshAfterTargetedParamsChange(members);
        updateListButtons();
        updateExtractBanner(currentSelection());
    }

    // ==================== 右键菜单（右键菜单功能需求） ====================

    /** 构建右键菜单（拍板 Q1 命名统一"移除"；Q5 一律禁用制：菜单恒 16 项同序；浏览器查看HTML需求后移除"打开"） */
    private void buildContextMenu() {
        ctxSelect = new MenuItem(I18n.get("list.menuSelect"));
        ctxSelectAll = new MenuItem(I18n.get("list.menuSelectAll"));
        ctxOpenFolder = new MenuItem(I18n.get("list.menuOpenFolder"));
        ctxOpenInBrowser = new MenuItem(I18n.get("browser.menuOpen"));
        ctxViewSource = new MenuItem(I18n.get("browser.menuViewSource"));
        ctxParams = new MenuItem(I18n.get("params.menuSet"));
        ctxClearParams = new MenuItem(I18n.get("params.menuClear"));
        ctxApplyGlobal = new MenuItem(I18n.get("params.menuApplyGlobal"));
        ctxGroup = new MenuItem(I18n.get("group.menuMerge"));
        ctxUngroup = new MenuItem(I18n.get("group.menuUngroup"));
        ctxRemove = new MenuItem(I18n.get("list.menuRemove"));
        ctxRemoveGroup = new MenuItem(I18n.get("group.menuRemove"));

        ctxSelect.setOnAction(_ -> onContextSelect());
        ctxSelectAll.setOnAction(_ -> onContextSelectAll());
        ctxOpenFolder.setOnAction(_ -> onContextOpenFolder());
        ctxOpenInBrowser.setOnAction(_ -> onContextOpenInBrowser());
        ctxViewSource.setOnAction(_ -> onContextViewSource());
        ctxParams.setOnAction(_ -> onContextParams());
        ctxClearParams.setOnAction(_ -> onContextClearParams());
        ctxApplyGlobal.setOnAction(_ -> onContextApplyGlobal());
        ctxGroup.setOnAction(_ -> onGroupClicked());
        ctxUngroup.setOnAction(_ -> onUngroupClicked());
        ctxRemove.setOnAction(_ -> onRemoveClicked());
        ctxRemoveGroup.setOnAction(_ -> onContextRemoveGroup());

        contextMenu = new ContextMenu(
                ctxSelect, ctxSelectAll,
                new SeparatorMenuItem(),
                ctxOpenInBrowser, ctxOpenFolder, ctxViewSource,
                new SeparatorMenuItem(),
                ctxParams, ctxClearParams, ctxApplyGlobal,
                new SeparatorMenuItem(),
                ctxGroup, ctxUngroup,
                new SeparatorMenuItem(),
                ctxRemove, ctxRemoveGroup);

        // 菜单显示期间挂主窗口按压过滤（点击菜单外即关闭），隐藏时移除
        contextMenu.setOnShown(_ -> {
            if (fileListView.getScene() != null) {
                fileListView.getScene().getWindow()
                        .addEventFilter(MouseEvent.MOUSE_PRESSED, contextClickAway);
            }
        });
        contextMenu.setOnHidden(_ -> {
            if (fileListView.getScene() != null) {
                fileListView.getScene().getWindow()
                        .removeEventFilter(MouseEvent.MOUSE_PRESSED, contextClickAway);
            }
        });
    }

    /**
     * 右键菜单弹出（拍板 Q7/Q8/Q16）：定位目标 → 选中该行（不改勾选、不动 Shift 锚点）→
     * 按目标类型刷新禁用状态 → 显示。拖拽中不弹菜单；列表完全为空不出菜单；
     * 动画锁定期间照常弹出，动作由按钮逻辑的既有 listAnimating 守卫拦截。
     */
    private void onContextMenuRequested(ContextMenuEvent e) {
        if (dragActive) {
            return;
        }
        contextTarget = pickItemAt(e);
        if (contextTarget == null && visibleCount() == 0) {
            return;
        }
        if (contextTarget != null) {
            fileListView.getSelectionModel().select(contextTarget);
        }
        updateContextMenuState(contextTarget);
        contextMenu.show(fileListView, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    /** 右键位置拾取列表条目：命中节点向上找单元格；未命中 cell = 空白区域（Q7） */
    private Object pickItemAt(ContextMenuEvent e) {
        javafx.scene.Node node = e.getPickResult().getIntersectedNode();
        while (node != null) {
            if (node instanceof ScrollBar) {
                return null;    // 滚动条右键不视为列表区域
            }
            if (node instanceof PreviewListCell c) {
                Object item = c.getItem();
                return (item instanceof PreviewItem it && it.removed) ? null : item;
            }
            node = node.getParent();
        }
        return null;
    }

    /** 菜单项禁用状态按右键目标类型刷新（拍板 Q4/Q5/Q7 + 条目独立参数需求：恒 16 项同序） */
    /* test-support */ void updateContextMenuState(Object target) {
        boolean blank = target == null;
        boolean topItem = target instanceof PreviewItem it && it.parentGroup == null;
        boolean member = target instanceof PreviewItem it && it.parentGroup != null;
        boolean group = target instanceof GroupItem<?>;

        ctxSelect.setDisable(blank);                  // 项目条目勾选自身；组条目整组勾选
        ctxSelectAll.setDisable(false);               // 恒可用：成员/组 → 该组；顶层/空白 → 全列表
        ctxOpenFolder.setDisable(!(topItem || member));
        ctxOpenInBrowser.setDisable(!(topItem || member));
        ctxViewSource.setDisable(!(topItem || member));
        // 条目独立参数需求拍板：空白区域为全局参数入口，文本与独立参数设置区分
        ctxParams.setText(blank ? I18n.get("params.menuSetGlobal") : I18n.get("params.menuSet"));
        ctxParams.setDisable(false);                  // 恒可用
        // 清除参数拍板：启用看右键目标（空白/无参数 → 禁用），动作走勾选优先（与"移除"菜单既有模式一致）
        ctxClearParams.setDisable(!hasIndependentParams(target));
        ctxApplyGlobal.setDisable(!hasIndependentParams(target)); // 仅目标有独立参数时可用
        ctxGroup.setDisable(!(topItem || member));    // 仅项目条目
        ctxUngroup.setDisable(!group);                // 仅组条目
        ctxRemove.setDisable(!(topItem || member));   // 仅项目条目；组条目走"移除组"
        ctxRemoveGroup.setDisable(!(member || group)); // 组内成员/组条目
    }

    /** 选中（拍板 Q4）：项目条目勾选自身；组条目整组勾选（强制 true，不做 toggle） */
    private void onContextSelect() {
        if (listAnimating || contextTarget == null) {
            return;
        }
        updatingSelection = true;
        try {
            if (contextTarget instanceof PreviewItem it && !it.removed) {
                it.setSelected(true);
            } else if (contextTarget instanceof GroupItem<?> g) {
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewItem pi && !pi.removed) {
                        pi.setSelected(true);
                    }
                }
            }
        } finally {
            updatingSelection = false;
        }
        refreshSelectionUi();
        updateListButtons();
    }

    /** 全选（拍板 Q4/Q14 强制全选不回退）：组内成员/组条目 → 该组全部成员；顶层项目/空白 → 整个列表（含折叠组成员） */
    private void onContextSelectAll() {
        if (listAnimating) {
            return;
        }
        List<PreviewItem> targets;
        if (contextTarget instanceof GroupItem<?> g) {
            targets = visibleMembersOf(g);
        } else if (contextTarget instanceof PreviewItem it && it.parentGroup != null) {
            targets = visibleMembersOf(it.parentGroup);
        } else {
            targets = allVisibleItems();
        }
        updatingSelection = true;
        try {
            for (PreviewItem t : targets) {
                t.setSelected(true);
            }
        } finally {
            updatingSelection = false;
        }
        refreshSelectionUi();
        updateListButtons();
    }

    /** 打开所在文件夹（全新能力 5.2）：explorer /select 定位；Q15 拍板——文件缺失时不定位直接打开所在目录 */
    private void onContextOpenFolder() {
        if (contextTarget instanceof PreviewItem it && !it.removed) {
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
                NotificationHost.toastWarning(statusLabel, ErrorCode.FILE_DIR_MISSING.message("path", it.path), FontAwesomeSolid.FOLDER_OPEN);
            }
        } catch (Exception ex) {
            log.error(I18n.msg("log.maincontroller.21"), ex);
            NotificationHost.error(statusLabel, ErrorCode.OPEN_FOLDER_FAILED.message(), FontAwesomeSolid.FOLDER_OPEN);
        }
    }

    /** 在浏览器中查看（浏览器查看HTML需求）：导出 MHT 内嵌 HTML 后用系统默认浏览器打开渲染视图 */
    private void onContextOpenInBrowser() {
        if (contextTarget instanceof PreviewItem it && !it.removed) {
            openHtmlInBrowser(it, false);
        }
    }

    /** 在浏览器中查看源码（浏览器查看HTML需求）：用 view-source: 打开导出页面的源码视图 */
    private void onContextViewSource() {
        if (contextTarget instanceof PreviewItem it && !it.removed) {
            openHtmlInBrowser(it, true);
        }
    }

    /** 在系统默认浏览器中查看该 MHT 内嵌 HTML（sourceMode=true 走 view-source 源码视图） */
    private void openHtmlInBrowser(PreviewItem it, boolean sourceMode) {
        File f = new File(it.path);
        if (!f.exists()) {
            log.warn(I18n.msg("log.maincontroller.22", it.path));
            NotificationHost.toastWarning(statusLabel, ErrorCode.FILE_MISSING.message("path", it.path), FontAwesomeSolid.FILE);
            return;
        }
        if (!browserExporting.compareAndSet(false, true)) {
            log.info(I18n.msg("log.maincontroller.23"));
            return;
        }
        statusLabel.setText(I18n.get("browser.generatingPage"));
        Thread t = new Thread(() -> {
            try {
                MimeParser.ParsedMht parsed = new MimeParser().parse(it.path, false, () -> false, null, null);
                if (parsed.getHtmlContent() == null) {
                    throw new IllegalStateException(I18n.get("browser.exportUnparseable"));
                }
                HtmlExportBuilder.ExportResult r = HtmlExportBuilder.export(it.path, parsed);
                if (!r.success()) {
                    throw new IllegalStateException(r.error());
                }
                log.info(I18n.msg("log.maincontroller.24", r.htmlFile(), r.imageCount(), r.warnings().size()));
                // 浏览器启动可能耗时（注册表探测 / CDP 端点等待最长约 15 秒），独立后台线程避免冻结界面
                Thread launcher = new Thread(() -> {
                    boolean ok = sourceMode
                            ? BrowserLauncher.openViewSource(r.htmlFile())
                            : BrowserLauncher.openHtml(r.htmlFile());
                    if (!ok) {
                        log.warn(I18n.msg("log.maincontroller.25", r.htmlFile()));
                        Platform.runLater(() -> NotificationHost.toastWarning(statusLabel,
                                ErrorCode.BROWSER_LAUNCH_FAILED.message("file", r.htmlFile()), FontAwesomeSolid.GLOBE));
                    }
                }, "html-browser-launch");
                launcher.setDaemon(true);
                launcher.start();
            } catch (Exception e) {
                log.error(I18n.msg("log.maincontroller.26", it.path), e);
                Platform.runLater(() -> NotificationHost.error(statusLabel, ErrorCode.BROWSER_VIEW_FAILED.message(), FontAwesomeSolid.GLOBE));
            } finally {
                browserExporting.set(false);
                Platform.runLater(() -> statusLabel.setText(I18n.get("main.statusReady")));
            }
        }, "html-browser-export");
        t.setDaemon(true);
        t.start();
    }

    /** 设置提取参数（拍板 Q7/G6）：空白区域 → 强制全局参数；其余复用选中项路由（Q8 已选中该行） */
    private void onContextParams() {
        if (listAnimating) {
            return;
        }
        if (contextTarget == null) {
            openParamDialog(ParamScope.GLOBAL);
        } else {
            onParamSettings();
        }
    }

    /** 移除组（Q3 拍板：每次必弹确认；与按钮行整组移除共用同一路径，G2 统一） */
    private void onContextRemoveGroup() {
        if (listAnimating) {
            return;
        }
        GroupItem<PreviewItem> group = null;
        if (contextTarget instanceof GroupItem<?> g) {
            group = asPreviewGroup(g);
        } else if (contextTarget instanceof PreviewItem it && it.parentGroup != null) {
            group = it.parentGroup;
        }
        if (group != null) {
            removeGroupWithConfirm(group);
        }
    }

    /**
     * 把 {@code GroupItem<?>} 安全转换为 {@code GroupItem<PreviewItem>}（unchecked 转换的唯一收口点）。
     *
     * <p>转换前先验证全部成员确为 {@code PreviewItem}：验证通过则转换必然安全
     * （本控制器的建组路径只会产生 {@code GroupItem<PreviewItem>}）；验证失败立即抛异常，
     * 避免堆污染导致的 {@code ClassCastException} 在远离根因处爆发。</p>
     */
    @SuppressWarnings("unchecked")
    private static GroupItem<PreviewItem> asPreviewGroup(GroupItem<?> g) {
        for (Object m : g.getMembers()) {
            if (!(m instanceof PreviewItem)) {
                throw new IllegalStateException("Non-PreviewItem member in group " + g.getName() + ": " + m);
            }
        }
        return (GroupItem<PreviewItem>) g;
    }

    // ==================== 应用到全局（条目独立参数需求） ====================

    /** 目标生效参数来源是否为独立参数（条目级或组参数；拍板：仅此状态"应用到全局"可用）—— 判定收口到服务（R8-①） */
    private boolean hasIndependentParams(Object target) {
        return effectiveParamsService.hasIndependentParams(target, effectiveContext());
    }

    /** 右键菜单"应用到全局"：目标 = 右键点击的条目 */
    private void onContextApplyGlobal() {
        if (listAnimating || !hasIndependentParams(contextTarget)) {
            return;
        }
        applyGlobalParamsTo(contextTarget);
    }

    /** 按钮行"应用到全局"：目标 = 当前选中条目 */
    @FXML
    private void onApplyGlobalClicked() {
        if (listAnimating) {
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
        boolean imageMode = imageModeToggle.isSelected();
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
        if (listAnimating) {
            return;
        }
        clearParamsForTargets(resolveClearTargets(currentSelection()));
    }

    /** 右键菜单"清除参数"：动作逻辑与按钮一致（勾选优先 → 右键目标；启用看右键目标） */
    private void onContextClearParams() {
        if (listAnimating) {
            return;
        }
        clearParamsForTargets(resolveClearTargets(contextTarget));
    }

    /** 勾选集合优先 → 回退对象（与"移除"的勾选优先拍板一致） */
    private List<Object> resolveClearTargets(Object fallback) {
        List<PreviewItem> checked = allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        if (!checked.isEmpty()) {
            return new ArrayList<>(checked);
        }
        return fallback == null ? new ArrayList<>() : new ArrayList<>(List.of(fallback));
    }

    /**
     * 清除参数（拍板 Q1-Q4）：只清当前模式。组外条目 → 清条目级参数；组内成员/组条目 → 清整组组参数
     * （按组去重）；无参数目标自然跳过；一次操作一条 CLEAR_PARAMS 撤销记录（before 快照），一次撤销全部恢复。
     */
    private void clearParamsForTargets(List<Object> rawTargets) {
        boolean imageMode = imageModeToggle.isSelected();
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
                GroupItem<PreviewItem> grp = asPreviewGroup(g);
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
        fileListView.refresh();
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
                extractResultBanner.setText(prefix + I18n.get("list.colon") + it.extractDetail);
                extractResultBanner.setVisible(true);
                extractResultBanner.setManaged(true);
                return;
            }
        }
        if (sel instanceof PreviewItem it && it.status == PreviewItem.Status.FAILED) {
            extractResultBanner.setText(I18n.get("extract.parseFailed") + it.errorMsg);
            extractResultBanner.setVisible(true);
            extractResultBanner.setManaged(true);
            return;
        }
        extractResultBanner.setVisible(false);
        extractResultBanner.setManaged(false);
    }

    /* test-support */ void refreshAfterParamsChanged() {
        Object sel = currentSelection();
        updateBadgeHeader();
        updateOutputModeLabel(sel);
        updateRenameNote(sel);
        fileListView.refresh();
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
        recomputeBadges();
        fileListView.refresh();
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
        if (listAnimating) {
            return;
        }
        String inputFolder = inputFolderField.getText();
        String outputFolder = outputFolderField.getText();

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
            if (denial.level() == ExtractionPreconditions.Level.ERROR) {
                NotificationHost.error(statusLabel, denial.code().message(), FontAwesomeSolid.EXCLAMATION_CIRCLE);
            } else {
                NotificationHost.toastWarning(statusLabel, denial.code().message(), FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            }
            return;
        }

        boolean imageMode = imageModeToggle.isSelected();

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
            NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.SLIDERS_H, ok -> {
                if (ok) {
                    startExtractionTask();
                }
            });
            return;
        }

        startExtractionTask();
    }

    /** 以已确认参数（未确认时用默认参数）+ 主窗口 GUI 状态构建运行时提取配置 */
    /* test-support */ AppConfig buildRuntimeConfig() {
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
        boolean imageMode = imageModeToggle.isSelected();

        // 生成时间戳会话目录，隔离每次提取运行
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(I18n.get("params.datetimeFormat")));
        String sessionOutputFolder = new File(outputFolderField.getText(), timestamp).getPath();
        if (!new File(sessionOutputFolder).mkdirs()) {
            log.warn(I18n.msg("log.maincontroller.31", sessionOutputFolder));
        }
        log.info(I18n.msg("log.maincontroller.32", sessionOutputFolder));

        // 构建输出单元计划（FX 线程快照；单元参数补充运行时 GUI 字段）
        List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                items, ITEM_VIEW, imageMode, textParamsCache, imageParamsCache, sessionOutputFolder);
        for (OutputUnitPlanner.OutputUnit unit : units) {
            AppConfig params = unit.getParams();
            params.setInputFolder(inputFolderField.getText());
            params.setOutputFolder(outputFolderField.getText());
            params.setImageModeEnabled(imageMode);
            params.setOpenFolderAfterComplete(openFolderAfterCompleteCheck.isSelected());
        }
        log.info(I18n.msg("log.maincontroller.33", units.stream()
                .map(u -> I18n.get("extract.groupFileCount", "group", u.label(),
                        "count", u.fileCount())).toList()));
        Map<String, PerFileOverrides> overrides = overrides();

        extractionCoordinator.run(
                new WorkspaceExtractionService.ExtractionRequest(sessionOutputFolder, units, overrides, imageMode,
                        recursionDecision()),
                statusLabel,
                extractionUi(sessionOutputFolder, openFolderAfterCompleteCheck.isSelected()));
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
            Platform.runLater(() -> NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.SITEMAP, future::complete));
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
                progressBar.setProgress(value);
            }

            @Override
            public void setProgressBarOutcome(ExtractionTaskCoordinator.ExtractOutcomeStyle style) {
                setProgressBarActivity(switch (style) {
                    case FAILED -> STYLE_PROGRESS_EXTRACT_FAILED;
                    case WARNED -> STYLE_PROGRESS_EXTRACT_WARNED;
                    case SUCCESS -> STYLE_PROGRESS_EXTRACT_SUCCESS;
                });
            }

            @Override
            public void extractionStarted() {
                startButton.setDisable(true);
                stopButton.setDisable(false);
                progressBar.setVisible(true);
                progressBar.setProgress(0);
                setProgressBarActivity(STYLE_PROGRESS_EXTRACT_SUCCESS);   // 提取中 = 开始提取按钮绿
            }

            @Override
            public void applyResults(BatchProcessor.ProcessStats stats) {
                applyExtractResults(stats);
            }

            @Override
            public void setStatusText(String text) {
                statusLabel.setText(text);
            }
        };
    }

    /**
     * 单次提取的运行标记（测试支撑）：投影到协调器持有的唯一实例 ——
     * 解析回调据此让出进度条 / 状态栏，语义与搬迁前 {@code running} 字段一致。
     */
    /* test-support */ AtomicBoolean running() {
        return extractionCoordinator.running;
    }

    /**
     * 最近一次提取的 partial/完整统计（测试支撑）：<b>只读</b>投影到协调器持有的唯一 holder ——
     * 写入只发生在 {@code ExtractionTaskCoordinator.run} 一处（服务返回值落一次）。
     */
    /* test-support */ AtomicReference<BatchProcessor.ProcessStats> extractStats() {
        return extractionCoordinator.lastExtractStats;
    }

    @FXML
    private void onStopProcessing() {
        extractionCoordinator.cancel();
        extractionCoordinator.running.set(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        progressBar.setVisible(false);
        setProgressBarActivity(null);
        statusLabel.setText(I18n.get("extract.stopped"));
        log.info(I18n.msg("log.maincontroller.41"));
    }

    // ==================== 列表渲染与拖拽排序 ====================

    /** 列表单元格工厂（单元格实现已外迁为顶层类 PreviewListCell，见该类 javadoc） */
    private ListCell<Object> createPreviewCell() {
        return new PreviewListCell(this);
    }

    /**
     * 拖放核心执行（阶段 7 6.6 拖拽规则）：落点解析 → 数据序修改 → REORDER/MERGE_GROUPS 入撤销栈 →
     * 释放动画 → 父组变化项目重新预解析（生效参数切换，阶段 5 机制）。
     * cell 非空 = 真实 DnD 路径（含动画）；cell 为 null = 程序化演练路径（跳过动画）。
     */
    /* test-support */ boolean performDrop(Object dragged, Object target, DropResolver.Zone zone,
                                PreviewListCell cell) {
        if (dragged == null || target == null || (target instanceof PreviewItem it && it.removed)) {
            return false;
        }
        DropResolver.Operation op = DropResolver.resolve(items, dragged, target, zone);
        if (op == null) {
            return false;
        }
        // 立即结束提起动画（若仍在进行），快照按当前实际位置重新采集
        if (cell != null) {
            finishLift();
        }
        double gapTopY = 0;
        if (cell != null) {
            gapTopY = cellBoundsInOverlay(cell).getMinY()
                    + (zone == DropResolver.Zone.TOP ? 0 : cell.getHeight());
        }
        return performResolvedDrop(dragged, op, gapTopY, cell);
    }

    /** 已解析操作的执行核心：快照 → apply → 撤销记录（均由模型负责）→ 动画/瞬时重建 → 父组变化重解析 */
    private boolean performResolvedDrop(Object dragged, DropResolver.Operation op,
                                        double gapTopY, PreviewListCell cell) {
        // 快照、受影响条目、各自操作前的父组指针、合并载荷与撤销记录的压栈全在模型内完成 ——
        // 采集顺序必须早于数据序修改，那个约束现在由 WorkspaceModel.applyResolvedDrop 独占保证。
        WorkspaceModel.DropResult drop = ws.applyResolvedDrop(op);
        // 注意：DropResolver 拖拽致空组（R4）在原实现与新实现下都**只改结构、不打日志**；
        // log.workspacemodel.01 只属于 WorkspaceModel.group(...) 与 removeItems(...) 两条路径，本步未触碰。
        // 合并语义的打点（log.maincontroller.42）文案、实参、调用次数与位置一律保持原样。
        if (drop.changed() && drop.type() == UndoManager.RecordType.MERGE_GROUPS) {
            log.info(I18n.msg("log.maincontroller.42", drop.merged().getName(),
                    drop.mergeTarget().getName(), drop.mergedMembers().size(), drop.merged().getName()));
        }
        if (cell != null) {
            animateReleaseInsert(dragged, gapTopY);
        } else {
            // 空白吸附：瞬时重建显示序并复位拖拽状态
            rebuildDisplay();
            finishDragReset();
        }
        // 项目入组/出组/组合并 → 生效参数切换：受影响项目清缓存重新预解析（阶段 5 机制）
        List<PreviewItem> reparse = new ArrayList<>();
        for (PreviewItem it : drop.affected()) {
            if (!it.removed && drop.affectedBefore().get(it) != it.parentGroup) {
                // 条目独立参数生命周期拍板：入组即清除条目级参数（组内成员只认组参数）
                if (it.parentGroup != null) {
                    it.textItemParams = null;
                    it.imageItemParams = null;
                }
                clearPreviewCache(it);
                reparse.add(it);
            }
        }
        if (!reparse.isEmpty() && currentModeConfirmed()) {
            startPreParseFor(reparse);
        }
        return true;
    }

    // ==================== 拖拽排序动画 ====================

    static SnapshotParameters transparentSnapParams() {
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

    /* test-support */ PreviewListCell findCell(Object item) {
        for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
            if (n instanceof PreviewListCell c && c.getItem() == item) {
                return c;
            }
        }
        return null;
    }

    /** 提起：被拖条目从显示序瞬间移除（数据序不动），下方条目快照上滑 0.2s 补位 */
    void beginDrag(PreviewListCell sourceCell) {
        draggedObject = sourceCell.getItem();
        dragPositionStale = false;
        dragActive = true;
        double dragGapTopY = cellBoundsInOverlay(sourceCell).getMinY();
        double rowHeight = sourceCell.getHeight();

        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c != sourceCell && c.getItem() != null
                        && !(c.getItem() instanceof PreviewItem it && it.removed)) {
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
            // 快照失败兜底：瞬时重排复位（cancelDrag 会清除 dragActive，防卡死）
            log.warn(I18n.msg("log.maincontroller.43", ex.getMessage()), ex);
            dragOverlay.getChildren().removeAll(views);
            cancelDrag();
            return;
        }

        adapter.hideRow(draggedObject);
        cells.forEach(c -> c.setVisible(false));

        liftCells = cells;
        liftViews = views;
        liftAnim = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(-rowHeight);
            liftAnim.getChildren().add(tt);
        }
        liftAnim.setOnFinished(_ -> finishLift());
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
     * 松手/取消共用：调用前数据序已就绪（松手路径由调用方调整，取消路径未动），
     * 重建显示序让条目出现在目标行（静态快照置于最上层），下方条目快照从其底下下滑 0.2s 让位
     */
    private void animateReleaseInsert(Object item, double gapTopY) {
        double rowHeight = 0;
        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c.getItem() != null
                        && !(c.getItem() instanceof PreviewItem it && it.removed)) {
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
            log.warn(I18n.msg("log.maincontroller.43", ex.getMessage()), ex);
            dragOverlay.getChildren().removeAll(views);
            rebuildDisplay();
            finishDragReset();
            return;
        }

        if (rowHeight <= 0) {
            rowHeight = 39;
        }

        rebuildDisplay();
        // 组合并后被拖组已不在显示序中：仅当对象仍存在时选中（避免 ListView.select 找不到对象）
        if (displayItems.contains(item)) {
            fileListView.getSelectionModel().select(item);
        }
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
                log.warn(I18n.msg("log.maincontroller.45", ex.getMessage()), ex);
            }
            itemCell.setVisible(false);
        }

        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(rowHeight);
            pt.getChildren().add(tt);
        }
        pt.setOnFinished(_ -> {
            dragOverlay.getChildren().clear();
            cells.forEach(c -> c.setVisible(true));
            if (itemCell != null) {
                itemCell.setVisible(true);
            }
            finishDragReset();
        });
        pt.play();
    }

    /** 拖拽全流程结束：复位状态并重算徽标/信息区/按钮/进度 */
    private void finishDragReset() {
        draggedObject = null;
        dragActive = false;
        dropHandled = false;
        onOrderChanged();
    }

    /** 取消拖拽：瞬时恢复到原显示位置（数据序未动，重建显示序即可）。
     *  曾用快照释放动画，但列表外松手的恢复路径存在间歇性丢失（条目消失），改为瞬时恢复保证可靠 */
    void cancelDrag() {
        finishLift();
        rebuildDisplay();
        finishDragReset();
    }

    private void onOrderChanged() {
        recomputeBadges();
        updateListButtons();
        updateProgressStatus(parseToken);
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        updateInfoArea(sel);
        if (!isTextMode() && sel instanceof PreviewItem it && it.cachedThumbs != null) {
            renderThumbnails(it);
        }
    }

    /** 顶层组列表（按数据序；供 agent 演练使用）—— 委托模型 */
    /* test-support */ List<GroupItem<PreviewItem>> topLevelGroups() {
        return ws.groups();
    }

    /** 组内有效成员（removed == false；供 agent 演练使用） */
    /* test-support */ List<PreviewItem> visibleMembersOf(GroupItem<?> g) {
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

    // ==================== 列表复选框（F1） ====================

    /** 列头 CheckBox 点击：当前全不选 → 全选；全选或半选 → 全不选（作用于全部有效项，含组内成员） */
    private void onSelectAllToggled() {
        if (updatingSelection || listAnimating) {
            return;
        }
        boolean target = SelectionState.computeToggleTarget(currentSelectionState());
        updatingSelection = true;
        for (PreviewItem item : allVisibleItems()) {
            item.setSelected(target);
        }
        updatingSelection = false;
        refreshSelectionUi();
    }

    /** 单项勾选变化 → 列头三态与组复选框实时联动 */
    private void onSelectionChanged() {
        if (!updatingSelection) {
            refreshSelectionUi();
            updateListButtons();
        }
    }

    /**
     * 行按压的 Ctrl/Shift 多选手势（路线 B 拍板）：只作用于勾选集合，不影响单选选中项。
     * Ctrl+点击 = 反选该行（组行 = 整组全选/全不选切换）；Shift+点击 = 从锚点按显示序
     * 区间勾选（区间内组行 = 整组勾选）；无修饰键 = 维持现状并更新锚点。
     * 动画锁定期间忽略；removed 项不可交互。
     */
    void onCellPressed(Object item, javafx.scene.input.MouseEvent e) {
        // 拖拽状态自愈：真实拖拽期间鼠标被 DnD 手势捕获，按压事件不会到达；
        // 此处仍有 dragActive = 上次拖拽异常残留（如列表外松手后 DONE 丢失）→ 立即恢复显示与状态，
        // 本次按压继续正常处理（数据序未动，cancelDrag 只是重建显示序 + 复位标志）
        if (dragActive) {
            cancelDrag();
        }
        // 仅左键：右键按压不得触发折叠/多选手势（右键由 contextMenuRequested 处理）
        if (listAnimating || item == null || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (item instanceof GroupItem<?> g) {
            GroupItem<PreviewItem> group = asPreviewGroup(g);
            // 箭头槽位（cell x 22-46）：折叠/展开，优先于 Ctrl/Shift 多选手势；
            // pressed 判定比 released 可靠（released 会被 checkbox 吞掉导致间歇丢失）
            if (e.getX() >= 22 && e.getX() <= 46) {
                toggleGroupExpanded(group);
                return;
            }
            if (e.isControlDown() && !e.isShiftDown()) {
                onGroupCheckToggled(group);
                selectionAnchor = group;
            } else if (e.isShiftDown() && selectionAnchor != null) {
                applyShiftRange(selectionAnchor, group);
            } else {
                selectionAnchor = group;
            }
            return;
        }
        PreviewItem it = (PreviewItem) item;
        if (it.removed) {
            return;
        }
        if (e.isControlDown() && !e.isShiftDown()) {
            it.setSelected(!it.selected);
            selectionAnchor = it;
            refreshSelectionUi();
            updateListButtons();
        } else if (e.isShiftDown() && selectionAnchor != null) {
            applyShiftRange(selectionAnchor, it);
        } else {
            selectionAnchor = it;
        }
    }

    /**
     * Shift 区间勾选/反选（拍板）：按显示序取锚点到当前行区间——区间内全部已完全勾选则取消，
     * 否则全部勾选（项目行 = 自身；组行 = 全部有效成员）。
     */
    private void applyShiftRange(Object anchor, Object current) {
        List<Object> range = SelectionState.shiftSelectRange(displayItems, anchor, current);
        if (range.isEmpty()) {
            return;
        }
        boolean allChecked = SelectionState.rangeAllChecked(range, this::isRowFullyChecked);
        boolean target = !allChecked;
        updatingSelection = true;
        try {
            for (Object o : range) {
                setRangeRow(o, target);
            }
        } finally {
            updatingSelection = false;
        }
        selectionAnchor = current;
        refreshSelectionUi();
        updateListButtons();
    }

    /** 行的"完全勾选"判定：项目行 = 自身勾选；组行 = 全部有效成员已勾选 */
    private boolean isRowFullyChecked(Object o) {
        if (o instanceof PreviewItem it) {
            return it.selected;
        }
        if (o instanceof GroupItem<?> g) {
            for (Object m : g.getMembers()) {
                if (!(m instanceof PreviewItem p) || (!p.removed && !p.selected)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    /** 区间内行设置勾选值：项目行设自身；组行设全部有效成员 */
    private void setRangeRow(Object o, boolean target) {
        if (o instanceof PreviewItem it && !it.removed) {
            it.setSelected(target);
        } else if (o instanceof GroupItem<?> g) {
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewItem p && !p.removed) {
                    p.setSelected(target);
                }
            }
        }
    }

    /**
     * 双击条目 toggle 勾选（拍板）：成员行双击文件名区域（cell x ≥ 50，checkbox 槽之外）
     * 反选自身；组行双击组名区域（cell x ≥ 46，折叠判定区之外）整组反选。
     * 图形交互槽位（复选框/折叠箭头）内双击不触发，避免与单击行为叠加。
     */
    void onCellDoubleClicked(Object item, javafx.scene.input.MouseEvent e) {
        // 仅左键双击（CLICKED 事件在松开后生成，isPrimaryButtonDown 恒为 false，须用 getButton 判定）。
        // 多击时间窗口内 clickCount 连续递增（快速连续双击会得到 3、4、5…），
        // 取偶数次点击（每个双击手势的第二次按下恒为偶数）保证每次双击恰好 toggle 一次。
        if (listAnimating || item == null || e.getClickCount() < 2 || e.getClickCount() % 2 != 0
                || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (item instanceof GroupItem<?> g) {
            if (e.getX() >= 46) {
                onGroupCheckToggled(asPreviewGroup(g));
            }
        } else if (item instanceof PreviewItem it && !it.removed) {
            if (e.getX() >= 50) {
                it.setSelected(!it.selected);
                selectionAnchor = it;
            }
        }
    }

    /** 按当前列表统计三态：有效项 = removed == false 的项目（含组内成员） */
    private SelectionState.TriState currentSelectionState() {
        List<PreviewItem> vis = allVisibleItems();
        int total = vis.size();
        int selected = (int) vis.stream().filter(i -> i.selected).count();
        return SelectionState.computeState(selected, total);
    }

    /** 列头三态刷新 + 列表单元格（含组复选框）联动刷新 */
    private void refreshSelectionUi() {
        SelectionState.TriState state = currentSelectionState();
        selectAllCheck.setIndeterminate(state == SelectionState.TriState.SOME);
        selectAllCheck.setSelected(state == SelectionState.TriState.ALL);
        fileListView.refresh();
    }

    // ==================== 显示序 / 数据序（F5） ====================

    /** 数据模型中的所有项目（含组内成员与已移除项）—— 委托 {@link WorkspaceModel#allItems()}（本类只读查询） */
    /* test-support */ List<PreviewItem> allPreviewItems() {
        return ws.allItems();
    }

    /** 全部有效项目（removed == false，含组内成员）。按数据序遍历：折叠组的成员不在显示序中，
     *  若按显示序遍历会导致全选/提取名单/进度统计漏掉折叠组成员（冒烟测试发现，阶段 4 遗留） */
    /* test-support */ List<PreviewItem> allVisibleItems() {
        return ws.allItems().stream().filter(i -> !i.removed).toList();
    }

    /** 重建父组指针（显示序重建时同步调用）—— 委托模型，本类不再留第二份实现 */
    private void rebuildParentPointers() {
        ws.rebuildParentPointers();
    }

    /** 按数据序重建显示序（ListView 渲染展平列表）。动画期间的外部重建（换目录/模式切换等）先快进完成动画 */
    /* test-support */ void rebuildDisplay() {
        if (listAnimating && !suppressAnimRebuild) {
            finishExpandAnim();
        }
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
    /* test-support */ long visibleCount() {
        return ws.visibleCount();
    }

    /** 显示序中"可见行"判定：有效项目或组条目 */
    private boolean isVisibleRow(Object o) {
        return (o instanceof PreviewItem it && !it.removed) || o instanceof GroupItem<?>;
    }

    private void updateFileCount() {
        fileCountLabel.setText(I18n.get("list.fileCount", "count", visibleCount()));
    }

    /* 列表操作按钮可用性：列表为空时移除禁用；撤销栈为空时撤销禁用；合并为组需勾选集合或选中项目；拆分组仅选中组条目时可用（F6） */
    /** 查看HTML源码按钮（浏览器查看HTML需求）：对列表当前选中条目打开源码视图 */
    @FXML
    private void onViewSourceButton() {
        if (listAnimating) {
            return;
        }
        if (currentSelection() instanceof PreviewItem it && !it.removed) {
            openHtmlInBrowser(it, true);
        }
    }

    /* test-support */ void updateListButtons() {
        removeButton.setDisable(visibleCount() == 0);
        undoButton.setDisable(!ws.canUndo());
        boolean anyChecked = allVisibleItems().stream().anyMatch(i -> i.selected);
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        boolean selItem = sel instanceof PreviewItem it && !it.removed;
        groupButton.setDisable(!anyChecked && !selItem);
        ungroupButton.setDisable(!(sel instanceof GroupItem<?>));
        viewSourceButton.setDisable(!selItem);
        // 条目独立参数需求拍板：仅当选中条目生效参数为独立参数时可"应用到全局"
        applyGlobalButton.setDisable(!hasIndependentParams(sel));
        // 清除参数拍板 Q3：勾选集合任一目标带参数（无勾选 → 单选目标）即启用
        boolean anyClearable = anyChecked
                ? allVisibleItems().stream().filter(i -> i.selected)
                        .anyMatch(this::hasIndependentParams)
                : hasIndependentParams(sel);
        clearParamsButton.setDisable(!anyClearable);
    }

    /** 开始按钮可用性的唯一派生函数 —— 所有禁用/恢复都必须经由此函数 */
    /* test-support */ void updateStartButtonState() {
        boolean anyMode = textModeToggle.isSelected() || imageModeToggle.isSelected();
        startButton.setDisable(!anyMode || visibleCount() == 0 || isImporting());
    }

    /**
     * 列表结构变化（移除/撤销/导入/建组）后的统一刷新，按指南拆成两段：
     * ① <b>刷新模型投影</b> —— {@code adapter.refresh()}（全量投影 + 按对象钉住选中，
     *    即原 {@code rebuildDisplay()} 加"仍在显示序则重选"那三行，规则逐字未改）；
     * ② <b>更新计数/按钮/选择等 UI 派生状态</b> —— 下面这些自己读模型/控件的派生刷新。
     */
    /* test-support */ void refreshAfterListChange() {
        if (listAnimating && !suppressAnimRebuild) {
            finishExpandAnim();
        }
        rebuildParentPointers();
        adapter.refresh();
        updateFileCount();
        updateListButtons();
        updateStartButtonState();
        refreshSelectionUi();
        recomputeBadges();
        // 方案A：无条件刷新——全部无参数时 updateProgressStatus 会隐藏进度条且不动状态栏
        updateProgressStatus(parseToken);
        Object selAfter = fileListView.getSelectionModel().getSelectedItem();
        updateInfoArea(selAfter);
        updateContentArea(selAfter);
    }

    @FXML
    private void onRemoveClicked() {
        if (listAnimating) {
            return;
        }
        // R5：有复选框勾选时按勾选集合移除（优先于当前选中项，含组内成员）
        List<PreviewItem> targets = allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        // D7：无勾选且选中组条目 → 整组移除（组名/参数随组对象保留在撤销记录中）
        if (targets.isEmpty() && sel instanceof GroupItem<?> g) {
            // G2（拍板）：整组移除一律必弹确认（R6"仅列表将空时确认"的有意例外），按钮行与右键菜单共用
            removeGroupWithConfirm(asPreviewGroup(g));
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
            NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.TRASH_ALT, ok -> {
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
            NotificationHost.toastInfo(statusLabel, ErrorCode.KEEP_AT_LEAST_ONE.message(), FontAwesomeSolid.LIST);
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
        NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.OBJECT_GROUP, ok -> {
            if (ok) {
                removeGroup(group);
                log.info(I18n.msg("log.maincontroller.47", group.getName(), memberCount, visibleCount()));
                if (visibleCount() == 0) {
                    NotificationHost.toastInfo(statusLabel, ErrorCode.KEEP_AT_LEAST_ONE.message(), FontAwesomeSolid.LIST);
                }
            }
        });
    }

    /** 隐藏式移除：结构变更（removed 标记 + 空组自动销毁 D11）与撤销记录由 {@link WorkspaceModel#removeItems} 负责；
     *  本类只做缓存规则 (c) 与选择/刷新收尾 */
    /* test-support */ void removeItems(List<PreviewItem> targets) {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
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
        Object sel = fileListView.getSelectionModel().getSelectedItem();
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
                GroupItem<PreviewItem> group = asPreviewGroup(g);
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
        if (listAnimating || !ws.canUndo()) {
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
                    Object sel = fileListView.getSelectionModel().getSelectedItem();
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
                            .filter(i -> !i.removed).findFirst().ifPresent(firstVisible -> fileListView.getSelectionModel().select(firstVisible));
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
                    if (record.paramTargetPath() != null) {
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
                                GroupItem<PreviewItem> grp = asPreviewGroup(g);
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
                    fileListView.refresh();
                    updateInfoArea(currentSelection());
                    updateExtractBanner(currentSelection());
                    updateListButtons();
                    log.info(I18n.msg("log.maincontroller.53", modeWord(im)));
                }
                case CLEAR_PARAMS -> {
                    // 清除参数拍板 Q4：按 before 快照一次恢复全部被清除的条目/组参数，并重走联动刷新
                    boolean im = record.clearImageMode();
                    Map<String, AppConfig> itemBefore = record.clearItemParamsBefore();
                    List<PreviewItem> restoredItems = new ArrayList<>();
                    for (PreviewItem it : allPreviewItems()) {
                        if (itemBefore.containsKey(it.path)) {
                            if (im) {
                                it.imageItemParams = itemBefore.get(it.path);
                            } else {
                                it.textItemParams = itemBefore.get(it.path);
                            }
                            restoredItems.add(it);
                        }
                    }
                    Map<Integer, AppConfig> groupBefore = record.clearGroupParamsBefore();
                    List<PreviewItem> affected = new ArrayList<>(restoredItems);
                    for (Object o : items) {
                        if (o instanceof GroupItem<?> g) {
                            GroupItem<PreviewItem> grp = asPreviewGroup(g);
                            if (groupBefore.containsKey(grp.getGroupNo())) {
                                if (im) {
                                    grp.setImageParamsCache(groupBefore.get(grp.getGroupNo()));
                                } else {
                                    grp.setTextParamsCache(groupBefore.get(grp.getGroupNo()));
                                }
                                affected.addAll(grp.getMembers().stream()
                                        .filter(m -> !m.removed).toList());
                            }
                        }
                    }
                    clearExtractOutcomes(affected);
                    // 规则 (a)：撤销恢复被清除的参数 → 受影响条目生效参数变化 → 删其缓存目录
                    workspaceParamService.paramsChanged(im, affected);
                    refreshAfterTargetedParamsChange(affected);
                    updateListButtons();
                    updateExtractBanner(currentSelection());
                    log.info(I18n.msg("log.maincontroller.54", modeWord(im), restoredItems.size(), groupBefore.size()));
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
            fileListView.getSelectionModel().select(selectAfter);
        }
        // 父组指针已在 refreshAfterListChange 重建，此时按恢复后的生效参数重解析受影响项目
        if (!reparseAfter.isEmpty() && currentModeConfirmed()) {
            startPreParseFor(reparseAfter);
        }
    }

    /** 选中最近的可见行（项目或组条目；优先下一行，无则选上一行，都没有则清空选中） */
    private void selectNearestVisible(int fromDisplayIndex) {
        int start = Math.max(0, Math.min(fromDisplayIndex, displayItems.size() - 1));
        for (int i = start; i < displayItems.size(); i++) {
            if (isVisibleRow(displayItems.get(i))) {
                fileListView.getSelectionModel().select(displayItems.get(i));
                return;
            }
        }
        for (int i = start - 1; i >= 0; i--) {
            if (isVisibleRow(displayItems.get(i))) {
                fileListView.getSelectionModel().select(displayItems.get(i));
                return;
            }
        }
        fileListView.getSelectionModel().clearSelection();
    }

    // ==================== 合并为组 / 组操作（F5） ====================

    /** 合并为组：有复选框勾选按勾选集合，否则按当前选中项（单个项目可建组 R9）；新组默认名"组N"（全局自增） */
    @FXML
    private void onGroupClicked() {
        if (listAnimating) {
            return;
        }
        List<PreviewItem> targets = allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        if (targets.isEmpty()) {
            if (sel instanceof PreviewItem it && !it.removed) {
                targets = List.of(it);
            } else {
                return;
            }
        }
        // 结构（落点计算 / 成员摘除 / 致空组销毁 / 条目级参数清除 / 压 GROUP 撤销记录）全部由模型负责；
        // 组名前缀由本类传入本地化文案 —— 模型不产出面向用户的文案。
        GroupItem<PreviewItem> group = ws.group(targets, I18n.get("core.groupDefault"));
        if (group == null) {
            return;
        }
        registerGroupNameRefresh(group);
        // 拍板：合并为组后勾选集合复位，避免勾选残留导致下一批建组误合并
        updatingSelection = true;
        try {
            for (PreviewItem t : targets) {
                t.setSelected(false);
            }
        } finally {
            updatingSelection = false;
        }
        fileListView.getSelectionModel().select(group);
        refreshAfterListChange();
        log.info(I18n.msg("log.maincontroller.56", group.getName(), targets.size()));
    }

    /** 拆分组（F6）：选中组条目时解散该组——成员按组内顺序回顶层原组位置，组名/组参数随组对象留在撤销记录中 */
    @FXML
    private void onUngroupClicked() {
        if (listAnimating) {
            return;
        }
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        if (!(sel instanceof GroupItem<?> g)) {
            return;
        }
        GroupItem<PreviewItem> group = asPreviewGroup(g);
        if (items.indexOf(group) < 0) {
            return;
        }
        int displayIdx = displayItems.indexOf(group);
        // 拆分是整组操作，与展开/折叠无关。结构变更（成员按组内顺序回顶层原位置 + 组参数下发为
        // 成员条目级参数 + 压 UNGROUP 撤销记录）全部由模型负责，本类只做刷新与选中收尾。
        List<PreviewItem> members = ws.ungroup(group);
        refreshAfterListChange();
        // 选中的组已解散：跳转到原组位置的最近有效行（拆分后为原组第一个成员）
        selectNearestVisible(displayIdx);
        log.info(I18n.msg("log.maincontroller.57", group.getName(), members.size()));
    }

    /** 组复选框点击（F1 2.5）：组内全选 → 全不选；全不选或半选 → 全选组内全部成员 */
    void onGroupCheckToggled(GroupItem<PreviewItem> g) {
        if (listAnimating) {
            return;
        }
        int total = visibleMemberCount(g);
        int selected = (int) g.getMembers().stream()
                .filter(m -> !m.removed && m.selected).count();
        boolean target = SelectionState.computeToggleTarget(
                SelectionState.computeState(selected, total));
        updatingSelection = true;
        for (PreviewItem m : g.getMembers()) {
            if (!m.removed) {
                m.setSelected(target);
            }
        }
        updatingSelection = false;
        refreshSelectionUi();
        updateListButtons();
    }

    /**
     * 组条目折叠/展开（方案 B 拍板）：抽屉式滑出/滑入——内容不裁切，成员行整体平移，
     * 下方行同步补位/让位（快照动画手法与拖拽一致）。200ms ease-out。
     * 动画期间列表项目不可交互（按钮可点但无效果）；快速连点/拖拽会话中直接忽略。
     */
    /* test-support */ void toggleGroupExpanded(GroupItem<PreviewItem> g) {
        if (listAnimating || dragActive) {
            return;
        }
        if (g.isExpanded()) {
            collapseGroupAnimated(g);
        } else {
            expandGroupAnimated(g);
        }
    }

    /**
     * 折叠动画（抽屉收起）：成员行快照整体下滑滑出，下方行快照上滑补位。
     * 数据终态（expanded=false + 显示序移除成员）在动画开始时立即生效；
     * 外部重建触发快进兜底时只清理快照即可。
     */
    private void collapseGroupAnimated(GroupItem<PreviewItem> g) {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        if (sel instanceof PreviewItem it && it.parentGroup == g) {
            fileListView.getSelectionModel().select(g);
        }
        listAnimating = true;
        animatingGroup = g;
        animExpanding = false;
        animLockedSelection = g;
        groupNameField.setDisable(true);

        // 采集快照（动画前：成员行与下方行均在原位置）；组行底部 = 成员收进抽屉的目标位置
        List<ImageView> memberViews = new ArrayList<>();
        List<ImageView> belowViews = new ArrayList<>();
        List<PreviewListCell> belowCells = new ArrayList<>();
        double groupBottom = -1;
        double memberBottom = -1;
        double totalHeight = 0;
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (!(n instanceof PreviewListCell c) || c.getItem() == null
                        || (c.getItem() instanceof PreviewItem it && it.removed)) {
                    continue;
                }
                Bounds b = cellBoundsInOverlay(c);
                if (c.getItem() == g) {
                    groupBottom = b.getMaxY();
                } else if (c.getItem() instanceof PreviewItem it && it.parentGroup == g) {
                    Image snap = c.snapshot(transparentSnapParams(), null);
                    if (snap == null) {
                        continue;   // 快照失败跳过该行，避免空白视图盖住列表
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    dragOverlay.getChildren().add(iv);
                    memberViews.add(iv);
                    memberBottom = Math.max(memberBottom, b.getMaxY());
                    totalHeight += c.getHeight();
                } else if (memberBottom > 0 && b.getMinY() >= memberBottom - 1) {
                    // 成员块下方的行（原位置快照，动画中上滑补位）
                    Image snap = c.snapshot(transparentSnapParams(), null);
                    if (snap == null) {
                        continue;
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    dragOverlay.getChildren().add(iv);
                    belowViews.add(iv);
                    belowCells.add(c);
                }
            }
        } catch (Exception ex) {
            log.warn(I18n.msg("log.maincontroller.58", ex.getMessage()), ex);
            dragOverlay.getChildren().removeAll(memberViews);
            dragOverlay.getChildren().removeAll(belowViews);
            g.setExpanded(false);
            rebuildDisplay();
            finishExpandAnim();
            return;
        }

        // 数据终态：成员行移出显示序
        g.setExpanded(false);
        suppressAnimRebuild = true;
        try {
            rebuildDisplay();
        } finally {
            suppressAnimRebuild = false;
        }
        belowCells.forEach(c -> c.setVisible(false));

        double slide = totalHeight > 0 ? totalHeight : MEMBER_ROW_HEIGHT_FALLBACK;
        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : memberViews) {
            // 抽屉收起：每个成员滑到组行底部（位移 = 各自到组行底的距离，互不相同）
            double dist = Math.max(0, iv.getLayoutY() - groupBottom);
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(-dist);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        for (ImageView iv : belowViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(-slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        if (memberViews.isEmpty() && belowViews.isEmpty()) {
            // 无可见成员可动画：直接终态
            dragOverlay.getChildren().removeAll(memberViews);
            dragOverlay.getChildren().removeAll(belowViews);
            belowCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }
        collapseAnim = pt;
        collapseViews = memberViews;
        collapseViews.addAll(belowViews);
        collapseCells = belowCells;
        pt.setOnFinished(_ -> finishExpandAnim());
        pt.play();
    }

    /**
     * 展开动画（抽屉拉出）：显示序先重建到终态，成员行快照从上方滑入到位，
     * 下方行快照下滑让位。快进兜底只清理快照。
     */
    private void expandGroupAnimated(GroupItem<PreviewItem> g) {
        listAnimating = true;
        animatingGroup = g;
        animExpanding = true;
        animLockedSelection = fileListView.getSelectionModel().getSelectedItem();
        groupNameField.setDisable(true);
        g.setExpanded(true);
        suppressAnimRebuild = true;
        try {
            rebuildDisplay();
        } finally {
            suppressAnimRebuild = false;
        }
        fileListView.layout();

        List<ImageView> memberViews = new ArrayList<>();
        List<ImageView> belowViews = new ArrayList<>();
        List<PreviewListCell> hiddenCells = new ArrayList<>();
        double groupBottom = -1;
        double memberBottom = -1;
        double totalHeight = 0;
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (!(n instanceof PreviewListCell c) || c.getItem() == null
                        || (c.getItem() instanceof PreviewItem it && it.removed)) {
                    continue;
                }
                Bounds b = cellBoundsInOverlay(c);
                if (c.getItem() == g) {
                    groupBottom = b.getMaxY();
                } else if (c.getItem() instanceof PreviewItem it && it.parentGroup == g) {
                    Image snap = c.snapshot(transparentSnapParams(), null);
                    if (snap == null) {
                        continue;   // 快照失败跳过该行，避免空白视图盖住列表
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    dragOverlay.getChildren().add(iv);
                    memberViews.add(iv);
                    memberBottom = Math.max(memberBottom, b.getMaxY());
                    totalHeight += c.getHeight();
                    hiddenCells.add(c);
                } else if (memberBottom > 0 && b.getMinY() >= memberBottom - 1) {
                    Image snap = c.snapshot(transparentSnapParams(), null);
                    if (snap == null) {
                        continue;
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    dragOverlay.getChildren().add(iv);
                    belowViews.add(iv);
                    hiddenCells.add(c);
                }
            }
        } catch (Exception ex) {
            log.warn(I18n.msg("log.maincontroller.59", ex.getMessage()), ex);
            dragOverlay.getChildren().removeAll(memberViews);
            dragOverlay.getChildren().removeAll(belowViews);
            hiddenCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }

        hiddenCells.forEach(c -> c.setVisible(false));

        double slide = totalHeight > 0 ? totalHeight : MEMBER_ROW_HEIGHT_FALLBACK;
        // 抽屉拉出：每个成员快照从组行底部出发（初始位移 = 各自到组行底的距离），滑到各自位置
        for (ImageView iv : memberViews) {
            double dist = Math.max(0, iv.getLayoutY() - groupBottom);
            iv.setTranslateY(-dist);
        }
        for (ImageView iv : belowViews) {
            iv.setTranslateY(-slide);
        }
        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : memberViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        for (ImageView iv : belowViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        if (memberViews.isEmpty() && belowViews.isEmpty()) {
            dragOverlay.getChildren().removeAll(memberViews);
            dragOverlay.getChildren().removeAll(belowViews);
            hiddenCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }
        collapseAnim = pt;
        collapseViews = memberViews;
        collapseViews.addAll(belowViews);
        collapseCells = hiddenCells;
        pt.setOnFinished(_ -> finishExpandAnim());
        pt.play();
    }

    /** 折叠/展开动画结束（正常完成或外部重建快进）：清理快照、恢复 cell、解锁 */
    /* test-support */ void finishExpandAnim() {
        if (collapseAnim != null) {
            collapseAnim.stop();
            collapseAnim = null;
        }
        if (collapseViews != null) {
            dragOverlay.getChildren().removeAll(collapseViews);
            collapseViews = null;
        }
        if (collapseCells != null) {
            collapseCells.forEach(c -> c.setVisible(true));
            collapseCells = null;
        }
        animatingGroup = null;
        animExpanding = false;
        animLockedSelection = null;
        listAnimating = false;
        groupNameField.setDisable(false);
        // 解锁后刷新单元格：动画开始时按 listAnimating=true 渲染的 checkbox 禁用状态需重渲染恢复
        fileListView.refresh();
        fileListView.requestLayout();
    }

    // ==================== 组名编辑（F5 6.3） ====================

    /** 选中组条目：信息区第二行挂接组名编辑器（输入框显示组名，编辑实时绑定） */
    private void attachGroupNameEditor(GroupItem<PreviewItem> g) {
        if (nameEditingGroup != g) {
            nameEditingGroup = g;
            lastValidGroupName = g.getName();
            groupNameField.setText(g.getName());
        }
    }

    /** 组名输入即生效；重名时自动追加序号直至唯一并日志提示（R7） */
    private void onGroupNameEdited(String newVal) {
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
            groupNameField.setText(unique);
            updatingGroupName = false;
        }
        g.setName(unique);
        lastValidGroupName = unique;
    }

    /** 输入框失焦时空组名恢复为最近一次有效组名 */
    private void onGroupNameFocusLost() {
        GroupItem<PreviewItem> g = nameEditingGroup;
        if (g == null) {
            return;
        }
        String text = groupNameField.getText();
        if (text == null || text.trim().isEmpty()) {
            updatingGroupName = true;
            groupNameField.setText(lastValidGroupName);
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

    private void onItemSelected(Object item) {
        // 折叠/展开动画期间列表选择锁定：选择变化回选至锁定项（选中项一致时不触发回选，防递归）
        if (listAnimating) {
            Object locked = animLockedSelection;
            if (locked != null && fileListView.getSelectionModel().getSelectedItem() != locked) {
                fileListView.getSelectionModel().select(locked);
            }
            return;
        }
        requestToken++;
        imageLoadActive = false;
        loadingItem = null;
        if (lastSelected instanceof PreviewItem last && last != item) {
            // 仅当前文件的图片数据驻留内存
            last.cachedImages = null;
            last.cachedThumbs = null;
        }
        lastSelected = item;
        // 组行选中/未选中背景样式随选择变化切换；拖拽动画期间暂停刷新避免打乱快照画面
        if (!dragActive) {
            fileListView.refresh();
        }
        updateAdjustCheck(item);
        updateInfoArea(item);
        updateContentArea(item);
        updateListButtons();
    }

    /** 章节徽标重算（R11）：每个组 = 独立提取单元（组内重算），未分组项目按顶层顺序；每文件章节覆盖优先 */
    private void recomputeBadges() {
        int top = 1;
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                int n = 1;
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewItem it) {
                        n = assignBadge(it, n);
                    }
                }
            } else if (o instanceof PreviewItem it) {
                top = assignBadge(it, top);
            }
        }
        fileListView.refresh();
    }

    /** 单项徽标赋值：按该条目生效参数判定章节编号（组参数可独立开关）；章节覆盖关闭或未解析完成时不占编号 */
    private int assignBadge(PreviewItem it, int n) {
        if (it.removed) {
            it.badge = null;
            return n;
        }
        AppConfig eff = effectiveForObject(it);
        boolean numbering = isTextMode() && eff != null && eff.isAddChapterNumber();
        if (numbering && it.status == PreviewItem.Status.READY && it.chapterEnabled) {
            it.badge = String.valueOf(n);
            return n + 1;
        }
        it.badge = null;
        return n;
    }

    private void updateAdjustCheck(Object item) {
        // 第三行：文本模式 = 章节复选框（逐文件调整）；图片模式 = 输出结果展示（全局打包配置）；组条目两者均不显示
        boolean isItem = item instanceof PreviewItem it && !it.removed;
        AppConfig eff = isItem ? effectiveForObject(item) : null;
        boolean checkVisible = isTextMode() && eff != null && eff.isAddChapterNumber();
        adjustCheck.setVisible(checkVisible);
        adjustCheck.setManaged(checkVisible);
        if (checkVisible) {
            PreviewItem it = (PreviewItem) item;
            adjustCheck.setText(I18n.get("params.addChapterNumbers"));
            updatingAdjustCheck = true;
            adjustCheck.setSelected(it.chapterEnabled);
            updatingAdjustCheck = false;
        }
        boolean resultVisible = !isTextMode() && eff != null;
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
        return I18n.get("extract.unitFolder");
    }

    private void updateInfoArea(Object item) {
        updateExtractBanner(item);
        // 组条目：第二行（原"输出文件："位置）变为"输出组文件名："+ 可编辑输入框（F5 6.3）
        if (item instanceof GroupItem<?> g) {
            titleInfoLabel.setVisible(false);
            titleInfoLabel.setManaged(false);
            outputNameLabel.setVisible(false);
            outputNameLabel.setManaged(false);
            groupNameBox.setVisible(true);
            groupNameBox.setManaged(true);
            attachGroupNameEditor(asPreviewGroup(g));
            return;
        }
        AppConfig params = effectiveForObject(item);
        boolean confirmed = params != null;
        if (params == null) {
            params = new AppConfig();
        }
        nameEditingGroup = null;
        groupNameBox.setVisible(false);
        groupNameBox.setManaged(false);
        // 提取标题行：文本模式 + 标题提取开启且起始标记非空时显示（与正式提取判定一致，按该条目生效参数）
        boolean showTitle = confirmed && isTextMode() && params.isEnableTitleExtraction()
                && params.getTitleStartMarker() != null && !params.getTitleStartMarker().isEmpty();
        titleInfoLabel.setVisible(showTitle);
        titleInfoLabel.setManaged(showTitle);
        if (!(item instanceof PreviewItem it) || !confirmed) {
            outputNameLabel.setVisible(false);
            outputNameLabel.setManaged(false);
            return;
        }
        if (showTitle) {
            switch (it.status) {
                case WAITING -> titleInfoLabel.setText(I18n.get("preview.titleParsing"));
                case FAILED, EMPTY -> titleInfoLabel.setText(I18n.get("preview.titleNone"));
                case READY -> titleInfoLabel.setText(it.cachedTitle != null
                        ? I18n.get("preview.titlePrefix") + it.cachedTitle
                        : I18n.get("preview.titleNotFound"));
            }
        }
        outputNameLabel.setVisible(true);
        outputNameLabel.setManaged(true);
        if (isTextMode()) {
            if (params.isSeparateFiles()) {
                outputNameLabel.setText(I18n.get("preview.outputFilePrefix") + stripMhtExt(it.name) + ".txt");
            } else {
                String inputDirName = new File(inputFolderField.getText()).getName();
                if (inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                outputNameLabel.setText(I18n.get("preview.outputFilePrefix") + inputDirName + ".txt"
                        + I18n.get("preview.outputFileTxtSuffix"));
            }
        } else if (params.isSingleOutputMode()) {
            String folderName = mergedFolderName(it);
            outputNameLabel.setText(I18n.get("preview.outputDirPrefix") + folderName
                    + I18n.get("preview.outputDirMergedNote"));
        } else {
            String dirName = separateDirName(it);
            outputNameLabel.setText(I18n.get("preview.outputDirPrefix") + dirName);
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
        return stripMhtExt(item.name);
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
        return ImageBatchProcessor.determineDefaultFolderName(inputFolderField.getText());
    }

    private String stripMhtExt(String name) {
        return name.replaceAll("\\.(?i)(mht|mhtml)$", "");
    }

    // ==================== 内容区渲染 ====================

    private void updateContentArea(Object item) {
        boolean anyMode = textModeToggle.isSelected() || imageModeToggle.isSelected();
        if (!anyMode) {
            showCurrentModeArea();
            textPreviewArea.setText(HINT_NO_MODE_OR_DIR);
            return;
        }
        // 组条目：预览区显示组信息与参数状态提示（6.5）
        if (item instanceof GroupItem<?> g) {
            GroupItem<PreviewItem> group = asPreviewGroup(g);
            String info = I18n.get("preview.groupInfo") + group.getName()
                    + I18n.get("preview.groupItemCount", "count", visibleMemberCount(group))
                    + I18n.get("preview.paramsLabel") + groupParamStatusText(group);
            if (isTextMode()) {
                imagePreviewBox.setVisible(false);
                imagePreviewBox.setManaged(false);
                textPreviewArea.setVisible(true);
                textPreviewArea.setManaged(true);
                textPreviewArea.setText(info);
            } else {
                textPreviewArea.setVisible(false);
                textPreviewArea.setManaged(false);
                imagePreviewBox.setVisible(true);
                imagePreviewBox.setManaged(true);
                resetImageArea(info, false);
            }
            return;
        }
        PreviewItem it = item instanceof PreviewItem p ? p : null;
        if (isTextMode()) {
            imagePreviewBox.setVisible(false);
            imagePreviewBox.setManaged(false);
            textPreviewArea.setVisible(true);
            textPreviewArea.setManaged(true);
            if (it == null) {
                textPreviewArea.setText(visibleCount() == 0 ? HINT_NO_FILES : "");
                return;
            }
            if (effectiveForObject(it) == null) {
                textPreviewArea.setText(HINT_NEED_PARAMS);
                return;
            }
            switch (it.status) {
                case WAITING -> textPreviewArea.setText(I18n.get("preview.imageParsing"));
                case FAILED -> textPreviewArea.setText(I18n.get("preview.parseFailed") + it.errorMsg);
                case EMPTY -> textPreviewArea.setText(I18n.get("preview.emptyContentShort"));
                case READY -> renderTextPreview(it);
            }
        } else {
            textPreviewArea.setVisible(false);
            textPreviewArea.setManaged(false);
            imagePreviewBox.setVisible(true);
            imagePreviewBox.setManaged(true);
            if (it == null) {
                resetImageArea(visibleCount() == 0 ? HINT_NO_FILES : "", false);
                return;
            }
            if (effectiveForObject(it) == null) {
                resetImageArea(HINT_NEED_PARAMS, false);
                return;
            }
            if (it.status == PreviewItem.Status.FAILED) {
                imageHintLabel.setText(I18n.get("preview.parseFailed") + it.errorMsg);
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(false);
                imageScrollPane.setManaged(false);
            } else if (it.cachedThumbs != null) {
                renderThumbnails(it);
            } else if (imageLoadActive && loadingItem == it) {
                // 加载中：保留渐进追加的网格，不重置
                imageHintLabel.setText(I18n.get("preview.imageLoading"));
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

    /** 组参数状态提示（6.5）：组条目选中时预览区显示"该组使用独立参数"或"该组继承全局参数" */
    private String groupParamStatusText(GroupItem<PreviewItem> g) {
        boolean imageMode = imageModeToggle.isSelected();
        AppConfig own = imageMode ? g.getImageParamsCache() : g.getTextParamsCache();
        if (own != null) {
            return I18n.get("group.paramsIndependent");
        }
        AppConfig global = imageMode ? imageParamsCache : textParamsCache;
        return global != null ? I18n.get("group.paramsInherited") : I18n.get("group.paramsInheritedUnconfirmed");
    }

    private void renderTextPreview(PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        if (params == null) {
            params = new AppConfig();
        }
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
                    + I18n.get("preview.extractedContentDivider")
                    + text.substring(text.length() - 5000)
                    + tail);
        }
    }

    /** 重置图片区：hint 为 null 时显示默认按需预览说明 */
    private void resetImageArea(String hint, boolean showPreviewButton) {
        imageHintLabel.setText(hint != null ? hint : I18n.get("preview.imageLazyHint"));
        imageHintLabel.setVisible(true);
        previewEffectButton.setVisible(showPreviewButton);
        previewEffectButton.setDisable(false);
        imageScrollPane.setVisible(false);
        imageScrollPane.setManaged(false);
        thumbnailPane.getChildren().clear();
    }

    private void renderThumbnails(PreviewItem item) {
        List<ThumbnailData> thumbs = item.cachedThumbs;
        thumbnailPane.getChildren().clear();
        if (thumbs == null || thumbs.isEmpty()) {
            imageHintLabel.setText(I18n.get("preview.noImages"));
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

        // 下标 = 缓存清单 meta.images() 的序号（cachedThumbs 与 meta 同序 1:1，见 openCachedImage 的索引语义）
        for (int i = 0; i < thumbs.size(); i++) {
            thumbnailPane.getChildren().add(buildThumbBox(item, thumbs.get(i), i));
        }
    }

    private void appendThumb(PreviewItem item, ThumbnailData entry, int index) {
        imageHintLabel.setVisible(false);
        previewEffectButton.setVisible(false);
        imageScrollPane.setVisible(true);
        imageScrollPane.setManaged(true);
        thumbnailPane.getChildren().add(buildThumbBox(item, entry, index));
    }

    private VBox buildThumbBox(PreviewItem item, ThumbnailData entry, int index) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        // E1.4：模型只存像素；JavaFX Image 在此（渲染层）按需构造。构造失败等价于原 image == null
        Image fxImage = toFxImage(entry);
        if (fxImage == null) {
            box.getChildren().add(new Label(I18n.get("preview.loadFailed")));
        } else {
            ImageView iv = new ImageView(fxImage);
            iv.setFitWidth(150);
            iv.setFitHeight(150);
            iv.setPreserveRatio(true);
            box.getChildren().add(iv);
        }
        Label nameLabel = new Label(thumbFileName(entry, item));
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(150);
        nameLabel.setStyle("-fx-font-size: 0.85em;");
        box.getChildren().add(nameLabel);
        // 点击查看（需求：预览区图片可点击查看）：单击缩略图 → 系统默认图片查看器打开缓存目录里对应的那张图
        box.setCursor(Cursor.HAND);
        Tooltip.install(box, new Tooltip(I18n.get("preview.thumbClickHint")));
        box.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) openCachedImage(item, index); });
        return box;
    }

    /** 单击缩略图：用系统默认图片查看器打开缓存目录里对应的图片（非 AWT；启动/校验/环境兜底见 {@link FileOpener}）。 */
    private void openCachedImage(PreviewItem item, int index) {
        Path file = previewCacheLifecycle.store().imageFileFor(item.cachedMeta, index);
        if (file == null || !Files.isRegularFile(file)) {
            log.warn(I18n.msg("log.maincontroller.65", item.name));
            return;
        }
        // index 语义见 renderThumbnails 注释；打开最多等 3 秒（校验退出码）再兜底 → 后台线程，避免冻结 FX 线程
        Thread t = new Thread(() -> {
            FileOpener.Route route = FileOpener.open(file);
            if (route == FileOpener.Route.FAILED) {
                log.warn(I18n.msg("log.maincontroller.65", item.name));
            } else {
                log.info(I18n.msg(route == FileOpener.Route.PHOTOS_PROTOCOL
                        ? "log.maincontroller.66" : "log.maincontroller.64", file));
            }
        }, "open-cached-image");
        t.setDaemon(true);
        t.start();
    }

    /** 合并输出且不重命名时，文件名按当前列表位置重算 f%02d_ 前缀（拖拽重排后同步刷新，按条目生效参数判定） */
    private String thumbFileName(ThumbnailData entry, PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        boolean mergedNoRename = params != null && params.isSingleOutputMode()
                && !params.isEnableBatchRenaming() && visibleCount() > 1;
        return mergedNoRename
                ? String.format("f%02d_", (int) unitIndex(item) + 1) + entry.coreName()
                : entry.coreName();
    }

    /**
     * 解码单张缩略图：WebP 走 JWebP 缩放解码（150 边界），其余走 JavaFX 原生缩放解码。
     *
     * <p>E1.4：返回值由"持有 JavaFX {@code Image}"改为"持有纯像素"（{@link ThumbnailData}），
     * 使该数据可脱离 UI 框架存在。解码仍在<b>后台线程</b>完成（缩放边界与算法不变），
     * JavaFX {@code Image} 的构造下沉到渲染层的 {@link #toFxImage}。失败时 pixels=null，
     * 渲染层显示"无法加载"占位 —— 与原 {@code image == null} 语义一致。</p>
     */
    private ThumbnailData decodeThumb(ImageExtractor.ImageData imageData, int index, AppConfig params) {
        byte[] data = imageData.getData();
        String ext = SanitizeUtils.detectExtensionByMagicNumber(data, "jpg");
        boolean rename = params != null && params.isEnableBatchRenaming();
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
        return toThumbnailData(img, coreName);
    }

    private void updateRenameNote(Object sel) {
        AppConfig params = effectiveForObject(sel);
        if (params == null) {
            params = currentParamsOrDefaults();
        }
        boolean show = !isTextMode() && params.isSingleOutputMode() && params.isEnableBatchRenaming();
        renameNoteLabel.setVisible(show);
        renameNoteLabel.setManaged(show);
    }

    // ==================== 图片按需加载 ====================

    @FXML
    private void onPreviewEffect() {
        onPreviewEffect(false);
    }

    /** 图片按需加载主体。{@code largeFileOk=true} = 用户已确认大文件（大文件确认改成窗口内卡片后，
     *  用"重入自己"表达"确认后继续"，好过把后面几十行加载逻辑塞进 lambda）。 */
    private void onPreviewEffect(boolean largeFileOk) {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        if (!(sel instanceof PreviewItem item)) {
            return;
        }
        final AppConfig params = effectiveForObject(item);
        if (params == null) {
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
        if (!largeFileOk && f.length() > LARGE_FILE_THRESHOLD) {
            String msg = I18n.get("preview.largeFileConfirmHeader") + "\n"
                    + item.name + I18n.get("preview.largeFileConfirmBody",
                            "size", f.length() / 1024 / 1024);
            NotificationHost.confirm(statusLabel, msg, FontAwesomeSolid.FILE_ALT, ok -> {
                if (ok) {
                    onPreviewEffect(true);
                }
            });
            return;
        }

        final PreviewCacheKey cacheKey;
        try {
            cacheKey = PreviewCacheKey.compute(Path.of(item.path),
                    params.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        } catch (IOException e) {
            log.error(I18n.msg("log.maincontroller.61", item.path), e);
            NotificationHost.error(statusLabel, ErrorCode.READ_FILE_INFO_FAILED.message(), FontAwesomeSolid.FILE_ALT);
            return;
        }

        final int token = requestToken;
        imageLoadActive = true;
        loadingItem = item;
        imageHintLabel.setText(I18n.get("preview.imageLoading"));
        previewEffectButton.setDisable(true);
        // 相邻修复：提取运行期间进度条/状态栏归提取任务独占，图片加载不动它们
        if (!running().get()) {
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            setProgressBarActivity(null);   // 预览加载 = 默认色（完成后回落预解析显示）
            statusLabel.setText(I18n.get("preview.loadingImage", "name", item.name));
        }

        Thread t = new Thread(() -> {
            try {
                // 每项目写锁（§2.6(6)）：同项目「查缓存→提取→落盘」串行，不同项目并行。
                // 缓存业务逻辑已搬入 com.mhtextractor.app.PreviewService（步骤 2），控制器只做 UI。
                final com.mhtextractor.app.PreviewService.PreviewResult pr;
                synchronized (previewCacheLifecycle.lockFor(cacheKey.absolutePath())) {
                    pr = previewService.ensureCached(Path.of(item.path), params, () -> token != requestToken);
                }
                if (token != requestToken) {
                    return;   // 代次失效：放弃上屏（缓存保留，重选时命中复用）
                }
                // C2：缩略图从磁盘真实图片逐张解码，内存只留缩略图 + meta，不驻留全尺寸字节。
                // 元数据（coreName）由无头服务给出；像素解码仍在此处（JavaFX 缩放解码 + PixelReader）。
                final PreviewCacheMeta meta = pr.meta();
                List<ThumbnailData> thumbs = new ArrayList<>();
                for (int i = 0; i < meta.images().size(); i++) {
                    if (token != requestToken) {
                        return;
                    }
                    PreviewCacheMeta.ImageEntry entry = meta.images().get(i);
                    final ThumbnailData thumb;
                    try {
                        byte[] data = Files.readAllBytes(pr.imagesDir().resolve(entry.file()));
                        thumb = decodeThumb(new ImageExtractor.ImageData(
                                entry.originalName(), entry.sourceId(), data), i, params);
                    } catch (IOException e) {
                        log.warn(I18n.msg("log.maincontroller.62", pr.imagesDir().resolve(entry.file())), e);
                        continue;
                    }
                    thumbs.add(thumb);
                    final int idx = i;
                    Platform.runLater(() -> {
                        if (token != requestToken
                                || fileListView.getSelectionModel().getSelectedItem() != item) {
                            return;
                        }
                        appendThumb(item, thumb, idx);
                    });
                }

                Platform.runLater(() -> {
                    previewLoadThread = null;
                    // 落盘期间参数/条目可能已变化（参数弹窗确认、移除等）：真正失效
                    // （条目已移除或生效图片参数指纹与写盘时不符）时删除刚落盘的目录，避免残留。
                    // 注意用与模式无关的图片参数解析（切模式不影响图片缓存有效性）
                    AppConfig imageParamsNow = ParamScope.effectiveImageParams(
                            item.parentGroup, item.imageItemParams, imageParamsCache);
                    boolean stale = item.removed || imageParamsNow == null
                            || !imageParamsNow.imageExtractionFingerprint()
                                    .equals(cacheKey.paramFingerprint());
                    if (stale) {
                        Thread cleanup = new Thread(() -> {
                            try {
                                previewCacheLifecycle.store().delete(cacheKey);
                            } catch (IOException e) {
                                log.warn(I18n.msg("log.maincontroller.63", cacheKey.toDirName()), e);
                            }
                        }, "preview-cache-stale-delete");
                        cleanup.setDaemon(true);
                        cleanup.start();
                        imageLoadActive = false;
                        loadingItem = null;
                        previewEffectButton.setDisable(false);
                        updateProgressStatus(parseToken);
                        return;
                    }
                    if (token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        // 既有语义：选中/代次变化时放弃上屏；缓存保留（与选中无关），重选时命中复用
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    item.cachedThumbs = thumbs;
                    item.cachedMeta = meta;
                    item.cachedImages = null;
                    if (meta.images().isEmpty()) {
                        // 全量解析确认无图片 → 空内容标记
                        item.status = PreviewItem.Status.EMPTY;
                        fileListView.refresh();
                    }
                    renderThumbnails(item);
                    updateProgressStatus(parseToken);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    previewLoadThread = null;
                    if (token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    imageHintLabel.setText(I18n.get("preview.parseFailed") + e.getMessage());
                    imageHintLabel.setVisible(true);
                    previewEffectButton.setDisable(false);
                    updateProgressStatus(parseToken);
                });
            }
        }, "preview-image-load");
        previewLoadThread = t;
        t.setDaemon(true);
        t.start();
    }

    // ==================== 预解析（文本全量 / 图片轻量） ====================

    private void startPreParse() {
        List<PreviewItem> targets = allVisibleItems();
        if (targets.isEmpty()) {
            return;
        }
        // 提取运行期间进度条归提取任务独占（相邻修复），预解析照常跑但不动进度条
        if (!running().get()) {
            progressBar.setVisible(true);
            progressBar.setProgress(0);
            setProgressBarActivity(STYLE_PROGRESS_PREPARSE);
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
            MimeParser parser = new MimeParser();
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
                    MimeParser.ParsedMht parsed = parser.parse(item.path, true, () -> false, null, null);
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
            if (!dragActive && !listAnimating) {
                // 拖拽/折叠动画期间暂停列表刷新，避免打乱动画画面；动画结束后统一重算
                fileListView.refresh();
                recomputeBadges();
            }
            Object sel = fileListView.getSelectionModel().getSelectedItem();
            updateInfoArea(sel);
            if (sel == item) {
                updateContentArea(item);
            }
            updateProgressStatus(token);
        });
    }

    /* test-support */ void updateProgressStatus(int token) {
        if (token != parseToken) {
            return;
        }
        // 相邻修复（拍板 2026-09-24）：提取运行期间进度条/状态栏归提取任务独占，
        // 预解析完成等回调一律不更新（避免覆盖提取进度显示）
        if (running().get()) {
            return;
        }
        if (imageLoadActive) {
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            return;
        }
        // 拍板 2026-09-24（方案A）：只统计有生效参数的条目。无参数条目由 preParseItems 跳过
        // （留待参数确认后解析），不参与进度，否则独立参数场景下进度条会卡在中间。
        List<PreviewItem> vis = allVisibleItems().stream()
                .filter(i -> effectiveForObject(i) != null).toList();
        long total = vis.size();
        long done = vis.stream().filter(i -> i.status != PreviewItem.Status.WAITING).count();
        if (total == 0) {
            progressBar.setVisible(false);   // 全部无生效参数：不预解析，不动状态栏文案
            setProgressBarActivity(null);
            return;
        }
        if (done >= total) {
            progressBar.setVisible(false);
            setProgressBarActivity(null);
        } else {
            progressBar.setVisible(true);
            progressBar.setProgress((double) done / total);
            setProgressBarActivity(STYLE_PROGRESS_PREPARSE);
        }
        statusLabel.setText(I18n.get("preview.parseDone") + done + "/" + total);
    }

    /** 进度条活动样式切换（拍板 2026-09-24）：一次只保留一个活动类；null = 默认色 */
    private void setProgressBarActivity(String styleClass) {
        progressBar.getStyleClass().removeAll(STYLE_PROGRESS_PREPARSE, STYLE_PROGRESS_EXTRACT_SUCCESS,
                STYLE_PROGRESS_EXTRACT_WARNED, STYLE_PROGRESS_EXTRACT_FAILED);
        if (styleClass != null) {
            progressBar.getStyleClass().add(styleClass);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 会话 12：磁盘占用跨台阶回调（在预览后台线程被 checkSizeAlert 同步调用）。
     * 台阶序号换算 GiB、取当前实际占用后，回 FX 线程弹<b>右上角非阻塞 toast</b>
     * （会话 19：原非模态 Alert 换成应用内提醒；连续跨台阶由 toast 图层自行限流，不再需要"先关再弹"）。
     */
    private void onSizeAlertStep(int step) {
        long stepGiB = step * PreviewCacheLifecycle.SIZE_ALERT_STEP_BYTES / (1024L * 1024 * 1024);
        double actualGiB = previewCacheLifecycle.lastObservedTotalBytes() / (1024.0 * 1024 * 1024);
        String message = I18n.get("cache.sizeAlert",
                        "step", stepGiB, "actual", String.format("%.1f", actualGiB),
                        "dir", previewCacheLifecycle.store().root())
                + I18n.get("cache.sizeAlertNote");
        NotificationHost.toastWarning(statusLabel, message, FontAwesomeSolid.HDD);
    }
}
