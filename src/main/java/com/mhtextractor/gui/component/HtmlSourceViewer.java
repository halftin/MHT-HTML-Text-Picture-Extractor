package com.mhtextractor.gui.component;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.platform.HtmlSyntaxPalette;
import com.mhtextractor.platform.HtmlSyntaxTokens;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
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
 * <h2>横向滚动（R2.8）</h2>
 * <p>{@code ListView} 默认把行软换行、也不横向滚动，而源码视图的语义是
 * {@code white-space: pre}（与 {@code SourceViewPage} 的 {@code .lc} 一致）。
 * 做法：<b>{@link ScrollPane} 包住 {@code ListView}</b>，并把 {@code ListView} 的
 * {@code prefWidth}/{@code minWidth} 设成"行号栏 + 最长行"的像素宽
 * （行长按<b>等宽字体的字符数</b>折算，见 {@link #measureCharWidth()}）——
 * 于是超长行把内容撑宽、由外层 {@code ScrollPane} 横向滚动；行号栏与内容行同属一个
 * 被滚动的子节点，<b>横向滚动时不会错位</b>。</p>
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
 */
@Slf4j
public final class HtmlSourceViewer {

    /** 宿主 → 查看器。Weak 键：窗口关闭后不留引用（与 ParamSidePanel / NotificationHost 同法）。 */
    private static final Map<SidePanelHost, HtmlSourceViewer> VIEWERS = new WeakHashMap<>();

    /** 源码字号（与 {@code SourceViewPage} 的 {@code .row} 的 13px 对齐）。 */
    private static final double FONT_SIZE = 13;
    /** 等宽字体族（与页面上的 {@code Consolas,"Courier New",monospace} 同一顺序）。 */
    private static final String FONT_FAMILY = "Consolas, \"Courier New\", monospace";
    /** 行高（页面上是 {@code line-height:1.5}）。 */
    private static final double ROW_HEIGHT = FONT_SIZE * 1.5;
    /** 行号栏与内容之间的间距（页面上是 {@code margin-right:10px} 加一点内边距）。 */
    private static final double GUTTER_GAP = 14;
    /** 内容右侧留白（页面上的 {@code .lc} 没有右边距，这里留一点让最后一列不贴边）。 */
    private static final double CONTENT_TAIL = 24;
    /** 量字符宽用的样本（等宽字体下 40 个 M 的布局宽除以 40 即单字宽）。 */
    private static final String SAMPLE_MEASURE = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";

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
    /** 行号格式化宽度 = 总行数的位数（R2.8：行号列宽度固定，横向滚动时才不错位）。 */
    private int digits = 1;
    /** 单字符像素宽（等宽字体 ⇒ 行宽 = 字符数 × 本值）。 */
    private double charWidth = FONT_SIZE * 0.6;
    /** 内容列的总像素宽（行号栏 + 最长行 + 右侧留白）。 */
    private double contentWidth = 0;
    /** 归一化后的整篇源码（查找索引的坐标基准）。 */
    private String source = "";
    private List<HtmlSyntaxTokens.Line> lines = List.of();

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
     * （{@code MainController.openHtmlInBrowser} 的 {@code html-browser-launch} 线程）就不在 FX 线程上
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

    /** 行内容列当前的像素宽（= 行号栏 + 最长行 + 右侧留白）。 */
    public double contentPixelWidth() {
        return contentWidth;
    }

    /** 以纯文本取某行的内容。 */
    public String lineText(int index) {
        return index >= 0 && index < rows.size() ? plainText(rows.get(index).tokens()) : null;
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
     * <p>行宽按"等宽字体的字符数"折算而不是逐个量 {@code TextFlow}：后者要为上万行建
     * 一次性节点，正是 R2.7 要避免的开销。</p>
     */
    private void applySource(Source src) {
        digits = Math.max(1, String.valueOf(src.lines().size()).length());
        source = src.normalized();
        lines = src.lines();
        resetFind();
        charWidth = measureCharWidth();
        int longest = 0;
        for (String text : src.plainLines()) {
            longest = Math.max(longest, text.length());
        }
        contentWidth = digits * charWidth + GUTTER_GAP + longest * charWidth + CONTENT_TAIL;
        listView.setPrefWidth(contentWidth);
        listView.setMinWidth(contentWidth);
        scroller.setHvalue(0);
        rows.setAll(src.rows());
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
        // R2.8：默认不软换行 —— cell 的首选宽 = 内容宽（见 SourceCell.updateItem），
        // 行号栏与内容同属一个被横向滚动的子节点，所以滚动时两者一起移动、不会错位。
        listView.setFixedCellSize(ROW_HEIGHT);
        scroller.setId("sourceViewerScroll");
        scroller.getStyleClass().add("html-source-scroll");
        scroller.setContent(listView);
        scroller.setFitToWidth(false);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // 纵向滚动由内层 ListView 自己负责，外层不要再来一条
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
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
     * 一个行 cell：左边行号、右边 {@link TextFlow}。
     *
     * <p>行号列宽度固定（{@link #digits} 位，右对齐），内容列宽度固定为
     * {@link #contentWidth} 减去行号栏 —— 于是横向滚动时"行号 + 内容"作为整体移动，
     * 相对位置不变（R2.8 的判据之一）。</p>
     *
     * <p>⚠️ {@code TextFlow} 绝不设 {@code wrapText}：一旦软换行，超长行会折成多行，
     * 行号与内容立刻错位，也与源码视图的 {@code white-space: pre} 语义矛盾。</p>
     */
    private final class SourceCell extends ListCell<Row> {
        private final Label gutter = new Label();
        private final TextFlow flow = new TextFlow();
        private final HBox box = new HBox();

        private SourceCell() {
            gutter.getStyleClass().add("html-source-gutter");
            gutter.setAlignment(Pos.CENTER_RIGHT);
            gutter.setMinWidth(Region.USE_PREF_SIZE);
            gutter.setTextFill(Color.web(HtmlSyntaxPalette.GUTTER_COLOR));
            gutter.setFont(monoFont());
            flow.getStyleClass().add("html-source-line");
            flow.setMaxWidth(Double.MAX_VALUE);
            box.getStyleClass().add("html-source-row");
            box.setAlignment(Pos.TOP_LEFT);
            box.getChildren().addAll(gutter, flow);
            setText(null);
            setPrefHeight(ROW_HEIGHT);
            setMinHeight(ROW_HEIGHT);
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
            gutter.setPrefWidth(digits * charWidth + GUTTER_GAP);
            flow.getChildren().setAll(buildRuns(row));
            // cell 宽 = 内容宽：把行撑到最长行那么宽（横向滚动的依据），
            // 而不是让 ListView 自己去量 TextFlow（那会把整个列表拉成一行那么长）。
            double w = Math.max(contentWidth - CONTENT_TAIL, 0);
            setPrefWidth(w);
            setMinWidth(w);
            setMaxWidth(w);
            box.setPrefWidth(w);
            setGraphic(box);
        }

        /** 把一行的 token 变成节点；当前命中的那段换成带高亮背景的 {@link Label}。 */
        private List<Node> buildRuns(Row row) {
            List<Node> runs = new ArrayList<>();
            int col = 0;
            boolean active = row.number() - 1 == currentHitLine();
            int[] hit = active ? currentHitColumns() : null;
            for (HtmlSyntaxTokens.Token t : row.tokens()) {
                int len = t.text().length();
                if (hit != null && len > 0 && col < hit[1] && col + len > hit[0]) {
                    int localStart = Math.max(0, hit[0] - col);
                    int localEnd = Math.min(len, hit[1] - col);
                    if (localStart > 0) {
                        runs.add(textNode(t.kind(), t.text().substring(0, localStart)));
                    }
                    runs.add(hitNode(t.kind(), t.text().substring(localStart, localEnd)));
                    if (localEnd < len) {
                        runs.add(textNode(t.kind(), t.text().substring(localEnd)));
                    }
                } else {
                    runs.add(textNode(t.kind(), t.text()));
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
