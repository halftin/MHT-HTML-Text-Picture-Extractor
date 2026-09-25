package com.mhtextractor.gui.controller;

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
import com.mhtextractor.gui.util.BrowserLauncher;
import com.mhtextractor.gui.util.JavaFxUtil;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.FileResult;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.processor.TextBatchProcessor;
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
import com.mhtextractor.util.UndoManager;
import com.mhtextractor.util.UnitProgressAggregator;
import javafx.animation.Interpolator;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主界面逻辑控制器：预览工作台常驻主窗口，参数设置弹窗化。
 *
 * <p>预览状态机：选定输入目录后增量导入文件列表（按绝对路径去重、新文件追加末尾，不解析）；当前模式参数经参数窗口
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
    /** 预解析失败样式（提取结果状态需求拍板：浅黄；2026-09-25 用户反馈改为接近 Windows/WinUI 警告黄 #FFF9AE） */
    private static final String FAILED_PAINT = "#fff9ae";
    /** 选中态 = 基础色混合 40% 浅灰 #bebebe（拍板 2026-09-22，与组行选中切换同机制） */
    private static final String FAILED_SELECTED_PAINT = "#e5e1b4";
    private static final String FAILED_ROW_STYLE = CELL_PAD + " -fx-background-color: " + FAILED_PAINT
            + "; -fx-text-background-color: #856404;";
    private static final String FAILED_SELECTED_ROW_STYLE = CELL_PAD + " -fx-background-color: "
            + FAILED_SELECTED_PAINT + "; -fx-text-background-color: #856404;";
    /** 提取结果背景画值（提取结果状态需求拍板，配色修订 2026-09-21、2026-09-25）：浅红=失败/跳过；标准绿=成功；分割色=成功+警告（左1/3红右2/3绿）。
     *  2026-09-25 用户反馈：浅红改 Windows 错误红 #E81123（白字）；警告分割行右侧绿加深 #2E7D32 以承载白字 */
    private static final String EXTRACT_FAILED_PAINT = "#E81123";
    private static final String EXTRACT_FAILED_SELECTED_PAINT = "#D75661";
    private static final String EXTRACT_SUCCESS_PAINT = "#4CAF50";
    private static final String EXTRACT_SUCCESS_SELECTED_PAINT = "#7AB57C";
    private static final String EXTRACT_WARNED_PAINT =
            "linear-gradient(to right, #E81123 0%, #E81123 33%, #2E7D32 33%, #2E7D32 100%)";
    private static final String EXTRACT_WARNED_SELECTED_PAINT =
            "linear-gradient(to right, #D75661 0%, #D75661 33%, #68976A 33%, #68976A 100%)";
    /** 进度条活动样式类（进度条活动配色拍板 2026-09-24）：预解析=浅蓝；提取成功=绿；有警告=黄；有失败/跳过=浅红 */
    private static final String STYLE_PROGRESS_PREPARSE = "progress-preparse";
    private static final String STYLE_PROGRESS_EXTRACT_SUCCESS = "progress-extract-success";
    private static final String STYLE_PROGRESS_EXTRACT_WARNED = "progress-extract-warned";
    private static final String STYLE_PROGRESS_EXTRACT_FAILED = "progress-extract-failed";

    /** 行背景样式组装 */
    private static String rowStyle(String paint) {
        return CELL_PAD + " -fx-background-color: " + paint + ";";
    }
    private static final String BADGE_STYLE =
            "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white;"
                    + " -fx-padding: 1 4; -fx-background-radius: 9;";
    /** 8.5 条目背景画值（配色修订 2026-09-21）：深蓝 = 生效参数为全局参数（白字）；浅蓝 = 生效参数为组/条目级独立参数 */
    private static final String GLOBAL_PARAM_PAINT = "#1e4d8f";
    private static final String GLOBAL_PARAM_SELECTED_PAINT = "#5e7aa2";
    private static final String GROUP_PARAM_PAINT = "#d6eaf8";
    private static final String GROUP_PARAM_SELECTED_PAINT = "#ccd8e1";
    /** 组条目行样式（配色修订 2026-09-21）：标准黑色底、白色文字；行高大幅缩小为比文字稍高 */
    private static final String GROUP_ROW_STYLE =
            CELL_PAD + " -fx-background-color: #000000; -fx-text-background-color: #ffffff;";
    /** 组条目行选中样式（配色修订 2026-09-21）：黑灰底（#444444），与未选中黑色明显区分 */
    private static final String GROUP_ROW_SELECTED_STYLE =
            CELL_PAD + " -fx-background-color: #444444; -fx-text-background-color: #ffffff;";
    private static final Insets ROW_PAD = new Insets(7, 10, 7, 10);
    /** 组行左内边距 2px：内容朝左顶格（成员行维持 10px 列对齐） */
    private static final Insets GROUP_ROW_PAD = new Insets(1, 2, 1, 10);
    /** 组内成员行内边距（拍板）：行高略低于顶层项目行（顶层 ROW_PAD 34px → 组内约 30px） */
    private static final Insets GROUP_MEMBER_PAD = new Insets(2, 10, 2, 10);
    /** 插入指示线颜色（拖拽排序反馈） */
    private static final String DROP_LINE_STYLE = "-fx-background-color: #3b82f6;";
    /** 组行"入组/合并"整行高亮（琥珀色底+边框，与成员行蓝色插入线视觉区分，6.6 视觉细节） */
    private static final String INTO_OVERLAY_STYLE =
            "-fx-background-color: rgba(245, 158, 11, 0.18); -fx-border-color: #f59e0b; -fx-border-width: 2;";
    /** 组行落点三区比例：上下边缘为行间空隙（顶层/组间落点），中间带为整行（入组末尾/两组合并） */
    private static final double GROUP_ROW_EDGE_ZONE = 0.25;
    /** 折叠/展开动画时长（拍板 200ms；缓动照搬 TitledPaneSkin 的 LINEAR） */
    private static final Duration EXPAND_ANIM_DURATION = Duration.millis(200);
    /** 成员行动画基准高度兜底（px）：ROW_PAD 7×2 + 单行文本 ≈ 34；动画开始时优先实测 */
    private static final double MEMBER_ROW_HEIGHT_FALLBACK = 34.0;
    /** 组条目行目标高度（px）：比文字稍高（拍板） */
    private static final double GROUP_ROW_HEIGHT = 20.0;
    /** 组行复选框槽位宽度（px）：仅容纳图形，复选框与内容之间不留空白（拍板） */
    private static final double GROUP_CHECK_WIDTH = 20.0;

    /** 徽标列/位置序号列/复选框列的固定槽位宽度 */
    private static final double BADGE_COL_WIDTH = 40;
    private static final double POS_COL_WIDTH = 40;
    private static final double CHECK_COL_WIDTH = 40;

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
        /** 输入来源目录：随导入写入，不随输入目录切换变化（F3/R1） */
        final String sourceFolder;
        final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.WAITING);
        final StringProperty errorMsg = new SimpleStringProperty("");
        final BooleanProperty chapterEnabled = new SimpleBooleanProperty(true);
        /** 复选框勾选状态（F1）：默认未选中，仅用于批量选择，不影响提取名单 */
        final BooleanProperty selected = new SimpleBooleanProperty(false);
        /** 移除标记（F2）：隐藏式移除——不实际从列表删除，仅隐藏并排除在序号/徽标/提取名单外 */
        boolean removed = false;
        /** 所属组（F5）：null = 顶层未分组；由 rebuildDisplay 统一维护，勿手工赋值（volatile：预解析线程读取） */
        volatile GroupItem<PreviewItem> parentGroup = null;
        final StringProperty badge = new SimpleStringProperty(null);
        volatile String cachedText;
        volatile String cachedTitle;
        volatile List<ImageExtractor.ImageData> cachedImages;
        volatile List<ThumbEntry> cachedThumbs;
        /** 预览缓存元数据（需求 2 会话 9）：磁盘落盘后的 meta（含图片清单，不含字节），C2「内存只留 meta + 缩略图」 */
        volatile PreviewCacheMeta cachedMeta;
        /** 最近一次提取结果（提取结果状态需求）：NONE 无结果；仅在 FX 线程回写/清除 */
        ExtractOutcome extractOutcome = ExtractOutcome.NONE;
        /** 提取结果详情（失败/跳过原因 + 警告，分号拼接）；点击含红条目时预览区 banner 展示 */
        String extractDetail;
        /** 条目级独立参数（条目独立参数需求）：文本/图片各一套；null = 未设置；
         *  仅顶层项目持有（入组/合并时清除）；volatile：预解析线程读取 */
        volatile AppConfig textItemParams;
        volatile AppConfig imageItemParams;

        PreviewItem(String path, String name, LocalDateTime modifiedTime, String sourceFolder) {
            this.path = path;
            this.name = name;
            this.modifiedTime = modifiedTime;
            this.sourceFolder = sourceFolder;
        }
    }

    /** 输出单元规划的字段视图（路径/来源目录/移除标记，阶段 8） */
    private static final OutputUnitPlanner.ItemView<PreviewItem> ITEM_VIEW = new OutputUnitPlanner.ItemView<>() {
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
    @FXML private ToggleButton imageModeToggle;
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
    @FXML private LogTextArea logTextArea;
    @FXML private ToggleSwitch openFolderAfterCompleteCheck;

    // ===== 状态 =====
    /** 数据序：顶层混合列表（PreviewItem | GroupItem），组内成员存于 GroupItem.members */
    private final ObservableList<Object> items = FXCollections.observableArrayList();
    /** 显示序：ListView 直接渲染的展平列表，按数据序实时重建（F5 显示序/数据序分离） */
    private final ObservableList<Object> displayItems = FXCollections.observableArrayList();
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 最近一次提取的 partial/完整统计（提取结果状态需求）：Task 取消时 getValue() 不可用，回写经此 holder */
    private final AtomicReference<BatchProcessor.ProcessStats> lastExtractStats = new AtomicReference<>();

    // ===== 撤销栈（F4）：REMOVE/IMPORT/GROUP/REMOVE_GROUP 四类记录，深度 35，超出丢弃最旧 =====
    private final UndoManager<PreviewItem> undoManager = new UndoManager<>();
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
    /** 预览会话缓存（需求 2 会话 9）：存储层 + 四条生命周期规则；root = <java.io.tmpdir>/mht-extractor-preview（P6/P8，与 HtmlExportBuilder 的 mht-extractor-view 并列） */
    private final PreviewCacheLifecycle previewCacheLifecycle = new PreviewCacheLifecycle(
            new PreviewCacheStore(Paths.get(System.getProperty("java.io.tmpdir"), "mht-extractor-preview")));
    /** 在途预览加载线程（规则 d 退出清空时等待其结束用；仅 FX 线程写） */
    private volatile Thread previewLoadThread = null;
    /** 退出清空已完成：二次 close 放行（规则 d） */
    private boolean shutdownCacheCleared = false;
    private boolean updatingAdjustCheck = false;
    /** 全选/全不选批量写入期间的联动抑制标记（F1） */
    private boolean updatingSelection = false;
    private Object lastSelected = null;
    private CellIndicator lastDragOverCell = null;
    /** Shift 区间多选的锚点行（显示序元素：PreviewItem 或 GroupItem）；无锚点时 Shift 点击忽略 */
    private Object selectionAnchor = null;

    // ===== 分组状态（F5） =====
    /** 组编号全局自增计数器（组1、组2…，不复用已销毁组的编号） */
    private int groupNoCounter = 0;
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
    private Object draggedObject = null;
    private double dragGapTopY = 0;
    private boolean dragActive = false;
    private boolean dropHandled = false;
    private boolean dragPositionStale = false;
    private ParallelTransition liftAnim = null;
    private List<PreviewListCell> liftCells = null;
    private List<ImageView> liftViews = null;

    // ==================== 统一导入会话 ====================
    /** 当前导入会话：非 null = 导入进行中（用于禁用开始提取 + 恢复判定） */
    private ImportSession importPending = null;
    /** 本次导入累计新增的项目（收尾统一预解析，避免 O(n²)） */
    private final List<PreviewItem> importAccumulated = new ArrayList<>();
    /** 外部导入拖拽进行中（与内部排序通道互斥；hasFiles() 判定） */
    private boolean importDragOver = false;
    /** 拖拽目标嵌套深度计数（DRAG_ENTERED_TARGET/DRAG_EXITED_TARGET 成对计数） */
    private int importDragDepth = 0;
    /** 外部导入拖拽的列表高亮样式类 */
    private static final String STYLE_LIST_IMPORT_HOVER = "list-import-hover";
    /** 每轮配额（与 MhtImportPlanner 常量一致） */
    private static final int IMPORT_QUOTA_GROUPS = MhtImportPlanner.DEFAULT_MAX_GROUPS;
    private static final int IMPORT_QUOTA_FILES  = MhtImportPlanner.DEFAULT_MAX_FILES;
    /** 本次导入累计自动创建的组数（配额确认框/收尾汇总用） */
    private int importGroupCount = 0;
    /** 本次导入扫描计数器（applyImportPlan 每轮同步；Plan 的计数为扫描器累计值） */
    private int importIgnoredNonMht = 0;
    private int importHiddenDirs = 0;
    private int importEmptyDirs = 0;
    private int importUnreadable = 0;
    private int importDepthLimited = 0;

    /** 导入会话（不可变） */
    private record ImportSession(List<String> addresses, String sourceDesc, boolean fromDrag) {}

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
    private MenuItem ctxClearParams;
    private MenuItem ctxGroup;
    private MenuItem ctxUngroup;
    private MenuItem ctxRemove;
    private MenuItem ctxRemoveGroup;
    private MenuItem ctxApplyGlobal;
    /** 菜单显示期间的主窗口按压过滤：点击菜单外任意处即关闭（JavaFX autoHide 在本应用窗口组合下不可靠，手动兜底） */
    private final EventHandler<MouseEvent> contextClickAway = e -> {
        if (contextMenu.isShowing()) {
            contextMenu.hide();
        }
    };

    // ===== 折叠/展开动画状态（方案 B 抽屉式，快照平移手法与拖拽一致） =====
    /** 动画全局锁：动画期间列表项目不可交互（按钮可点但无效果），快速连点折叠箭头直接忽略 */
    private boolean listAnimating = false;
    /** 正在动画的组；null = 无动画 */
    private GroupItem<PreviewItem> animatingGroup = null;
    /** true = 展开动画；false = 折叠动画 */
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
        badgeHeaderLabel.setTooltip(new Tooltip(
                "勾选\"添加章节序号\"的文件的最终章节号（阿拉伯数字），随勾选与排序实时变化"));

        // 1. 列表：单元格工厂、选择联动、逐文件章节勾选、拖拽动画滚动联动
        fileListView.setCellFactory(lv -> createPreviewCell());
        fileListView.setItems(displayItems);
        setupExternalImportDnD();

        // 空白/非预期区域松手：不处理 drop（无蓝条、无吸附），走取消路径瞬时恢复原位不消失

        // 右键菜单（右键菜单功能需求）：ListView 级弹出（命中 cell = 条目目标；未命中 = 空白区域）
        buildContextMenu();
        fileListView.setOnContextMenuRequested(this::onContextMenuRequested);

        // 列头全选 CheckBox 三态联动（F1）：单项勾选变化实时刷新列头与组复选框，新导入项目自动挂监听
        selectAllCheck.setOnAction(e -> onSelectAllToggled());
        groupNameField.textProperty().addListener((obs, old, newVal) -> onGroupNameEdited(newVal));
        groupNameField.focusedProperty().addListener((obs, old, newVal) -> {
            if (!newVal) {
                onGroupNameFocusLost();
            }
        });
        items.addListener((ListChangeListener<Object>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Object o : change.getAddedSubList()) {
                        if (o instanceof PreviewItem it && selectionListened.add(it)) {
                            it.selected.addListener((obs2, a, b) -> onSelectionChanged());
                        }
                    }
                }
            }
        });

        fileListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, newVal) -> onItemSelected(newVal));

        adjustCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (updatingAdjustCheck) {
                return;
            }
            Object sel = fileListView.getSelectionModel().getSelectedItem();
            if (!(sel instanceof PreviewItem it) || effectiveForObject(it) == null) {
                return;
            }
            if (isTextMode()) {
                it.chapterEnabled.set(newVal);
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

        // 2. 模式切换：互斥由 modeToggleGroup 承担，点击已选按钮可取消回到"未选择"态；
        //    不清空另一模式参数：GUI 状态与已确认缓存按模式隔离保留
        modeToggleGroup.selectedToggleProperty().addListener((obs, old, newVal) -> onModeChanged());

        // 3. 目录/开关变更：刷新列表 + 持久化 GUI 状态
        inputFolderField.textProperty().addListener((obs, old, newVal) -> onInputFolderChanged());
        outputFolderField.textProperty().addListener((obs, old, newVal) -> saveGuiState());
        openFolderAfterCompleteCheck.selectedProperty().addListener((obs, old, newVal) -> saveGuiState());

        // 4. 恢复 GUI 状态（无状态文件时与旧版启动逻辑一致）
        restoreGuiState();

        // 5. 初始状态一致性（无状态文件时：两个模式都未选中，全部禁用）
        updateModeUi();
        updateFileCount();
        updateListButtons();

        // 6. 预览会话缓存（需求 2 会话 9）：启动扫孤儿（P7 崩溃恢复）；
        //    关窗清空处理器经 scene/window 装配后挂接（initialize 阶段 scene 尚未存在，App.start 稍后装配）
        previewCacheLifecycle.startupCleanup();
        fileListView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.windowProperty().addListener((o2, oldW, newW) -> {
                if (newW instanceof Stage stage) {
                    stage.setOnCloseRequest(this::onWindowCloseRequest);
                }
            });
        });

        Platform.runLater(() -> {
            logTextArea.appendText("欢迎使用 MHT 资源提取器！\n");
            logTextArea.appendText("请选择提取模式与输入目录，通过\"提取参数设置\"确认参数后即可预览并提取。\n");
        });
    }

    /** 规则 (d)：关窗清空（需求 2 §2.2）。有未清理缓存 → 弹提示 → 与在途预览线程协调后清空 → 放行关闭；
     *  无缓存 → 直接放行（不弹窗）。test.mode 下跳过弹窗只清空（防自动化关窗阻塞）。
     *  并发协调（§2.6(6)）：先置停止标志（cancelWorkspaceActivity → requestToken++ 令在途线程放弃结果），
     *  再等其在途写入结束（join，最多 2s；超时则接受删除与写入竞争——原子写入方案下最坏是写入失败被吞），
     *  最后清空。只清本应用专属根目录（PreviewCacheStore 的 root），绝不触碰 java.io.tmpdir 整体。 */
    private void onWindowCloseRequest(WindowEvent e) {
        if (shutdownCacheCleared || !previewCacheLifecycle.hasCachedEntries()) {
            return;
        }
        e.consume();
        if (!"true".equals(System.getProperty("test.mode"))) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "关闭程序前需要清空缓存，清空后关闭，请稍后");
            alert.setTitle("退出前清空缓存");
            alert.setHeaderText(null);
            alert.showAndWait();
        }
        cancelWorkspaceActivity();
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
        Stage stage = (Stage) e.getSource();
        stage.close();
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
            boolean sameDir = dir.equals(inputFolderField.getText());
            inputFolderField.setText(dir);
            // 重选同一目录不触发 textProperty 变化：显式触发导入（如撤销导入后再导回同一目录）
            if (sameDir) {
                onInputFolderChanged();
            }
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

    /** 输入目录变更：作为导入源增量合并（F3）——已有项目保持不变，新文件按绝对路径去重后追加末尾 */
    private void onInputFolderChanged() {
        String text = inputFolderField.getText();
        if (text == null || text.trim().isEmpty()) {
            saveGuiState();
            return;
        }
        if (!new File(text).isDirectory()) {
            log.warn("输入目录不存在，列表保持不变：{}", text);
            saveGuiState();
            return;
        }
        importFromFolder(text);
        saveGuiState();
    }

    /** 增量导入：统一入口（BC-1：改为递归扫描 + 自动建组） */
    private void importFromFolder(String dir) {
        importByPaths(List.of(dir), "目录 " + dir, false);
    }

    // ==================== 统一导入（拖拽 + 浏览共用入口） ====================

    /** 是否正在导入 */
    private boolean isImporting() {
        return importPending != null;
    }

    /**
     * 统一导入入口：来源只接受地址字段。
     * 拖拽路径（fromDrag=true）在盘符根目录时弹确认框（BC-8）；浏览路径不弹。
     */
    private void importByPaths(List<String> addresses, String sourceDesc, boolean fromDrag) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        if (isImporting()) {
            log.warn("已有导入进行中，忽略本次请求");
            return;
        }
        // 地址规范化（RISK-6）：祖先/后代包含关系合并为祖先，防重复扫描与重复批次
        List<String> normalized = normalizeAddresses(addresses);
        if (normalized.isEmpty()) {
            return;
        }
        log.info("导入开始：来源={}，地址 {} 个", sourceDesc, normalized.size());

        // BC-8：仅拖拽来源 + 盘符根目录 → 确认
        if (fromDrag) {
            List<String> roots = MhtImportPlanner.findDriveRoots(normalized);
            if (!roots.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION);
                a.setTitle("盘符根目录导入确认");
                a.setHeaderText(null);
                a.setContentText("正在使用盘符根目录进行导入，是否执行？\n\n" + String.join("\n", roots)
                        + "\n\n（将递归扫描该盘，可能导入大量文件）");
                a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    log.info("用户取消盘符根目录导入：{}", roots);
                    return;
                }
            }
        }

        ImportSession session = new ImportSession(normalized, sourceDesc, fromDrag);
        importPending = session;
        importAccumulated.clear();
        importGroupCount = 0;
        importIgnoredNonMht = 0;
        importHiddenDirs = 0;
        importEmptyDirs = 0;
        importUnreadable = 0;
        importDepthLimited = 0;
        updateStartButtonState();                 // C-1：禁用开始提取

        Thread t = new Thread(() -> runImportRounds(session), "mht-import-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 地址规范化：重复地址合并；祖先/后代包含关系合并为祖先（丢弃后代）并 info 日志说明。
     * 无效路径格式原样保留（扫描器按"不存在"计 unreadable）。
     */
    private List<String> normalizeAddresses(List<String> addresses) {
        List<String> kept = new ArrayList<>();
        for (String a : addresses) {
            if (a == null || a.isBlank()) {
                continue;
            }
            if (kept.stream().anyMatch(k -> a.equals(k))) {
                log.info("地址 {} 重复，已合并，不再重复导入", a);
                continue;
            }
            String ancestor = kept.stream().filter(k -> isDescendantOf(a, k)).findFirst().orElse(null);
            if (ancestor != null) {
                log.info("地址 {} 已包含在 {} 中，合并为祖先，不再单独导入", a, ancestor);
                continue;
            }
            kept.removeIf(k -> isDescendantOf(k, a));
            kept.add(a);
        }
        return kept;
    }

    private static boolean isDescendantOf(String child, String ancestor) {
        try {
            Path cp = Path.of(child);
            Path ap = Path.of(ancestor);
            return !cp.equals(ap) && cp.startsWith(ap);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 应用一个计划（一批或一轮）；FX 线程；生产与自动化测试共用。
     * 撤销与预解析职责在本层上移：一次导入累计压一条 IMPORT 记录（BC-5），
     * 预解析由 finishImport 统一执行（RISK-7）。
     */
    private void applyImportPlan(MhtImportPlanner.Plan plan) {
        // 计数器为扫描器累计值：即使本轮无批次也同步（finishImport 汇总用）
        importIgnoredNonMht = plan.ignoredNonMht();
        importHiddenDirs = plan.hiddenDirs();
        importEmptyDirs = plan.emptyDirs();
        importUnreadable = plan.unreadable();
        importDepthLimited = plan.depthLimited();

        List<GroupItem<PreviewItem>> createdGroups = new ArrayList<>();

        for (MhtImportPlanner.Batch batch : plan.batches()) {
            // 去重：与列表已有项比对（复用 F4），只保留真正的新文件
            List<File> fresh = IncrementalImport.findNewFiles(
                    batch.files(), allPreviewItems().stream().map(i -> i.path).toList());
            if (fresh.isEmpty()) {
                // RISK-1 硬约束：无新文件则该批次不建组
                log.debug("批次跳过（{} 内文件均已导入）", batch.dirPath());
                continue;
            }

            boolean wasEmpty = visibleCount() == 0;
            List<PreviewItem> added = new ArrayList<>();
            for (File f : fresh) {
                BatchProcessor.MhtFileInfo info = BatchProcessor.toMhtFileInfo(f);
                added.add(new PreviewItem(info.getPath(), info.getName(),
                        info.getModifiedTime(), f.getParent()));
            }

            if (batch instanceof MhtImportPlanner.GroupBatch gb) {
                // 组成员只入组、不入顶层（数据模型：组成员仅存于 GroupItem.members；
                // 若同时入顶层，visibleCount/显示序/输出单元都会双计——§5.6 断言口径为单重表示）
                GroupItem<PreviewItem> group = createGroupForImport(added, gb.folderName());
                createdGroups.add(group);
            } else {
                items.addAll(added);              // TopLevelBatch：平铺
            }
            importAccumulated.addAll(added);

            if (wasEmpty) {
                fileListView.getSelectionModel().selectFirst();
            }
        }
        importGroupCount += createdGroups.size();

        // 撤销栈：一次导入 = 一条记录（BC-5；多轮导入时每轮记录均携带全量累计，一次撤销撤整批）
        if (!importAccumulated.isEmpty()) {
            undoManager.push(new UndoManager.Record<>(
                    UndoManager.RecordType.IMPORT, new ArrayList<>(importAccumulated),
                    new ArrayList<>(createdGroups)));
        }

        refreshAfterListChange();                 // 每轮一次（RISK-8，不是每批一次）
    }

    /**
     * 建组：组名 sanitize → 唯一化 → 注册名字刷新监听 → 追加顶层（BC-4 默认展开）。
     * 注意：不清 members 的条目级参数——成员均为本次新导入项，本就没有条目级参数；
     * 不要照抄 onGroupClicked 的条目参数清除（那会清掉既有条目的参数，RISK-1）。
     */
    private GroupItem<PreviewItem> createGroupForImport(List<PreviewItem> members, String folderName) {
        // ① 组名：sanitize（保证"列表显示名 == 输出名"，F10/BC-6）
        String base = SanitizeUtils.sanitize(folderName);
        if (base.isEmpty() || base.equals("unnamed")) {
            base = "组" + GroupStructure.nextGroupNo(groupNoCounter);
        }
        // ② 唯一化：复用既有实现（F7）
        String name = GroupStructure.uniqueGroupName(base, allGroupNames());
        if (!name.equals(base)) {
            log.info("组名冲突：{} 已被其它组使用，自动改为 {}", base, name);
        }
        // ③ 建组 + 注册名字刷新监听（F8——漏了会导致改名后列表不刷新）
        int groupNo = GroupStructure.nextGroupNo(groupNoCounter);
        groupNoCounter = groupNo;
        GroupItem<PreviewItem> group = new GroupItem<>(groupNo, name);
        registerGroupNameRefresh(group);
        group.setExpanded(true);                  // BC-4：默认展开
        // ④ 成员入组
        group.getMembers().addAll(members);
        // ⑤ 追加顶层（导入一律追加到末尾，不与落点/既有项目交互）
        items.add(group);
        return group;
    }

    /** 组名刷新监听注册（手动/自动建组共用；F8/RISK-11） */
    private void registerGroupNameRefresh(GroupItem<PreviewItem> group) {
        group.nameProperty().addListener((obs, old, newVal) -> fileListView.refresh());
    }

    /** 全部组名集合（供自动建组唯一化使用） */
    private List<String> allGroupNames() {
        List<String> names = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                names.add(((GroupItem<?>) g).getName());
            }
        }
        return names;
    }

    /** 后台线程：轮扫描 + 询问 FX 确认；不经手 GUI 控件 */
    private void runImportRounds(ImportSession session) {
        MhtImportPlanner.Scanner scanner = MhtImportPlanner.scanner(
                session.addresses(), MhtImportPlanner.DEFAULT_MAX_DEPTH,
                IMPORT_QUOTA_GROUPS, IMPORT_QUOTA_FILES);
        try {
            while (true) {
                MhtImportPlanner.Plan round = scanner.nextRound();
                Platform.runLater(() -> applyImportPlan(round));
                if (!round.hasMore()) {
                    break;
                }
                if (!round.quotaHit()) {
                    continue;                     // 防御：无配额命中却还有剩余
                }
                log.info("已达每轮上限（{} 组 / {} 文件），询问用户是否继续",
                        IMPORT_QUOTA_GROUPS, IMPORT_QUOTA_FILES);
                boolean go = awaitQuotaConfirm(round);
                if (!go) {
                    log.info("用户选择停止导入；已完成部分保留");
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("导入扫描失败", ex);
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "导入失败：" + ex.getMessage()));
        } finally {
            Platform.runLater(this::finishImport);   // 唯一恢复点
        }
    }

    /** 后台线程阻塞等待 FX 线程上的配额确认框 */
    private boolean awaitQuotaConfirm(MhtImportPlanner.Plan round) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("导入数量确认");
            a.setHeaderText(null);
            a.setContentText(String.format(
                    "本次导入已达上限（每轮 %d 个组 / %d 个文件），是否继续导入？%n%n"
                    + "继续：从断点扫描下一轮（每满一轮会再次询问）%n"
                    + "停止：保留已导入的 %d 个文件 / %d 个组",
                    IMPORT_QUOTA_GROUPS, IMPORT_QUOTA_FILES,
                    importAccumulated.size(), importGroupCount));
            a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            future.complete(a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK);
        });
        return future.join();
    }

    /** 收尾：唯一恢复点 + 统一预解析 + 汇总日志 */
    private void finishImport() {
        try {
            // 预解析在导入期间抑制，收尾统一跑一次（RISK-7，避免 O(n²)）
            if (!importAccumulated.isEmpty() && currentModeConfirmed()) {
                startPreParseFor(new ArrayList<>(importAccumulated));
            }
            log.info("导入完成：新增 {} 个项目、自动创建 {} 个组；忽略非 MHT {} 个、隐藏目录 {} 个、"
                            + "空目录 {} 个、不可读 {} 个、超深 {} 个",
                    importAccumulated.size(), importGroupCount,
                    importIgnoredNonMht, importHiddenDirs, importEmptyDirs,
                    importUnreadable, importDepthLimited);
        } finally {
            importPending = null;
            importAccumulated.clear();
            updateStartButtonState();             // ★ 必须走派生更新，不裸 setDisable
        }
    }

    /** 外部导入拖拽落点高亮（去抖：状态未变化不重复操作样式类） */
    private void updateImportDropHighlight(boolean on) {
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
    private void clearImportDropHighlight() {
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
        fileListView.setOnDragExited(e -> clearImportDropHighlight());
        fileListView.setOnDragDropped(this::onExternalFilesDropped);
        fileListView.setOnDragDone(e -> clearImportDropHighlight());
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
                // ★ 拖放回调在 FX 线程；盘符根目录确认框需 showAndWait。
                //   延后到 runLater，避免在 dragDropped 分发过程中开嵌套事件循环干扰 DnD 状态机。
                Platform.runLater(() -> importByPaths(addresses, "拖拽导入", true));
                ok = true;
            }
        } catch (Exception ex) {
            log.warn("拖拽导入失败：{}", ex.getMessage(), ex);
        } finally {
            clearImportDropHighlight();
        }
        e.setDropCompleted(ok);
        e.consume();
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

    /** 当前模式下某条目的生效参数（三层覆盖，条目独立参数需求拍板）：组参数 > 条目级参数 > 全局；均未确认 → null。
     *  图片模式经 ParamScope.effectiveImageParams 唯一入口解析（会话 7 收敛，与提取路径一致） */
    private AppConfig effectiveForObject(Object o) {
        GroupItem<?> parent = o instanceof PreviewItem it ? it.parentGroup
                : (o instanceof GroupItem<?> g ? g : null);
        AppConfig itemText = o instanceof PreviewItem it ? it.textItemParams : null;
        AppConfig itemImage = o instanceof PreviewItem it ? it.imageItemParams : null;
        return imageModeToggle.isSelected()
                ? ParamScope.effectiveImageParams(parent, itemImage, imageParamsCache)
                : ParamScope.effectiveParams(parent, itemText, itemImage, false, textParamsCache, imageParamsCache);
    }

    /** 任一有效项目存在生效参数（组参数或全局参数已确认） */
    private boolean anyVisibleItemHasEffectiveParams() {
        return allVisibleItems().stream().anyMatch(i -> effectiveForObject(i) != null);
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
        modeLabel.setText("模式：" + (imageModeToggle.isSelected() ? "图片"
                : (textModeToggle.isSelected() ? "文本" : "未选择")));
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
                ? (params.isSeparateFiles() ? "输出模式：单独输出模式" : "输出模式：合并输出模式")
                : (params.isSingleOutputMode() ? "输出模式：合并输出模式" : "输出模式：单独输出模式"));
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
        ParamScope.Target target;
        if (sel instanceof GroupItem<?> g) {
            target = new ParamScope.GroupTarget(g);
        } else if (sel instanceof PreviewItem it && it.parentGroup != null) {
            target = new ParamScope.GroupTarget(it.parentGroup);
        } else if (sel instanceof PreviewItem it) {
            target = new ParamScope.ItemTarget<>(it, it.textItemParams, it.imageItemParams);
        } else {
            target = ParamScope.GLOBAL;
        }
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
                if (target instanceof ParamScope.GroupTarget gt) {
                    applyGroupParams((GroupItem<PreviewItem>) gt.group(), imageMode, ctrl.getResult());
                } else if (target instanceof ParamScope.ItemTarget<?> it) {
                    applyItemParams((PreviewItem) it.item(), imageMode, ctrl.getResult());
                } else {
                    applyConfirmedParams(imageMode, ctrl.getResult());
                    log.info("{}模式提取参数已确认生效", imageMode ? "图片" : "文本");
                }
            }
        } catch (Exception e) {
            log.error("参数设置窗口打开失败", e);
            showAlert(Alert.AlertType.ERROR, "参数设置窗口打开失败: " + e.getMessage());
        }
    }

    /** 写入某条目的条目级独立参数（条目独立参数需求）：不写全局，仅清缓存重解析该条目；入撤销栈（PARAM_SET） */
    private void applyItemParams(PreviewItem item, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? item.imageItemParams : item.textItemParams;
        if (imageMode) {
            item.imageItemParams = params;
        } else {
            item.textItemParams = params;
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, item.path, null, before,
                false, null, null));
        clearExtractOutcomes(List.of(item));
        clearPreviewCache(item);
        if (imageMode) {
            previewCacheLifecycle.onParamsChanged(item.path);   // 规则 (a)：参数变化 → 删该项目缓存目录
        }
        startPreParseFor(List.of(item));
        log.info("条目 {} 已设置{}模式独立参数", item.name, imageMode ? "图片" : "文本");
        fileListView.refresh();
        updateListButtons();
        updateInfoArea(item);
        updateExtractBanner(item);
    }

    /** 写入某模式的已确认参数缓存并刷新工作区（agent 演练/自动化测试也走此入口）；入撤销栈（PARAM_SET） */
    private void applyConfirmedParams(boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? imageParamsCache : textParamsCache;
        if (imageMode) {
            imageParamsCache = params;
        } else {
            textParamsCache = params;
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, null, null, before,
                false, null, null));
        // 拍板：全局参数重新确认 → 全部条目旧提取结果色失效
        clearExtractOutcomes(allPreviewItems());
        if (imageMode) {
            // 规则 (a)：全局参数变化 → 全部条目缓存目录删除（保守全删：无独立参数的条目生效参数确实变了，
            // 有独立参数的条目删除也只是下次预览重建，语义安全）
            previewCacheLifecycle.onParamsChangedBatch(
                    allPreviewItems().stream().map(i -> i.path).toList());
        }
        refreshAfterParamsChanged();
        updateExtractBanner(currentSelection());
    }

    /** 写入某组的已确认参数缓存（R8）：参数作用域仅限预览解析与显示，提取管线按组参数输出属阶段 8 */
    private void applyGroupParams(GroupItem<PreviewItem> group, boolean imageMode, AppConfig params) {
        AppConfig before = imageMode ? group.getImageParamsCache() : group.getTextParamsCache();
        if (imageMode) {
            group.setImageParamsCache(params);
        } else {
            group.setTextParamsCache(params);
        }
        log.info("组 {} 已确认{}模式独立参数，组内项目重新解析", group.getName(), imageMode ? "图片" : "文本");
        // 入撤销栈（PARAM_SET，组参数设置可撤销）
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, imageMode, null, group.getGroupNo(), before,
                false, null, null));
        // 拍板：组参数重新确认 → 该组成员旧提取结果色失效
        List<PreviewItem> members = group.getMembers().stream().filter(m -> !m.removed).toList();
        clearExtractOutcomes(members);
        if (imageMode) {
            // 规则 (a)：组参数变化 → 该组成员缓存目录删除
            previewCacheLifecycle.onParamsChangedBatch(members.stream().map(m -> m.path).toList());
        }
        refreshAfterTargetedParamsChange(members);
        updateListButtons();
        updateExtractBanner(currentSelection());
    }

    // ==================== 右键菜单（右键菜单功能需求） ====================

    /** 构建右键菜单（拍板 Q1 命名统一"移除"；Q5 一律禁用制：菜单恒 16 项同序；浏览器查看HTML需求后移除"打开"） */
    private void buildContextMenu() {
        ctxSelect = new MenuItem("选中");
        ctxSelectAll = new MenuItem("全选");
        ctxOpenFolder = new MenuItem("打开所在文件夹");
        ctxOpenInBrowser = new MenuItem("在浏览器中打开");
        ctxViewSource = new MenuItem("在浏览器中查看源码");
        ctxParams = new MenuItem("设置提取参数");
        ctxClearParams = new MenuItem("清除参数");
        ctxApplyGlobal = new MenuItem("将参数应用到全局");
        ctxGroup = new MenuItem("合并为组");
        ctxUngroup = new MenuItem("拆分组");
        ctxRemove = new MenuItem("移除");
        ctxRemoveGroup = new MenuItem("移除组");

        ctxSelect.setOnAction(e -> onContextSelect());
        ctxSelectAll.setOnAction(e -> onContextSelectAll());
        ctxOpenFolder.setOnAction(e -> onContextOpenFolder());
        ctxOpenInBrowser.setOnAction(e -> onContextOpenInBrowser());
        ctxViewSource.setOnAction(e -> onContextViewSource());
        ctxParams.setOnAction(e -> onContextParams());
        ctxClearParams.setOnAction(e -> onContextClearParams());
        ctxApplyGlobal.setOnAction(e -> onContextApplyGlobal());
        ctxGroup.setOnAction(e -> onGroupClicked());
        ctxUngroup.setOnAction(e -> onUngroupClicked());
        ctxRemove.setOnAction(e -> onRemoveClicked());
        ctxRemoveGroup.setOnAction(e -> onContextRemoveGroup());

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
        contextMenu.setOnShown(e -> {
            if (fileListView.getScene() != null) {
                fileListView.getScene().getWindow()
                        .addEventFilter(MouseEvent.MOUSE_PRESSED, contextClickAway);
            }
        });
        contextMenu.setOnHidden(e -> {
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
    private void updateContextMenuState(Object target) {
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
        ctxParams.setText(blank ? "设置提取参数（全局）" : "设置提取参数");
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
                it.selected.set(true);
            } else if (contextTarget instanceof GroupItem<?> g) {
                for (PreviewItem m : ((GroupItem<PreviewItem>) g).getMembers()) {
                    if (!m.removed) {
                        m.selected.set(true);
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
            targets = visibleMembersOf((GroupItem<PreviewItem>) g);
        } else if (contextTarget instanceof PreviewItem it && it.parentGroup != null) {
            targets = visibleMembersOf(it.parentGroup);
        } else {
            targets = allVisibleItems();
        }
        updatingSelection = true;
        try {
            for (PreviewItem t : targets) {
                t.selected.set(true);
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
                log.warn("文件不存在，直接打开所在目录（不定位）：{}", it.path);
                // 与"打开"同一机制（ShellExecute），提权环境下可靠
                Desktop.getDesktop().open(dir);
            } else {
                log.warn("打开所在文件夹失败：目录不存在 {}", dir == null ? it.path : dir.getAbsolutePath());
                showAlert(Alert.AlertType.WARNING, "文件所在目录不存在：\n" + it.path);
            }
        } catch (Exception ex) {
            log.error("打开所在文件夹失败", ex);
            showAlert(Alert.AlertType.ERROR, "打开所在文件夹失败：" + ex.getMessage());
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
            log.warn("浏览器查看失败：文件不存在（可能已被移动或删除）{}", it.path);
            showAlert(Alert.AlertType.WARNING, "文件不存在，可能已被移动或删除：\n" + it.path);
            return;
        }
        if (!browserExporting.compareAndSet(false, true)) {
            log.info("已有查看页生成任务在进行中，忽略本次请求");
            return;
        }
        statusLabel.setText("正在生成浏览器查看页…");
        Thread t = new Thread(() -> {
            try {
                MimeParser.ParsedMht parsed = new MimeParser().parse(it.path, false, () -> false, null, null);
                if (parsed.getHtmlContent() == null) {
                    throw new IllegalStateException("无法解析文件（HTML 内容缺失）");
                }
                HtmlExportBuilder.ExportResult r = HtmlExportBuilder.export(it.path, parsed);
                if (!r.success()) {
                    throw new IllegalStateException(r.error());
                }
                log.info("已生成浏览器查看页：{}（图片 {} 张，警告 {} 条）",
                        r.htmlFile(), r.imageCount(), r.warnings().size());
                // 浏览器启动可能耗时（注册表探测 / CDP 端点等待最长约 15 秒），独立后台线程避免冻结界面
                Thread launcher = new Thread(() -> {
                    boolean ok = sourceMode
                            ? BrowserLauncher.openViewSource(r.htmlFile())
                            : BrowserLauncher.openHtml(r.htmlFile());
                    if (!ok) {
                        log.warn("浏览器启动失败：{}", r.htmlFile());
                        Platform.runLater(() -> showAlert(Alert.AlertType.WARNING,
                                "未能启动浏览器，页面已生成：\n" + r.htmlFile()));
                    }
                }, "html-browser-launch");
                launcher.setDaemon(true);
                launcher.start();
            } catch (Exception e) {
                log.error("生成浏览器查看页失败：{}", it.path, e);
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "生成查看页失败：" + e.getMessage()));
            } finally {
                browserExporting.set(false);
                Platform.runLater(() -> statusLabel.setText("就绪"));
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
            group = (GroupItem<PreviewItem>) g;
        } else if (contextTarget instanceof PreviewItem it && it.parentGroup != null) {
            group = it.parentGroup;
        }
        if (group != null) {
            removeGroupWithConfirm(group);
        }
    }

    // ==================== 应用到全局（条目独立参数需求） ====================

    /** 目标生效参数来源是否为独立参数（条目级或组参数；拍板：仅此状态"应用到全局"可用） */
    private boolean hasIndependentParams(Object target) {
        if (target instanceof GroupItem<?> g) {
            GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
            return imageModeToggle.isSelected()
                    ? group.getImageParamsCache() != null : group.getTextParamsCache() != null;
        }
        if (target instanceof PreviewItem it) {
            return ParamScope.backgroundStatus(it.parentGroup,
                    it.textItemParams, it.imageItemParams, imageModeToggle.isSelected(),
                    textParamsCache, imageParamsCache) == ParamScope.Status.GROUP;
        }
        return false;
    }

    /** 右键菜单"应用到全局"：目标 = 右键点击的条目 */
    private void onContextApplyGlobal() {
        if (listAnimating || contextTarget == null || !hasIndependentParams(contextTarget)) {
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
        if (sel != null && hasIndependentParams(sel)) {
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
                GroupItem<PreviewItem> grp = (GroupItem<PreviewItem>) g;
                groupBefore.put(grp.getGroupNo(),
                        imageMode ? grp.getImageParamsCache() : grp.getTextParamsCache());
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
                GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
                if (imageMode) {
                    group.setImageParamsCache(null);
                } else {
                    group.setTextParamsCache(null);
                }
            }
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.APPLY_GLOBAL, List.of(),
                null, -1, List.of(), List.of(), List.of(), List.of(),
                imageMode, globalBefore, itemBefore, groupBefore, false, null, null, null,
                false, null, null));
        clearExtractOutcomes(allPreviewItems());
        if (imageMode) {
            // 规则 (a)：全局参数写入 + 全部独立参数清空 → 全部条目生效参数变化 → 删除全部缓存目录
            previewCacheLifecycle.onParamsChangedBatch(
                    allPreviewItems().stream().map(i -> i.path).toList());
        }
        log.info("已应用到全局（{}模式）：所有独立参数已清空，全部条目继承新全局参数",
                imageMode ? "图片" : "文本");
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
                .filter(i -> i.selected.get()).toList();
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
                GroupItem<PreviewItem> grp = (GroupItem<PreviewItem>) g;
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
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.CLEAR_PARAMS, List.of(),
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
        if (imageMode) {
            // 规则 (a)：清除参数也是参数变化（生效参数回落组/全局）→ 受影响条目缓存目录删除
            previewCacheLifecycle.onParamsChangedBatch(affected.stream().map(i -> i.path).toList());
        }
        log.info("已清除 {} 个条目、{} 个组的{}模式参数", topItems.size(), groups.size(),
                imageMode ? "图片" : "文本");
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
        log.info("提取结果已回写列表：{} 个文件", byPath.size());
    }

    /** 结果详情拼接：失败/跳过原因 + 警告，分号分隔；无内容返回 null */
    private String buildExtractDetail(FileResult r) {
        List<String> parts = new ArrayList<>();
        if (r.message() != null && !r.message().isEmpty()) {
            parts.add(r.message());
        }
        parts.addAll(r.warnings());
        return parts.isEmpty() ? null : String.join("；", parts);
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
                case FAILED -> "提取失败";
                case SKIPPED -> "已跳过";
                case SUCCESS_WARNED -> "提取成功，但有警告";
                default -> null;
            };
            if (prefix != null) {
                extractResultBanner.setText(prefix + "：" + it.extractDetail);
                extractResultBanner.setVisible(true);
                extractResultBanner.setManaged(true);
                return;
            }
        }
        if (sel instanceof PreviewItem it && it.status.get() == Status.FAILED) {
            extractResultBanner.setText("解析失败：" + it.errorMsg.get());
            extractResultBanner.setVisible(true);
            extractResultBanner.setManaged(true);
            return;
        }
        extractResultBanner.setVisible(false);
        extractResultBanner.setManaged(false);
    }

    private void refreshAfterParamsChanged() {
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
            m.status.set(Status.WAITING);
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
                .filter(i -> i.status.get() == Status.WAITING && !affected.contains(i)).toList();
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
        if (visibleCount() == 0) {
            showAlert(Alert.AlertType.WARNING,
                    items.isEmpty() ? "未找到任何 MHT 文件" : "至少保留一个文件");
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

        startExtractionTask();
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

    private Map<String, PerFileOverrides> overrides() {
        Map<String, PerFileOverrides> map = new LinkedHashMap<>();
        for (PreviewItem item : allVisibleItems()) {
            map.put(item.path, new PerFileOverrides(item.chapterEnabled.get()));
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
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH：mm：ss"));
        String sessionOutputFolder = new File(outputFolderField.getText(), timestamp).getPath();
        if (!new File(sessionOutputFolder).mkdirs()) {
            log.warn("会话输出目录创建失败或已存在：{}", sessionOutputFolder);
        }
        log.info("会话输出目录：{}", sessionOutputFolder);

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
        log.info("输出单元计划：{}", units.stream()
                .map(u -> String.format("[%s]%d 个文件", u.label(), u.fileCount())).toList());
        Map<String, PerFileOverrides> overrides = overrides();

        running.set(true);
        startButton.setDisable(true);
        stopButton.setDisable(false);
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        setProgressBarActivity(STYLE_PROGRESS_EXTRACT_SUCCESS);   // 提取中 = 开始提取按钮绿

        Task<BatchProcessor.ProcessStats> task = new Task<>() {
            @Override
            protected BatchProcessor.ProcessStats call() {
                try {
                    UnitProgressAggregator progress = new UnitProgressAggregator(
                            units.stream().map(OutputUnitPlanner.OutputUnit::fileCount).toList());
                    BatchProcessor.ProcessStats total = new BatchProcessor.ProcessStats();
                    total.setStartTime(System.currentTimeMillis());
                    total.setTotalFiles(progress.totalFiles());

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

                    for (int i = 0; i < units.size(); i++) {
                        if (isCancelled()) {
                            log.info("用户取消处理，剩余 {} 个输出单元未启动", units.size() - i);
                            break;
                        }
                        OutputUnitPlanner.OutputUnit unit = units.get(i);
                        log.info("开始处理输出单元[{}]（{} 个文件）→ {}", unit.label(), unit.fileCount(),
                                unit.getOutputPath());
                        final int unitIndex = i;
                        BatchProcessor.ProgressCallback progressCallback =
                                (current, totalInUnit, fileName, subProgress) ->
                                        updateProgress(progress.overall(unitIndex, current, subProgress), 1.0);
                        try {
                            BatchProcessor.ProcessStats unitStats = processUnit(unit, sessionOutputFolder,
                                    overrides, this::isCancelled, recursionCallback, progressCallback);
                            total.setSuccessCount(total.getSuccessCount() + unitStats.getSuccessCount());
                            total.setFailCount(total.getFailCount() + unitStats.getFailCount());
                            total.setSkipCount(total.getSkipCount() + unitStats.getSkipCount());
                            total.setWarnCount(total.getWarnCount() + unitStats.getWarnCount());
                            total.getFailDetails().addAll(unitStats.getFailDetails());
                            total.getSkipDetails().addAll(unitStats.getSkipDetails());
                            total.getWarnDetails().addAll(unitStats.getWarnDetails());
                            total.addAllFileResults(unitStats.getFileResults());
                        } catch (Exception e) {
                            log.error("输出单元[{}]处理失败", unit.label(), e);
                            for (int k = 0; k < unit.fileCount(); k++) {
                                total.incrementFail();
                            }
                            total.addFailDetail(String.format("输出单元[%s]处理失败：%s",
                                    unit.label(), e.getMessage()));
                        }
                    }
                    total.setEndTime(System.currentTimeMillis());
                    log.info("{}", total);
                    // 取消路径经 holder 回写（Task 取消时 getValue 不可用）
                    lastExtractStats.set(total);
                    return total;
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
                // 满格条按结果配色（拍板 2026-09-24）：有失败/跳过 → 浅红；有警告 → 黄；否则保持绿
                setProgressBarActivity(stats.getFailCount() + stats.getSkipCount() > 0
                        ? STYLE_PROGRESS_EXTRACT_FAILED
                        : stats.getWarnCount() > 0 ? STYLE_PROGRESS_EXTRACT_WARNED
                        : STYLE_PROGRESS_EXTRACT_SUCCESS);
                // 提取结果一次性回写（拍板）：完成后列表条目背景反映本次提取结果
                applyExtractResults(stats);
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
            setProgressBarActivity(null);
            statusLabel.setText("处理异常");
            Throwable ex = task.getException();
            log.error("处理异常", ex);
            showAlert(Alert.AlertType.ERROR, "处理异常: " + ex.getMessage());
        });

        task.setOnCancelled(e -> {
            // 拍板：中途取消回写已处理部分（未处理的保持原样）
            BatchProcessor.ProcessStats partial = lastExtractStats.get();
            if (partial != null) {
                applyExtractResults(partial);
            }
        });

        currentTask = task;
        new Thread(task).start();
    }

    /** 执行单个输出单元（文本/图片分派；图片合并输出经显式输出名指定组名/来源名） */
    private BatchProcessor.ProcessStats processUnit(OutputUnitPlanner.OutputUnit unit,
                                                    String sessionOutputFolder,
                                                    Map<String, PerFileOverrides> overrides,
                                                    java.util.function.Supplier<Boolean> cancelSupplier,
                                                    java.util.function.Supplier<Boolean> recursionCallback,
                                                    BatchProcessor.ProgressCallback callback) {
        AppConfig config = unit.getParams();
        List<BatchProcessor.MhtFileInfo> files = BatchProcessor.buildMhtFileInfoList(unit.getPaths());
        if (config.isImageModeEnabled()) {
            ImageBatchProcessor processor = new ImageBatchProcessor(config);
            processor.setCancelSupplier(cancelSupplier);
            processor.setRecursionContinueCallback(recursionCallback);
            if (config.isSingleOutputMode()) {
                return processor.process(files, sessionOutputFolder, unit.getOutputName(), callback);
            }
            return processor.process(files, unit.getOutputPath(), callback);
        } else {
            TextBatchProcessor processor = new TextBatchProcessor(config);
            processor.setCancelSupplier(cancelSupplier);
            processor.setRecursionContinueCallback(recursionCallback);
            if (config.isSeparateFiles()) {
                File outDir = new File(unit.getOutputPath());
                if (!outDir.exists() && !outDir.mkdirs()) {
                    log.warn("输出文件夹创建失败：{}", unit.getOutputPath());
                }
                return processor.processSeparate(files, unit.getOutputPath(), callback, overrides);
            }
            return processor.processMerged(files, unit.getOutputPath(), callback, overrides);
        }
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
        setProgressBarActivity(null);
        statusLabel.setText("已停止");
        log.info("用户手动停止处理");
    }

    // ==================== 列表渲染与拖拽排序 ====================

    /** 可显示插入指示线的单元格（拖拽排序反馈；INTO = 组行整行高亮，入组末尾/两组合并语义） */
    private interface CellIndicator {
        void showInsertIndicator(boolean top);

        void showIntoIndicator();

        void clearInsertIndicator();
    }

    private ListCell<Object> createPreviewCell() {
        return new PreviewListCell();
    }

    /**
     * 预览列表单元格：组条目 / 项目条目两种渲染（徽标/顺序/文件名/状态）+ 顶层项目拖拽排序（静态蓝线 + 滑动补位动画）
     */
    private class PreviewListCell extends ListCell<Object> implements CellIndicator {
        private final HBox box = new HBox(6);
        private final StackPane wrapper = new StackPane();
        private final CheckBox checkBox = new CheckBox();
        private final Label arrowLabel = new Label();
        private final Label badgeLabel = new Label();
        private final Label posLabel = new Label();
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Region spacer = new Region();
        private final Region topLine = new Region();
        private final Region bottomLine = new Region();
        private final Region intoOverlay = new Region();
        private final FontIcon arrowIcon = new FontIcon();
        private PreviewItem boundItem;

        PreviewListCell() {
            // 内边距与列头 HBox 一致（10px 水平），保证列对齐
            box.setPadding(ROW_PAD);
            checkBox.setMinWidth(CHECK_COL_WIDTH);
            checkBox.setPrefWidth(CHECK_COL_WIDTH);
            checkBox.setMaxWidth(CHECK_COL_WIDTH);
            checkBox.setAlignment(Pos.CENTER);
            checkBox.setFocusTraversable(false);
            checkBox.setStyle("-fx-padding: 0;");
            // 从复选框按下的拖拽手势在此消费，不冒泡到单元格，避免误触发排序拖拽（F1 2.4）
            checkBox.setOnDragDetected(e -> e.consume());
            arrowLabel.setMinWidth(16);
            arrowLabel.setPrefWidth(16);
            arrowLabel.setMaxWidth(16);
            arrowLabel.setAlignment(Pos.CENTER);
            arrowLabel.setStyle("-fx-padding: 0;");
            arrowLabel.setVisible(false);
            arrowLabel.setManaged(false);
            // 折叠箭头用 Ikonli 图标：主题字体可能缺少 ▾/▸ 字形导致空白
            arrowIcon.setIconSize(12);
            // 拍板 2026-09-22：箭头恒为白色——内联样式（优先级高于主题 CSS 的 -fx-icon-color），
            // 程序化 setIconColor 会被样式表覆盖（黑底上变深色不可见）
            arrowIcon.setStyle("-fx-icon-color: white;");
            arrowLabel.setGraphic(arrowIcon);
            badgeLabel.setMinWidth(BADGE_COL_WIDTH);
            badgeLabel.setPrefWidth(BADGE_COL_WIDTH);
            badgeLabel.setMaxWidth(BADGE_COL_WIDTH);
            badgeLabel.setAlignment(Pos.CENTER);
            posLabel.setMinWidth(POS_COL_WIDTH);
            posLabel.setPrefWidth(POS_COL_WIDTH);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(checkBox, arrowLabel, badgeLabel, posLabel, nameLabel, spacer, statusLabel);

            // 插入指示线：覆盖在内容上下边缘的 2px 静态蓝线（StackPane 覆盖层，不占布局）。
            // 必须鼠标透明：否则 drop 事件目标为线本身，e.getY() 变为 0-2 导致落点分区误判（下缘落点被当成上缘）
            topLine.setMinHeight(2);
            topLine.setPrefHeight(2);
            topLine.setMaxHeight(2);
            topLine.setStyle(DROP_LINE_STYLE);
            topLine.setVisible(false);
            topLine.setMouseTransparent(true);
            StackPane.setAlignment(topLine, Pos.TOP_CENTER);
            bottomLine.setMinHeight(2);
            bottomLine.setPrefHeight(2);
            bottomLine.setMaxHeight(2);
            bottomLine.setStyle(DROP_LINE_STYLE);
            bottomLine.setVisible(false);
            bottomLine.setMouseTransparent(true);
            StackPane.setAlignment(bottomLine, Pos.BOTTOM_CENTER);

            // 组行整行高亮覆盖层（入组/合并语义）：鼠标透明，不拦截拖放事件
            intoOverlay.setStyle(INTO_OVERLAY_STYLE);
            intoOverlay.setVisible(false);
            intoOverlay.setMouseTransparent(true);

            wrapper.getChildren().addAll(box, topLine, bottomLine, intoOverlay);
            setupDnD();
        }

        @Override
        public void showInsertIndicator(boolean top) {
            intoOverlay.setVisible(false);
            topLine.setVisible(top);
            bottomLine.setVisible(!top);
        }

        @Override
        public void showIntoIndicator() {
            topLine.setVisible(false);
            bottomLine.setVisible(false);
            intoOverlay.setVisible(true);
        }

        @Override
        public void clearInsertIndicator() {
            topLine.setVisible(false);
            bottomLine.setVisible(false);
            intoOverlay.setVisible(false);
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (boundItem != null) {
                checkBox.selectedProperty().unbindBidirectional(boundItem.selected);
                boundItem = null;
            }
            checkBox.setOnAction(null);
            arrowLabel.setOnMouseClicked(null);
            setOnMouseClicked(null);
            setOnMouseReleased(null);
            setOnMousePressed(null);
            clearInsertIndicator();
            // 折叠/展开动画期间列表项目不可交互：所有可见单元格复选框禁用
            checkBox.setDisable(listAnimating);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                setStyle("");
                setVisible(false);
                setManaged(false);
                return;
            }
            if (item instanceof GroupItem<?> g) {
                renderGroupRow((GroupItem<PreviewItem>) g);
                // 折叠点击走 cell 级 pressed 判定（箭头槽位 cell x 22-46）：released 会被
                // checkbox 吞掉（间歇丢失），pressed 到达更可靠；判定由 onCellPressed 统一处理
            } else if (item instanceof PreviewItem it) {
                if (it.removed) {
                    setGraphic(null);
                    setTooltip(null);
                    setStyle("");
                    setVisible(false);
                    setManaged(false);
                    return;
                }
                renderItemRow(it);
            }
            // Ctrl/Shift 多选手势（路线 B 拍板）：pressed 即处理（先于 released 的折叠判定，互不冲突）
            setOnMousePressed(e -> onCellPressed(getItem(), e));
            // 双击 toggle 勾选（拍板）：clicked 的 clickCount==2 判定
            setOnMouseClicked(e -> onCellDoubleClicked(getItem(), e));
        }

        /** 组条目行：组复选框（三态随成员联动）+ 折叠箭头 + 组名（项目数）；深蓝底、行高比文字稍高、不随参数变化 */
        private void renderGroupRow(GroupItem<PreviewItem> g) {
            setVisible(true);
            setManaged(true);
            box.setPadding(GROUP_ROW_PAD);
            // 行高锁定为紧凑高度（子组件如复选框默认高度会撑高整行）
            box.setMinHeight(GROUP_ROW_HEIGHT);
            box.setPrefHeight(GROUP_ROW_HEIGHT);
            box.setMaxHeight(GROUP_ROW_HEIGHT);
            // 组行复选框槽位缩窄（与成员行 40px 列错位是拍板接受的视觉取舍），内容紧跟顶格
            checkBox.setMinWidth(GROUP_CHECK_WIDTH);
            checkBox.setPrefWidth(GROUP_CHECK_WIDTH);
            checkBox.setMaxWidth(GROUP_CHECK_WIDTH);
            checkBox.setScaleX(0.75);
            checkBox.setScaleY(0.75);
            int total = visibleMemberCount(g);
            int selected = (int) g.getMembers().stream()
                    .filter(m -> !m.removed && m.selected.get()).count();
            SelectionState.TriState state = SelectionState.computeState(selected, total);
            checkBox.setSelected(state == SelectionState.TriState.ALL);
            checkBox.setIndeterminate(state == SelectionState.TriState.SOME);
            checkBox.setOnAction(e -> {
                e.consume();
                onGroupCheckToggled(g);
            });
            arrowIcon.setIconLiteral(g.isExpanded() ? "fas-caret-down" : "fas-caret-right");
            arrowLabel.setVisible(true);
            arrowLabel.setManaged(true);
            // 折叠点击由 cell 级 handler 判定（见 updateItem），此处不挂 arrowLabel 监听
            badgeLabel.setVisible(false);
            badgeLabel.setManaged(false);
            badgeLabel.setText("");
            badgeLabel.setStyle("");
            posLabel.setVisible(false);
            posLabel.setManaged(false);
            posLabel.setText("");
            nameLabel.setPadding(Insets.EMPTY);
            nameLabel.setText(g.getName() + "（" + total + " 项）");
            // 组行字体加粗 + 白字（拍板）；-fx-text-background-color 不跨节点继承，须直接作用于 Label
            nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff;");
            statusLabel.setText("");
            // 选中态灰色高亮（拍板）：inline 深蓝会覆盖默认选中色，需按选中状态显式切换
            boolean isSelected = fileListView.getSelectionModel().getSelectedItem() == g;
            setStyle(isSelected ? GROUP_ROW_SELECTED_STYLE : GROUP_ROW_STYLE);
            setTooltip(null);
            setGraphic(wrapper);
        }

        /** 项目条目行（顶层与组内成员共用；组内成员文件名缩进显示） */
        private void renderItemRow(PreviewItem it) {
            setVisible(true);
            setManaged(true);
            boolean inGroup = it.parentGroup != null;
            // 组内成员（拍板）：行高略低、名称字体变小加粗；顶层项目维持现状
            box.setPadding(inGroup ? GROUP_MEMBER_PAD : ROW_PAD);
            box.setMinHeight(Region.USE_COMPUTED_SIZE);
            box.setPrefHeight(Region.USE_COMPUTED_SIZE);
            box.setMaxHeight(Region.USE_COMPUTED_SIZE);
            checkBox.setMinWidth(CHECK_COL_WIDTH);
            checkBox.setPrefWidth(CHECK_COL_WIDTH);
            checkBox.setMaxWidth(CHECK_COL_WIDTH);
            checkBox.setScaleX(1.0);
            checkBox.setScaleY(1.0);
            // 单元格可能刚渲染过组条目，先按项目勾选状态对齐再绑定，避免双向绑定初始同步串值
            checkBox.setSelected(it.selected.get());
            checkBox.setIndeterminate(false);
            checkBox.selectedProperty().bindBidirectional(it.selected);
            boundItem = it;
            arrowLabel.setVisible(false);
            arrowLabel.setManaged(false);
            AppConfig eff = effectiveForObject(it);
            boolean showBadgeCol = isTextMode() && eff != null && eff.isAddChapterNumber();
            badgeLabel.setVisible(showBadgeCol);
            badgeLabel.setManaged(showBadgeCol);
            // 单元格可能刚渲染过组条目，恢复顺序栏可见（组行会隐藏该列，不复原则项目名左移挤占顺序栏）
            posLabel.setVisible(true);
            posLabel.setManaged(true);
            int pos = (int) unitIndex(it) + 1;
            posLabel.setText(pos + ".");
            nameLabel.setPadding(inGroup ? new Insets(0, 0, 0, 16) : Insets.EMPTY);
            nameLabel.setText(it.name);
            statusLabel.setText(switch (it.status.get()) {
                case WAITING -> "⏳";
                case READY -> "✅";
                case EMPTY -> "⚠️";
                case FAILED -> "❌";
            });
            String badge = it.badge.get();
            if (badge == null) {
                badgeLabel.setText("");
                badgeLabel.setStyle("");
            } else {
                badgeLabel.setText(badge);
                badgeLabel.setStyle(BADGE_STYLE);
            }
            boolean isSelected = fileListView.getSelectionModel().getSelectedItem() == it;
            if (it.extractOutcome != ExtractOutcome.NONE) {
                // 提取结果色优先级最高（拍板）：覆盖解析失败黄与参数色；选中态用混合灰的显式变体色（组行同机制）
                setStyle(rowStyle(isSelected
                        ? extractOutcomeSelectedPaint(it.extractOutcome)
                        : extractOutcomePaint(it.extractOutcome)));
                setTooltip(it.extractDetail == null || it.extractDetail.isEmpty()
                        ? null : new Tooltip(it.extractDetail));
            } else if (it.status.get() == Status.FAILED) {
                setStyle(isSelected ? FAILED_SELECTED_ROW_STYLE : FAILED_ROW_STYLE);
                setTooltip(new Tooltip(it.errorMsg.get()));
            } else {
                setStyle(itemParamStyle(it, isSelected));
                setTooltip(it.status.get() == Status.EMPTY
                        ? new Tooltip("内容为空，正式提取时将被跳过") : null);
            }
            // 深色底需白字（-fx-text-background-color 不跨节点继承，直接设 label 填充色）：
            // 全局参数深蓝底、提取失败/跳过/警告行（Windows 错误红 #E81123 及红绿分割行）
            boolean whiteText = (it.extractOutcome == ExtractOutcome.FAILED
                    || it.extractOutcome == ExtractOutcome.SKIPPED
                    || it.extractOutcome == ExtractOutcome.SUCCESS_WARNED)
                    || (it.extractOutcome == ExtractOutcome.NONE
                            && it.status.get() != Status.FAILED
                            && ParamScope.backgroundStatus(it.parentGroup,
                                    it.textItemParams, it.imageItemParams, imageModeToggle.isSelected(),
                                    textParamsCache, imageParamsCache) == ParamScope.Status.GLOBAL);
            nameLabel.setStyle((inGroup ? "-fx-font-size: 12px; -fx-font-weight: bold;" : "")
                    + (whiteText ? " -fx-text-fill: #ffffff;" : ""));
            posLabel.setStyle(whiteText ? "-fx-text-fill: #ffffff;" : "");
            setGraphic(wrapper);
        }

        /** 8.5 条目背景色（拍板 2026-09-22 选中态切换，组行同机制）：无生效参数 = 现状色（选中用主题默认高亮）；
         *  全局参数 = 深蓝；组/条目级独立参数 = 浅蓝；选中时切换到混合灰的变体色 */
        private String itemParamStyle(PreviewItem it, boolean selected) {
            String paint = switch (ParamScope.backgroundStatus(it.parentGroup,
                    it.textItemParams, it.imageItemParams, imageModeToggle.isSelected(),
                    textParamsCache, imageParamsCache)) {
                case GLOBAL -> selected ? GLOBAL_PARAM_SELECTED_PAINT : GLOBAL_PARAM_PAINT;
                case GROUP -> selected ? GROUP_PARAM_SELECTED_PAINT : GROUP_PARAM_PAINT;
                case NONE -> null;
            };
            return paint == null ? CELL_PAD : rowStyle(paint);
        }

        /** 提取结果背景画值（拍板）：成功标准绿；失败/跳过浅红；成功+警告左1/3红右2/3绿 */
        private static String extractOutcomePaint(ExtractOutcome outcome) {
            return switch (outcome) {
                case SUCCESS -> EXTRACT_SUCCESS_PAINT;
                case SUCCESS_WARNED -> EXTRACT_WARNED_PAINT;
                case FAILED, SKIPPED -> EXTRACT_FAILED_PAINT;
                default -> null;
            };
        }

        /** 提取结果选中态画值（拍板 2026-09-22）：基础色混合 40% 浅灰的显式变体 */
        private static String extractOutcomeSelectedPaint(ExtractOutcome outcome) {
            return switch (outcome) {
                case SUCCESS -> EXTRACT_SUCCESS_SELECTED_PAINT;
                case SUCCESS_WARNED -> EXTRACT_WARNED_SELECTED_PAINT;
                case FAILED, SKIPPED -> EXTRACT_FAILED_SELECTED_PAINT;
                default -> null;
            };
        }

        private void setupDnD() {
            setOnDragDetected(e -> {
                // 阶段 7：项目（含组内成员）与组条目均可发起拖拽；已移除项已隐藏不可拖；
                // 折叠动画期间禁止拖拽；Ctrl/Shift 修饰键按下不启动拖拽（多选手势）
                if (listAnimating || e.isControlDown() || e.isShiftDown()
                        || isEmpty() || getItem() == null
                        || (getItem() instanceof PreviewItem it && it.removed)) {
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
                // 外部文件/文件夹拖入：本单元格不参与落点判定，仅放行事件给 ListView 级导入处理器。
                // 必须不 consume：本 handler 末尾无条件 e.consume() 会吞掉事件，列表级处理器永远收不到。
                // 不放 acceptTransferModes —— 落点位置无语义（导入一律追加到列表末尾）。
                if (e.getDragboard().hasFiles() && !dragActive) {
                    return;
                }
                // 不能用 getGestureSource() != this 做守卫：拖起后源单元格被 ListView 虚拟化复用
                // 去显示下一条目，光标悬停其上会被误判为"自身"而拒绝落点（向下拖拽必现）。
                // 本应用拖拽会话以 draggedObject 为准；自身落点由 resolve（dragged == target）拦截。
                if (e.getDragboard().hasString() && draggedObject != null) {
                    Object t = getItem();
                    if (t != null && !(t instanceof PreviewItem it && it.removed)) {
                        DropResolver.Zone zone = dropZone(t, e.getY(), getHeight());
                        // 合法落点判定与落点解析共用同一规则（resolve 返回 null = 非法组合）
                        if (DropResolver.resolve(items, draggedObject, t, zone) != null) {
                            e.acceptTransferModes(TransferMode.MOVE);
                            if (zone == DropResolver.Zone.INTO) {
                                showIntoIndicator();
                            } else {
                                showInsertIndicator(zone == DropResolver.Zone.TOP);
                            }
                            lastDragOverCell = this;
                        }
                    }
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
                // 外部文件/文件夹拖入：与 setOnDragOver 的放行分支对应——本单元格不消费 drop，
                // 事件冒泡到 ListView 级导入处理器（松手落在单元格上时若不放行，列表级处理器
                // 永远收不到，外部导入表现为"有高亮但拖不进来"）。
                if (e.getDragboard().hasFiles() && !dragActive) {
                    return;
                }
                Dragboard db = e.getDragboard();
                boolean ok = false;
                clearInsertIndicator();
                lastDragOverCell = null;
                try {
                    if (db.hasString() && draggedObject != null) {
                        handleDrop(e);
                        ok = dropHandled;
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
                // 未发生 drop 的拖拽结束（Esc / 拖出列表）→ 回到原位。
                // 守卫不检查 draggedObject：cancelDrag 只做"重建显示序 + 复位状态"（数据序未动），
                // draggedObject 为 null 时同样能恢复显示（防"条目消失 + 交互锁死"的卡死形态）
                if (dragActive && !dropHandled) {
                    cancelDrag();
                }
                e.consume();
            });
        }

        /**
         * 拖放执行：由 DnD 事件驱动（cell 非空时附带释放动画）。
         * 解析/执行/撤销记录等核心逻辑见 {@link MainController#performDrop}。
         */
        private void handleDrop(javafx.scene.input.DragEvent e) {
            Object t = getItem();
            if (t == null || (t instanceof PreviewItem it && it.removed)) {
                return;
            }
            DropResolver.Zone zone = dropZone(t, e.getY(), getHeight());
            if (MainController.this.performDrop(draggedObject, t, zone, this)) {
                dropHandled = true;
            }
        }
    }

    /**
     * 拖放核心执行（阶段 7 6.6 拖拽规则）：落点解析 → 数据序修改 → REORDER/MERGE_GROUPS 入撤销栈 →
     * 释放动画 → 父组变化项目重新预解析（生效参数切换，阶段 5 机制）。
     * cell 非空 = 真实 DnD 路径（含动画）；cell 为 null = 程序化演练路径（跳过动画）。
     */
    private boolean performDrop(Object dragged, Object target, DropResolver.Zone zone,
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

    /** 已解析操作的执行核心：快照 → apply → 撤销记录 → 动画/瞬时重建 → 父组变化重解析 */
    @SuppressWarnings("unchecked")
    private boolean performResolvedDrop(Object dragged, DropResolver.Operation op,
                                        double gapTopY, PreviewListCell cell) {
        // 快照与合并载荷须在数据序修改前采集
        List<Object> before = GroupStructure.snapshotStructure(items);
        List<PreviewItem> affected = parentChangeTargets(op);
        Map<PreviewItem, GroupItem<PreviewItem>> prevParents = new IdentityHashMap<>();
        for (PreviewItem it : affected) {
            prevParents.put(it, it.parentGroup);
        }
        UndoManager.RecordType type = op instanceof DropResolver.MergeInto
                ? UndoManager.RecordType.MERGE_GROUPS : UndoManager.RecordType.REORDER;
        GroupItem<PreviewItem> merged = null;
        int mergedIdx = -1;
        List<PreviewItem> mergedMembers = List.of();
        if (type == UndoManager.RecordType.MERGE_GROUPS) {
            merged = (GroupItem<PreviewItem>) ((DropResolver.MergeInto) op).dragged();
            mergedIdx = items.indexOf(merged);
            mergedMembers = new ArrayList<>(merged.getMembers());
        }
        boolean changed = DropResolver.apply(items, op, this::isRemovedObject);
        rebuildParentPointers();
        if (changed) {
            // 一次拖放会话 = 一条撤销记录（REORDER 存拖拽前结构快照；MERGE_GROUPS 另存被拖组及其成员副本）
            if (type == UndoManager.RecordType.MERGE_GROUPS) {
                undoManager.push(new UndoManager.Record<>(type, mergedMembers, merged,
                        mergedIdx, List.of(), List.of(), before));
                log.info("拖拽合并：组 {} 并入组 {}（{} 个成员），{} 已销毁",
                        merged.getName(), ((DropResolver.MergeInto) op).target().getName(),
                        mergedMembers.size(), merged.getName());
            } else {
                undoManager.push(new UndoManager.Record<>(type,
                        op instanceof DropResolver.InsertTop it && it.item() instanceof PreviewItem p
                                ? List.of(p)
                                : op instanceof DropResolver.InsertInto ii && ii.item() instanceof PreviewItem p2
                                        ? List.of(p2)
                                        : List.of(),
                        null, -1, List.of(), List.of(), before));
            }
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
        for (PreviewItem it : affected) {
            if (!it.removed && prevParents.get(it) != it.parentGroup) {
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

    private PreviewListCell findCell(Object item) {
        for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
            if (n instanceof PreviewListCell c && c.getItem() == item) {
                return c;
            }
        }
        return null;
    }

    /** 提起：被拖条目从显示序瞬间移除（数据序不动），下方条目快照上滑 0.2s 补位 */
    private void beginDrag(PreviewListCell sourceCell) {
        draggedObject = sourceCell.getItem();
        dragPositionStale = false;
        dragActive = true;
        dragGapTopY = cellBoundsInOverlay(sourceCell).getMinY();
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
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
            dragOverlay.getChildren().removeAll(views);
            cancelDrag();
            return;
        }

        displayItems.remove(draggedObject);
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
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
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

    /** 拖拽全流程结束：复位状态并重算徽标/信息区/按钮/进度 */
    private void finishDragReset() {
        draggedObject = null;
        dragActive = false;
        dropHandled = false;
        onOrderChanged();
    }

    /** 取消拖拽：瞬时恢复到原显示位置（数据序未动，重建显示序即可）。
     *  曾用快照释放动画，但列表外松手的恢复路径存在间歇性丢失（条目消失），改为瞬时恢复保证可靠 */
    private void cancelDrag() {
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

    /** 拖拽落点分区：组行三区（上下边缘 = 行间空隙，中间带 = 整行入组/合并）；项目行两区（上/下） */
    private DropResolver.Zone dropZone(Object row, double y, double height) {
        if (row instanceof GroupItem<?>) {
            if (y < height * GROUP_ROW_EDGE_ZONE) {
                return DropResolver.Zone.TOP;
            }
            if (y < height * (1 - GROUP_ROW_EDGE_ZONE)) {
                return DropResolver.Zone.INTO;
            }
            return DropResolver.Zone.BOTTOM;
        }
        return y < height / 2 ? DropResolver.Zone.TOP : DropResolver.Zone.BOTTOM;
    }

    /** 拖拽中父组将发生变化的项目：入组/出组项目自身；组合并为被拖组全部成员 */
    private List<PreviewItem> parentChangeTargets(DropResolver.Operation op) {
        if (op instanceof DropResolver.InsertTop it && it.item() instanceof PreviewItem p) {
            return List.of(p);
        }
        if (op instanceof DropResolver.InsertInto ii && ii.item() instanceof PreviewItem p) {
            return List.of(p);
        }
        if (op instanceof DropResolver.MergeInto mi && mi.dragged() instanceof GroupItem<?> g) {
            List<PreviewItem> out = new ArrayList<>();
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewItem p) {
                    out.add(p);
                }
            }
            return out;
        }
        return List.of();
    }

    /** 空组销毁判定谓词（拖拽致空组销毁与移除一致，R4） */
    private boolean isRemovedObject(Object o) {
        return o instanceof PreviewItem it && it.removed;
    }

    /** 顶层组列表（按数据序；供 agent 演练使用） */
    @SuppressWarnings("unchecked")
    private List<GroupItem<PreviewItem>> topLevelGroups() {
        List<GroupItem<PreviewItem>> out = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                out.add((GroupItem<PreviewItem>) g);
            }
        }
        return out;
    }

    /** 组内有效成员（removed == false；供 agent 演练使用） */
    private List<PreviewItem> visibleMembersOf(GroupItem<PreviewItem> g) {
        return g.getMembers().stream().filter(m -> !m.removed).toList();
    }

    /** 清空预览缓存并回到待解析（生效参数切换后重新预解析，阶段 5 机制）。只清内存态；磁盘缓存由 PreviewCacheLifecycle 四条规则管理 */
    private void clearPreviewCache(PreviewItem it) {
        it.cachedText = null;
        it.cachedTitle = null;
        it.cachedImages = null;
        it.cachedThumbs = null;
        it.cachedMeta = null;
        it.status.set(Status.WAITING);
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
            item.selected.set(target);
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
    @SuppressWarnings("unchecked")
    private void onCellPressed(Object item, javafx.scene.input.MouseEvent e) {
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
            GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
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
            it.selected.set(!it.selected.get());
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
            return it.selected.get();
        }
        if (o instanceof GroupItem<?> g) {
            for (Object m : ((GroupItem<PreviewItem>) g).getMembers()) {
                if (!(m instanceof PreviewItem p) || (!p.removed && !p.selected.get())) {
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
            it.selected.set(target);
        } else if (o instanceof GroupItem<?> g) {
            for (Object m : ((GroupItem<PreviewItem>) g).getMembers()) {
                if (m instanceof PreviewItem p && !p.removed) {
                    p.selected.set(target);
                }
            }
        }
    }

    /**
     * 双击条目 toggle 勾选（拍板）：成员行双击文件名区域（cell x ≥ 50，checkbox 槽之外）
     * 反选自身；组行双击组名区域（cell x ≥ 46，折叠判定区之外）整组反选。
     * 图形交互槽位（复选框/折叠箭头）内双击不触发，避免与单击行为叠加。
     */
    @SuppressWarnings("unchecked")
    private void onCellDoubleClicked(Object item, javafx.scene.input.MouseEvent e) {
        // 仅左键双击（CLICKED 事件在松开后生成，isPrimaryButtonDown 恒为 false，须用 getButton 判定）。
        // 多击时间窗口内 clickCount 连续递增（快速连续双击会得到 3、4、5…），
        // 取偶数次点击（每个双击手势的第二次按下恒为偶数）保证每次双击恰好 toggle 一次。
        if (listAnimating || item == null || e.getClickCount() < 2 || e.getClickCount() % 2 != 0
                || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (item instanceof GroupItem<?> g) {
            if (e.getX() >= 46) {
                onGroupCheckToggled((GroupItem<PreviewItem>) g);
            }
        } else if (item instanceof PreviewItem it && !it.removed) {
            if (e.getX() >= 50) {
                it.selected.set(!it.selected.get());
                selectionAnchor = it;
            }
        }
    }

    /** 按当前列表统计三态：有效项 = removed == false 的项目（含组内成员） */
    private SelectionState.TriState currentSelectionState() {
        List<PreviewItem> vis = allVisibleItems();
        int total = vis.size();
        int selected = (int) vis.stream().filter(i -> i.selected.get()).count();
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

    /** 数据模型中的所有项目（含组内成员与已移除项） */
    private List<PreviewItem> allPreviewItems() {
        List<PreviewItem> out = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof PreviewItem it) {
                out.add(it);
            } else if (o instanceof GroupItem<?> g) {
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewItem it) {
                        out.add(it);
                    }
                }
            }
        }
        return out;
    }

    /** 全部有效项目（removed == false，含组内成员）。按数据序遍历：折叠组的成员不在显示序中，
     *  若按显示序遍历会导致全选/提取名单/进度统计漏掉折叠组成员（冒烟测试发现，阶段 4 遗留） */
    private List<PreviewItem> allVisibleItems() {
        List<PreviewItem> out = new ArrayList<>();
        for (PreviewItem it : allPreviewItems()) {
            if (!it.removed) {
                out.add(it);
            }
        }
        return out;
    }

    /** 重建父组指针：以数据序为准统一维护 PreviewItem.parentGroup（显示序重建时同步调用） */
    private void rebuildParentPointers() {
        for (Object o : items) {
            if (o instanceof GroupItem<?> g) {
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewItem it) {
                        it.parentGroup = (GroupItem<PreviewItem>) g;
                    }
                }
            } else if (o instanceof PreviewItem it) {
                it.parentGroup = null;
            }
        }
    }

    /** 按数据序重建显示序（ListView 渲染展平列表）。动画期间的外部重建（换目录/模式切换等）先快进完成动画 */
    private void rebuildDisplay() {
        if (listAnimating && !suppressAnimRebuild) {
            finishExpandAnim();
        }
        rebuildParentPointers();
        displayItems.setAll(GroupStructure.flatten(items, this::isRemovedObject));
    }

    /** 组内有效成员数（removed == false） */
    private int visibleMemberCount(GroupItem<PreviewItem> g) {
        return (int) g.getMembers().stream().filter(m -> !m.removed).count();
    }

    /** 条目在所属单元内的可见序号（0 起）：顶层项目按顶层可见项目计数，组内成员按组内可见成员计数（R10） */
    private long unitIndex(PreviewItem item) {
        if (item.parentGroup == null) {
            return items.stream().takeWhile(o -> o != item)
                    .filter(o -> o instanceof PreviewItem it && !it.removed).count();
        }
        GroupItem<PreviewItem> g = item.parentGroup;
        return g.getMembers().stream().takeWhile(m -> m != item)
                .filter(m -> !m.removed).count();
    }

    // ==================== 移除 / 撤销（F2 / F4） ====================

    /** 可见文件数：removed 标记的项目在列表中完全隐藏，不参与任何计数（组条目不计入） */
    private long visibleCount() {
        return allVisibleItems().size();
    }

    /** 显示序中"可见行"判定：有效项目或组条目 */
    private boolean isVisibleRow(Object o) {
        return (o instanceof PreviewItem it && !it.removed) || o instanceof GroupItem<?>;
    }

    private void updateFileCount() {
        fileCountLabel.setText("共 " + visibleCount() + " 个文件");
    }

    /** 列表操作按钮可用性：列表为空时移除禁用；撤销栈为空时撤销禁用；合并为组需勾选集合或选中项目；拆分组仅选中组条目时可用（F6） */
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

    private void updateListButtons() {
        removeButton.setDisable(visibleCount() == 0);
        undoButton.setDisable(undoManager.isEmpty());
        boolean anyChecked = allVisibleItems().stream().anyMatch(i -> i.selected.get());
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        boolean selItem = sel instanceof PreviewItem it && !it.removed;
        groupButton.setDisable(!anyChecked && !selItem);
        ungroupButton.setDisable(!(sel instanceof GroupItem<?>));
        viewSourceButton.setDisable(!selItem);
        // 条目独立参数需求拍板：仅当选中条目生效参数为独立参数时可"应用到全局"
        applyGlobalButton.setDisable(!hasIndependentParams(sel));
        // 清除参数拍板 Q3：勾选集合任一目标带参数（无勾选 → 单选目标）即启用
        boolean anyClearable = anyChecked
                ? allVisibleItems().stream().filter(i -> i.selected.get())
                        .anyMatch(this::hasIndependentParams)
                : hasIndependentParams(sel);
        clearParamsButton.setDisable(!anyClearable);
    }

    /** 开始按钮可用性的唯一派生函数 —— 所有禁用/恢复都必须经由此函数 */
    private void updateStartButtonState() {
        boolean anyMode = textModeToggle.isSelected() || imageModeToggle.isSelected();
        startButton.setDisable(!anyMode || visibleCount() == 0 || isImporting());
    }

    /** 列表结构变化（移除/撤销/导入/建组）后的统一刷新：显示序重建、计数、按钮、序号/徽标、进度、信息区 */
    private void refreshAfterListChange() {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        rebuildDisplay();
        // 显示序重建时选择模型按索引保留选中项，需按对象重新钉住
        if (sel != null && displayItems.contains(sel)) {
            fileListView.getSelectionModel().select(sel);
        }
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
                .filter(i -> i.selected.get()).toList();
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        // D7：无勾选且选中组条目 → 整组移除（组名/参数随组对象保留在撤销记录中）
        if (targets.isEmpty() && sel instanceof GroupItem<?> g) {
            // G2（拍板）：整组移除一律必弹确认（R6"仅列表将空时确认"的有意例外），按钮行与右键菜单共用
            removeGroupWithConfirm((GroupItem<PreviewItem>) g);
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
            // R13 确认框文案：全选含折叠组成员时提示（配合阶段 3 移除确认框）
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "确定移除全部 " + targets.size() + " 个项目？移除后列表为空，无法提取"
                            + collapsedGroupHint(targets),
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("移除确认");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        removeItems(targets);
        log.info("已移除 {} 个项目，剩余可见 {} 个", targets.size(), visibleCount());
        if (visibleCount() == 0) {
            showAlert(Alert.AlertType.INFORMATION, "至少保留一个文件");
        }
    }

    /** 整组移除确认 + 执行（G2 拍板：按钮行与右键菜单统一每次必弹确认；文案区分"列表将空"与折叠提示） */
    private void removeGroupWithConfirm(GroupItem<PreviewItem> group) {
        int memberCount = visibleMemberCount(group);
        String collapsedHint = group.isExpanded()
                ? ""
                : "\n（该组当前已折叠，组内项目在列表中不可见）";
        String msg = memberCount >= visibleCount()
                ? "确定移除整组（" + memberCount + " 个项目）？移除后列表为空，无法提取" + collapsedHint
                : "确定移除整组（" + memberCount + " 个项目）？" + collapsedHint;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("移除确认");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        removeGroup(group);
        log.info("已移除整组 {}（{} 个项目），剩余可见 {} 个", group.getName(), memberCount, visibleCount());
        if (visibleCount() == 0) {
            showAlert(Alert.AlertType.INFORMATION, "至少保留一个文件");
        }
    }

    /** 隐藏式移除：removed=true、selected 重置、压撤销栈；成员全被移除的空组自动销毁（D11），撤销时一并恢复 */
    private void removeItems(List<PreviewItem> targets) {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        int selIndex = displayItems.indexOf(sel);
        for (PreviewItem t : targets) {
            t.removed = true;
            t.selected.set(false);
        }
        // 规则 (c)：从列表移除 → 删该项目缓存目录
        previewCacheLifecycle.onItemsRemoved(targets.stream().map(t -> t.path).toList());
        // 空组自动销毁：降序遍历记录原顶层索引，撤销时按原序恢复
        List<GroupItem<PreviewItem>> destroyed = new ArrayList<>();
        List<Integer> destroyedIdx = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) instanceof GroupItem<?> g) {
                GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
                if (GroupStructure.isEmptyGroup(group, m -> m.removed)) {
                    destroyed.add(group);
                    destroyedIdx.add(i);
                    items.remove(i);
                    log.info("组 {} 已无有效成员，自动销毁", group.getName());
                }
            }
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE, targets,
                null, -1, destroyed, destroyedIdx, List.of()));
        boolean selRemoved = sel != null
                && ((sel instanceof PreviewItem it && targets.contains(it))
                || (sel instanceof GroupItem<?> g && destroyed.contains(g)));
        if (selRemoved) {
            selectNearestVisible(selIndex);
        }
        refreshAfterListChange();
    }

    /** 整组移除（D7）：组内成员全部标记 removed，组条目隐藏；组对象（名/参数）保留在撤销记录中可整体恢复 */
    private void removeGroup(GroupItem<PreviewItem> group) {
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        int selIndex = displayItems.indexOf(sel);
        int idx = items.indexOf(group);
        List<PreviewItem> members = new ArrayList<>(group.getMembers());
        for (PreviewItem m : members) {
            m.removed = true;
            m.selected.set(false);
        }
        // 规则 (c)：整组移除 → 删组内成员缓存目录
        previewCacheLifecycle.onItemsRemoved(members.stream().map(m -> m.path).toList());
        items.remove(group);
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE_GROUP, members,
                group, idx, List.of(group), List.of(idx), List.of()));
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
                GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
                if (!group.isExpanded()) {
                    long cnt = group.getMembers().stream()
                            .filter(m -> !m.removed && targets.contains(m)).count();
                    if (cnt > 0) {
                        parts.add(group.getName() + "（" + cnt + " 个）");
                    }
                }
            }
        }
        return parts.isEmpty()
                ? ""
                : "\n（注意：其中 " + String.join("、", parts) + " 位于已折叠的组内，当前不可见）";
    }

    @FXML
    private void onUndoClicked() {
        if (listAnimating || undoManager.isEmpty()) {
            return;
        }
        UndoManager.Record<PreviewItem> record = undoManager.pop();
        Object selectAfter = null;
        // 结构恢复类操作（REORDER/MERGE_GROUPS）：恢复后生效参数可能变化，受影响项目重新预解析
        List<PreviewItem> reparseAfter = List.of();
        switch (record.type()) {
            case REMOVE, REMOVE_GROUP -> {
                for (PreviewItem it : record.items()) {
                    it.removed = false;
                    it.selected.set(false);
                }
                // 销毁的空组/整组按记录的原顶层索引恢复（降序记录，直接按序插回）
                for (int k = 0; k < record.destroyedGroups().size(); k++) {
                    GroupItem<PreviewItem> g = record.destroyedGroups().get(k);
                    items.add(Math.min(record.destroyedIndexes().get(k), items.size()), g);
                }
                log.info("撤销移除：恢复 {} 个项目、{} 个组", record.items().size(), record.destroyedGroups().size());
            }
            case IMPORT -> {
                Object sel = fileListView.getSelectionModel().getSelectedItem();
                int selIndex = displayItems.indexOf(sel);
                boolean selRemoved = sel != null && record.items().contains(sel);
                for (PreviewItem it : record.items()) {
                    it.removed = true;
                    it.selected.set(false);
                    // 防遗漏：导入项若已进入组（组内成员），一并从成员列表移除
                    GroupItem<PreviewItem> pg = it.parentGroup;
                    if (pg != null) {
                        pg.getMembers().remove(it);
                    }
                }
                // 规则 (c)：撤销导入 = 从列表移除这批项目 → 删其缓存目录
                previewCacheLifecycle.onItemsRemoved(
                        record.items().stream().map(i -> i.path).toList());
                items.removeAll(record.items());
                // 组内项目随撤销导入移除后可能产生空组：直接销毁（撤销导入本身不再记录）
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (items.get(i) instanceof GroupItem<?> g
                            && GroupStructure.isEmptyGroup((GroupItem<PreviewItem>) g, m -> m.removed)) {
                        items.remove(i);
                    }
                }
                if (selRemoved) {
                    selectNearestVisible(selIndex);
                }
                log.info("撤销导入：本次新增的 {} 个项目、{} 个自动创建的组已移除",
                        record.items().size(), record.groups().size());
            }
            case GROUP, REORDER, MERGE_GROUPS -> {
                // 撤销恢复拖拽前结构（顶层顺序、组位置、各组内顺序），组对象复用（组名/参数保留）
                GroupStructure.restoreStructure(items, record.structureBefore());
                if (record.type() == UndoManager.RecordType.GROUP) {
                    PreviewItem firstVisible = record.items().stream()
                            .filter(i -> !i.removed).findFirst().orElse(null);
                    if (firstVisible != null) {
                        fileListView.getSelectionModel().select(firstVisible);
                    }
                    log.info("撤销合并为组：组 {} 已解散，成员回到原位置", record.group().getName());
                } else {
                    reparseAfter = new ArrayList<>();
                    for (PreviewItem it : record.items()) {
                        if (!it.removed) {
                            clearPreviewCache(it);
                            reparseAfter.add(it);
                        }
                    }
                    log.info("撤销{}：列表结构已恢复拖拽前状态",
                            record.type() == UndoManager.RecordType.REORDER ? "拖拽排序" : "组合并");
                }
            }
            case UNGROUP -> {
                // F6：组重建——成员从顶层移除并重新划入，组对象（组名/组参数）原样插回原位置
                GroupStructure.restoreGroup(items, record.group(), record.groupIndex(), record.items());
                // 条目独立参数生命周期拍板：撤销拆分后成员回组，拆分时复制的条目级参数清除
                for (PreviewItem m : record.items()) {
                    m.textItemParams = null;
                    m.imageItemParams = null;
                }
                selectAfter = record.group();
                log.info("撤销拆分：组 {} 已重建（{} 个项目），组名与组参数恢复",
                        record.group().getName(), record.items().size());
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
                            if (im) {
                                // 规则 (a)：撤销恢复旧参数 = 生效参数变化 → 删该项目缓存目录
                                previewCacheLifecycle.onParamsChanged(it.path);
                            }
                            startPreParseFor(List.of(it));
                            break;
                        }
                    }
                } else if (record.paramTargetGroupNo() != null) {
                    for (Object o : items) {
                        if (o instanceof GroupItem<?> g
                                && ((GroupItem<PreviewItem>) g).getGroupNo() == record.paramTargetGroupNo()) {
                            GroupItem<PreviewItem> grp = (GroupItem<PreviewItem>) g;
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
                            if (im) {
                                // 规则 (a)：撤销恢复组参数 → 成员生效参数变化 → 删其缓存目录
                                previewCacheLifecycle.onParamsChangedBatch(
                                        members.stream().map(m -> m.path).toList());
                            }
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
                    if (im) {
                        // 规则 (a)：撤销恢复全局参数 → 全部条目生效参数可能变化 → 保守全删
                        previewCacheLifecycle.onParamsChangedBatch(
                                allPreviewItems().stream().map(i -> i.path).toList());
                    }
                    refreshAfterParamsChanged();
                }
                fileListView.refresh();
                updateInfoArea(currentSelection());
                updateExtractBanner(currentSelection());
                updateListButtons();
                log.info("撤销设置提取参数：{}模式参数已恢复", im ? "图片" : "文本");
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
                        GroupItem<PreviewItem> grp = (GroupItem<PreviewItem>) g;
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
                if (im) {
                    // 规则 (a)：撤销恢复被清除的参数 → 受影响条目生效参数变化 → 删其缓存目录
                    previewCacheLifecycle.onParamsChangedBatch(
                            affected.stream().map(i -> i.path).toList());
                }
                refreshAfterTargetedParamsChange(affected);
                updateListButtons();
                updateExtractBanner(currentSelection());
                log.info("撤销清除参数：{}模式 {} 个条目、{} 个组的参数已恢复",
                        im ? "图片" : "文本", restoredItems.size(), groupBefore.size());
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
                        GroupItem<PreviewItem> grp = (GroupItem<PreviewItem>) g;
                        if (groupBefore.containsKey(grp.getGroupNo())) {
                            AppConfig p = groupBefore.get(grp.getGroupNo());
                            if (im) {
                                grp.setImageParamsCache(p);
                            } else {
                                grp.setTextParamsCache(p);
                            }
                        }
                    }
                }
                clearExtractOutcomes(allPreviewItems());
                if (im) {
                    // 规则 (a)：撤销应用到全局 → 全部条目生效参数可能变化 → 保守全删
                    previewCacheLifecycle.onParamsChangedBatch(
                            allPreviewItems().stream().map(i -> i.path).toList());
                }
                refreshAfterParamsChanged();
                updateListButtons();
                updateExtractBanner(currentSelection());
                log.info("撤销应用到全局：{}模式参数与全部独立参数已恢复", im ? "图片" : "文本");
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
                .filter(i -> i.selected.get()).toList();
        Object sel = fileListView.getSelectionModel().getSelectedItem();
        if (targets.isEmpty()) {
            if (sel instanceof PreviewItem it && !it.removed) {
                targets = List.of(it);
            } else {
                return;
            }
        }
        List<Object> before = GroupStructure.snapshotStructure(items);
        int groupNo = GroupStructure.nextGroupNo(groupNoCounter);
        groupNoCounter = groupNo;
        GroupItem<PreviewItem> group = new GroupItem<>(groupNo, "组" + groupNo);
        registerGroupNameRefresh(group);

        // 插入槽位：最早目标在显示序中的位置（顶层项目=自身位置；组内成员=其组之后），拆出时同步修正索引
        int insertIdx = Integer.MAX_VALUE;
        for (PreviewItem t : targets) {
            insertIdx = Math.min(insertIdx,
                    t.parentGroup == null ? items.indexOf(t) : items.indexOf(t.parentGroup) + 1);
        }
        for (PreviewItem t : targets) {
            GroupItem<PreviewItem> g = t.parentGroup;
            if (g == null) {
                int i = items.indexOf(t);
                items.remove(t);
                if (i < insertIdx) {
                    insertIdx--;
                }
            } else {
                g.getMembers().remove(t);
                if (GroupStructure.isEmptyGroup(g, m -> m.removed)) {
                    int i = items.indexOf(g);
                    items.remove(g);
                    if (i < insertIdx) {
                        insertIdx--;
                    }
                    log.info("组 {} 已无有效成员，自动销毁", g.getName());
                }
            }
        }
        group.getMembers().addAll(targets);
        // 条目独立参数生命周期拍板：合并为组清除成员条目级参数（新组参数未设，继承全局）
        for (PreviewItem t : targets) {
            t.textItemParams = null;
            t.imageItemParams = null;
        }
        items.add(Math.max(0, Math.min(insertIdx, items.size())), group);
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP, targets, group,
                items.indexOf(group), List.of(), List.of(), before));
        // 拍板：合并为组后勾选集合复位，避免勾选残留导致下一批建组误合并
        updatingSelection = true;
        try {
            for (PreviewItem t : targets) {
                t.selected.set(false);
            }
        } finally {
            updatingSelection = false;
        }
        fileListView.getSelectionModel().select(group);
        refreshAfterListChange();
        log.info("已创建组 {}（{} 个项目）", group.getName(), targets.size());
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
        GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
        int topIdx = items.indexOf(group);
        if (topIdx < 0) {
            return;
        }
        int displayIdx = displayItems.indexOf(group);
        // 拆分为整组操作，与展开/折叠无关（GroupStructure.dissolveGroup 不依赖 expanded）
        List<PreviewItem> members = GroupStructure.dissolveGroup(items, group);
        // 条目独立参数生命周期拍板：拆分组后成员回到顶层，组参数集复制为成员条目级参数
        for (PreviewItem m : members) {
            m.textItemParams = group.getTextParamsCache();
            m.imageItemParams = group.getImageParamsCache();
        }
        undoManager.push(new UndoManager.Record<>(UndoManager.RecordType.UNGROUP, members,
                group, topIdx, List.of(), List.of(), List.of()));
        refreshAfterListChange();
        // 选中的组已解散：跳转到原组位置的最近有效行（拆分后为原组第一个成员）
        selectNearestVisible(displayIdx);
        log.info("已解散组 {}（{} 个项目），成员回到顶层", group.getName(), members.size());
    }

    /** 组复选框点击（F1 2.5）：组内全选 → 全不选；全不选或半选 → 全选组内全部成员 */
    private void onGroupCheckToggled(GroupItem<PreviewItem> g) {
        if (listAnimating) {
            return;
        }
        int total = visibleMemberCount(g);
        int selected = (int) g.getMembers().stream()
                .filter(m -> !m.removed && m.selected.get()).count();
        boolean target = SelectionState.computeToggleTarget(
                SelectionState.computeState(selected, total));
        updatingSelection = true;
        for (PreviewItem m : g.getMembers()) {
            if (!m.removed) {
                m.selected.set(target);
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
    private void toggleGroupExpanded(GroupItem<PreviewItem> g) {
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
            log.warn("折叠快照失败，退化为瞬时重建：{}", ex.getMessage(), ex);
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
        pt.setOnFinished(e -> finishExpandAnim());
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
            log.warn("展开快照失败，退化为瞬时重建：{}", ex.getMessage(), ex);
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
        pt.setOnFinished(e -> finishExpandAnim());
        pt.play();
    }

    /** 折叠/展开动画结束（正常完成或外部重建快进）：清理快照、恢复 cell、解锁 */
    private void finishExpandAnim() {
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
            log.info("组名冲突：{} 已被其它组使用，自动改为 {}", trimmed, unique);
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
                for (Object m : ((GroupItem<?>) g).getMembers()) {
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
            it.badge.set(null);
            return n;
        }
        AppConfig eff = effectiveForObject(it);
        boolean numbering = isTextMode() && eff != null && eff.isAddChapterNumber();
        if (numbering && it.status.get() == Status.READY && it.chapterEnabled.get()) {
            it.badge.set(String.valueOf(n));
            return n + 1;
        }
        it.badge.set(null);
        return n;
    }

    private void updateAdjustCheck(Object item) {
        // 第三行：文本模式 = 章节复选框（逐文件调整）；图片模式 = 输出结果展示（全局打包配置）；组条目两者均不显示
        boolean isItem = item instanceof PreviewItem it && !it.removed;
        AppConfig eff = isItem ? effectiveForObject(item) : null;
        boolean checkVisible = isTextMode() && eff != null && isItem && eff.isAddChapterNumber();
        adjustCheck.setVisible(checkVisible);
        adjustCheck.setManaged(checkVisible);
        if (checkVisible) {
            PreviewItem it = (PreviewItem) item;
            adjustCheck.setText("添加章节序号");
            updatingAdjustCheck = true;
            adjustCheck.setSelected(it.chapterEnabled.get());
            updatingAdjustCheck = false;
        }
        boolean resultVisible = !isTextMode() && eff != null && isItem;
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
            attachGroupNameEditor((GroupItem<PreviewItem>) g);
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
            switch (it.status.get()) {
                case WAITING -> titleInfoLabel.setText("提取标题：解析中…");
                case FAILED, EMPTY -> titleInfoLabel.setText("提取标题：—");
                case READY -> titleInfoLabel.setText(it.cachedTitle != null
                        ? "提取标题：" + it.cachedTitle
                        : "提取标题：未提取到标题");
            }
        }
        outputNameLabel.setVisible(true);
        outputNameLabel.setManaged(true);
        if (isTextMode()) {
            if (params.isSeparateFiles()) {
                outputNameLabel.setText("输出文件：" + stripMhtExt(it.name) + ".txt");
            } else {
                String inputDirName = new File(inputFolderField.getText()).getName();
                if (inputDirName == null || inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                outputNameLabel.setText("输出文件：" + inputDirName + ".txt（合并输出，所有文件共用）");
            }
        } else if (params.isSingleOutputMode()) {
            String folderName = mergedFolderName(it);
            outputNameLabel.setText("输出目录：" + folderName + "（合并输出，所有文件共用）");
        } else {
            String dirName = separateDirName(it);
            outputNameLabel.setText("输出目录：" + dirName);
        }
    }

    private String separateDirName(PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        if (params != null && params.isEnableNameExtraction()) {
            if (item.status.get() == Status.WAITING) {
                return "解析中…";
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
                if (first.status.get() == Status.WAITING) {
                    return "解析中…";
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
            GroupItem<PreviewItem> group = (GroupItem<PreviewItem>) g;
            String info = "组信息\n组名：" + group.getName()
                    + "\n组内项目数：" + visibleMemberCount(group) + " 项"
                    + "\n参数：" + groupParamStatusText(group);
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
            switch (it.status.get()) {
                case WAITING -> textPreviewArea.setText("解析中...");
                case FAILED -> textPreviewArea.setText("解析失败：" + it.errorMsg.get());
                case EMPTY -> textPreviewArea.setText("内容为空，提取时该文件将被跳过");
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
            if (it.status.get() == Status.FAILED) {
                imageHintLabel.setText("解析失败：" + it.errorMsg.get());
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(false);
                imageScrollPane.setManaged(false);
            } else if (it.cachedThumbs != null) {
                renderThumbnails(it);
            } else if (imageLoadActive && loadingItem == it) {
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

    /** 组参数状态提示（6.5）：组条目选中时预览区显示"该组使用独立参数"或"该组继承全局参数" */
    private String groupParamStatusText(GroupItem<PreviewItem> g) {
        boolean imageMode = imageModeToggle.isSelected();
        AppConfig own = imageMode ? g.getImageParamsCache() : g.getTextParamsCache();
        if (own != null) {
            return "该组使用独立参数";
        }
        AppConfig global = imageMode ? imageParamsCache : textParamsCache;
        return global != null ? "该组继承全局参数" : "该组继承全局参数（全局参数尚未确认）";
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
                    + "\n……【提取内容】……\n"
                    + text.substring(text.length() - 5000)
                    + tail);
        }
    }

    /** 重置图片区：hint 为 null 时显示默认按需预览说明 */
    private void resetImageArea(String hint, boolean showPreviewButton) {
        imageHintLabel.setText(hint != null
                ? hint
                : "图片预览需要加载整个 MHT 文件，为节省内存开销，采用按需预览。点击'查看预览效果'按钮可查看提取效果。");
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
        Label nameLabel = new Label(thumbFileName(entry, item));
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(150);
        nameLabel.setStyle("-fx-font-size: 0.85em;");
        box.getChildren().add(nameLabel);
        return box;
    }

    /** 合并输出且不重命名时，文件名按当前列表位置重算 f%02d_ 前缀（拖拽重排后同步刷新，按条目生效参数判定） */
    private String thumbFileName(ThumbEntry entry, PreviewItem item) {
        AppConfig params = effectiveForObject(item);
        boolean mergedNoRename = params != null && params.isSingleOutputMode()
                && !params.isEnableBatchRenaming() && visibleCount() > 1;
        return mergedNoRename
                ? String.format("f%02d_", (int) unitIndex(item) + 1) + entry.coreName()
                : entry.coreName();
    }

    /** 解码单张缩略图：WebP 走 JWebP 缩放解码（150 边界），其余走 JavaFX 原生缩放解码；失败返回 image=null */
    private ThumbEntry decodeThumb(ImageExtractor.ImageData imageData, int index, AppConfig params) {
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
        return new ThumbEntry(img, coreName);
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

        final PreviewCacheKey cacheKey;
        try {
            cacheKey = PreviewCacheKey.compute(Path.of(item.path),
                    params.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        } catch (IOException e) {
            log.error("计算缓存键失败：{}", item.path, e);
            showAlert(Alert.AlertType.ERROR, "读取文件信息失败：" + e.getMessage());
            return;
        }

        final int token = requestToken;
        imageLoadActive = true;
        loadingItem = item;
        imageHintLabel.setText("正在加载...");
        previewEffectButton.setDisable(true);
        // 相邻修复：提取运行期间进度条/状态栏归提取任务独占，图片加载不动它们
        if (!running.get()) {
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            setProgressBarActivity(null);   // 预览加载 = 默认色（完成后回落预解析显示）
            statusLabel.setText("正在加载图片：" + item.name);
        }

        Thread t = new Thread(() -> {
            try {
                // 每项目写锁（§2.6(6)）：同项目「查缓存→提取→落盘」串行，不同项目并行
                final PreviewCacheMeta meta;
                synchronized (previewCacheLifecycle.lockFor(cacheKey.absolutePath())) {
                    meta = loadOrWritePreviewCache(item, params, cacheKey, token);
                }
                if (meta == null) {
                    return;   // 代次失效：放弃（未写缓存）
                }
                // C2：缩略图从磁盘真实图片逐张解码，内存只留缩略图 + meta，不驻留全尺寸字节
                List<ThumbEntry> thumbs = new ArrayList<>();
                Path cacheDir = previewCacheLifecycle.store().dirFor(cacheKey);
                for (int i = 0; i < meta.images().size(); i++) {
                    if (token != requestToken) {
                        return;
                    }
                    PreviewCacheMeta.ImageEntry entry = meta.images().get(i);
                    final ThumbEntry thumb;
                    try {
                        byte[] data = Files.readAllBytes(cacheDir.resolve(entry.file()));
                        thumb = decodeThumb(new ImageExtractor.ImageData(
                                entry.originalName(), entry.sourceId(), data), i, params);
                    } catch (IOException e) {
                        log.warn("读取缓存图片失败，跳过缩略图：{}", cacheDir.resolve(entry.file()), e);
                        continue;
                    }
                    thumbs.add(thumb);
                    Platform.runLater(() -> {
                        if (token != requestToken
                                || fileListView.getSelectionModel().getSelectedItem() != item) {
                            return;
                        }
                        appendThumb(item, thumb);
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
                                log.warn("删除失效预览缓存失败：{}", cacheKey.toDirName(), e);
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
                        item.status.set(Status.EMPTY);
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
                    imageHintLabel.setText("解析失败：" + e.getMessage());
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

    /**
     * 预览缓存读取或重建（必须在每项目写锁内调用；§2.6(8)：只有点「查看预览效果」才建立缓存，预解析不写）。
     * 命中 → 返回磁盘 meta；未命中 → 解析 + 提取 + 原子落盘（P11）。
     * meta 损坏（有 .complete 但解析失败）→ 删目录重建（会话 8 遗留风险兜底：否则同 key 下反复报错无法恢复）。
     * 代次失效（token 变化）时不写缓存并返回 null（放弃本次预览）。
     */
    private PreviewCacheMeta loadOrWritePreviewCache(PreviewItem item, AppConfig params,
                                                     PreviewCacheKey cacheKey, int token) throws Exception {
        try {
            Optional<PreviewCacheMeta> hit = previewCacheLifecycle.store().load(cacheKey);
            if (hit.isPresent()) {
                log.info("预览缓存命中：{}", item.name);
                return hit.get();
            }
        } catch (IOException e) {
            log.warn("预览缓存元数据损坏，删除重建：{}（{}）", cacheKey.toDirName(), e.getMessage());
            try {
                previewCacheLifecycle.store().delete(cacheKey);
            } catch (IOException del) {
                log.warn("删除损坏缓存失败：{}", del.getMessage());
            }
        }

        MimeParser parser = new MimeParser();
        MimeParser.ParsedMht parsed = parser.parse(item.path, false, () -> false, null, null);
        ImageExtractor imageExtractor = new ImageExtractor(parsed,
                params.getImageNameStartMarker(), params.getImageNameEndMarker(),
                params.getImageRegionStartMarker(), params.getImageRegionEndMarker());
        List<ImageExtractor.ImageData> images = imageExtractor.extractImages(null);
        if (token != requestToken) {
            return null;
        }
        // 元数据先行构建；imagesWriter 写盘文件名必须与 meta.images[].file 一致（store.load 据此校验）
        List<String> plainNames = new ArrayList<>();
        List<PreviewCacheMeta.ImageEntry> entries = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ImageExtractor.ImageData img = images.get(i);
            String ext = SanitizeUtils.detectExtensionByMagicNumber(img.getData(), "jpg");
            String plain = String.format("%04d.%s", i + 1, ext);
            plainNames.add(plain);
            entries.add(new PreviewCacheMeta.ImageEntry(
                    PreviewCacheStore.IMAGES_DIR_NAME + "/" + plain,
                    img.getContentId(), img.getFilename(), ext, List.of()));
        }
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(cacheKey.absolutePath(), cacheKey.size(), cacheKey.mtime()),
                cacheKey.paramFingerprint(), true, entries,
                imageExtractor.getLastWarnings());
        previewCacheLifecycle.store().write(cacheKey, meta, imagesDir -> {
            for (int i = 0; i < images.size(); i++) {
                Files.write(imagesDir.resolve(plainNames.get(i)), images.get(i).getData());
            }
        });
        log.info("预览图片已落盘缓存：{}（{} 张）", item.name, images.size());
        return meta;
    }

    // ==================== 预解析（文本全量 / 图片轻量） ====================

    private void startPreParse() {
        List<PreviewItem> targets = allVisibleItems();
        if (targets.isEmpty()) {
            return;
        }
        // 提取运行期间进度条归提取任务独占（相邻修复），预解析照常跑但不动进度条
        if (!running.get()) {
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

    private void updateProgressStatus(int token) {
        if (token != parseToken) {
            return;
        }
        // 相邻修复（拍板 2026-09-24）：提取运行期间进度条/状态栏归提取任务独占，
        // 预解析完成等回调一律不更新（避免覆盖提取进度显示）
        if (running.get()) {
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
        long done = vis.stream().filter(i -> i.status.get() != Status.WAITING).count();
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
        statusLabel.setText("解析完成 " + done + "/" + total);
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

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.showAndWait();
    }

    // ==================== Tracing Agent 演练 ====================

    /** 供 Tracing Agent 自动演练所有 UI 功能，捕获完整的反射/JNI 元数据 */
    public void exerciseAllFeatures() throws Exception {
        // Phase 0: 清场——移除启动时 GUI 状态恢复导入的既有文件并重置撤销栈，保证后续演练脚本可预测
        if (!items.isEmpty()) {
            items.clear();
            undoManager.clear();
            refreshAfterListChange();
        }
        // 统一导入为异步：等待启动时可能在途的导入收尾，再二次清场（防 applyImportPlan 回填列表）
        waitForImportDone();
        if (!items.isEmpty()) {
            items.clear();
            undoManager.clear();
            refreshAfterListChange();
        }

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
                null, false, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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
                null, true, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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
                null, true, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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

        // Phase 8: 增量导入 + 复选框 + 移除 + 撤销（阶段 1–3）
        try {
            java.nio.file.Path importDir = java.nio.file.Files.createTempDirectory("mht-import-agent");
            createSampleMht(importDir, "import01.mht");
            createSampleMht(importDir, "import02.mht");
            textModeToggle.setSelected(true);
            Thread.sleep(200);
            inputFolderField.setText(importDir.toString());
            Thread.sleep(1200);
            // 增量导入：新增第三个文件后再次导入同目录 → 仅新增 1 个（IMPORT 入撤销栈）
            createSampleMht(importDir, "import03.mht");
            importFromFolder(importDir.toString());
            Thread.sleep(1200);
            // 背景色三态之"无生效参数"（NONE）：临时清空全局缓存走渲染路径后恢复
            AppConfig savedTextCache = textParamsCache;
            textParamsCache = null;
            fileListView.refresh();
            Thread.sleep(200);
            textParamsCache = savedTextCache;
            fileListView.refresh();
            Thread.sleep(200);
            // 列头全选/全不选（三态联动）
            selectAllCheck.fire();
            Thread.sleep(200);
            selectAllCheck.fire();
            Thread.sleep(200);
            // 部分勾选 → 移除（不弹确认框）→ 撤销移除 → 撤销两次增量导入
            List<PreviewItem> vis = allVisibleItems();
            if (!vis.isEmpty()) {
                vis.get(0).selected.set(true);
                Thread.sleep(200);
                removeButton.fire();
                Thread.sleep(300);
                undoButton.fire();
                Thread.sleep(300);
                undoButton.fire();
                Thread.sleep(300);
                undoButton.fire();
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
            inputFolderField.setText(groupDir.toString());
            Thread.sleep(1500);
            List<PreviewItem> vis = allVisibleItems();
            if (vis.size() >= 4) {
                allVisibleItems().forEach(i -> i.selected.set(false));
                vis.get(0).selected.set(true);
                vis.get(1).selected.set(true);
                groupButton.fire();
                Thread.sleep(400);
                allVisibleItems().forEach(i -> i.selected.set(false));
                List<PreviewItem> tops = allVisibleItems().stream()
                        .filter(i -> i.parentGroup == null).toList();
                tops.get(0).selected.set(true);
                tops.get(1).selected.set(true);
                groupButton.fire();
                Thread.sleep(400);
                List<GroupItem<PreviewItem>> groups = topLevelGroups();
                if (groups.size() == 2) {
                    GroupItem<PreviewItem> g1 = groups.get(0);
                    GroupItem<PreviewItem> g2 = groups.get(1);
                    // 折叠/展开动画演练：触发后立即快进完成（演练运行于 FX 线程，sleep 会阻塞 pulse
                    // 使 Timeline 无法推进；动画真实推进由临时 JUnit 测试与人工走查验证）
                    toggleGroupExpanded(g1);
                    finishExpandAnim();
                    toggleGroupExpanded(g1);
                    finishExpandAnim();
                    Thread.sleep(300);
                    // 组名编辑：g2 改成与 g1 重名 → 自动追加序号（R7）
                    fileListView.getSelectionModel().select(g1);
                    Thread.sleep(300);
                    groupNameField.setText("演练分组");
                    Thread.sleep(300);
                    fileListView.getSelectionModel().select(g2);
                    Thread.sleep(300);
                    groupNameField.setText("演练分组");
                    Thread.sleep(300);
                    // 组参数（文本模式）：g1 确认独立参数 → 深绿背景 + 组内重解析
                    ParamDialogController groupDialog = ParamDialogController.showDirect(
                            null, false, textParamsCache, imageParamsCache,
                            new ParamScope.GroupTarget(g1));
                    Thread.sleep(300);
                    groupDialog.enableTitleExtractCheck.setSelected(true);
                    groupDialog.enableTemplateCheck.setSelected(false);
                    groupDialog.confirmButton.fire();
                    Thread.sleep(300);
                    if (groupDialog.isConfirmed()) {
                        applyGroupParams(g1, false, groupDialog.getResult());
                    }
                    fileListView.refresh();
                    Thread.sleep(400);
                    // 拆分组 + 撤销拆分（组名/参数恢复）→ 撤销组参数（PARAM_SET）→ 撤销两次建组
                    fileListView.getSelectionModel().select(g1);
                    Thread.sleep(200);
                    ungroupButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                }
            }
        } catch (Exception e) {
            log.warn("阶段 4–6 演练失败：{}", e.getMessage(), e);
        }

        // Phase 10: 拖拽（performDrop 程序化驱动，覆盖组内排序/跨组/出组/组行移动/两组合并/非法落点，阶段 7）
        try {
            List<PreviewItem> vis = allVisibleItems();
            if (vis.size() >= 4) {
                allVisibleItems().forEach(i -> i.selected.set(false));
                vis.get(0).selected.set(true);
                vis.get(1).selected.set(true);
                groupButton.fire();
                Thread.sleep(400);
                allVisibleItems().forEach(i -> i.selected.set(false));
                List<PreviewItem> tops = allVisibleItems().stream()
                        .filter(i -> i.parentGroup == null).toList();
                tops.get(0).selected.set(true);
                tops.get(1).selected.set(true);
                groupButton.fire();
                Thread.sleep(400);
                List<GroupItem<PreviewItem>> groups = topLevelGroups();
                if (groups.size() == 2) {
                    GroupItem<PreviewItem> g1 = groups.get(0);
                    GroupItem<PreviewItem> g2 = groups.get(1);
                    // 组内排序：g1 第二个成员拖到第一个成员之前
                    performDrop(visibleMembersOf(g1).get(1), visibleMembersOf(g1).get(0),
                            DropResolver.Zone.TOP, null);
                    rebuildDisplay();
                    Thread.sleep(300);
                    // 跨组入组末尾：g1 第一个成员拖到 g2 条目 INTO
                    performDrop(visibleMembersOf(g1).get(0), g2, DropResolver.Zone.INTO, null);
                    rebuildDisplay();
                    Thread.sleep(300);
                    // 出组到顶层：g2 一个成员拖到 g1 组行下方空隙
                    performDrop(visibleMembersOf(g2).get(0), g1, DropResolver.Zone.BOTTOM, null);
                    rebuildDisplay();
                    Thread.sleep(300);
                    // 组行移动：g2 拖到 g1 之前
                    performDrop(g2, g1, DropResolver.Zone.TOP, null);
                    rebuildDisplay();
                    Thread.sleep(300);
                    // 两组合并：g2 拖到 g1 条目 INTO
                    performDrop(g2, g1, DropResolver.Zone.INTO, null);
                    rebuildDisplay();
                    Thread.sleep(300);
                    // 非法组合：组拖到自身成员行 → 无操作
                    performDrop(g1, visibleMembersOf(g1).get(0), DropResolver.Zone.TOP, null);
                    rebuildDisplay();
                    Thread.sleep(200);
                    // 撤销组合并 → 撤销组行移动 → 撤销出组 → 撤销入组 → 撤销组内排序
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
                    Thread.sleep(300);
                    undoButton.fire();
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
            importFromFolder(srcDir2.toString());
            Thread.sleep(1200);
            String sessionDir = new File(System.getProperty("java.io.tmpdir"), "mht-agent-session").getPath();
            List<OutputUnitPlanner.OutputUnit> textUnits = OutputUnitPlanner.plan(
                    items, ITEM_VIEW, false, textParamsCache, imageParamsCache, sessionDir);
            log.info("Agent 文本输出单元计划：{}",
                    textUnits.stream().map(u -> u.label() + "(" + u.fileCount() + ")").toList());
            List<OutputUnitPlanner.OutputUnit> imageUnits = OutputUnitPlanner.plan(
                    items, ITEM_VIEW, true, textParamsCache, imageParamsCache, sessionDir);
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
        textModeToggle.setSelected(false);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 13: 收尾
        textModeToggle.setSelected(true);
        imageModeToggle.setSelected(false);
        Thread.sleep(300);

        // Phase 14: 导入相关（统一导入改造）：高亮样式类 + 盘符根目录判定（不真正扫描盘符）
        updateImportDropHighlight(true);
        Thread.sleep(100);
        clearImportDropHighlight();
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
        while (isImporting() && System.currentTimeMillis() < deadline) {
            pumpFxEvents();
            Thread.sleep(20);
        }
    }

    /** 自动化测试：依次执行所有功能并记录结果到文件（test.mode=true 触发） */
    public void runAutomatedTests() {
        String logPath = "logs/test-results.txt";
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(logPath))) {
            log.info("=== Starting automated tests ===");
            out.println("=== MHT Extractor Automated Test Results ===");
            out.println("Time: " + java.time.LocalDateTime.now()); out.println();
            int p = 0, f = 0;

            // 清场：移除启动时 GUI 状态恢复导入的既有文件并重置撤销栈（保证断言可预测）
            if (!items.isEmpty()) {
                items.clear();
                undoManager.clear();
                refreshAfterListChange();
            }
            // 统一导入为异步：等待启动时可能在途的导入收尾，再二次清场（防 applyImportPlan 回填列表）
            waitForImportDone();
            if (!items.isEmpty()) {
                items.clear();
                undoManager.clear();
                refreshAfterListChange();
            }

            // 临时样例目录（不依赖固定测试数据路径）
            java.nio.file.Path textDir = java.nio.file.Files.createTempDirectory("mht-auto-text");
            java.nio.file.Path imageDir = java.nio.file.Files.createTempDirectory("mht-auto-image");
            createSampleMht(textDir, "t01.mht");
            createSampleMht(textDir, "t02.mht");
            createSampleMht(imageDir, "i01.mht");
            createSampleMht(imageDir, "i02.mht");

            try { textModeToggle.setSelected(true); Thread.sleep(300); out.println("[PASS] Text mode toggle"); p++;
            } catch (Exception e) { out.println("[FAIL] Text mode: " + e.getMessage()); f++; }

            try { imageModeToggle.setSelected(true); Thread.sleep(300); out.println("[PASS] Image mode toggle"); p++;
            } catch (Exception e) { out.println("[FAIL] Image mode: " + e.getMessage()); f++; }

            // 文本参数弹窗：开关联动 + 确定生效
            try { textModeToggle.setSelected(true); Thread.sleep(200);
                ParamDialogController textDialog = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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
                        null, true, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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

            // 增量导入：先导入 2 个，追加 1 个后再次导入 → 仅新增 1 个
            try { textModeToggle.setSelected(true); Thread.sleep(200);
                inputFolderField.setText(textDir.toString()); Thread.sleep(1200);
                waitForImportDone();
                long before = visibleCount();
                createSampleMht(textDir, "t03.mht");
                importFromFolder(textDir.toString()); Thread.sleep(500);
                waitForImportDone();
                if (visibleCount() != before + 1) {
                    throw new IllegalStateException("增量导入数量不符：" + before + " -> " + visibleCount());
                }
                out.println("[PASS] Incremental import"); p++;
            } catch (Exception e) { out.println("[FAIL] Incremental import: " + e.getMessage()); f++; }

            // 撤销导入后同目录可重新导入（回归：撤销导入曾清空列表且无法重新导入）
            try { long before = visibleCount();
                undoButton.fire(); Thread.sleep(400);
                if (visibleCount() != before - 1) {
                    throw new IllegalStateException("撤销导入数量不符：" + before + " -> " + visibleCount());
                }
                importFromFolder(textDir.toString()); Thread.sleep(500);
                waitForImportDone();
                if (visibleCount() != before) {
                    throw new IllegalStateException("撤销导入后重新导入失败：" + visibleCount());
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

                int before = (int) visibleCount();
                int groupsBefore = topLevelGroups().size();

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
                applyImportPlan(plan); Thread.sleep(600);
                if (visibleCount() != before + 6) throw new IllegalStateException("导入总数不符：" + visibleCount()); // 2 根 + 4 组内
                if (topLevelGroups().size() != groupsBefore + 4) throw new IllegalStateException("组数不符");
                boolean noMhtGroup = topLevelGroups().stream().anyMatch(g -> "noMhtHere".equals(g.getName()));
                if (noMhtGroup) throw new IllegalStateException("本层无 MHT 的目录不应建组");
                if (topLevelGroups().stream().noneMatch(g -> "sub2(2)".equals(g.getName()))) {
                    throw new IllegalStateException("同名文件夹组名未唯一化");
                }
                out.println("[PASS] Unified import: recursion + layering + dedupe naming"); p++;
            } catch (Exception e) { out.println("[FAIL] Unified import: " + e.getMessage()); f++; }

            // 统一导入：一次撤销撤整批（含自动创建的组）
            try {
                int itemsBefore = (int) visibleCount();
                int groupsBefore = topLevelGroups().size();
                undoButton.fire(); Thread.sleep(600);
                if (visibleCount() != itemsBefore - 6) throw new IllegalStateException("整批撤销项目数不符：" + visibleCount());
                if (topLevelGroups().size() != groupsBefore - 4) throw new IllegalStateException("整批撤销未带走自动创建的组");
                out.println("[PASS] Unified import: single undo removes whole batch (items + groups)"); p++;
            } catch (Exception e) { out.println("[FAIL] Unified import undo: " + e.getMessage()); f++; }

            // 统一导入：重复导入同目录无新文件 → 列表不变、不建组、不清既有参数（RISK-1）
            try {
                java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mht-auto-import");
                java.nio.file.Path sub1 = java.nio.file.Files.createDirectories(root.resolve("sub1"));
                createSampleMht(root, "r1.mht");
                createSampleMht(sub1, "s1.mht");
                int itemsBefore = (int) visibleCount();
                int groupsBefore = topLevelGroups().size();
                importByPaths(List.of(root.toString()), "自动化重复导入", false); Thread.sleep(800);
                waitForImportDone();
                if (visibleCount() != itemsBefore + 2 || topLevelGroups().size() != groupsBefore + 1) {
                    throw new IllegalStateException("首次导入目录失败");
                }
                // 为导入条目设置条目级参数 → 重复导入不得清空（RISK-1 拍板）
                PreviewItem r1 = allPreviewItems().stream()
                        .filter(i -> i.path.endsWith("r1.mht")).findFirst().orElse(null);
                if (r1 == null) {
                    throw new IllegalStateException("未找到导入条目");
                }
                r1.textItemParams = new AppConfig();
                importByPaths(List.of(root.toString()), "自动化重复导入", false); Thread.sleep(800);
                waitForImportDone();
                if (visibleCount() != itemsBefore + 2 || topLevelGroups().size() != groupsBefore + 1) {
                    throw new IllegalStateException("重复导入不应改变列表");
                }
                if (r1.textItemParams == null) {
                    throw new IllegalStateException("重复导入清空了既有条目级参数");
                }
                // 清场：撤销本次导入（后续测试段假设列表为 3 个顶层项目、无组）
                undoButton.fire(); Thread.sleep(600);
                out.println("[PASS] Unified import: re-import is a no-op"); p++;
            } catch (Exception e) { out.println("[FAIL] Unified import re-import: " + e.getMessage()); f++; }

            // 按钮状态：导入期间禁用 + 收尾恢复
            try {
                importPending = new ImportSession(List.of(), "自动化按钮状态", false);
                updateStartButtonState();
                if (!startButton.isDisabled()) throw new IllegalStateException("导入期间开始提取应禁用");
                importPending = null;
                updateStartButtonState();
                boolean anyMode = textModeToggle.isSelected() || imageModeToggle.isSelected();
                if (startButton.isDisabled() != (!anyMode || visibleCount() == 0)) {
                    throw new IllegalStateException("导入结束后开始按钮状态未按派生规则恢复");
                }
                out.println("[PASS] Import disables/restores start button"); p++;
            } catch (Exception e) { out.println("[FAIL] Import button state: " + e.getMessage()); f++; }

            // 拖拽高亮：硬复位
            try {
                importDragDepth = 3; updateImportDropHighlight(true);
                clearImportDropHighlight();
                if (fileListView.getStyleClass().contains("list-import-hover")) {
                    throw new IllegalStateException("拖拽高亮未清除");
                }
                out.println("[PASS] Drag-drop import highlight reset"); p++;
            } catch (Exception e) { out.println("[FAIL] Drag-drop highlight: " + e.getMessage()); f++; }

            // 复选框列头全选/全不选（三态联动）
            try { selectAllCheck.fire(); Thread.sleep(200);
                long sel = allVisibleItems().stream().filter(i -> i.selected.get()).count();
                if (sel != visibleCount()) { throw new IllegalStateException("全选未生效: " + sel); }
                selectAllCheck.fire(); Thread.sleep(200);
                sel = allVisibleItems().stream().filter(i -> i.selected.get()).count();
                if (sel != 0) { throw new IllegalStateException("全不选未生效: " + sel); }
                out.println("[PASS] Select-all checkbox tri-state"); p++;
            } catch (Exception e) { out.println("[FAIL] Select-all checkbox: " + e.getMessage()); f++; }

            // 移除 + 撤销（部分勾选不弹确认框）
            try { List<PreviewItem> vis = allVisibleItems();
                vis.get(0).selected.set(true); Thread.sleep(200);
                removeButton.fire(); Thread.sleep(300);
                if (vis.get(0).removed != true) { throw new IllegalStateException("移除未生效"); }
                undoButton.fire(); Thread.sleep(300);
                if (vis.get(0).removed) { throw new IllegalStateException("撤销移除未生效"); }
                out.println("[PASS] Remove + undo"); p++;
            } catch (Exception e) { out.println("[FAIL] Remove + undo: " + e.getMessage()); f++; }

            // 合并为组 + 组名编辑 + 组参数 + 拆分组 + 撤销
            try { List<PreviewItem> vis = allVisibleItems();
                allVisibleItems().forEach(i -> i.selected.set(false));
                vis.get(0).selected.set(true); vis.get(1).selected.set(true);
                groupButton.fire(); Thread.sleep(400);
                List<GroupItem<PreviewItem>> groups = topLevelGroups();
                if (groups.size() != 1) { throw new IllegalStateException("建组失败: " + groups.size()); }
                GroupItem<PreviewItem> g = groups.get(0);
                fileListView.getSelectionModel().select(g); Thread.sleep(300);
                groupNameField.setText("自动测试组"); Thread.sleep(300);
                if (!"自动测试组".equals(g.getName())) { throw new IllegalStateException("组名编辑失败"); }
                // 折叠/展开动画（触发后立即快进验证终态；FX 线程 sleep 会阻塞 pulse 动画无法推进）
                toggleGroupExpanded(g);
                finishExpandAnim();
                if (g.isExpanded()) { throw new IllegalStateException("折叠动画未生效"); }
                toggleGroupExpanded(g);
                finishExpandAnim();
                if (!g.isExpanded()) { throw new IllegalStateException("展开动画未生效"); }
                Thread.sleep(300);
                ParamDialogController groupDialog = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache, new ParamScope.GroupTarget(g));
                Thread.sleep(300);
                groupDialog.enableTitleExtractCheck.setSelected(true);
                groupDialog.confirmButton.fire(); Thread.sleep(300);
                if (!groupDialog.isConfirmed()) { throw new IllegalStateException("组参数窗口未确认"); }
                applyGroupParams(g, false, groupDialog.getResult()); Thread.sleep(300);
                if (g.getTextParamsCache() == null) { throw new IllegalStateException("组参数未写入"); }
                fileListView.getSelectionModel().select(g); Thread.sleep(200);
                ungroupButton.fire(); Thread.sleep(300);
                if (topLevelGroups().size() != 0) { throw new IllegalStateException("拆分组失败"); }
                undoButton.fire(); Thread.sleep(300);
                if (topLevelGroups().size() != 1 || !"自动测试组".equals(topLevelGroups().get(0).getName())) {
                    throw new IllegalStateException("撤销拆分失败（组名/参数未恢复）");
                }
                undoButton.fire(); Thread.sleep(300);   // 撤销组参数（PARAM_SET）
                if (g.getTextParamsCache() != null) {
                    throw new IllegalStateException("撤销组参数失败");
                }
                out.println("[PASS] Group + rename + group params + ungroup + undo chain"); p++;
            } catch (Exception e) { out.println("[FAIL] Group ops: " + e.getMessage()); f++; }

            // 拖拽（performDrop 程序化驱动）+ 撤销；第二组用单项目建组（R9）
            try { undoButton.fire(); Thread.sleep(300);   // 撤销上一段建组，成员回顶层
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
                allVisibleItems().forEach(i -> i.selected.set(false));
                vis.get(0).selected.set(true); vis.get(1).selected.set(true);
                groupButton.fire(); Thread.sleep(400);
                allVisibleItems().forEach(i -> i.selected.set(false));
                List<PreviewItem> tops = allVisibleItems().stream()
                        .filter(i -> i.parentGroup == null).toList();
                tops.get(0).selected.set(true);
                groupButton.fire(); Thread.sleep(400);
                List<GroupItem<PreviewItem>> groups = topLevelGroups();
                if (groups.size() != 2) { throw new IllegalStateException("建第二组失败: " + groups.size()); }
                GroupItem<PreviewItem> g1 = groups.get(0); GroupItem<PreviewItem> g2 = groups.get(1);
                performDrop(visibleMembersOf(g1).get(1), visibleMembersOf(g1).get(0),
                        DropResolver.Zone.TOP, null); rebuildDisplay(); Thread.sleep(200);
                performDrop(visibleMembersOf(g1).get(0), g2, DropResolver.Zone.INTO, null);
                rebuildDisplay(); Thread.sleep(200);
                performDrop(g2, g1, DropResolver.Zone.INTO, null);
                rebuildDisplay(); Thread.sleep(200);
                if (topLevelGroups().size() != 1) { throw new IllegalStateException("两组合并失败"); }
                undoButton.fire(); Thread.sleep(300);   // 撤销组合并
                undoButton.fire(); Thread.sleep(300);   // 撤销入组
                undoButton.fire(); Thread.sleep(300);   // 撤销组内排序
                if (topLevelGroups().size() != 2) { throw new IllegalStateException("撤销拖拽失败"); }
                out.println("[PASS] Drag-drop (reorder/cross-group/merge) + undo"); p++;
            } catch (Exception e) { out.println("[FAIL] Drag-drop: " + e.getMessage()); f++; }

            // 条目独立参数 + 应用到全局 + 撤销链（条目独立参数需求）
            try {
                undoButton.fire(); Thread.sleep(300);   // 撤销建组（第二组）
                undoButton.fire(); Thread.sleep(300);   // 撤销建组（第一组）
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() < 2) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
                PreviewItem target = vis.get(0);
                ParamDialogController itemDialog = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache,
                        new ParamScope.ItemTarget<>(target, target.textItemParams, target.imageItemParams));
                itemDialog.enableTitleExtractCheck.setSelected(true);
                itemDialog.confirmButton.fire(); Thread.sleep(300);
                if (!itemDialog.isConfirmed()) { throw new IllegalStateException("条目参数窗口未确认"); }
                applyItemParams(target, false, itemDialog.getResult());
                if (target.textItemParams == null) { throw new IllegalStateException("条目参数未写入"); }
                fileListView.getSelectionModel().select(target); Thread.sleep(200);
                if (applyGlobalButton.isDisabled()) { throw new IllegalStateException("应用到全局按钮未启用"); }
                AppConfig globalBeforeApply = textParamsCache;
                applyGlobalButton.fire(); Thread.sleep(300);
                if (target.textItemParams != null
                        || allVisibleItems().stream().anyMatch(i -> i.textItemParams != null)) {
                    throw new IllegalStateException("应用到全局未清空独立参数");
                }
                undoButton.fire(); Thread.sleep(300);   // 撤销应用到全局
                if (target.textItemParams == null || textParamsCache != globalBeforeApply) {
                    throw new IllegalStateException("撤销应用到全局未恢复");
                }
                undoButton.fire(); Thread.sleep(300);   // 撤销条目参数
                if (target.textItemParams != null) {
                    throw new IllegalStateException("撤销条目参数未恢复");
                }
                out.println("[PASS] Item params + apply-global + undo chain"); p++;
            } catch (Exception e) { out.println("[FAIL] Item params undo chain: " + e.getMessage()); f++; }

            // 清除参数（清除参数功能）：按钮/菜单禁用态 + 条目清除 + 撤销恢复（拍板 Q1-Q3）
            try {
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
                textModeToggle.setSelected(true); Thread.sleep(300);
                allVisibleItems().forEach(i -> i.selected.set(false));
                updateListButtons();
                PreviewItem a = vis.get(0), b = vis.get(1);
                if (!clearParamsButton.isDisabled()) { throw new IllegalStateException("无独立参数时按钮应禁用"); }
                ParamDialogController itemDlg = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache,
                        new ParamScope.ItemTarget<>(a, a.textItemParams, a.imageItemParams));
                itemDlg.enableTitleExtractCheck.setSelected(true);
                itemDlg.confirmButton.fire(); Thread.sleep(300);
                if (!itemDlg.isConfirmed()) { throw new IllegalStateException("条目参数窗口未确认"); }
                applyItemParams(a, false, itemDlg.getResult());
                if (a.textItemParams == null) { throw new IllegalStateException("条目参数未写入"); }
                fileListView.getSelectionModel().select(a); Thread.sleep(200);
                if (clearParamsButton.isDisabled()) { throw new IllegalStateException("选中带参数条目时按钮应启用"); }
                fileListView.getSelectionModel().select(b); updateListButtons();
                if (!clearParamsButton.isDisabled()) { throw new IllegalStateException("选中无参数条目时按钮应禁用"); }
                a.selected.set(true); b.selected.set(true); updateListButtons();
                if (clearParamsButton.isDisabled()) { throw new IllegalStateException("勾选集合含参数目标时按钮应启用"); }
                clearParamsButton.fire(); Thread.sleep(300);
                if (a.textItemParams != null) { throw new IllegalStateException("清除条目参数未生效"); }
                if (!clearParamsButton.isDisabled()) { throw new IllegalStateException("清除后按钮应禁用"); }
                allVisibleItems().forEach(i -> i.selected.set(false)); updateListButtons();
                undoButton.fire(); Thread.sleep(300);
                if (a.textItemParams == null) { throw new IllegalStateException("撤销清除参数未恢复"); }
                // 菜单启用态：空白/无参数禁用、带参数启用（启用看右键目标）
                updateContextMenuState(null);
                if (!ctxClearParams.isDisable()) { throw new IllegalStateException("空白区清除参数应禁用"); }
                updateContextMenuState(a);
                if (ctxClearParams.isDisable()) { throw new IllegalStateException("带参数条目清除参数应启用"); }
                updateContextMenuState(b);
                if (!ctxClearParams.isDisable()) { throw new IllegalStateException("无参数条目清除参数应禁用"); }
                out.println("[PASS] Clear params (item scope + enable matrix)"); p++;
            } catch (Exception e) { out.println("[FAIL] Clear params item: " + e.getMessage()); f++; }

            // 清除参数组语义：组内成员/组条目 → 清整组；混合多选（组内+组外）→ 都清；一次撤销全部恢复
            try {
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() < 3) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
                allVisibleItems().forEach(i -> i.selected.set(false));
                PreviewItem a = vis.get(0), b = vis.get(1), c = vis.get(2);
                b.selected.set(true); c.selected.set(true);
                groupButton.fire(); Thread.sleep(400);
                List<GroupItem<PreviewItem>> groups = topLevelGroups();
                if (groups.size() != 1) { throw new IllegalStateException("建组失败: " + groups.size()); }
                GroupItem<PreviewItem> g = groups.get(0);
                ParamDialogController groupDlg = ParamDialogController.showDirect(
                        null, false, textParamsCache, imageParamsCache, new ParamScope.GroupTarget(g));
                groupDlg.enableTitleExtractCheck.setSelected(true);
                groupDlg.confirmButton.fire(); Thread.sleep(300);
                if (!groupDlg.isConfirmed()) { throw new IllegalStateException("组参数窗口未确认"); }
                applyGroupParams(g, false, groupDlg.getResult());
                if (g.getTextParamsCache() == null) { throw new IllegalStateException("组参数未写入"); }
                // a（组外带参数）+ b（组内成员）混合多选 → 清 a 条目参数 + 清整组 g 参数（同组去重）
                a.selected.set(true); b.selected.set(true); updateListButtons();
                if (clearParamsButton.isDisabled()) { throw new IllegalStateException("混合多选按钮应启用"); }
                clearParamsButton.fire(); Thread.sleep(300);
                if (a.textItemParams != null) { throw new IllegalStateException("组外条目参数未清除"); }
                if (g.getTextParamsCache() != null) { throw new IllegalStateException("组参数未清除"); }
                undoButton.fire(); Thread.sleep(300);
                if (a.textItemParams == null || g.getTextParamsCache() == null) {
                    throw new IllegalStateException("撤销混合清除未全部恢复");
                }
                // 清场恢复：撤销组参数、建组、条目参数，回到本段前状态
                undoButton.fire(); Thread.sleep(300);
                undoButton.fire(); Thread.sleep(300);
                undoButton.fire(); Thread.sleep(300);
                if (topLevelGroups().size() != 0 || a.textItemParams != null) {
                    throw new IllegalStateException("清除参数测试段清场失败");
                }
                out.println("[PASS] Clear params (group + mixed multi-select + single undo)"); p++;
            } catch (Exception e) { out.println("[FAIL] Clear params group: " + e.getMessage()); f++; }

            // 进度条口径（修复：独立参数场景无参数条目导致进度条卡中间）——只统计有生效参数条目
            try {
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() < 2) { throw new IllegalStateException("顶层项目不足: " + vis.size()); }
                textModeToggle.setSelected(true); Thread.sleep(300);
                cancelWorkspaceActivity();   // 作废在途解析代次，保证断言确定性
                AppConfig savedGlobal = textParamsCache;
                for (PreviewItem it : allPreviewItems()) {
                    it.textItemParams = null;
                    it.status.set(Status.WAITING);
                }
                PreviewItem p0 = vis.get(0);
                textParamsCache = null;               // 全局未确认
                p0.textItemParams = new AppConfig();  // 仅该条目有独立参数
                updateProgressStatus(parseToken);
                if (!progressBar.isVisible() || Math.abs(progressBar.getProgress()) > 0.001) {
                    throw new IllegalStateException("无参数条目不应参与进度（期望可见且 0/1）");
                }
                if (!progressBar.getStyleClass().contains("progress-preparse")) {
                    throw new IllegalStateException("预解析进度条应为浅蓝样式");
                }
                p0.status.set(Status.READY);
                updateProgressStatus(parseToken);
                if (progressBar.isVisible()) {
                    throw new IllegalStateException("有参数条目全部完成后进度条应隐藏");
                }
                if (progressBar.getStyleClass().contains("progress-preparse")) {
                    throw new IllegalStateException("预解析完成后应移除浅蓝样式");
                }
                p0.textItemParams = null;
                updateProgressStatus(parseToken);
                if (progressBar.isVisible()) {
                    throw new IllegalStateException("全部无参数时进度条应隐藏");
                }
                // 相邻修复：提取运行期间进度条/状态栏归提取任务独占，解析回调不得覆盖
                running.set(true);
                progressBar.setVisible(true);
                progressBar.setProgress(0.5);
                String labelBefore = statusLabel.getText();
                updateProgressStatus(parseToken);
                if (Math.abs(progressBar.getProgress() - 0.5) > 0.001
                        || !statusLabel.getText().equals(labelBefore)) {
                    running.set(false);
                    throw new IllegalStateException("提取期间解析回调不应覆盖进度条/状态栏");
                }
                running.set(false);
                // 恢复全局参数并重解析
                textParamsCache = savedGlobal;
                refreshAfterParamsChanged();
                Thread.sleep(500);
                out.println("[PASS] Progress bar counts only effective-param items"); p++;
            } catch (Exception e) { out.println("[FAIL] Progress bar param-scope: " + e.getMessage()); f++; }

            // 双击 toggle 勾选（回归：CLICKED 事件按键判定须用 getButton，isPrimaryButtonDown 恒为 false）
            try {
                List<PreviewItem> vis = allVisibleItems();
                PreviewItem dt = vis.get(0);
                boolean before = dt.selected.get();
                PreviewListCell targetCell = findCell(dt);
                if (targetCell == null) { throw new IllegalStateException("未找到条目 cell"); }
                javafx.scene.input.MouseEvent click2 = new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 2,
                        false, false, false, false, false, false, false,
                        false, false, false, null);
                targetCell.fireEvent(click2); Thread.sleep(300);
                if (dt.selected.get() == before) {
                    throw new IllegalStateException("双击未切换勾选状态");
                }
                // 快速连续双击：第二次手势的 click 序列为 3、4（多击窗口内递增）→ 奇数跳过、偶数 toggle
                javafx.scene.input.MouseEvent click3 = new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 3,
                        false, false, false, false, false, false, false,
                        false, false, false, null);
                targetCell.fireEvent(click3); Thread.sleep(200);
                if (dt.selected.get() == before) {
                    throw new IllegalStateException("奇数次 click 不应 toggle");
                }
                javafx.scene.input.MouseEvent click4 = new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        100, 10, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 4,
                        false, false, false, false, false, false, false,
                        false, false, false, null);
                targetCell.fireEvent(click4); Thread.sleep(300);
                if (dt.selected.get() != before) {
                    throw new IllegalStateException("偶数次 click 应 toggle");
                }
                out.println("[PASS] Double-click toggle (rapid sequence)"); p++;
            } catch (Exception e) { out.println("[FAIL] Double-click toggle: " + e.getMessage()); f++; }

            // 输出单元拆分：组 + 未分组（撤销链后状态已变化，此处自行建组构造确定状态）
            try { String sessionDir = new File(System.getProperty("java.io.tmpdir"),
                        "mht-auto-session").getPath();
                List<PreviewItem> vis = allVisibleItems();
                if (vis.size() >= 3) {
                    allVisibleItems().forEach(i -> i.selected.set(false));
                    vis.get(0).selected.set(true);
                    vis.get(1).selected.set(true);
                    groupButton.fire(); Thread.sleep(400);
                }
                List<OutputUnitPlanner.OutputUnit> units = OutputUnitPlanner.plan(
                        items, ITEM_VIEW, false, textParamsCache, imageParamsCache, sessionDir);
                if (units.size() < 2) { throw new IllegalStateException("输出单元数异常: " + units.size()); }
                out.println("[PASS] Output unit plan (" + units.size() + " units)"); p++;
            } catch (Exception e) { out.println("[FAIL] Output unit plan: " + e.getMessage()); f++; }

            try { inputFolderField.setText(textDir.toString()); Thread.sleep(200);
                out.println("[PASS] Set input folder"); p++;
            } catch (Exception e) { out.println("[FAIL] Input folder: " + e.getMessage()); f++; }

            try { outputFolderField.setText(new File(System.getProperty("java.io.tmpdir"),
                        "mht-auto-output").getPath()); Thread.sleep(200);
                out.println("[PASS] Set output folder"); p++;
            } catch (Exception e) { out.println("[FAIL] Output folder: " + e.getMessage()); f++; }

            try { ParamDialogController comboDialog = ParamDialogController.showDirect(
                    null, false, textParamsCache, imageParamsCache, ParamScope.GLOBAL);
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

            // 文本提取（参数已确认 → 不弹提示；断言单元合并后 fileResults 完整，供回写背景色）
            try { textModeToggle.setSelected(true); Thread.sleep(300);
                inputFolderField.setText(textDir.toString());
                waitForImportDone();   // 统一导入为异步：先排空（本例为全量已导入 no-op），避免收尾回调在提取期间重算按钮状态
                outputFolderField.setText(new File(System.getProperty("java.io.tmpdir"),
                        "mht-auto-output-text").getPath());
                startButton.fire();
                // 提取回调（onSucceeded 等）在 FX 线程事件队列中，须嵌套泵排空后再断言
                long deadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < deadline
                        && (lastExtractStats.get() == null || startButton.isDisabled())) {
                    pumpFxEvents();
                }
                BatchProcessor.ProcessStats merged = lastExtractStats.get();
                if (merged == null || merged.getFileResults().size() != visibleCount()
                        || !merged.getFileResults().stream()
                                .allMatch(r -> r.outcome() == ExtractOutcome.SUCCESS)) {
                    throw new IllegalStateException("单元合并后 fileResults 缺失或状态异常："
                            + (merged == null ? "null" : merged.getFileResults().size()));
                }
                // 进度条配色拍板：全部成功 → 满格绿色
                if (!progressBar.isVisible() || Math.abs(progressBar.getProgress() - 1.0) > 0.001
                        || !progressBar.getStyleClass().contains("progress-extract-success")) {
                    throw new IllegalStateException("全部成功后进度条应满格且为绿色");
                }
                out.println("[PASS] Text extraction started"); p++;
            } catch (Exception e) { out.println("[FAIL] Text extraction: " + e.getMessage()); f++; }

            // 图片预览工作区：查看预览效果（懒加载 + 需求 2 C1 预览落盘断言）
            try { imageModeToggle.setSelected(true); Thread.sleep(500);
                PreviewItem pvTarget = allVisibleItems().stream()
                        .filter(i -> "t01.mht".equals(i.name)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("找不到 t01.mht"));
                fileListView.getSelectionModel().select(pvTarget); Thread.sleep(300);
                previewEffectButton.fire();
                long previewDeadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < previewDeadline && imageLoadActive) {
                    pumpFxEvents();
                }
                Thread.sleep(200); pumpFxEvents();
                Object selObj = fileListView.getSelectionModel().getSelectedItem();
                if (!(selObj instanceof PreviewItem pi) || pi.cachedThumbs == null
                        || pi.cachedThumbs.isEmpty()) {
                    throw new IllegalStateException("预览后应有缩略图（来自磁盘图片解码）");
                }
                PreviewItem previewed = (PreviewItem) selObj;
                // C1：缓存目录含 meta.json + images/ + .complete；C2：内存只留 meta + 缩略图
                AppConfig pvParams = effectiveForObject(pi);
                if (pvParams == null) {
                    throw new IllegalStateException("预览条目无生效参数");
                }
                PreviewCacheKey pvKey = PreviewCacheKey.compute(Path.of(pi.path),
                        pvParams.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
                if (previewCacheLifecycle.store().load(pvKey).isEmpty()) {
                    throw new IllegalStateException("预览后缓存应命中（meta.json/images/.complete）");
                }
                if (pi.cachedMeta == null || pi.cachedImages != null) {
                    throw new IllegalStateException("内存应只留 meta + 缩略图（cachedMeta 非空、cachedImages 为空）");
                }
                out.println("[PASS] Image preview workspace (C1/C2)"); p++;
            } catch (Exception e) { out.println("[FAIL] Image preview workspace: " + e.getMessage()); f++; }

            // 图片提取（含损坏文件 → 满格条应转为浅红，进度条配色拍板）
            try { imageModeToggle.setSelected(true); Thread.sleep(300);
                java.nio.file.Files.write(imageDir.resolve("bad.mht"),
                        "garbage-not-a-mht".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                inputFolderField.setText(imageDir.toString());
                waitForImportDone();   // 统一导入为异步：bad.mht 须完成导入后才开始提取，否则无失败条目
                outputFolderField.setText(new File(System.getProperty("java.io.tmpdir"),
                        "mht-auto-output-image").getPath());
                startButton.fire();
                long deadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < deadline
                        && (lastExtractStats.get() == null || startButton.isDisabled())) {
                    pumpFxEvents();
                }
                if (lastExtractStats.get() == null) {
                    throw new IllegalStateException("图片提取未在时限内完成");
                }
                if (!progressBar.isVisible() || Math.abs(progressBar.getProgress() - 1.0) > 0.001
                        || !progressBar.getStyleClass().contains("progress-extract-failed")) {
                    throw new IllegalStateException("存在失败条目时满格条应为浅红");
                }
                out.println("[PASS] Image extraction started"); p++;
            } catch (Exception e) { out.println("[FAIL] Image extraction: " + e.getMessage()); f++; }

            // 预览缓存生命周期（需求 2 会话 9）：参数失效 C3 / 移除清理 C7 / 退出清理 C8
            try { imageModeToggle.setSelected(true); Thread.sleep(300);
                PreviewItem target = allVisibleItems().stream()
                        .filter(i -> "i01.mht".equals(i.name)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("找不到 i01.mht"));
                // C3：全局参数变化 → 全部缓存目录删除（含此前图片预览块落盘的目录）
                AppConfig modified = new AppConfig();
                modified.setImageNameStartMarker("__auto_test_changed__");
                applyConfirmedParams(true, modified); Thread.sleep(300);
                if (!previewCacheLifecycle.store().listKeys().isEmpty()) {
                    throw new IllegalStateException("参数变化后缓存应全部删除（C3）");
                }
                // 预览 i01 → 落盘恰 1 个目录
                fileListView.getSelectionModel().select(target); Thread.sleep(200);
                previewEffectButton.fire();
                long lcDeadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < lcDeadline && imageLoadActive) {
                    pumpFxEvents();
                }
                Thread.sleep(200); pumpFxEvents();
                if (previewCacheLifecycle.store().listKeys().size() != 1) {
                    throw new IllegalStateException("预览后应恰有 1 个缓存目录，实际 "
                            + previewCacheLifecycle.store().listKeys().size());
                }
                // C7：移除项目 → 其缓存目录删除
                removeItems(List.of(target)); Thread.sleep(200);
                if (!previewCacheLifecycle.store().listKeys().isEmpty()) {
                    throw new IllegalStateException("移除项目后其缓存应被删除（C7）");
                }
                // C8：退出清空（test.mode 跳过弹窗只清空，关窗处理器同样逻辑）
                previewCacheLifecycle.onShutdown(() -> { });
                java.nio.file.Path cacheRoot = previewCacheLifecycle.store().root();
                if (java.nio.file.Files.isDirectory(cacheRoot)
                        && java.nio.file.Files.list(cacheRoot).findAny().isPresent()) {
                    throw new IllegalStateException("退出清空后缓存根目录应为空（C8）");
                }
                out.println("[PASS] Preview cache lifecycle (C3/C7/C8)"); p++;
            } catch (Exception e) { out.println("[FAIL] Preview cache lifecycle: " + e.getMessage()); f++; }
            // 兜底清场（无论成败）：自动化测试经 System.exit 退出不走关窗处理器，避免缓存残留跨会话
            try { previewCacheLifecycle.onShutdown(() -> { }); } catch (Exception ignored) { }

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
