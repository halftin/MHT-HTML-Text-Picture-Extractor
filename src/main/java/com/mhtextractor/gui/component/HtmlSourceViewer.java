package com.mhtextractor.gui.component;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.platform.HtmlSyntaxPalette;
import com.mhtextractor.platform.HtmlSyntaxTokens;
import com.mhtextractor.platform.HtmlTextSelection;
import com.mhtextractor.platform.HtmlWrapMap;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 应用内 HTML 源码查看器（Q8b / R2 + R5）：从主界面<b>左边框滑出</b>的覆盖式侧边栏，
 * 用 <b>JavaFX 节点</b>渲染 MHT 项里那段 HTML 的源码，底部带查找栏。
 *
 * <h2>它渲染的是源码，不是网页（R2.2）</h2>
 * <p>因此有一条<b>硬红线</b>：不用、也不允许用 {@code javafx.scene.web.WebView}（R2.6）。
 * 本机 Liberica Full JDK 把 {@code javafx.web} 内置成 JDK 模块，所以 WebView 在<b>开发机
 * 能跑</b>；但 {@code build-native.cmd} 不抽 {@code jfxwebkit.dll}，native 产物必然启动失败 ——
 * "本机能跑、打包必炸"是这条红线唯一的理由。渲染一律走 {@link TextFlow}
 * （它把 {@code <} {@code &} 这些字符本来就按<b>文本</b>处理，不需要
 * {@link HtmlSyntaxTokens#escapeHtml(String)}）。</p>
 *
 * <h2>高亮：token 模型是两个渲染器共用的（R2.4）</h2>
 * <p>本类<b>不</b>重写扫描逻辑：{@link HtmlSyntaxTokens#tokenize(String)} 给出逐行 token，
 * 颜色问 {@link HtmlSyntaxPalette}（外部浏览器页的 {@code <style>} 也由它生成）。
 * 于是"哪段是什么"与"那一类长什么样"各有唯一来源，两条渲染路径不会分叉。</p>
 *
 * <h2>逐行虚拟化（R2.7）</h2>
 * <p>{@link ListView} + 每行一个 cell（{@code TextFlow}）。MB 级 HTML 会切出上万行，
 * 逐个建 {@code Text} 节点必然卡死；{@code ListView} 只为可见区域建 cell
 * （判据是<b>实测节点数</b>，见 {@code tools/probes/HtmlViewerProbe}）。</p>
 *
 * <h2>软换行（E2，取代 R2.8 的横向滚动）</h2>
 * <p>长行<b>按字符网格折成多个视觉行</b>（等宽字体 ⇒ 每字符等宽，见
 * {@link #measureCharWidth()}），行号对每个逻辑行只显示一次、与<b>第一折行</b>对齐，
 * 后续折行用等宽 spacer 缩进；外层 {@link ScrollPane} {@code fitToWidth}、永不横向滚动。
 * 折行几何与逻辑字符列共享同一套坐标：{@link HtmlWrapMap}（纯函数）负责
 * "像素 → 视觉行/列 → 逻辑列"的投影，选择与查找高亮都按 {@link HtmlWrapMap#segments}
 * 切成"每视觉行一段"渲染 —— 逻辑行模型、{@link HtmlTextSelection} 的选择语义、
 * 复制的原始源码全部不变。</p>
 *
 * <h2>查找（R5）</h2>
 * <p>底部固定一行的查找栏：输入框 + 四个线条图标按钮（查 / 上一个 / 下一个 / 关闭）。匹配口径见
 * {@link HtmlFindIndex}（纯 {@code java.util.regex}，字面量 + 大小写不敏感，无命中不报错）。
 * 命中位置是查看器的<b>状态</b>，cell 渲染时按它着色；换命中时<b>只重建有命中的那几行</b>
 * （{@link #onActiveLineChanged(int[], int[])}），不 {@code setAll}/不整表 {@code refresh}。</p>
 *
 * <h2>线程方案（R2.7 的"MB 级不卡死"）</h2>
 * <p>读文件 + {@code tokenize} 都在后台线程（{@link Source#build(String)}），
 * 只有"把行模型交给 {@code ListView}"与所有节点操作回 FX 线程。
 * 载入期间面板立刻滑出、计数栏显示"载入中…"，所以大文件也不会看起来没反应。</p>
 *
 * <h2>无草稿语义（R2.9）</h2>
 * <p>收起就是隐藏：没有提交/丢弃，也不落盘。</p>
 *
 * <h2>字符选择与复制（E1）</h2>
 * <p>在内容区按下拖动可做<b>字符级</b>选择（可跨 token、跨行），平台复制键（Ctrl/Cmd+C）
 * 把<b>原始源码</b>放进系统剪贴板。逻辑模型在 {@link HtmlTextSelection}（零 JavaFX）：
 * 这里只做"像素坐标 → (行, 列)"的换算与渲染。</p>
 * <p>选择高亮是每 cell 的<b>覆盖矩形</b>（{@code selPane} 里，折行后每视觉行一段），
 * <b>不重建</b>任何 {@code Text} 节点 —— 所以拖动期间重绘不会把"鼠标抓着的节点"摘出
 * 场景图（重建方案会让拖拽中途丢事件），大文件上也绝不按总行数建节点
 * （只画可见 cell，R2.7 判据不变）。</p>
 */
@Slf4j
public final class HtmlSourceViewer {

    /** 宿主 → 查看器。Weak 键：窗口关闭后不留引用（与 ParamSidePanel / NotificationHost 同法）。 */
    private static final Map<SidePanelHost, HtmlSourceViewer> VIEWERS = new WeakHashMap<>();

    /** 源码字号（与 {@code SourceViewPage} 的 {@code .row} 的 13px 对齐）。 */
    private static final double FONT_SIZE = 13;
    /** 等宽字体族（与页面上的 {@code Consolas,"Courier New",monospace} 同一顺序）。 */
    private static final String FONT_FAMILY = "Consolas, \"Courier New\", monospace";
    /** 行高（页面上是 {@code line-height:1.5}；<b>向上取整到整像素</b>—— JavaFX 布局按像素
     *  吸附（snapToPixel），19.5 的行实际会被吸附成 20，选择/列折算若还用 19.5 会在多折行
     *  上累计漂移；取整后 ROW_HEIGHT 与吸附后的真实行高逐像素一致）。 */
    private static final double ROW_HEIGHT = Math.ceil(FONT_SIZE * 1.5);
    /** 行号栏与内容之间的间距（页面上是 {@code margin-right:10px} 加一点内边距）。 */
    private static final double GUTTER_GAP = 14;
    /** 内容右侧安全留白（E2：折行列数按"视口宽 − 行号栏 − 本值"折算，既让最后一列不贴边，
     *  也盖住 ListView 自身竖向滚动条的覆盖宽度，保证折行后字符不被它遮住）。 */
    private static final double CONTENT_TAIL = 24;
    /** 量字符宽用的样本（等宽字体下 40 个 M 的布局宽除以 40 即单字宽）。 */
    private static final String SAMPLE_MEASURE = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";
    /** 选择高亮背景色（E1；浅蓝，与 {@code html-source-hit} 的 #ffe680 可并存）。 */
    private static final String SELECTION_COLOR = "#cfe3ff";

    private final SidePanelHost host;
    private final ListView<Row> listView = new ListView<>();
    private final ScrollPane scroller = new ScrollPane();
    private final BorderPane root = new BorderPane();
    private final TextField queryField = new TextField();
    private final Label countLabel = new Label();
    private final HBox findBar = new HBox(6);
    private final Button findButton = iconButton("sourceViewerFind", FontAwesomeSolid.SEARCH,
            "viewer.findTooltip");
    private final Button prevButton = iconButton("sourceViewerPrev", FontAwesomeSolid.ANGLE_UP,
            "viewer.findPrevTooltip");
    private final Button nextButton = iconButton("sourceViewerNext", FontAwesomeSolid.ANGLE_DOWN,
            "viewer.findNextTooltip");
    private final Button closeFindButton = iconButton("sourceViewerCloseFind", FontAwesomeSolid.TIMES,
            "viewer.findCloseTooltip");

    /** 当前行模型（{@code rows.get(i)} 对应第 i 行源码）。 */
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    /** 行号格式化宽度 = 总行数的位数（行号列宽度固定，折行时后续视觉行才与内容不错位）。 */
    private int digits = 1;
    /** 单字符像素宽（等宽字体 ⇒ 折行列数 = 视口宽 ÷ 本值）。 */
    private double charWidth = FONT_SIZE * 0.6;
    /** 归一化后的整篇源码（查找索引的坐标基准）。 */
    private String source = "";
    private List<HtmlSyntaxTokens.Line> lines = List.of();
    /** 逐行纯文本（<b>复制的唯一文本来源</b>：不含行尾换行、行号与任何标记）。 */
    private List<String> plainLines = List.of();
    /** E1 选择锚点（按下处）；null = 无选择。 */
    private HtmlTextSelection.Pos selAnchor;
    /** E1 选择游标（当前拖到处）；null = 无选择。 */
    private HtmlTextSelection.Pos selCaret;

    /** 当前查找结果；{@link HtmlFindIndex#EMPTY} = 无命中。 */
    private HtmlFindIndex hits = HtmlFindIndex.EMPTY;
    /** 当前命中下标（-1 = 没有命中）。 */
    private int hitIndex = -1;
    /** 成功把内容装上 {@code ListView} 的次数（单调递增；探针用它等"这一次载入真的落地了"）。 */
    private long loadCount;
    /**
     * 重绘计数（R5 的"只刷命中行、不整表刷新"判据）。
     *
     * <p>{@code repaints} 每次"因命中变化而做的定点重绘"加一；{@code repaintedLines} 累计
     * <b>被指名重绘</b>的行数；{@code cellsBuiltInLastRefresh} 记下最近一次定点重绘里 cell
     * 工厂真正被调用的次数 —— 它是"代价只与可见区域有关、与文件行数无关"的<b>实测</b>证据
     * （整表刷新会让它等于总行数）。</p>
     */
    private long repaints;
    private long repaintedLines;
    private int cellsBuilt;
    private int cellsBuiltInLastRefresh;

    private HtmlSourceViewer(SidePanelHost host) {
        this.host = host;
        buildScene();
        wireFindBar();
    }

    // ==================== 对外 API ====================

    /**
     * 打开左侧查看器并显示<b>已经读好的</b>源码。
     *
     * <p>大文件请用 {@link #showFile(Node, String, Path)}：本方法里 {@code tokenize} 是同步的，
     * MB 级源码会把 FX 线程堵住。</p>
     *
     * @param anchor 主窗口内任意节点（用于定位 {@link SidePanelHost}）
     * @param title  标题栏文本（调用方给：platform 层不产本地化文案）
     * @param html   原始 HTML 源码文本（可含 {@code \r\n}）
     */
    public static void showSource(Node anchor, String title, String html) {
        onFxThread(() -> hostOf(anchor).showSourceNow(title, html));
    }

    /**
     * 打开左侧查看器并<b>在后台线程</b>读取 + 分词（R2.7 的"MB 级不卡死"）。
     *
     * <p>面板<b>立刻</b>滑出（计数栏显示"载入中…"），读文件与 {@code tokenize} 都在
     * {@code source-viewer-load} 线程上做，只有"把行模型交给 ListView"回 FX 线程。</p>
     *
     * <p>🚩 <b>可以从任意线程调用</b>：本方法刻意不要求调用方在 FX 线程上。真实调用点
     * （{@code SourceViewActions} 的 {@code html-browser-launch} 线程）就不在 FX 线程上
     * —— 那是原先"用外部浏览器打开"留下的线程模型（那里只有 IO/进程操作，不碰场景图），
     * 而查看器一上来就要 {@code showLeft} + {@code setCenter}。实测不加这层转派会抛
     * {@code IllegalStateException: Not on FX application thread}，而且因为抛在
     * {@code Thread.run()} 里，表现为<b>线程静默死亡、面板永不出现</b>（探针的"真实入口"断言抓到的）。</p>
     *
     * @param anchor   主窗口内任意节点
     * @param title    标题栏文本
     * @param htmlFile 已导出的 HTML 文件（来自既有导出路径，本类不解析 MHT）
     */
    public static void showFile(Node anchor, String title, Path htmlFile) {
        onFxThread(() -> hostOf(anchor).showSourceAsync(title, htmlFile));
    }

    /** 在 FX 线程上执行；已经在 FX 线程则直接执行（避免把请求排到"这一拍之后"）。 */
    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** 左侧面板当前是否由本查看器占据且已展开（守卫与探针用）。 */
    public static boolean isShowing(Node anchor) {
        SidePanelHost h = SidePanelHost.of(anchor);
        HtmlSourceViewer v = h == null ? null : VIEWERS.get(h);
        return v != null && h.isLeftShowing();
    }

    /** 由锚点定位（首次调用则创建）该窗口的查看器；宿主未安装 → 抛异常（不静默吞掉）。 */
    public static HtmlSourceViewer hostOf(Node anchor) {
        SidePanelHost sidePanelHost = SidePanelHost.of(anchor);
        if (sidePanelHost == null) {
            throw new IllegalStateException("SidePanelHost 未安装：见 App.start() 的 SidePanelHost.install(root)");
        }
        return VIEWERS.computeIfAbsent(sidePanelHost, HtmlSourceViewer::new);
    }

    // ==================== 探针 / 自动化接缝 ====================

    /** 查看器根节点（左侧面板的内容）；类型是 {@link Parent} 以便调用方直接 {@code layout()}。 */
    public Parent node() {
        return root;
    }

    /** 总行数。 */
    public int lineCount() {
        return rows.size();
    }

    /**
     * 内容被成功装上的累计次数（单调递增）。
     *
     * <p>给探针/自动化用：查看器的载入是异步的，光看"行数 &gt; 0"会把<b>上一次</b>的内容当成
     * 这一次的结果（实测踩过：真实入口的断言读到的是上一步的 5006 行）。判据应当是
     * "这一次载入的序号比调用前更大"。</p>
     */
    public long loadCount() {
        return loadCount;
    }

    /** 查找输入框（R5.2）。 */
    public TextField queryField() {
        return queryField;
    }

    /** 计数标签（R5.3："第 n / 共 m 处"）。 */
    public Label countLabel() {
        return countLabel;
    }

    /** 查找栏节点（R5.1：它挂在查看器底部）。 */
    public Node findBar() {
        return findBar;
    }

    /** 查找栏里的四个线条图标按钮：{@code [查, 上一个, 下一个, 关闭]}（顺序固定）。 */
    public List<Button> findButtons() {
        return List.of(findButton, prevButton, nextButton, closeFindButton);
    }

    /** 命中总数（R5.3 的 m）。 */
    public int hitCount() {
        return hits.size();
    }

    /** 当前命中序号（1 基；0 = 无命中）。 */
    public int currentHitNumber() {
        return hitIndex + 1;
    }

    /** 当前命中的行下标（-1 = 无）。 */
    public int currentHitLine() {
        return hitIndex >= 0 && hitIndex < hits.size() ? hits.get(hitIndex).line() : -1;
    }

    /** 当前命中的行内区间 {@code [start, end)}；无命中 → {@code null}。 */
    public int[] currentHitColumns() {
        if (hitIndex < 0 || hitIndex >= hits.size()) {
            return null;
        }
        HtmlFindIndex.Match m = hits.get(hitIndex);
        return new int[]{m.startCol(), m.endCol()};
    }

    /** 因命中变化而发生的定点重绘次数。 */
    public long repaintCount() {
        return repaints;
    }

    /** 累计被指名重绘的行数（判据：远小于总行数）。 */
    public long repaintedLineCount() {
        return repaintedLines;
    }

    /**
     * 最近一次定点重绘里 cell 工厂被调用的次数（= 真正重建的 cell 数）。
     *
     * <p>这是"不整表刷新"的实测证据：整表 {@code setAll}/{@code refresh} 会让它随总行数增长，
     * 定点重绘应当只与可见区域同阶。</p>
     */
    public int cellsBuiltInLastRefresh() {
        return cellsBuiltInLastRefresh;
    }

    /** 行号栏 + 间距的像素宽（R2.8 的对齐判据用）。 */
    public double gutterWidth() {
        return digits * charWidth + GUTTER_GAP;
    }

    /** 行号栏的位数（= 总行数的位数；R2.8 说行号列宽度必须固定，就是它钉的）。 */
    public int gutterDigits() {
        return digits;
    }

    /** 行号栏与内容之间的固定间距（探针按 {@code digits × charWidth + 本值} 复算行号列宽）。 */
    public double gutterGap() {
        return GUTTER_GAP;
    }

    /** 单字符像素宽（探针核对"不软换行"时按字符数折算用）。 */
    public double charWidth() {
        return charWidth;
    }

    /** 内层 {@code ListView}（虚拟化判据：{@code lookupAll(".list-cell")} 的实测节点数）。 */
    public ListView<?> listView() {
        return listView;
    }

    /** 外层横向滚动容器（R2.8）。 */
    public ScrollPane scroller() {
        return scroller;
    }

    /** 内容视口的像素宽（E2 起语义改为<b>视口宽</b>：软换行后内容不再比视口宽）。 */
    public double contentPixelWidth() {
        return scroller.getViewportBounds().getWidth();
    }

    /** 单视觉行的像素高（E2：折行后 cell 高度 = 视觉行数 × 本值；探针的几何判据用）。 */
    public double rowHeight() {
        return ROW_HEIGHT;
    }

    /** 当前每视觉行的逻辑列数（视口宽 ÷ 单字符宽；软换行的几何基准，渲染与选择共用）。 */
    public int wrapCols() {
        double contentW = scroller.getViewportBounds().getWidth() - gutterWidth() - CONTENT_TAIL;
        return HtmlWrapMap.colsPerRow(contentW, charWidth);
    }

    /** 以纯文本取某行的内容。 */
    public String lineText(int index) {
        return index >= 0 && index < rows.size() ? plainText(rows.get(index).tokens()) : null;
    }

    /** 当前字符选择范围（起止已归一化）；无选择 / 空选择 → {@code null}。 */
    public HtmlTextSelection.Range selectionRange() {
        if (selAnchor == null || selCaret == null) {
            return null;
        }
        HtmlTextSelection.Range r = HtmlTextSelection.ordered(selAnchor, selCaret);
        return HtmlTextSelection.isEmpty(r) ? null : r;
    }

    /** 把当前选择复制进系统剪贴板（空选择 → 不动剪贴板、不抛异常）。 */
    public void copySelection() {
        HtmlTextSelection.Range r = selectionRange();
        if (r == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(HtmlTextSelection.extract(plainLines, r));
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ==================== 载入 ====================

    private void showSourceNow(String title, String html) {
        Source src = Source.build(html);
        host.showLeft(root, title, host::hideLeft);
        applySource(src);
    }

    private void showSourceAsync(String title, Path htmlFile) {
        host.showLeft(root, title, host::hideLeft);
        showLoading();
        Thread t = new Thread(() -> {
            Source built;
            try {
                built = Source.build(Files.readString(htmlFile, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                log.warn("HTML source viewer could not read {}", htmlFile, e);
                built = null;
            }
            Source ready = built;
            Platform.runLater(() -> {
                if (ready == null) {
                    showLoadFailed();
                } else {
                    applySource(ready);
                }
            });
        }, "source-viewer-load");
        t.setDaemon(true);
        t.start();
    }

    /** 载入中（计数栏的位置先占住，避免面板高度跳变）。 */
    private void showLoading() {
        countLabel.setText(I18n.get("viewer.findLoading"));
    }

    /** 载入失败：如实显示，不假装空文件。 */
    private void showLoadFailed() {
        countLabel.setText(I18n.get("viewer.loadFailed"));
    }

    /**
     * 把行模型交给 {@code ListView}（<b>只在 FX 线程调用</b>）。
     *
     * <p>行宽不做任何预折算（E2 起软换行：cell 按折行后内容自适应高度，宽度贴合视口），
     * 代价仍是 R2.7 的口径 —— 只为可见区域建 cell，不为上万行逐个量 {@code TextFlow}。</p>
     */
    private void applySource(Source src) {
        digits = Math.max(1, String.valueOf(src.lines().size()).length());
        source = src.normalized();
        lines = src.lines();
        plainLines = src.plainLines();
        selAnchor = null;      // 新文档没有跨文档的选择
        selCaret = null;
        resetFind();
        charWidth = measureCharWidth();
        rows.setAll(src.rows());
        listView.scrollTo(0);   // 新文档回到顶部（可变行高下不要沿用上一文档的滚动位置）
        loadCount++;
        countLabel.setText(I18n.get("viewer.findCount", "current", 0, "total", 0));
    }

    private void resetFind() {
        hits = HtmlFindIndex.EMPTY;
        hitIndex = -1;
    }

    // ==================== 场景 ====================

    private void buildScene() {
        listView.setId("sourceViewerList");
        listView.getStyleClass().add("html-source-list");
        listView.setItems(rows);
        listView.setCellFactory(v -> new SourceCell());
        // E1：字符选择与复制。ListView 的整行选中对源码查看没有意义（还给整行染色、
        // 选中的又不是字符），关掉它，让鼠标手势只属于字符选择。
        listView.setSelectionModel(null);
        wireSelection();
        // E2：软换行 —— 不设固定行高（cell 高度随折行增长），外层 ScrollPane 贴合内容宽、
        // 永不横向滚动；长行由 SourceCell 按字符网格折成多个视觉行。
        scroller.setId("sourceViewerScroll");
        scroller.getStyleClass().add("html-source-scroll");
        scroller.setContent(listView);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // 纵向滚动由内层 ListView 自己负责，外层不要再来一条
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // 视口宽度变了（窗口/面板尺寸变化）→ 折行列数随之变，重建可见 cell 并按新几何重绘。
        // runLater 而不是同步：listener 可能触发在布局过程里，把重建排到下一拍更稳。
        scroller.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            if (Math.abs(newBounds.getWidth() - oldBounds.getWidth()) > 0.5) {
                Platform.runLater(() -> {
                    listView.refresh();
                    repaintSelection();
                });
            }
        });
        root.setId("sourceViewer");
        root.getStyleClass().add("html-source-viewer");
        root.setCenter(scroller);
        root.setBottom(findBar);
    }

    private void wireFindBar() {
        Label label = new Label(I18n.get("viewer.findLabel"));
        label.setMinWidth(Region.USE_PREF_SIZE);
        queryField.setId("sourceViewerQuery");
        queryField.setPromptText(I18n.get("viewer.findPlaceholder"));
        HBox.setHgrow(queryField, Priority.ALWAYS);
        countLabel.setId("sourceViewerCount");
        countLabel.setMinWidth(Region.USE_PREF_SIZE);
        countLabel.setText(I18n.get("viewer.findCount", "current", 0, "total", 0));

        findBar.setId("sourceViewerFindBar");
        findBar.getStyleClass().add("html-source-findbar");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setPadding(new Insets(8, 12, 8, 12));
        findBar.getChildren().addAll(label, queryField, countLabel,
                findButton, prevButton, nextButton, closeFindButton);

        // ⚠️ 刻意不做"每敲一个字符就重建索引"：MB 级源码每次都要扫全文。确认（ENTER）与
        // 「查」按钮走同一条路径。也不给这些按钮设 defaultButton —— defaultButton 是场景级的，
        // 会把整个主窗口的 ENTER 劫持到查看器（Q6a 踩过同类坑）。
        queryField.setOnAction(e -> runSearch());
        findButton.setOnAction(e -> runSearch());
        nextButton.setOnAction(e -> step(1));
        prevButton.setOnAction(e -> step(-1));
        closeFindButton.setOnAction(e -> closeFind());
    }

    private static Button iconButton(String id, FontAwesomeSolid icon, String tooltipKey) {
        Button b = new Button();
        b.setId(id);
        b.getStyleClass().add("side-panel-collapse");
        b.setGraphic(new FontIcon(icon));
        b.setTooltip(new Tooltip(I18n.get(tooltipKey)));
        return b;
    }

    // ==================== 字符选择与复制（E1） ====================

    private void wireSelection() {
        listView.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onSelectionPressed);
        listView.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onSelectionDragged);
        listView.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onSelectionReleased);
        listView.addEventHandler(KeyEvent.KEY_PRESSED, this::onSelectionKey);
    }

    /** 按下：锚点 = 命中的行 + 内容 x 折算的列（行号区列 clamp 到 0，行号不进选择）。 */
    private void onSelectionPressed(MouseEvent e) {
        if (!e.isPrimaryButtonDown()) {
            return;
        }
        listView.requestFocus();   // 让复制键随后送到本组件
        HtmlTextSelection.Pos p = posAt(e.getSceneX(), e.getSceneY());
        if (p == null) {
            return;
        }
        selAnchor = p;
        selCaret = p;
        repaintSelection();
    }

    /** 拖动：游标跟着指针走（锚点不动）。 */
    private void onSelectionDragged(MouseEvent e) {
        if (selAnchor == null || !e.isPrimaryButtonDown()) {
            return;
        }
        HtmlTextSelection.Pos p = posAt(e.getSceneX(), e.getSceneY());
        if (p == null) {
            return;   // 拖出所有可见行（如查找栏上）：保持当前游标
        }
        selCaret = p;
        repaintSelection();
    }

    /** 松开：定稿；单击（锚点==游标）视为清空选择。 */
    private void onSelectionReleased(MouseEvent e) {
        if (selAnchor == null) {
            return;
        }
        if (selAnchor.equals(selCaret)) {
            selAnchor = null;
            selCaret = null;
            repaintSelection();
        }
    }

    /** 平台复制键（Windows/Linux=Ctrl、macOS=Cmd）+ C：把选择放进系统剪贴板。 */
    private void onSelectionKey(KeyEvent e) {
        if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
            copySelection();
        }
    }

    /**
     * 场景坐标 → (行, 列)。行由<b>真实 cell 的布局边界</b>命中得出（不写死
     * {@code y = 行 × ROW_HEIGHT}，不受 ListView 内边距、虚拟流滚动偏移影响）；
     * cell 内先按 {@link #rowHeight()} 折算出<b>视觉行</b>（clamp 到该逻辑行的折行数内），
     * 再按字符宽折算<b>视觉列</b>，逻辑列 = 视觉行 × 每行列数 + 视觉列，
     * clamp 到 {@code [0, 该行字符数]} —— 与渲染共用的同一套折行投影（{@link HtmlWrapMap}）。
     */
    private HtmlTextSelection.Pos posAt(double sceneX, double sceneY) {
        for (Node n : listView.lookupAll(".list-cell")) {
            if (!(n instanceof SourceCell cell) || cell.isEmpty() || cell.getIndex() < 0) {
                continue;
            }
            Bounds b = cell.localToScene(cell.getBoundsInLocal());
            if (sceneX < b.getMinX() || sceneX > b.getMaxX()
                    || sceneY < b.getMinY() || sceneY > b.getMaxY()) {
                continue;
            }
            String text = plainLines.get(cell.getIndex());
            int cols = wrapCols();
            int vRow = (int) ((sceneY - b.getMinY()) / ROW_HEIGHT);
            vRow = Math.max(0, Math.min(vRow, HtmlWrapMap.visualRowCount(text.length(), cols) - 1));
            // round 而不是 floor：把指针位置归到"最近的字符边界"（标准文本选择语义），
            // 也免受首字符 left-bearing 造成的亚像素偏移影响（floor 在整列边界上会差 1）
            int vCol = (int) Math.round((sceneX - cell.contentSceneMinX()) / charWidth);
            int maxInRow = Math.min(cols, text.length() - vRow * cols);
            vCol = Math.max(0, Math.min(vCol, maxInRow));
            int col = Math.max(0, Math.min(vRow * cols + vCol, text.length()));
            return new HtmlTextSelection.Pos(cell.getIndex(), col);
        }
        return null;
    }

    /**
     * 把选择画到<b>可见</b> cell 的覆盖矩形上：只动可见 cell 里的矩形属性，
     * 不触碰 items 模型、不重建任何 {@code Text} 节点 —— 所以拖动期间调用也安全
     * （重建方案会把"鼠标抓着的节点"摘出场景图，拖拽中途丢事件），且代价只与
     * 可见区域有关、与文件行数无关（R2.7 判据不变）。
     */
    private void repaintSelection() {
        for (Node n : listView.lookupAll(".list-cell")) {
            if (n instanceof SourceCell cell && !cell.isEmpty() && cell.getIndex() >= 0) {
                cell.paintSelection(cell.getIndex());
            }
        }
    }

    // ==================== 查找（R5） ====================

    /** 用输入框里的内容重建索引并跳到第 1 处（「查」按钮 / ENTER）。 */
    private void runSearch() {
        int[] before = activeLines();
        hits = HtmlFindIndex.build(source, lines, queryField.getText());
        hitIndex = hits.isEmpty() ? -1 : 0;
        updateCount();
        onActiveLineChanged(before, activeLines());
        scrollToCurrent();
    }

    /** 上/下一个：<b>循环</b>（R5.4）。还没查过时，「下一个」兼作「执行查询」。 */
    private void step(int delta) {
        if (hits.isEmpty()) {
            runSearch();
            return;
        }
        int[] before = activeLines();
        hitIndex = Math.floorMod(hitIndex + delta, hits.size());
        updateCount();
        onActiveLineChanged(before, activeLines());
        scrollToCurrent();
    }

    /** 关闭查找栏：清掉命中与高亮，计数归零（无命中不报错，R5.4）。 */
    private void closeFind() {
        int[] before = activeLines();
        queryField.clear();
        resetFind();
        countLabel.setText(I18n.get("viewer.findCount", "current", 0, "total", 0));
        onActiveLineChanged(before, activeLines());
    }

    private void updateCount() {
        countLabel.setText(I18n.get("viewer.findCount",
                "current", currentHitNumber(), "total", hits.size()));
    }

    private void scrollToCurrent() {
        if (hitIndex >= 0 && hitIndex < hits.size()) {
            listView.scrollTo(hits.get(hitIndex).line());
        }
    }

    /** 有高亮的行：当前命中所在行（没有命中 → 空数组）。 */
    private int[] activeLines() {
        int line = currentHitLine();
        return line < 0 ? new int[0] : new int[]{line};
    }

    /**
     * 命中变化后的<b>定点重绘</b>（本步最容易做出性能问题的地方）。
     *
     * <p>做法：只对"旧命中行"与"新命中行"这两个下标把 item 原位重设一次
     * （{@code ObservableList.set(i, sameItem)}）。{@code ListView} 的 VirtualFlow 收到
     * "某一项变了"后只重建<b>屏幕上可见</b>的那几个 cell —— 代价与窗口高度同阶、与文件行数无关。
     * 绝不 {@code setAll}（重建整个 items 模型）也不整表 {@code refresh()}：
     * 后者会把每一行的 item 都换一次，VirtualFlow 于是重建全部可见 cell 乃至重新定位，
     * MB 级源码上是肉眼可见的卡顿。</p>
     *
     * <p>为什么逐行 {@code set} 是"定点"而不是"整表"：实测（探针的
     * {@link #cellsBuiltInLastRefresh()}）逐行 {@code set} 只让 cell 工厂调用
     * <b>可见行数量级</b> 的次数，而 {@code setAll}/{@code refresh} 会把它推到总行数量级 ——
     * 判据因此是可量化的，不靠"感觉很快"。</p>
     */
    private void onActiveLineChanged(int[] before, int[] after) {
        int before0 = cellsBuilt;
        for (int line : union(before, after)) {
            if (line >= 0 && line < rows.size()) {
                rows.set(line, rows.get(line));
                repaintedLines++;
            }
        }
        cellsBuiltInLastRefresh = cellsBuilt - before0;
        repaints++;
    }

    private static int[] union(int[] a, int[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0 || (a.length == 1 && b.length == 1 && a[0] == b[0])) {
            return a;
        }
        List<Integer> out = new ArrayList<>(a.length + b.length);
        for (int x : a) {
            out.add(x);
        }
        for (int y : b) {
            if (!out.contains(y)) {
                out.add(y);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    // ==================== 渲染 ====================

    /** 等宽字体下"一字符多少像素"：用 {@link Text} 的实测布局宽反推，不写死魔数。 */
    private double measureCharWidth() {
        Text probe = new Text(SAMPLE_MEASURE);
        probe.setFont(monoFont());
        double w = probe.getLayoutBounds().getWidth() / SAMPLE_MEASURE.length();
        return w > 0 ? w : FONT_SIZE * 0.6;
    }

    private static Font monoFont() {
        return Font.font(FONT_FAMILY, FONT_SIZE);
    }

    /** 一行的 token 文本拼起来（R5 的列号就是这个坐标系里的列）。 */
    static String plainText(List<HtmlSyntaxTokens.Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (HtmlSyntaxTokens.Token t : tokens) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    /**
     * 一个行 cell：左边行号、右边每视觉行一个 {@link TextFlow}（E2 软换行）。
     *
     * <p>行号列宽度固定（{@link #digits} 位，右对齐），行号对每个逻辑行<b>只显示一次</b>、
     * 与第一折行对齐；后续折行是等宽 spacer 缩进（gutter 与 flows 同一 HBox，
     * 视觉行自然排在行号栏右侧）。cell 高度随折行增长 = 视觉行数 × {@link #ROW_HEIGHT}。</p>
     *
     * <p>⚠️ {@code TextFlow} 不设 {@code wrapText}：软换行是<b>按字符网格手工切</b>的
     * （见 {@link #buildVisualRows(Row)}）——{@code wrapText} 按 token 边界折行，折行几何
     * 与 {@link HtmlWrapMap} 的字符网格投影对不上，选择/高亮的坐标会错位。</p>
     */
    private final class SourceCell extends ListCell<Row> {
        private final Label gutter = new Label();
        /** 一视觉行一个 {@link TextFlow}（软换行：cell 高度 = 视觉行数 × 行高）。 */
        private final VBox flows = new VBox();
        private final HBox box = new HBox();
        /** 选择覆盖层（E1）：叠在内容上方的透明 Pane，折行后每视觉行一段矩形。 */
        private final Pane selPane = new Pane();
        /** cell 内容 = 行（行号 + 视觉行流）上面叠选择层。 */
        private final StackPane wrapper = new StackPane(box, selPane);

        private SourceCell() {
            gutter.getStyleClass().add("html-source-gutter");
            gutter.setAlignment(Pos.CENTER_RIGHT);
            gutter.setMinWidth(Region.USE_PREF_SIZE);
            // 行号只显示一次、与第一折行对齐：高度钉成一行高（后续折行是等宽 spacer 缩进）
            gutter.setPrefHeight(ROW_HEIGHT);
            gutter.setMinHeight(ROW_HEIGHT);
            gutter.setMaxHeight(ROW_HEIGHT);
            gutter.setTextFill(Color.web(HtmlSyntaxPalette.GUTTER_COLOR));
            gutter.setFont(monoFont());
            box.getStyleClass().add("html-source-row");
            box.setAlignment(Pos.TOP_LEFT);
            box.getChildren().addAll(gutter, flows);
            // 覆盖层对鼠标完全透明：命中测试仍落到下面的 Text 上（与没有选择层时一致）
            selPane.setMouseTransparent(true);
            setText(null);
        }

        /** 内容列（行号栏之后）在本 cell 里的场景 x 起点（取第一视觉行的 TextFlow 左缘）。 */
        private double contentSceneMinX() {
            if (flows.getChildren().isEmpty()) {
                return 0;
            }
            Node first = flows.getChildren().get(0);
            return first.localToScene(first.getBoundsInLocal()).getMinX();
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            cellsBuilt++;
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            gutter.setText(String.format("%0" + digits + "d", row.number()));
            gutter.setPrefWidth(gutterWidth());
            flows.getChildren().setAll(buildVisualRows(row));
            setGraphic(wrapper);
            paintSelection(row.number() - 1);
        }

        /**
         * 本行的选择段（与 {@code [0, 行长)} 的交集）按折行切成"每视觉行一段"，每段一个
         * 覆盖矩形；无交集 → 一个矩形都不画。只动覆盖层的矩形、不重建文本节点
         * （见 {@link HtmlSourceViewer#repaintSelection()}）。
         */
        private void paintSelection(int line) {
            HtmlTextSelection.Range r = HtmlSourceViewer.this.selectionRange();
            String text = line >= 0 && line < plainLines.size() ? plainLines.get(line) : null;
            if (r == null || text == null || line < r.startLine() || line > r.endLine()) {
                selPane.getChildren().clear();
                return;
            }
            int from = line == r.startLine() ? r.startCol() : 0;
            int to = line == r.endLine() ? r.endCol() : text.length();
            int cols = wrapCols();
            selPane.getChildren().clear();
            for (int[] seg : HtmlWrapMap.segments(text.length(), from, to, cols)) {
                int vRow = seg[0] / cols;
                Rectangle rect = new Rectangle(0, 0, 0, 0);
                rect.getStyleClass().add("html-source-selected");
                rect.setFill(Color.web(SELECTION_COLOR));
                rect.setX(gutterWidth() + (seg[0] - vRow * cols) * charWidth);
                rect.setY(vRow * ROW_HEIGHT);
                rect.setWidth((seg[1] - seg[0]) * charWidth);
                rect.setHeight(ROW_HEIGHT);
                selPane.getChildren().add(rect);
            }
        }

        /**
         * 把一行切成"每视觉行一个 TextFlow"（软换行按字符网格手工切，不用 TextFlow 的
         * {@code wrapText} —— 后者按 token 边界折、几何与选择坐标对不上）。行号只在第一
         * 视觉行旁边出现一次（gutter 与 flows 是同一 HBox 的两个格子）。
         */
        private List<Node> buildVisualRows(Row row) {
            String text = plainText(row.tokens());
            int cols = wrapCols();
            int rowCount = HtmlWrapMap.visualRowCount(text.length(), cols);
            boolean active = row.number() - 1 == currentHitLine();
            int[] hit = active ? currentHitColumns() : null;
            List<Node> out = new ArrayList<>(rowCount);
            for (int v = 0; v < rowCount; v++) {
                int from = v * cols;
                int to = Math.min(text.length(), (v + 1) * cols);
                out.add(buildRowFlow(row, from, to, hit));
            }
            return out;
        }

        /** 一个视觉行：逻辑列区间 {@code [from, to)} 内的 token 切片拼成一个固定行高的 TextFlow。 */
        private TextFlow buildRowFlow(Row row, int from, int to, int[] hit) {
            TextFlow flow = new TextFlow();
            flow.getStyleClass().add("html-source-line");
            flow.setPrefHeight(ROW_HEIGHT);
            flow.setMinHeight(ROW_HEIGHT);
            flow.setMaxHeight(ROW_HEIGHT);
            flow.getChildren().setAll(buildRowRuns(row, from, to, hit));
            return flow;
        }

        /**
         * 一行的 token 在逻辑列区间 {@code [from, to)} 内的切片（每视觉行调用一次）；
         * 当前命中的那段在切片内仍换成带高亮背景的 {@link StackPane}（命中跨折行边界时
         * 每个视觉行各出一段，样式不变、列号仍是<b>逻辑列</b>）。
         */
        private List<Node> buildRowRuns(Row row, int from, int to, int[] hit) {
            List<Node> runs = new ArrayList<>();
            int col = 0;
            for (HtmlSyntaxTokens.Token t : row.tokens()) {
                int len = t.text().length();
                int s = Math.max(from, col);
                int e = Math.min(to, col + len);
                if (e > s) {
                    String slice = t.text().substring(s - col, e - col);
                    if (hit != null && s < hit[1] && e > hit[0]) {
                        int localStart = Math.max(0, hit[0] - s);
                        int localEnd = Math.min(slice.length(), hit[1] - s);
                        if (localStart > 0) {
                            runs.add(textNode(t.kind(), slice.substring(0, localStart)));
                        }
                        runs.add(hitNode(t.kind(), slice.substring(localStart, localEnd)));
                        if (localEnd < slice.length()) {
                            runs.add(textNode(t.kind(), slice.substring(localEnd)));
                        }
                    } else {
                        runs.add(textNode(t.kind(), slice));
                    }
                }
                col += len;
            }
            if (runs.isEmpty()) {
                // 空行：给一个零宽节点，保证行高与其它行一致（页面上的 .lc 也是这个语义）
                runs.add(textNode(HtmlSyntaxTokens.Kind.TEXT, ""));
            }
            return runs;
        }

        /** 普通 token → {@link Text}（颜色来自共用的配色表）。 */
        private Text textNode(HtmlSyntaxTokens.Kind kind, String text) {
            Text node = new Text(text);
            node.getStyleClass().add("html-source-text");
            node.setFont(monoFont());
            HtmlSyntaxPalette palette = HtmlSyntaxPalette.of(kind);
            node.setFill(Color.web(palette == null ? HtmlSyntaxPalette.TEXT_COLOR : palette.color()));
            if (palette != null && palette.italic()) {
                node.setStyle("-fx-font-style: italic;");
            }
            if (palette != null && palette.underline()) {
                node.setUnderline(true);
            }
            return node;
        }

        /**
         * 命中片段：{@link TextFlow} 没有背景色，所以在命中段外面包一层带背景的
         * {@link StackPane}（样式 class {@code html-source-hit} 是命中样式的<b>唯一</b>
         * 定义处）。字体样式仍按原来的类别给，否则命中的注释会丢掉斜体
         * （在别处拼一份"命中时该长什么样"正是 R2.4 要消灭的第二份实现）。
         */
        private Node hitNode(HtmlSyntaxTokens.Kind kind, String text) {
            Text t = new Text(text);
            t.setFont(monoFont());
            t.setFill(Color.web(HtmlSyntaxPalette.TEXT_COLOR));
            t.getStyleClass().add("html-source-text");
            HtmlSyntaxPalette palette = HtmlSyntaxPalette.of(kind);
            if (palette != null && palette.italic()) {
                t.setStyle("-fx-font-style: italic;");
            }
            if (palette != null && palette.underline()) {
                t.setUnderline(true);
            }
            StackPane pane = new StackPane(t);
            pane.getStyleClass().add("html-source-hit");
            return pane;
        }
    }

    /** 一行源码：行号（1 基）+ token。 */
    record Row(int number, List<HtmlSyntaxTokens.Token> tokens) {
    }

    /**
     * 载入结果：归一化全文（查找的坐标基准）、逐行 token、行模型、逐行纯文本。
     *
     * <p>{@link #build(String)} 是纯 CPU、零 JavaFX 的部分，所以可以在后台线程跑。</p>
     */
    record Source(String normalized, List<HtmlSyntaxTokens.Line> lines,
                  List<Row> rows, List<String> plainLines) {

        static Source build(String html) {
            String normalized = HtmlSyntaxTokens.normalizeNewlines(html);
            List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(normalized);
            List<Row> rows = new ArrayList<>(lines.size());
            List<String> plain = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                rows.add(new Row(i + 1, lines.get(i).tokens()));
                plain.add(plainText(lines.get(i).tokens()));
            }
            return new Source(normalized, lines, rows, plain);
        }
    }
}
