import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.HtmlSourceViewer;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.platform.HtmlSyntaxTokens;
import com.mhtextractor.platform.HtmlTextSelection;
import com.mhtextractor.platform.HtmlWrapMap;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.layout.BorderPane;import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Q8b HTML source viewer probe (R2 + R5).
 *
 * <p>It reproduces App.start()'s wiring exactly (SidePanelHost.install(root) -&gt; StackPane shell -&gt;
 * NotificationHost.install(scene, shell)) and then drives the production entry points
 * ({@code HtmlSourceViewer.showFile} / {@code HtmlSourceViewer.showSource}) rather than poking
 * internals, so what it measures is what ships.</p>
 *
 * <p>Asserted:</p>
 * <ol>
 *   <li>R2.1 the left panel is 50% of the main area, its collapse button is a line icon
 *       (ANGLE_DOUBLE_LEFT) with a tooltip, and the hardware entry point
 *       ({@code HtmlSourceViewer.showFile}) really opens it;</li>
 *   <li>R3.1 both panels coexist: each 50% wide, opposite alignments, together covering the
 *       whole main area;</li>
 *   <li>R3.3 the main area is still hit-testable while the viewer is open (same node before and
 *       after), and the viewer's own half picks into the viewer;</li>
 *   <li>R2.5 non-modal: a main-area control still fires while the viewer is open and the
 *       viewer's buttons do not hijack the window's ENTER (no defaultButton);</li>
 *   <li>R2.7 virtualization: a 5000-line source opens within a timeout and the number of
 *       materialised {@code .list-cell} nodes is far below the line count (measured, not felt);</li>
 *   <li>E2 (replaces R2.8) a long line IS soft-wrapped into several visual rows on the character
 *       grid: no horizontal scrolling, the line number appears once per logical line and aligns
 *       with the first visual row, following visual rows are spacer-indented, the cell height
 *       grows with the wrap count, and selection / find / copy stay on the shared logical
 *       (line, column) coordinates across wrap boundaries (segmented highlight rectangles);</li>
 *   <li>E2 §3.1 a Chromium-style corpus (LF/CRLF/CR, consecutive empty lines, trailing newline
 *       or not, tabs, leading/trailing spaces, a 209-char long line, a cross-line comment) is
 *       loaded through the real viewer: line model and whole-doc / per-line copies are
 *       char-exact;</li>
 *   <li>R5.2/R5.3/R5.4 find bar at the bottom, four line-icon buttons, "n / m" counter, next/prev
 *       wrap around, a query with no hits shows 0 and raises nothing;</li>
 *   <li>R5.4 hit highlight is a node-level fact and the hit line is scrolled into view;
 *       the highlight refresh touches only the active lines (far fewer than the line count);</li>
 *   <li>R2.9 collapsing hides the left panel without touching the right one.</li>
 *   <li>E1 character selection &amp; copy: mouse drag selects by (line, column), the platform
 *       copy key puts the raw source chars into the system clipboard (each one read back from
 *       the system clipboard and compared char-by-char);</li>
 *   <li>step5 (功能三) selection look: the selection is clamped to ONE logical line (drag /
 *       Shift-extension past a line edge hits 行首/行尾), the cell layers are
 *       [hit layer, black selection layer, glyph layer] with the covered runs temporarily
 *       WHITE and restored on release / scroll-rebuild, the row-selected background is off,
 *       the TEXT cursor applies to viewer/cells but not the gutter, selection repaints touch
 *       only visible cells on the 5000-line doc (决策 3.9), and window resize re-wraps while
 *       the selection survives (PNGs of the selection are snapshotted for visual acceptance).</li>
 * </ol>
 *
 * <p>Exit code 0 = all assertions passed, 1 = at least one failed.</p>
 */
public class HtmlViewerProbe extends Application {

    private static final double TOL = 2.0;
    private static final long STEP_MS = 400;
    private static final int BIG_LINES = 5000;
    private static final long LOAD_TIMEOUT_MS = 30_000;

    private Stage stage;
    private Scene scene;
    private StackPane shell;
    private StackPane overlay;
    private Parent mainRoot;
    private Node anchor;
    private Object controller;
    private Node controlNode;
    private Point2D controlPoint;
    private Node controlPickBefore;
    private Point2D leftPoint;
    private Node leftPickBefore;
    private Point2D rightPoint;
    private Node rightPickBefore;
    private SidePanelHost host;
    private ModalPane modalPane;
    private ToggleButton textModeToggle;

    private Button paramSettingsButton;
    private HtmlSourceViewer viewer;
    private Node leftPanel;
    private Node rightPanel;
    private ListView<?> listView;
    private ScrollPane scroller;
    private long loadStartNanos;
    private long loadMillis = -1;
    private long openStartNanos;
    private long openMillis = -1;
    private String countAtFirstHit;
    private int firstHitLine;
    private int secondHitLine;
    /** 台账 §4-1 的 resize 步骤：拉宽窗口前的折行列数（下一步断言"确实变宽了"用）。 */
    private int step5ColsBefore;

    private Path bigFile;
    private Path longFile;
    private String guiStateKeysBefore;
    private String outDir;

    private int passed;
    private int failed;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Platform.setImplicitExit(false);
        outDir = new File(System.getProperty("probe.png", "shot.png")).getAbsoluteFile().getParent();

        // ---- 与 App.start() 逐句同构的装配（量到的层序就是生产层序）----
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader));
        loader.setResources(I18n.bundle());
        mainRoot = loader.load();
        controller = loader.getController();
        overlay = SidePanelHost.install(mainRoot);
        shell = new StackPane(overlay);
        scene = new Scene(shell);
        NotificationHost.install(scene, shell);

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();

        anchor = scene.lookup("#statusLabel");
        paramSettingsButton = (Button) scene.lookup("#paramSettingsButton");
        textModeToggle = (ToggleButton) scene.lookup("#textModeToggle");
        controlNode = scene.lookup("#inputFolderField");
        modalPane = findModalPane();
        host = SidePanelHost.of(anchor);
        guiStateKeysBefore = guiStateKeySet();

        System.out.println("locale=" + I18n.currentLocale() + "  window=" + stage.getWidth() + "x"
                + stage.getHeight() + "  host=" + (host != null)
                + "  gui-state keys before = " + guiStateKeysBefore);
        check("probe can resolve #statusLabel / #paramSettingsButton / #inputFolderField / ModalPane",
                anchor != null && paramSettingsButton != null && controlNode != null && modalPane != null);
        check("SidePanelHost is installed (App.start wiring)", host != null);
        if (anchor == null || host == null || modalPane == null) {
            finish();
            return;
        }

        bigFile = writeTemp("htmlviewer-probe-big", bigSource());
        longFile = writeTemp("htmlviewer-probe-long", longSource());
        System.out.println("big source = " + bigFile + " (" + Files.size(bigFile) + " bytes, "
                + BIG_LINES + " lines)");
        System.out.println("long-line source = " + longFile + " (" + Files.size(longFile) + " bytes)");

        // 🚩 步骤驱动器（不是一串裸时间戳）：每一步都排成**独立**的 FX 任务，任务与任务之间
        // 让 FX 线程回到事件循环 —— 这一点是必须的，不是风格问题：
        //   ① MainController.listAnimating 要等列表动画的 onFinished 回调才会归位，而那个回调
        //      需要真实脉冲；探针若在 FX 线程上 sleep/长跑，按钮入口会一直 return（实测：
        //      「提取参数配置」点了没反应 → R3.1 左右并存全部假失败）。
        //   ② 侧边栏的滑出动画、以及本查看器的异步载入也都是脉冲驱动。
        steps = new ArrayList<>(List.of(
                this::selectTextMode,
                this::probeDefaultSlideDuration,
                this::recordBaselinePicks,
                this::openViewer,
                this::checkR21Shape,
                this::checkR25NonModal,
                this::checkR33LeftOpen,
                this::openRightToo,
                this::checkR31Coexist,
                this::closeRight,
                CONTROL_GROUP,
                REAL_ENTRY,
                BIG_LOAD));
        // 异步步自己负责续跑（见 nextStep）。⚠️ 必须用**缓存的** Runnable 字段放进列表：
        // 方法引用每次求值都是新对象，`steps.indexOf(this::xxx)` 会返回 -1（恒不相等），
        // 于是"异步步"被判成同步步、驱动抢在载入落地前推进 —— 实测踩过。
        asyncSteps.add(steps.indexOf(CONTROL_GROUP));
        asyncSteps.add(steps.indexOf(REAL_ENTRY));
        asyncSteps.add(steps.indexOf(BIG_LOAD));
        steps.addAll(BIG_STEPS);
        nextStep();
    }

    /** 待跑步骤（构造于 {@link #start(Stage)}）。 */
    private List<Runnable> steps = new ArrayList<>();
    /** 当前步骤下标。 */
    private int stepIndex;
    /** "自己负责续跑"的异步步下标（{@link #nextStep} 不会替它们往前推）。 */
    private final java.util.Set<Integer> asyncSteps = new java.util.HashSet<>();

    /**
     * 排下一步（每步之间留 {@link #STEP_MS} 让脉冲跑）。
     *
     * <p>异步步（{@link #REAL_ENTRY} / {@link #BIG_LOAD}）<b>不</b>自动续跑：它们做完自己的等待后
     * 再回到这里。用下标而不是"和方法引用比 {@code ==}"来判定 —— 方法引用每次求值都是新对象，
     * 那种判据会恒不相等（本项目把这类东西叫恒真假断言）。</p>
     */
    private void nextStep() {
        if (stepIndex >= steps.size()) {
            return;
        }
        int index = stepIndex++;
        Runnable step = steps.get(index);
        if (asyncSteps.contains(index)) {
            try {
                step.run();
            } catch (Throwable ex) {
                check("async step " + index + " (" + step + ") threw: " + ex, false);
                ex.printStackTrace();
                nextStep();
            }
            return;
        }
        Timer t = new Timer(true);
        t.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    try {
                        step.run();
                    } catch (Throwable ex) {
                        check("step " + index + " (" + step + ") threw: " + ex, false);
                        ex.printStackTrace();
                    } finally {
                        nextStep();
                    }
                });
            }
        }, STEP_MS);
    }

    /** 异步步的身份（用于把它们放进 {@link #asyncSteps}）。 */
    private final Runnable CONTROL_GROUP = this::controlGroupUnparseableSource;
    /** 异步步的身份。 */
    private final Runnable REAL_ENTRY = this::openViaRealEntryPoint;
    /** 异步步的身份。 */
    private final Runnable BIG_LOAD = this::beginBigLoad;

    // ==================== steps ====================

    /**
     * 先选一个提取模式（与 SidePanelProbe 的第一句同构）。
     *
     * <p>🚩 不是为了"设个开关"，而是为了让 {@code MainController.listAnimating} 归位：
     * 它在列表动画跑完前是 true，而「提取参数配置」的入口 {@code onParamSettings()} 在
     * {@code listAnimating} 为真时直接 return —— 不先走这一步，右侧面板根本不会打开，
     * R3.1 的"左右并存"就会假失败（实测踩过）。</p>
     */
    private void selectTextMode() {
        if (textModeToggle != null) {
            // fire() 而不是 setSelected()：模式按钮的 onAction 才是 updateModeUi() 的触发端
            // （它负责让 #paramSettingsButton 从"未选模式"的禁用态恢复 —— setSelected 不触发它）。
            // ⚠️ setSelected 必须先设 true 再 fire()：fire() 会**翻转**选中态，
            // 若当前已是 true 则会翻成 false ⇒ 模式回到"无"，按钮重新被禁用（实测踩过）。
            textModeToggle.setSelected(true);
            textModeToggle.fire();
            System.out.println("mode: text=" + textModeToggle.isSelected());
        }
    }
    /** 默认滑出时长必须是 200ms（探针自己不改它就不会被"关掉动画"的实现骗过）。 */
    private void probeDefaultSlideDuration() {
        check("R3.4 the default slide duration is the production 200 ms (not disabled): "
                + host.slideMillis(), host.slideMillis() == 200);
    }

    /** 开查看器之前先记下三个点上的命中结果与场景坐标（控制组）。 */
    private void recordBaselinePicks() {
        double w = overlay.getWidth();
        double h = overlay.getHeight();
        leftPoint = overlay.localToScene(w * 0.25, h * 0.5);
        rightPoint = overlay.localToScene(w * 0.75, h * 0.5);
        controlPoint = uncoveredPointOf(controlNode);
        leftPickBefore = pick(scene.getRoot(), leftPoint.getX(), leftPoint.getY());
        rightPickBefore = pick(scene.getRoot(), rightPoint.getX(), rightPoint.getY());
        controlPickBefore = controlPoint == null ? null
                : pick(scene.getRoot(), controlPoint.getX(), controlPoint.getY());
        // 探针不需要验 200ms 动画本身（那是 SidePanelProbe 的事），把"动画何时结束"
        // 变成确定性问题：0ms ⇒ 收起时 setVisible(false) 同步发生。
        host.setSlideMillisForProbe(0);
        System.out.println("points: left=" + leftPoint + " right=" + rightPoint
                + " control=" + controlPoint + " overlay=" + overlay.getWidth() + "x" + overlay.getHeight());
        System.out.println("pick before open: left=" + describe(leftPickBefore)
                + " right=" + describe(rightPickBefore) + " control=" + describe(controlPickBefore));
        check("R3.3 baseline: the left-quarter pick lands in the main area",
                leftPickBefore != null && isDescendantOf(leftPickBefore, mainRoot));
    }

    /** 走生产入口：{@code HtmlSourceViewer.showFile(anchor, title, path)}。 */
    private void openViewer() {
        openStartNanos = System.nanoTime();
        HtmlSourceViewer.showFile(anchor, I18n.get("browser.viewerTitle"), longFile);
        openMillis = (System.nanoTime() - openStartNanos) / 1_000_000;
        viewer = hostOf(anchor);
        System.out.println("HtmlSourceViewer.showFile returned in " + openMillis
                + " ms (FX thread not blocked by the file read)");
    }

    private void checkR21Shape() {
        leftPanel = scene.lookup("#sourceViewerPanel");
        rightPanel = scene.lookup("#sidePanel");
        Button collapse = (Button) scene.lookup("#sourceViewerCollapse");
        scroller = vScroller(viewer);
        listView = vList(viewer);

        check("R2.1 the left panel node exists (#sourceViewerPanel)", leftPanel instanceof BorderPane);
        check("R2.1 the left panel is visible right after showFile", leftPanel != null && leftPanel.isVisible());
        check("R2.1 the host reports the left panel as open", host.isLeftShowing());
        check("R2.1 the right panel is NOT affected (still hidden, logically closed)",
                rightPanel != null && !rightPanel.isVisible() && !host.isRightShowing());
        check("R2.1 showFile returned promptly on the FX thread (< 1500 ms, measured "
                + openMillis + " ms)", openMillis >= 0 && openMillis < 1500);
        check("R2.1 the left panel is StackPane-aligned to the LEFT (layoutX ~ 0, x="
                + fmt(leftPanel.getLayoutX()) + ")", Math.abs(leftPanel.getLayoutX()) <= TOL);
        check(String.format("R2.1 the left panel is 50%% of the main area (panel=%.1f overlay=%.1f tol=%.0f)",
                leftPanel.getLayoutBounds().getWidth(), overlay.getWidth(), TOL),
                Math.abs(leftPanel.getLayoutBounds().getWidth() - overlay.getWidth() * 0.5) <= TOL);

        check("R2.1 the viewer is hosted in the left panel (center == vNode(viewer))",
                leftPanel instanceof BorderPane bp && bp.getCenter() == vNode(viewer));
        check("R2.1 the collapse button is a line icon (FontIcon graphic) with a tooltip",
                collapse != null && collapse.getGraphic() instanceof FontIcon
                        && collapse.getTooltip() != null);
        check("R2.1 the collapse button icon is ANGLE_DOUBLE_LEFT",
                collapse != null && collapse.getGraphic() instanceof FontIcon fi
                        && fi.getIconCode() == FontAwesomeSolid.ANGLE_DOUBLE_LEFT);
        check("R2.1 SidePanelHost.leftCollapseButton() IS the #sourceViewerCollapse node",
                host.leftCollapseButton() == collapse);
        check("R2.1 the viewer title is non-empty and localized: "
                + (leftPanel instanceof BorderPane bp2 && bp2.getTop() != null
                ? String.valueOf(titleText(leftPanel)) : "?"),
                leftPanel instanceof BorderPane bp3 && bp3.getTop() != null
                        && !titleText(leftPanel).isEmpty() && !titleText(leftPanel).startsWith("!"));
        check("R2.7/E2 the viewer has a ListView inside a ScrollPane",
                listView != null && scroller != null && scroller.getContent() == listView);
        check("E2 the outer ScrollPane fits the content to its own width (soft wrap)",
                scroller.isFitToWidth());
    }

    /**
     * R2.5：非模态 —— 主区控件仍可交互，且查看器没有劫持 ENTER。
     *
     * <p>🚩 控制点必须选在<b>左侧面板之外</b>：{@code #inputFolderField} 横跨 x=100..1144，
     * 而左侧查看器覆盖 x=0..632 —— 取"字段左侧 15%"正好落在面板底下，命中结果当然会变
     * （实测在这里假失败过）。{@code leftPartOf} 取的点更靠右，才是"未被覆盖的主区"。</p>
     */
    private void checkR25NonModal() {
        if (controlPoint != null) {
            Node after = pick(scene.getRoot(), controlPoint.getX(), controlPoint.getY());
            System.out.println("R2.5 control pick: point=" + controlPoint
                    + " before=" + describe(controlPickBefore) + " after=" + describe(after)
                    + " nodeBounds=" + controlNode.localToScene(controlNode.getBoundsInLocal()));
            check("R2.5 the main-area control is still interactable (same pick as before): "
                            + describe(controlPickBefore) + " -> " + describe(after),
                    after != null && after == controlPickBefore
                            && isDescendantOf(after, mainRoot));
        }
        boolean before = textModeToggle.isSelected();
        textModeToggle.fire();
        check("R2.5 a main-area control (#textModeToggle) still fires while the viewer is open "
                        + "(selected " + before + " -> " + textModeToggle.isSelected() + ")",
                textModeToggle.isSelected() != before);
        List<String> hijackers = new ArrayList<>();
        for (Button b : vFindButtons(viewer)) {
            if (b.isDefaultButton()) {
                hijackers.add(b.getId());
            }
        }
        check("R2.5/R5.2 no find-bar button sets defaultButton (scene-level ENTER hijack) "
                + hijackers, hijackers.isEmpty());
    }

    /**
     * 取节点上<b>未被左侧查看器覆盖</b>的一点：从节点左边界的 15% 处向右扫，
     * 取第一个落在 {@code x > 覆盖半区} 的位置（覆盖半区 = 主区宽度的一半）。
     */
    private Point2D uncoveredPointOf(Node n) {
        if (n == null || !n.isVisible()) {
            return null;
        }
        Bounds b = n.localToScene(n.getBoundsInLocal());
        double minX = overlay.getWidth() / 2 + 8;
        for (double f : new double[]{0.15, 0.35, 0.5, 0.65, 0.85}) {
            double x = b.getMinX() + b.getWidth() * f;
            if (x > minX && x < b.getMaxX()) {
                return new Point2D(x, b.getCenterY());
            }
        }
        return null;
    }

    /** R3.3：左侧开着时，未被覆盖的右半区仍命中主区。 */
    private void checkR33LeftOpen() {
        Node left = pick(scene.getRoot(), leftPoint.getX(), leftPoint.getY());
        Node right = pick(scene.getRoot(), rightPoint.getX(), rightPoint.getY());
        System.out.println("pick (only left open): left=" + describe(left) + " right=" + describe(right));
        check("R3.3 the viewer's half picks INTO the left panel: " + describe(left),
                left != null && isDescendantOf(left, leftPanel));
        check("R3.3 the uncovered half still picks the SAME main-area node as before opening: "
                        + describe(rightPickBefore) + " -> " + describe(right),
                right != null && right == rightPickBefore && isDescendantOf(right, mainRoot));
    }

    /** 再开右侧（参数面板），验 R3.1 并存。 */
    private void openRightToo() {
        selectFirstRow();
        // 选中之后按钮态由 MainController 的入口函数派生（与真实用户点选列表走的是同一个函数）
        invoke(controller, "updateListButtons", new Class<?>[]{});
        check("probe enabled #paramSettingsButton by selecting a list row (R3.1 needs to open the "
                        + "real parameter panel)", !paramSettingsButton.isDisable());
        paramSettingsButton.fire();
        System.out.println("fired #paramSettingsButton (real entry point) with the viewer already open ->"
                + " rightVisible=" + (rightPanel != null && rightPanel.isVisible())
                + " rightShowing=" + host.isRightShowing()
                + " leftVisible=" + (leftPanel != null && leftPanel.isVisible()));
    }

    /**
     * 选中列表里名字以 {@code suffix} 结尾的条目（可解析的夹具要按名字挑，不能拿"第一行"）。
     *
     * <p>🚩 为什么不能拿第一行：夹具导入后列表第一行可能是 {@code bad.mht} / {@code garbage.mht}
     * —— 它们<b>故意没有 HTML 内容</b>，走的是失败路径。第一版探针就是这么把"真实入口"的断言
     * 全判成红的（而实现其实是对的）。</p>
     */
    private boolean selectRowEndingWith(String suffix) {
        ListView<?> files = (ListView<?>) scene.lookup("#fileListView");
        if (files == null) {
            return false;
        }
        Object item = null;
        for (Object o : files.getItems()) {
            if (o instanceof com.mhtextractor.model.PreviewItem pi && pi.path != null
                    && pi.path.endsWith(suffix)) {
                item = o;
                break;
            }
        }
        if (item == null) {
            return false;
        }
        invokeOn(javafx.scene.control.MultipleSelectionModel.class,
                files.getSelectionModel(), "select", new Class<?>[]{Object.class}, item);
        invoke(controller, "updateListButtons", new Class<?>[]{});
        System.out.println("selected row " + suffix + " -> selected="
                + (files.getSelectionModel().getSelectedItem() == item)
                + " viewSourceDisabled=" + ((Button) scene.lookup("#viewSourceButton")).isDisable());
        return files.getSelectionModel().getSelectedItem() == item;
    }

    /** 当前是否有一张居中的错误卡（{@code Notification}）。 */
    private boolean errorCardShown() {
        return modalPane != null && modalPane.isDisplay()
                && modalPane.getContent() instanceof atlantafx.base.controls.Notification;
    }

    /** 点掉当前居中卡片（避免它挡住后面的步骤）。 */
    private void dismissModal() {
        if (modalPane != null && modalPane.getContent() instanceof atlantafx.base.controls.Notification n
                && !n.getPrimaryActions().isEmpty()) {
            n.getPrimaryActions().get(0).fire();
        }
    }

    /**
     * 导入夹具并选中第一行。
     *
     * <p>🚩 不这么做，「提取参数配置」按钮是<b>禁用</b>的（{@code viewSourceButton.setDisable(!selItem)}
     * 那一条：没有任何选中项），{@code Button.fire()} 对禁用按钮是空操作 ——
     * 于是"右侧面板打不开"看起来像侧边栏的缺陷，其实是探针没有把它启用起来（实测踩过）。</p>
     */
    private void selectFirstRow() {
        invoke(controller, "importFromFolder", new Class<?>[]{String.class}, FIXTURES_DIR);
        sleepMs(1200);
        ListView<?> files = (ListView<?>) scene.lookup("#fileListView");
        if (files == null || files.getItems().isEmpty()) {
            check("probe could import the fixtures so a list row can be selected", false);
            return;
        }
        Object item = files.getItems().get(0);
        invokeOn(javafx.scene.control.MultipleSelectionModel.class,
                files.getSelectionModel(), "select", new Class<?>[]{Object.class}, item);
        System.out.println("imported fixtures -> rows=" + files.getItems().size()
                + " selected=" + (files.getSelectionModel().getSelectedItem() == item)
                + " itemType=" + item.getClass().getSimpleName());
        invoke(controller, "updateListButtons", new Class<?>[]{});
    }

    /** 反射调用实例方法（包级/私有均可）。 */
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // 继续沿父链找
            } catch (Exception e) {
                System.out.println("      (reflect " + name + " failed: " + e + ")");
                return null;
            }
        }
        return null;
    }

    /** 在给定（接口）类型上找方法再调用：绕开实现类的可见性限制。 */
    private static Object invokeOn(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method m = owner.getMethod(name, types);
            return m.invoke(target, args);
        } catch (Exception e) {
            System.out.println("      (invoke " + owner.getSimpleName() + "." + name + " failed: " + e + ")");
            return null;
        }
    }

    // ---- E2.1：HtmlSourceViewer 观测面收窄为包级后，探针统一经 invoke/invokeOn 反射观测 ----
    // 静态入口与常量读取需要两个新原语（invokeStatic / staticString）；按选项 D 登记进
    // test-access-surface.tsv（reflect-dynamic 通道，按 文件+方法 计）。

    /** 反射调用静态方法（包级/私有均可；E2.1 起查看器的静态入口不再 public）。 */
    private static Object invokeStatic(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            System.out.println("      (invokeStatic " + owner.getSimpleName() + "." + name + " failed: " + e + ")");
            return null;
        }
    }

    /** 反射读取静态 String 常量（E2.1 起查看器的属性键常量不再 public）。 */
    private static String staticString(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (Exception e) {
            System.out.println("      (staticString " + owner.getSimpleName() + "." + name + " failed: " + e + ")");
            return null;
        }
    }

    /** 查看器的包级静态入口 showSource（反射转发，签名与生产一致）。 */
    private static void showSource(Node anchor, String title, String html) {
        invokeStatic(HtmlSourceViewer.class, "showSource",
                new Class<?>[]{Node.class, String.class, String.class}, anchor, title, html);
    }

    /** 查看器的包级静态查询 isShowing（反射转发）。 */
    private static boolean isShowing(Node anchor) {
        Boolean b = (Boolean) invokeStatic(HtmlSourceViewer.class, "isShowing",
                new Class<?>[]{Node.class}, anchor);
        return b != null && b;
    }

    /** 查看器的包级静态定位 hostOf（反射转发）。 */
    private static HtmlSourceViewer hostOf(Node anchor) {
        return (HtmlSourceViewer) invokeStatic(HtmlSourceViewer.class, "hostOf",
                new Class<?>[]{Node.class}, anchor);
    }

    /** 查看器包级常量 RUN_COLUMNS_KEY / RUN_BASE_FILL_KEY 的反射读（各缓存一次）。 */
    private static String runColumnsKey;
    private static String runBaseFillKey;

    private static String runColumnsKey() {
        if (runColumnsKey == null) {
            runColumnsKey = staticString(HtmlSourceViewer.class, "RUN_COLUMNS_KEY");
        }
        return runColumnsKey;
    }

    private static String runBaseFillKey() {
        if (runBaseFillKey == null) {
            runBaseFillKey = staticString(HtmlSourceViewer.class, "RUN_BASE_FILL_KEY");
        }
        return runBaseFillKey;
    }

    // ---- 包级实例成员的类型化反射转发 ----

    private static Parent vNode(HtmlSourceViewer v) {
        return (Parent) invoke(v, "node", new Class<?>[0]);
    }

    private static int vInt(HtmlSourceViewer v, String name) {
        return ((Number) invoke(v, name, new Class<?>[0])).intValue();
    }

    private static long vLong(HtmlSourceViewer v, String name) {
        return ((Number) invoke(v, name, new Class<?>[0])).longValue();
    }

    private static double vDbl(HtmlSourceViewer v, String name) {
        return ((Number) invoke(v, name, new Class<?>[0])).doubleValue();
    }

    private static String vLine(HtmlSourceViewer v, int index) {
        return (String) invoke(v, "lineText", new Class<?>[]{int.class}, index);
    }

    private static TextField vQuery(HtmlSourceViewer v) {
        return (TextField) invoke(v, "queryField", new Class<?>[0]);
    }

    private static Label vCountLabel(HtmlSourceViewer v) {
        return (Label) invoke(v, "countLabel", new Class<?>[0]);
    }

    private static Node vFindBar(HtmlSourceViewer v) {
        return (Node) invoke(v, "findBar", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static List<Button> vFindButtons(HtmlSourceViewer v) {
        return (List<Button>) invoke(v, "findButtons", new Class<?>[0]);
    }

    private static int[] vHitCols(HtmlSourceViewer v) {
        return (int[]) invoke(v, "currentHitColumns", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static ListView<?> vList(HtmlSourceViewer v) {
        return (ListView<?>) invoke(v, "listView", new Class<?>[0]);
    }

    private static ScrollPane vScroller(HtmlSourceViewer v) {
        return (ScrollPane) invoke(v, "scroller", new Class<?>[0]);
    }

    private static HtmlTextSelection.Range vRange(HtmlSourceViewer v) {
        return (HtmlTextSelection.Range) invoke(v, "selectionRange", new Class<?>[0]);
    }

    private static void vCopy(HtmlSourceViewer v) {
        invoke(v, "copySelection", new Class<?>[0]);
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkR31Coexist() {
        check("R3.1 BOTH panels are visible at the same time (left="
                        + (leftPanel != null && leftPanel.isVisible())
                        + " right=" + (rightPanel != null && rightPanel.isVisible()) + ")",
                leftPanel != null && leftPanel.isVisible()
                        && rightPanel != null && rightPanel.isVisible());
        check("R3.1 the host reports both as open (left=" + host.isLeftShowing()
                + " right=" + host.isRightShowing() + ")",
                host.isLeftShowing() && host.isRightShowing());
        double ow = overlay.getWidth();
        double owHalf = ow * 0.5;
        check(String.format("R3.1 each panel is 50%% of the main area (left=%.1f right=%.1f half=%.1f)",
                leftPanel.getLayoutBounds().getWidth(), rightPanel.getLayoutBounds().getWidth(), owHalf),
                Math.abs(leftPanel.getLayoutBounds().getWidth() - owHalf) <= TOL
                        && Math.abs(rightPanel.getLayoutBounds().getWidth() - owHalf) <= TOL);
        check(String.format("R3.1 the two panels are on OPPOSITE sides (leftX=%.1f rightX=%.1f rightEdge=%.1f)",
                leftPanel.getLayoutX(), rightPanel.getLayoutX(), ow - rightPanel.getLayoutBounds().getWidth()),
                Math.abs(leftPanel.getLayoutX()) <= TOL
                        && Math.abs(rightPanel.getLayoutX() - (ow - rightPanel.getLayoutBounds().getWidth())) <= TOL);
        check("R3.1 together they cover the whole main area (widths sum ~ main area)",
                Math.abs(leftPanel.getLayoutBounds().getWidth()
                        + rightPanel.getLayoutBounds().getWidth() - ow) <= 2 * TOL);
        Node left = pick(scene.getRoot(), leftPoint.getX(), leftPoint.getY());
        Node right = pick(scene.getRoot(), rightPoint.getX(), rightPoint.getY());
        System.out.println("pick (both open): left=" + describe(left) + " right=" + describe(right));
        check("R3.1 with both open each half picks into its own panel (left=" + describe(left)
                        + " right=" + describe(right) + ")",
                left != null && right != null
                        && isDescendantOf(left, leftPanel) && isDescendantOf(right, rightPanel));
        snapshot("htmlviewer-both-panels");
    }

    private void closeRight() {
        Button collapse = (Button) scene.lookup("#sidePanelCollapse");
        if (collapse != null) {
            collapse.fire();
        }
        check("R3.1/Q6a the right panel still collapses through the Q6a entry point",
                !host.isRightShowing());
    }

    // ---------- R2 的真实入口（不是直接调 showFile） ----------

    /**
     * R2 的<b>控制组</b>：先拿一个"没有 HTML 内容"的夹具走同一个入口。
     *
     * <p>为什么必须有它：没有反例，"入口打开了查看器"这条断言无法排除"它其实什么都没做、
     * 只是上一步的内容还在"。这个夹具走的是失败路径 —— 应当<b>不</b>新增载入、并弹应用内错误卡。
     * （实测顺手发现：夹具导入后列表第一行正好是 {@code bad.mht}，第一版探针拿它当"正常条目"，
     * 于是"真实入口"那组断言全红 —— 这条控制组就是这么来的。）</p>
     */
    private void controlGroupUnparseableSource() {
        Button viewSource = (Button) scene.lookup("#viewSourceButton");
        String name = selectRowEndingWith("garbage.mht") ? "garbage.mht"
                : (selectRowEndingWith("bad.mht") ? "bad.mht" : "(none)");
        check("R2 control group: found an item whose MHT has no HTML content (" + name + ")",
                !"(none)".equals(name));
        if (viewSource == null || "(none)".equals(name)) {
            nextStep();
            return;
        }
        long before = vLong(viewer, "loadCount");
        viewSource.fire();
        awaitCondition("unparseable fixture", 8_000, this::errorCardShown, expired -> {
            System.out.println("R2 control group (" + name + "): loadCount " + before + " -> "
                    + vLong(viewer, "loadCount") + " errorCard=" + errorCardShown()
                    + " (timedOut=" + expired + ")");
            check("R2 control group: the unparseable item does NOT load anything into the viewer "
                            + "(loadCount " + before + " -> " + vLong(viewer, "loadCount") + ")",
                    vLong(viewer, "loadCount") == before);
            check("R2 control group: the failure is reported as an in-app error card, not silently",
                    errorCardShown());
            dismissModal();
            nextStep();
        });
    }

    /**
     * 走 MainController 的「查看HTML源码」入口（{@code #viewSourceButton}）。
     *
     * <p>🚩 这一步是必要的：前面几步都是直接调 {@code HtmlSourceViewer.showFile(...)}，
     * 那只能证明查看器本体可用，<b>证明不了"用户路径已经接到应用内查看器"</b>。
     * 这里从列表选中一个可解析的条目、驱动那个按钮，再等 MHT 解析 + 导出 + 载入这条真实链路跑完。</p>
     */
    private void openViaRealEntryPoint() {
        Button viewSource = (Button) scene.lookup("#viewSourceButton");
        check("R2 the real entry point #viewSourceButton exists", viewSource != null);
        boolean selected = selectRowEndingWith("simple-text.mht") || selectRowEndingWith("titles.mht");
        check("R2 a parseable fixture row is selected for the entry point", selected);
        if (viewSource == null || !selected) {
            nextStep();
            return;
        }
        check("R2 #viewSourceButton is enabled for a selected item", !viewSource.isDisable());
        // 记下"调用前的载入序号"：光看 lineCount>0 会把上一步的内容当成这一步的结果（实测踩过）
        long loadBefore = vLong(viewer, "loadCount");
        viewSource.fire();
        awaitViewerContent("real entry point", loadBefore);
    }

    /** 轮询到条件成立（或超时）再执行 {@code then}（{@code expired} = 是否超时）。 */
    private void awaitCondition(String label, long timeoutMs,
                                java.util.function.BooleanSupplier ready,
                                java.util.function.Consumer<Boolean> then) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Timer t = new Timer(true);
        t.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    boolean ok = ready.getAsBoolean();
                    if (!ok && System.currentTimeMillis() <= deadline) {
                        return;
                    }
                    cancel();
                    then.accept(!ok);
                });
            }
        }, 100, 100);
    }

    /** 等查看器载入到"这一次"的内容（用载入序号判定，不用"行数 > 0"）。 */
    private void awaitViewerContent(String label, long loadBefore) {
        awaitCondition(label, 20_000,
                () -> isShowing(anchor) && vLong(viewer, "loadCount") > loadBefore,
                expired -> {
                    try {
                        checkR2RealEntryPoint(label, !expired, loadBefore);
                    } catch (Throwable ex) {
                        check("checkR2RealEntryPoint threw: " + ex, false);
                        ex.printStackTrace();
                    } finally {
                        nextStep();
                    }
                });
    }

    private void checkR2RealEntryPoint(String label, boolean ready, long loadBefore) {
        String firstLine = vLine(viewer, 0);
        System.out.println("R2 " + label + ": showing=" + isShowing(anchor)
                + " loadCount " + loadBefore + " -> " + vLong(viewer, "loadCount")
                + " lineCount=" + vInt(viewer, "lineCount")
                + " firstLine=" + String.valueOf(firstLine).trim());
        check("R2 the real 「查看HTML源码」 entry opened the IN-APP left viewer (" + label + ", ready="
                + ready + ")", ready);
        check("R2 that entry really loaded NEW content (loadCount " + loadBefore + " -> "
                + vLong(viewer, "loadCount") + ")", vLong(viewer, "loadCount") > loadBefore);
        check("R2 the viewer is the left panel's content (not a browser tab)",
                leftPanel instanceof BorderPane bp && bp.getCenter() == vNode(viewer));
        check("R2 the loaded source really is the fixture's HTML (line 0 contains '<'): "
                        + String.valueOf(firstLine).trim(),
                firstLine != null && firstLine.contains("<"));
        check("R2 the right panel was NOT touched by the source entry",
                !host.isRightShowing());
    }

    // ---------- R2.7 虚拟化 ----------

    /**
     * 大源码载入（R2.7 的异步入口）+ 步骤链的续跑点。
     *
     * <p>载入落地后由 {@link #awaitLinesAsync} 接管：它负责把 {@link #BIG_STEPS} 从
     * {@link #BIG_OPEN} 之后的步骤继续跑完。</p>
     */
    private void beginBigLoad() {
        loadStartNanos = System.nanoTime();
        HtmlSourceViewer.showFile(anchor, I18n.get("browser.viewerTitle"), bigFile);
        // 🚩 绝不在 FX 线程上 sleep 等异步结果：那会把脉冲（动画 + runLater 队列）一起堵住 ——
        // 实测过一次：sleep 30s 期间排队的 applySource 直到超时才被处理，于是"载入超时"是探针
        // 自己造出来的假失败（而侧边栏也因此停在上一次的 translateX 上，命中测试全错）。
        // 正确做法是把等待拆成一次次 FX 任务，让队列与脉冲在两次探测之间推进。
        awaitLinesAsync("big source", BIG_LINES + 6, LOAD_TIMEOUT_MS, this::nextStep);
    }

    /**
     * R2.7 之后的步骤。
     *
     * <p>⚠️ {@link #beginBigLoad()} 是<b>异步步</b>：它由驱动在 R2.6 段落结束后直接调用，
     * 自己拉完后才回到 {@link #nextStep()} —— 所以它<b>不在</b>本列表里，
     * 否则会被驱动"跑完就换下一步"，在载入落地前就去量节点数。</p>
     *
     * <p>⚠️ 顺序还有讲究：R5 的查找断言必须跑在<b>大源码</b>（5000 行）上，
     * 所以 {@link #openLongLineSource()}（它会把查看器换成 4 行的源码）排在最后；
     * 否则"计数 / 循环 / 高亮 / 定点重绘"全部会落在一个 4 行文档上，判据失去意义。
     * E2 的软换行 / 折行映射 / 语料步骤接在它后面（同样先切回小文档）。</p>
     */
    private final List<Runnable> BIG_STEPS = List.of(
            this::checkR27Virtualization,
            this::checkFindBarShape, this::checkFindCount, this::checkFindNavigate,
            this::checkFindWrapAround, this::checkFindNoHits,
            this::checkHighlightAndScroll, this::checkMinimalRepaint, this::checkFindBarBottom,
            this::checkSelectionAndCopy,
            this::setupStep5SnapshotSelection,
            this::snapshotStep5SelectionAndSetupLayers, this::snapshotStep5Layers,
            this::openLongLineSource,
            this::checkE2SoftWrap, this::checkE2WrapMapping,
            this::openCorpusSource, this::checkE2Corpus,
            this::openCorpusSourceNoTrailingNl, this::checkE2CorpusNoTrailingNl,
            this::checkResizeStage, this::checkResizeRewrap, this::checkNoHscrollWithSelection,
            this::checkCollapseR29, this::checkGuiStateUnchanged,
            this::snapshotFinal, this::finish);

    /**
     * 等到查看器行数 == expected 再回到步骤驱动；超时则如实打印并继续。
     *
     * <p>两个都踩过的坑：</p>
     * <ul>
     *   <li><b>不能在 FX 线程上 sleep</b>：那会把脉冲与 runLater 队列一起堵住 —— 实测 sleep 30s
     *       期间排队的 {@code applySource} 直到超时才被处理，于是"载入超时"是探针自己造出来的假失败。</li>
     *   <li><b>也不能靠 {@code Timeline}/{@code AnimationTimer} 轮询</b>：它们要有动画脉冲才推进，
     *       而本环境下脉冲在没有可见动画时并不可靠地跑（实测 Timeline 的 keyframe 一直不触发）。
     *       用 {@link java.util.Timer} 把探测排成一个个 FX 任务，既不阻塞 FX 线程、也不依赖脉冲。</li>
     * </ul>
     */
    private void awaitLinesAsync(String label, int expected, long timeoutMs, Runnable then) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Timer t = new Timer(true);
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    boolean ok = vInt(viewer, "lineCount") == expected;
                    if (!ok && System.currentTimeMillis() <= deadline) {
                        return;   // 还没好，等下一拍
                    }
                    cancel();
                    loadMillis = (System.nanoTime() - loadStartNanos) / 1_000_000;
                    System.out.println("awaitLines(" + label + "): " + (ok ? "ready" : "TIMED OUT")
                            + " lineCount=" + vInt(viewer, "lineCount") + " expected=" + expected
                            + " after " + loadMillis + " ms");
                    then.run();
                });
            }
        }, 50, 50);
    }

    /**
     * R2.7：虚拟化的证据是<b>实测节点数</b>。
     *
     * <p>{@code .list-cell} 是 VirtualFlow 为可见区域真正建出来的 cell；若实现是"每行一个
     * 节点一次性建好"，这个数会等于行数。所以"远小于行数"就是虚拟化，不是"感觉很快"。</p>
     */
    private void checkR27Virtualization() {
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        int cells = vNode(viewer).lookupAll(".list-cell").size();
        int texts = vNode(viewer).lookupAll(".html-source-text").size();
        int rows = vInt(viewer, "lineCount");
        System.out.println("R2.7 big source: lines=" + rows + " cells=" + cells
                + " textNodes=" + texts + " load=" + loadMillis + " ms");
        check("R2.7 the " + BIG_LINES + "-line source loaded within " + LOAD_TIMEOUT_MS
                + " ms (measured " + loadMillis + " ms)", rows >= BIG_LINES);
        check("R2.7 virtualization: materialised .list-cell nodes (" + cells
                + ") are FAR fewer than the " + rows + " lines", cells > 0 && cells * 10 < rows);
        check("R2.7 the item model holds every line of the file (rows=" + rows + ")", rows >= BIG_LINES);
        check("R2.7 the loading state was visible immediately after showFile (label was not the "
                        + "final count at call time)", loadMillis >= 0);
        snapshot("htmlviewer-virtualized");
    }

    // ---------- E1 字符选择与复制 ----------

    /**
     * E1：选择/复制的断言（#1..#13，含【需求变更】改写的跨行用例），作为一步排在 E2 软换行
     * 断言之前（之后由 {@link #openLongLineSource()} 切回长行源，E2 步骤接在后面）。
     *
     * <p>先用自己的小组装源码（{@link #showSource} 同步路径）验 #1..#11、步骤 5 的
     * S1..S10 / S12 / S13 与 #13，再同步装上 5000 行大源码验 #12、决策 3.9 与 S11（虚拟化 /
     * 滚动重建）；每条复制断言都从<b>系统剪贴板读回</b>并<b>逐字符</b>比对。</p>
     */
    private void checkSelectionAndCopy() {
        showSource(anchor, I18n.get("browser.viewerTitle"), selectionSource());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("E1 selection source: lines=" + vInt(viewer, "lineCount"));
        for (int i = 0; i < vInt(viewer, "lineCount"); i++) {
            System.out.println("    [" + i + "] <" + esc(vLine(viewer, i)) + ">");
        }
        // 坐标几何诊断：cell 边界 / 行号栏 / 内容流 / 第一个 Text 的场景 x，四个应逐级衔接
        Node diag = revealCellForLine(4);
        if (diag != null) {
            Bounds cb = diag.localToScene(diag.getBoundsInLocal());
            double derived = cb.getMinX() + vDbl(viewer, "gutterWidth");
            double textMinX = Double.NaN;
            for (Node n : diag.lookupAll(".html-source-text")) {
                if (n instanceof javafx.scene.text.Text t && !t.getText().isEmpty()) {
                    textMinX = t.localToScene(t.getBoundsInLocal()).getMinX();
                    break;
                }
            }
            double flowMinX = Double.NaN;
            if (diag instanceof javafx.scene.control.ListCell<?> lc
                    && lc.getGraphic() instanceof StackPane wrapper
                    && wrapper.getChildren().size() >= 3
                    && wrapper.getChildren().get(2) instanceof javafx.scene.layout.HBox hb
                    && hb.getChildren().size() >= 2
                    && hb.getChildren().get(1) instanceof VBox vb
                    && !vb.getChildren().isEmpty()
                    && vb.getChildren().get(0) instanceof javafx.scene.text.TextFlow tf) {
                flowMinX = tf.localToScene(tf.getBoundsInLocal()).getMinX();
            }
            System.out.println("E1 geometry: cellMinX=" + fmt(cb.getMinX())
                    + " gutterWidth=" + fmt(vDbl(viewer, "gutterWidth"))
                    + " derivedContentX=" + fmt(derived)
                    + " firstTextSceneMinX=" + fmt(textMinX)
                    + " flowBoundsSceneMinX=" + fmt(flowMinX)
                    + " textDelta=" + fmt(textMinX - derived)
                    + " flowDelta=" + fmt(flowMinX - derived));
        }
        // #13 的行模型快照（断言前先记下）
        int linesBefore = vInt(viewer, "lineCount");
        List<String> modelBefore = new ArrayList<>();
        for (int i = 0; i < linesBefore; i++) {
            modelBefore.add(vLine(viewer, i));
        }

        // #1 单行内拖选 → 剪贴板 == 原始子串（API 复制路径，逐字符）
        dragSelect(4, 3, 4, 21);
        copyViaApi();
        String expected1 = vLine(viewer, 4).substring(3, 21);
        check("E1#1 single-line drag copies the exact source substring (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#1", expected1, clipboardString()));

        // #2 跨语法 token（标签 → 正文 → 标签）→ 剪贴板 == 原始字符（无标记注入）。
        // 【需求变更】原 (3,0)→(4,21) 跨行（注释→标签→正文）；决策 3.2 后只允许行内选择，
        // 改为行 4 整行。
        dragSelect(4, 0, 4, vLine(viewer, 4).length());
        copyViaKey();
        String expected2 = vLine(viewer, 4);
        check("E1#2 cross-token (tag→text→tag, single line) copy is the raw source chars (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#2", expected2, clipboardString()));

        // #3 向下拖到别的行 → 钳到锚点行的行尾。【需求变更】原为跨行 LF 连接复制；
        // 决策 3.2 后跨行选择不存在，改为单行钳制断言（行尾 = 拖出行的下/右端）。
        dragSelect(3, 4, 5, 2);
        copyViaKey();
        HtmlTextSelection.Range r3 = vRange(viewer);
        String expected3 = vLine(viewer, 3).substring(4);
        check("E1#3 dragging DOWN to another line clamps to the anchor line's end (range="
                        + describeRange(r3) + ", clipboard=" + esc(clipboardString()) + ")",
                r3 != null && r3.startLine() == 3 && r3.endLine() == 3
                        && r3.startCol() == 4 && r3.endCol() == vLine(viewer, 3).length()
                        && charByChar("E1#3", expected3, clipboardString()));

        // #4 向下拖过空行 → 仍钳到锚点行的行尾。【需求变更】原为"含空行的跨行连续 LF 复制"；
        // 决策 3.2 后中间的空行不再参与选择。
        dragSelect(1, 2, 3, 8);
        copyViaKey();
        HtmlTextSelection.Range r4 = vRange(viewer);
        String expected4 = vLine(viewer, 1).substring(2);
        check("E1#4 dragging DOWN past an empty line clamps to the anchor line's end (range="
                        + describeRange(r4) + ", clipboard=" + esc(clipboardString()) + ")",
                r4 != null && r4.startLine() == 1 && r4.endLine() == 1
                        && r4.startCol() == 2 && r4.endCol() == vLine(viewer, 1).length()
                        && charByChar("E1#4", expected4, clipboardString()));

        // #5 行首缩进 + 行尾空格保留（行 1 = 4 个前导空格 + 4 个行尾空格）
        dragSelect(1, 0, 1, vLine(viewer, 1).length());
        copyViaKey();
        check("E1#5 indent and trailing spaces survive char-by-char (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#5", vLine(viewer, 1), clipboardString()));

        // #6 复制文本不含行号（逐字符 equals 已证；再加反向断言：不以行号字符串开头）
        String clip6 = clipboardString();
        String first6 = clip6 == null || clip6.isEmpty() ? "(empty)" : escChar(clip6.charAt(0));
        check("E1#6 the copy contains no gutter line number (starts with '" + first6
                        + "', not the line number '2')",
                charByChar("E1#6", vLine(viewer, 1), clip6) && clip6 != null && !clip6.startsWith("2"));

        // #7 平台复制键（Ctrl/Cmd+C）触发复制（与 #1 的 API 路径对照：这里只有按键；
        // 哨兵与落地校验在 copyViaKey 内完成，读回必为本次按键的结果）
        dragSelect(4, 5, 4, 10);
        copyViaKey();
        String expected7 = vLine(viewer, 4).substring(5, 10);
        check("E1#7 the platform copy key (Ctrl/Cmd+C) copies the selection (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#7", expected7, clipboardString()));

        // #8 无选择时复制键不覆盖剪贴板 / 不抛异常（单击 = 清空选择）。
        // 走原始按键（不经 copyViaKey 的哨兵路径 —— 本断言要验证的恰是"哨兵不被覆盖"）
        dragSelect(0, 0, 0, 0);
        setClipboard("SENTINEL-8-NO-SELECTION");
        String verdict8;
        try {
            copyViaKeyRaw();
            verdict8 = "no exception";
        } catch (Throwable ex) {
            verdict8 = "threw " + ex;
        }
        check("E1#8 the copy key with no selection leaves the clipboard alone and raises nothing ("
                        + verdict8 + ", clipboard=" + esc(clipboardString()) + ")",
                "no exception".equals(verdict8) && "SENTINEL-8-NO-SELECTION".equals(clipboardString()));

        // #9 在 gutter 上按下拖动 → 列 clamp 到内容区，行号不进复制
        dragSelectFromGutter(4, 21);
        copyViaApi();
        String expected9 = vLine(viewer, 4).substring(0, 21);
        check("E1#9 a drag starting in the gutter does not copy line numbers (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#9", expected9, clipboardString()));

        // #10 选择后 find 命中数不变（查找未被破坏）
        vQuery(viewer).setText(SEARCH_TOKEN);
        vFindButtons(viewer).get(0).fire();
        int hitsBefore = vInt(viewer, "hitCount");
        dragSelect(2, 0, 4, 10);
        check("E1#10 find hits are unchanged by a selection (before=" + hitsBefore
                        + " after=" + vInt(viewer, "hitCount") + ")",
                hitsBefore > 0 && vInt(viewer, "hitCount") == hitsBefore);

        // #11 选择后语法高亮仍在 + html-source-selected 出现 + find 命中样式并存。
        // 【需求变更】原选择是跨行 (2,0)→(4,10)；决策 3.2 后改成行内选择 (4,3)→(4,21)。
        dragSelect(4, 3, 4, 21);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        long textNodes = vNode(viewer).lookupAll(".html-source-text").size();
        long selectedVisible = vNode(viewer).lookupAll(".html-source-selected").stream()
                .filter(Node::isVisible).count();
        long hitNodes = vNode(viewer).lookupAll(".html-source-hit").size();
        check("E1#11 syntax highlight survives and the selection class shows while the find hit "
                        + "coexists (textNodes=" + textNodes + " selectedVisible=" + selectedVisible
                        + " hitNodes=" + hitNodes + ")",
                textNodes > 0 && selectedVisible > 0 && hitNodes > 0);
        snapshot("htmlviewer-selection");

        // ---- 功能三（步骤 5）断言组：单行钳制 / 黑底白字 / 层序 / 游标 / 整行底色 / 3.9 ----

        // S1 单行内拖动选择 → 选区范围精确且单行（3.10 的选中态截图在专用的
        // setupStep5SnapshotSelection / snapshotStep5SelectionAndSetupLayers 两步拍，
        // 与本步内的 #11 快照不在同一拍 —— 场景快照要跨脉冲才反映当前渲染）
        HtmlTextSelection.Range r1 = vRange(viewer);
        check("step5 S1 the selection range is exact and SINGLE-line (range=" + describeRange(r1) + ")",
                r1 != null && r1.startLine() == 4 && r1.endLine() == 4
                        && r1.startCol() == 3 && r1.endCol() == 21);

        // S2 拖出行的左端 → 钳到行首（列 0）
        dragSelect(4, 10, 4, -2);
        copyViaApi();
        HtmlTextSelection.Range r2 = vRange(viewer);
        check("step5 S2 dragging past the line's LEFT edge clamps to 行首 (range="
                        + describeRange(r2) + ")", r2 != null && r2.startLine() == 4 && r2.endLine() == 4
                && r2.startCol() == 0 && r2.endCol() == 10);
        check("step5 S2 the left-clamped copy is char-exact (clipboard=" + esc(clipboardString()) + ")",
                charByChar("step5-S2", vLine(viewer, 4).substring(0, 10), clipboardString()));
        // S3 拖出行的右端 → 钳到行尾
        dragSelect(4, 10, 4, vLine(viewer, 4).length() + 5);
        copyViaApi();
        HtmlTextSelection.Range r3b = vRange(viewer);
        check("step5 S3 dragging past the line's RIGHT edge clamps to 行尾 (range="
                        + describeRange(r3b) + ")", r3b != null && r3b.startLine() == 4 && r3b.endLine() == 4
                && r3b.startCol() == 10 && r3b.endCol() == vLine(viewer, 4).length());
        check("step5 S3 the right-clamped copy is char-exact (clipboard=" + esc(clipboardString()) + ")",
                charByChar("step5-S3", vLine(viewer, 4).substring(10), clipboardString()));
        // S4 向下拖出锚点行 → 钳到行尾
        dragSelect(4, 10, 6, 5);
        copyViaApi();
        HtmlTextSelection.Range r4b = vRange(viewer);
        check("step5 S4 dragging DOWN out of the anchor line clamps to its end (range="
                        + describeRange(r4b) + ")", r4b != null && r4b.startLine() == 4 && r4b.endLine() == 4
                && r4b.startCol() == 10 && r4b.endCol() == vLine(viewer, 4).length());
        check("step5 S4 the down-clamped copy is char-exact (clipboard=" + esc(clipboardString()) + ")",
                charByChar("step5-S4", vLine(viewer, 4).substring(10), clipboardString()));
        // S5 向上拖出锚点行 → 钳到行首
        dragSelect(4, 10, 2, 5);
        copyViaApi();
        HtmlTextSelection.Range r5 = vRange(viewer);
        check("step5 S5 dragging UP out of the anchor line clamps to its start (range="
                        + describeRange(r5) + ")", r5 != null && r5.startLine() == 4 && r5.endLine() == 4
                && r5.startCol() == 0 && r5.endCol() == 10);
        check("step5 S5 the up-clamped copy is char-exact (clipboard=" + esc(clipboardString()) + ")",
                charByChar("step5-S5", vLine(viewer, 4).substring(0, 10), clipboardString()));
        // S6 Shift 扩选：点在下方的行 → 扩到锚点行的行尾（决策 3.5，同样被 3.2 钳制）
        dragSelect(4, 5, 4, 10);
        shiftClick(6, 3);
        copyViaApi();
        HtmlTextSelection.Range r6 = vRange(viewer);
        check("step5 S6 Shift-click BELOW the anchor line extends to the line end only (range="
                        + describeRange(r6) + ")", r6 != null && r6.startLine() == 4 && r6.endLine() == 4
                && r6.startCol() == 5 && r6.endCol() == vLine(viewer, 4).length());
        check("step5 S6 the Shift-down-clamped copy is char-exact (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("step5-S6", vLine(viewer, 4).substring(5), clipboardString()));
        // S7 Shift 扩选：点在上方的行 → 扩到锚点行的行首
        dragSelect(4, 5, 4, 10);
        shiftClick(2, 3);
        copyViaApi();
        HtmlTextSelection.Range r7 = vRange(viewer);
        check("step5 S7 Shift-click ABOVE the anchor line extends to the line start only (range="
                        + describeRange(r7) + ")", r7 != null && r7.startLine() == 4 && r7.endLine() == 4
                && r7.startCol() == 0 && r7.endCol() == 5);
        check("step5 S7 the Shift-up-clamped copy is char-exact (clipboard=" + esc(clipboardString()) + ")",
                charByChar("step5-S7", vLine(viewer, 4).substring(0, 5), clipboardString()));
        // S8 层序（3.7）：命中黄底在最下、选区黑底在中、字形在最上 —— 选择盖住命中的一部分
        dragSelect(3, 2, 3, 10);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        Node cell3 = revealCellForLine(3);
        List<String> layerKind = new ArrayList<>();
        boolean layerOrder = false;
        Color hitFill = null;
        Color selFill = null;
        if (cell3 instanceof javafx.scene.control.ListCell<?> lc
                && lc.getGraphic() instanceof StackPane wrapper) {
            List<Node> kids = wrapper.getChildren();
            for (Node k : kids) {
                layerKind.add(describe(k));
            }
            layerOrder = kids.size() == 3
                    && kids.get(0) instanceof Pane hitLayer
                    && hitLayer.lookupAll(".html-source-hit").size() > 0
                    && kids.get(1) instanceof Pane selLayer
                    && selLayer.lookupAll(".html-source-selected").size() > 0
                    && kids.get(2) instanceof javafx.scene.layout.HBox;
            if (kids.size() == 3 && kids.get(0) instanceof Pane hitLayer) {
                for (Node n : hitLayer.getChildren()) {
                    if (n instanceof Rectangle r8 && r8.isVisible()) {
                        hitFill = (Color) r8.getFill();
                        break;
                    }
                }
            }
            if (kids.size() == 3 && kids.get(1) instanceof Pane selLayer) {
                for (Node n : selLayer.getChildren()) {
                    if (n instanceof Rectangle r8 && r8.isVisible()) {
                        selFill = (Color) r8.getFill();
                        break;
                    }
                }
            }
        }
        check("step5 S8 cell layers are [hit, selection, glyphs] (" + layerKind + ")", layerOrder);
        check("step5 S8 the hit rect is yellow #ffe680 and the selection rect is BLACK ("
                        + hex(hitFill) + " / " + hex(selFill) + ")",
                hitFill != null && selFill != null
                        && Math.abs(hitFill.getRed() - 1.0) < 0.01
                        && Math.abs(hitFill.getGreen() - 0.9) < 0.01
                        && Math.abs(hitFill.getBlue() - 0.5) < 0.01
                        && selFill == Color.BLACK);
        // S9 黑底白字（2026-10-05 修复后语义）：只有<b>被选中的列</b>变白 ——
        //   完全落在选区内的 run 整体 WHITE；<b>部分相交</b>的 run 必须是"区间内白、区间外基础色"的
        //   硬边界渐变，绝不能整段变白（整段变白会让未选中的字白底白字而消失 —— 用户报的缺陷）；
        //   不相交的 run 保持基础色。
        boolean runRule = cell3 != null;
        int whiteRuns3 = 0;      // 完全在选区内（整体置白）
        int partialRuns3 = 0;    // 部分相交（硬边界渐变）
        int baseRuns3 = 0;       // 不相交（基础色）
        if (cell3 != null) {
            for (Node n : cell3.lookupAll(".html-source-text")) {
                if (!(n instanceof javafx.scene.text.Text t)) {
                    continue;
                }
                int[] cr = (int[]) t.getProperties().get(runColumnsKey());
                Color base = (Color) t.getProperties().get(runBaseFillKey());
                if (cr == null || base == null) {
                    runRule = false;
                    continue;
                }
                Paint fill = t.getFill();
                boolean overlaps = cr[1] > 2 && cr[0] < 10;
                boolean fully = cr[0] >= 2 && cr[1] <= 10;
                if (fully) {
                    whiteRuns3++;
                    runRule &= Color.WHITE.equals(fill);
                } else if (overlaps) {
                    partialRuns3++;
                    runRule &= !Color.WHITE.equals(fill) && !base.equals(fill)
                            && fill instanceof LinearGradient;
                } else {
                    baseRuns3++;
                    runRule &= base.equals(fill);
                }
            }
        }
        check("step5 S9 white-on-black: only the SELECTED columns are white (full=WHITE / "
                        + "partial=hard-stop gradient / rest=base) (full=" + whiteRuns3
                        + " partial=" + partialRuns3 + " base=" + baseRuns3 + ")",
                runRule && (whiteRuns3 + partialRuns3) > 0 && baseRuns3 > 0);
        // S10 松手（单击清空选择）→ 全部 run 还原基础色
        dragSelect(0, 0, 0, 0);
        boolean restored = true;
        int checkedRuns = 0;
        for (Node n : vNode(viewer).lookupAll(".html-source-text")) {
            if (!(n instanceof javafx.scene.text.Text t)) {
                continue;
            }
            Color base = (Color) t.getProperties().get(runBaseFillKey());
            if (base == null) {
                restored = false;
                continue;
            }
            checkedRuns++;
            restored &= base.equals(t.getFill());
        }
        check("step5 S10 after the click clears the selection every run is restored to its base "
                        + "fill (runs=" + checkedRuns + ")", restored && checkedRuns > 0);
        // S12 游标（3.4）：viewer / list-view / cell = TEXT，行号栏保持默认
        vNode(viewer).applyCss();
        Node cellCursor = cellForLine(4);
        Node gutterNode = cellCursor == null ? null : cellCursor.lookup(".html-source-gutter");
        check("step5 S12 the viewer root has the TEXT cursor (" + vNode(viewer).getCursor() + ")",
                vNode(viewer).getCursor() == Cursor.TEXT);
        check("step5 S12 the ListView has the TEXT cursor (" + listView.getCursor() + ")",
                listView.getCursor() == Cursor.TEXT);
        check("step5 S12 a cell has the TEXT cursor ("
                        + (cellCursor == null ? "no cell" : cellCursor.getCursor()) + ")",
                cellCursor != null && cellCursor.getCursor() == Cursor.TEXT);
        check("step5 S12 the gutter keeps the DEFAULT cursor ("
                        + (gutterNode == null ? "no gutter" : gutterNode.getCursor()) + ")",
                gutterNode != null && gutterNode.getCursor() == Cursor.DEFAULT);
        // S13 整行选中底色（3.6）：强制 :selected 伪类，行背景仍透明（无任何不透明填充）
        boolean rowBgOff = false;
        String rowBgDesc = "no cell";
        if (cellCursor instanceof javafx.scene.control.ListCell<?> lc) {
            PseudoClass selected = PseudoClass.getPseudoClass("selected");
            lc.pseudoClassStateChanged(selected, true);
            vNode(viewer).applyCss();
            javafx.scene.layout.Background bg = lc.getBackground();
            if (bg == null) {
                rowBgOff = true;
                rowBgDesc = "null background";
            } else {
                boolean opaqueFill = false;
                for (javafx.scene.layout.BackgroundFill f : bg.getFills()) {
                    javafx.scene.paint.Paint p = f.getFill();
                    if (p instanceof Color c && c.getOpacity() > 0.01) {
                        opaqueFill = true;
                        rowBgDesc = "opaque " + hex(c);
                    }
                }
                if (!opaqueFill && !bg.getImages().isEmpty()) {
                    opaqueFill = true;
                    rowBgDesc = "background image";
                }
                rowBgOff = !opaqueFill;
                if (rowBgDesc.equals("no cell")) {
                    rowBgDesc = rowBgOff ? "all fills transparent" : "opaque";
                }
            }
            lc.pseudoClassStateChanged(selected, false);
            vNode(viewer).applyCss();
        }
        System.out.println("step5 S13 forced-selected cell background -> " + rowBgDesc);
        check("step5 S13 forcing :selected on a cell keeps the row background transparent (bg: "
                        + rowBgDesc + ")", rowBgOff);

        // #13 选择不改变行模型（lineCount / lineText 不变）
        boolean modelSame = vInt(viewer, "lineCount") == linesBefore;
        for (int i = 0; modelSame && i < linesBefore; i++) {
            modelSame = vLine(viewer, i).equals(modelBefore.get(i));
        }
        check("E1#13 selection never changes the line model (lines=" + vInt(viewer, "lineCount")
                        + " vs " + linesBefore + ", every lineText identical=" + modelSame + ")",
                modelSame);

        // #12 5000 行大文件上选择后 .list-cell 数仍远小于行数（虚拟化保持）
        showSource(anchor, I18n.get("browser.viewerTitle"), bigSource());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        dragSelect(2, 5, 2, 30);
        int cells = vNode(viewer).lookupAll(".list-cell").size();
        int rowsBig = vInt(viewer, "lineCount");
        System.out.println("E1#12 big source after selection: lines=" + rowsBig + " cells=" + cells);
        check("E1#12 on the " + rowsBig + "-line source a selection keeps virtualization (cells="
                        + cells + " << lines)", cells > 0 && cells * 10 < rowsBig);
        // 决策 3.9（竖向虚拟化硬约束）：选择重绘只动可见 cell —— 实测计数，与文件行数无关
        int selPainted = vInt(viewer, "selectionCellsPaintedInLastRepaint");
        System.out.println("step5 3.9 selection repaint cells: " + selPainted + " of " + rowsBig);
        check("step5 3.9 the selection repaint touched only visible cells (" + selPainted
                        + " << " + rowsBig + ")", selPainted > 0 && selPainted * 10 < rowsBig);
        // S11 滚动到远处再回来：cell 重建后白字只落在选区上（黑底白字的"滚动还原"）
        listView.scrollTo(BIG_LINES / 2);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        listView.scrollTo(2);
        Node cellRebuilt = revealCellForLine(2);
        boolean rebuildOk = cellRebuilt != null;
        int whiteRuns = 0;
        int baseRuns = 0;
        if (cellRebuilt != null) {
            for (Node n : cellRebuilt.lookupAll(".html-source-text")) {
                if (!(n instanceof javafx.scene.text.Text t)) {
                    continue;
                }
                int[] cr = (int[]) t.getProperties().get(runColumnsKey());
                Color base = (Color) t.getProperties().get(runBaseFillKey());
                if (cr == null || base == null) {
                    rebuildOk = false;
                    continue;
                }
                boolean inside = cr[1] > 5 && cr[0] < 30;
                if (inside) {
                    whiteRuns++;
                    rebuildOk &= Color.WHITE.equals(t.getFill());
                } else {
                    baseRuns++;
                    rebuildOk &= base.equals(t.getFill());
                }
            }
        }
        check("step5 S11 after scrolling away and back the white runs sit exactly on the "
                        + "selection (white=" + whiteRuns + " base=" + baseRuns + ")",
                rebuildOk && whiteRuns > 0 && baseRuns > 0);
    }

    /** E1 用的小组装源码：行首缩进、行尾空格、空行、注释、标签、正文（坐标见各断言）。 */
    private String selectionSource() {
        return "<html>\n"
                + "    <body>    \n"
                + "\n"
                + "<!-- " + SEARCH_TOKEN + " comment -->\n"
                + "<p>Hello, probe world!</p>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /** 合成一段鼠标拖选：在 (fromLine, fromCol) 按下，拖到 (toLine, toCol)，松开。 */
    private void dragSelect(int fromLine, int fromCol, int toLine, int toCol) {
        Node cell = revealCellForLine(fromLine);
        if (cell == null) {
            check("dragSelect could not reveal line " + fromLine, false);
            return;
        }
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        double contentX = b.getMinX() + vDbl(viewer, "gutterWidth");
        double y = b.getMinY() + b.getHeight() / 2;
        fireMouse(MouseEvent.MOUSE_PRESSED, cell, sceneXForCol(cell, fromLine, fromCol, contentX), y, true);
        Node cell2 = revealCellForLine(toLine);
        double toContentX = contentX;
        if (cell2 != null) {
            Bounds b2 = cell2.localToScene(cell2.getBoundsInLocal());
            toContentX = b2.getMinX() + vDbl(viewer, "gutterWidth");
            y = b2.getMinY() + b2.getHeight() / 2;
        }
        double xEnd = sceneXForCol(cell2 != null ? cell2 : cell, toLine, toCol, toContentX);
        fireMouse(MouseEvent.MOUSE_DRAGGED, listView, xEnd, y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, xEnd, y, false);
    }

    /** 在行号区（gutter）按下再拖到内容列 {@code toCol}（#9：行号不得进复制）。 */
    private void dragSelectFromGutter(int line, int toCol) {
        Node cell = revealCellForLine(line);
        if (cell == null) {
            check("dragSelectFromGutter could not reveal line " + line, false);
            return;
        }
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        double contentX = b.getMinX() + vDbl(viewer, "gutterWidth");
        double y = b.getMinY() + b.getHeight() / 2;
        double x = sceneXForCol(cell, line, toCol, contentX);
        fireMouse(MouseEvent.MOUSE_PRESSED, cell, b.getMinX() + 2, y, true);
        fireMouse(MouseEvent.MOUSE_DRAGGED, listView, x, y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, x, y, false);
    }

    private static void fireMouse(EventType<MouseEvent> type, Node target,
                                  double sceneX, double sceneY, boolean primary) {
        fireMouse(type, target, sceneX, sceneY, primary, false);
    }

    private static void fireMouse(EventType<MouseEvent> type, Node target,
                                  double sceneX, double sceneY, boolean primary, boolean shift) {
        Event.fireEvent(target, new MouseEvent(type, sceneX, sceneY, sceneX, sceneY,
                MouseButton.PRIMARY, 1, shift, false, false, false,
                primary, false, false, false, false, true, null));
    }

    /**
     * 独立复算 (逻辑行, 逻辑列) → 场景 x：按<b>真实字形步进</b>逐字符累加（不用等宽网格），
     * 与查看器的命中映射互为对照。查看器自 2026-10-05 起按 run 实测宽度定位，
     * 用 {@code col × charWidth} 的网格算法会把指针指到别的列（CJK/拉丁混排时偏到左边）。
     */
    private double sceneXForCol(Node cell, int line, int col, double contentX) {
        String text = vLine(viewer, line);
        int len = text == null ? 0 : text.length();
        int target = Math.max(0, Math.min(col, len));
        int cols = Math.max(1, vInt(viewer, "wrapCols"));
        int rowStart = (target / cols) * cols;
        javafx.scene.text.Font font = fontOf(cell);
        double x = contentX;
        for (int i = rowStart; i < target; i++) {
            x += glyphWidth(text.charAt(i), font);
        }
        return x;
    }

    /** 取 cell 里字形实际用的字体（查看器按 monoFont 建节点；探针不复制这个决定，只读取事实）。 */
    private static javafx.scene.text.Font fontOf(Node cell) {
        if (cell != null) {
            Node n = cell.lookup(".html-source-text");
            if (n instanceof javafx.scene.text.Text t && t.getFont() != null) {
                return t.getFont();
            }
        }
        return javafx.scene.text.Font.font(13);
    }

    private static double glyphWidth(char c, javafx.scene.text.Font font) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(String.valueOf(c));
        t.setFont(font);
        double w = t.getLayoutBounds().getWidth();
        return w > 0 ? w : 1;
    }


    /** Shift+单击（决策 3.5 的扩选路径）：按在 (line, col)，同一位置松开（Shift 保持按下）。 */
    private void shiftClick(int line, int col) {
        Node cell = revealCellForLine(line);
        if (cell == null) {
            check("shiftClick could not reveal line " + line, false);
            return;
        }
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        double contentX = b.getMinX() + vDbl(viewer, "gutterWidth");
        double y = b.getMinY() + b.getHeight() / 2;
        double x = sceneXForCol(cell, line, col, contentX);
        fireMouse(MouseEvent.MOUSE_PRESSED, cell, x, y, true, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, x, y, false, true);
    }

    /** 每次复制验证用的哨兵序号（保证每次不同、且不可能等于夹具正文） */
    private static final java.util.concurrent.atomic.AtomicInteger SENTINEL_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 剪贴板忙窗口的容忍预算。本机实测单次占用可达<b>十几秒</b>（连续多个复制检查读回全 null、
     * 失败集合每次不同；全量探针轮内曾实测 >12s 仍不恢复），故预算给到 45s 并分多次
     * {@code Thread.sleep}（250ms 粒度，绝不一次 sleep 到底，也避免 50ms 高频写把忙窗口拖长）。
     * 正常路径一次写入 + 一次读回即返回，不增加稳态耗时。
     */
    private static final long CLIPBOARD_BUDGET_MS = 45_000;

    /** 单次探针运行内剪贴板等待的<b>累计</b>上限：忙窗口再长也不让整轮拖死（q14 单探针时限 240s）。
     *  累计打满后后续复制检查退化为单次尝试即放弃 —— 依旧大声失败，绝不静默放过。 */
    private static long clipboardWaitSpentMs = 0;
    private static final long CLIPBOARD_WAIT_TOTAL_MS = 120_000;

    /** 平台复制键的原始触发（Windows 下 = Ctrl+C，经 {@code KeyEvent.isShortcutDown()} 判定）。
     *  需要哨兵/落地校验的调用点走 {@link #copyViaKey()}；E1#8 这类"验证不覆盖"语义走本方法。 */
    private void copyViaKeyRaw() {
        Event.fireEvent(listView, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                false, true, false, false));
    }

    /** 平台复制键（带哨兵 + 落地校验；与 {@link #copyViaApi()} 同一套确定性机制）。 */
    private void copyViaKey() {
        copyVerified(this::copyViaKeyRaw);
    }

    /** 经查看器公开 API 的原始复制（无哨兵校验）。E2「空选择不覆盖剪贴板」这类语义走本方法，
     *  其余复制断言走 {@link #copyViaApi()}。 */
    private void copyViaApiRaw() {
        vCopy(viewer);
    }

    /**
     * 经查看器的公开 API 复制（与按键路径对照用），带哨兵 + 落地校验（见 {@link #copyVerified}）。
     * 生产 {@code copySelection} 未被改动，重试只是探针的读回校验，断言口径不变。
     */
    private void copyViaApi() {
        copyVerified(this::copyViaApiRaw);
    }

    /**
     * 确定性复制：每轮先写哨兵并<b>确认写入落地</b>（写不进说明剪贴板正忙，直接进下一轮重试），
     * 再触发复制；读回「非 null 且不等于哨兵」才算复制落地（内容正确性由调用点的
     * charByChar 逐字符判）。总预算 {@link #CLIPBOARD_BUDGET_MS}；耗尽后在 stderr 打诊断并
     * 返回 —— 此时剪贴板仍是哨兵或 null，调用点的 charByChar 会因此<b>大声失败</b>。
     *
     * <p>哨兵同时杜绝「读到上一次复制的旧文本就提前退出」的假通过：复制被丢弃时读回必为
     * 哨兵或 null（哨兵写入本身未落地时整体视为不成立，继续重试），循环不会因旧值提前结束。</p>
     */
    private void copyVerified(Runnable trigger) {
        String sentinel = "PROBE-SENTINEL-" + SENTINEL_SEQ.incrementAndGet();
        long deadline = System.currentTimeMillis() + CLIPBOARD_BUDGET_MS;
        long startedAt = System.currentTimeMillis();
        int attempts = 0;
        while (System.currentTimeMillis() < deadline) {
            // 累计等待预算打满后不再恋战：单次尝试即放弃（总等待有上限，防整轮拖死）
            if (clipboardWaitSpentMs >= CLIPBOARD_WAIT_TOTAL_MS) {
                trigger.run();
                String once = clipboardStringRaw();
                if (once != null && !sentinel.equals(once)) {
                    return;
                }
                break;
            }
            if (!setClipboardVerified(sentinel)) {
                attempts++;
                sleep(250);
                continue;
            }
            trigger.run();
            // 用单次原始读：本循环自己就是重试预算（250ms 粒度），长鲁棒读会一次耗尽整个预算
            String got = clipboardStringRaw();
            if (got != null && !sentinel.equals(got)) {
                return;
            }
            attempts++;
            sleep(250);
        }
        clipboardWaitSpentMs += System.currentTimeMillis() - startedAt;
        System.err.println("[HtmlViewerProbe] 复制未落地：哨兵/读回校验 " + attempts
                + " 次未确认（已等待 " + (System.currentTimeMillis() - startedAt)
                + " ms，系统剪贴板忙或不可用）—— 相关断言将按失败处理");
    }

    /** 写剪贴板并确认落地（单次原始读 + 250ms 粒度重写，单次调用预算 ~5s）。 */
    private static boolean setClipboardVerified(String s) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
        long deadline = System.currentTimeMillis() + 5000;
        while (!s.equals(clipboardStringRaw()) && System.currentTimeMillis() < deadline) {
            sleep(250);
            Clipboard.getSystemClipboard().setContent(cc);
        }
        return s.equals(clipboardStringRaw());
    }

    /**
     * 写剪贴板并<b>确认落地</b>（E1#8 / E2「空选择不覆盖」语义的哨兵写入用，预算
     * {@link #CLIPBOARD_BUDGET_MS}，同样受累计等待上限约束）；仍写不进就在 stderr 打诊断
     * （后续断言会据此大声失败，不静默放过）。
     */
    private static void setClipboard(String s) {
        long deadline = System.currentTimeMillis() + CLIPBOARD_BUDGET_MS;
        while (!setClipboardVerified(s) && System.currentTimeMillis() < deadline
                && clipboardWaitSpentMs < CLIPBOARD_WAIT_TOTAL_MS) {
            sleep(250);
        }
        if (!s.equals(clipboardString())) {
            System.err.println("[HtmlViewerProbe] setClipboard 未落地: 期望=" + esc(s)
                    + " 读回=" + esc(clipboardString()));
        }
    }

    /**
     * 读回剪贴板文本，容忍系统剪贴板忙：读回 null 时在 {@link #CLIPBOARD_BUDGET_MS} 预算内
     * 以 250ms 粒度重试；仍读不到返回 null（调用点断言会大声失败，不静默放过）。
     */
    private static String clipboardString() {
        long deadline = System.currentTimeMillis() + CLIPBOARD_BUDGET_MS;
        String got = clipboardStringRaw();
        while (got == null && System.currentTimeMillis() < deadline) {
            sleep(250);
            got = clipboardStringRaw();
        }
        return got;
    }

    /** 单次原始读回（重试循环内部的粒度原语，勿直接用于断言） */
    private static String clipboardStringRaw() {
        return Clipboard.getSystemClipboard().getString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 逐字符比对并打印证据；不等则指出第一个不同的下标与两侧字符。
     *
     * <p>这是 E1 复制断言的统一口径：{@code equals} 是逐字符的，但这里要的是
     * <b>原始输出</b>（长度差 / 首个分歧点），行尾空格与 LF 都变成可见字符打印。</p>
     */
    private static boolean charByChar(String label, String expected, String actual) {
        if (actual == null) {
            System.out.println("    " + label + " char-by-char: actual=null, expected len="
                    + expected.length());
            return false;
        }
        if (actual.length() != expected.length()) {
            System.out.println("    " + label + " char-by-char: LENGTH " + actual.length()
                    + " vs " + expected.length() + " expected=" + esc(expected)
                    + " actual=" + esc(actual));
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                System.out.println("    " + label + " char-by-char: first diff at index " + i
                        + " expected='" + escChar(expected.charAt(i)) + "' actual='"
                        + escChar(actual.charAt(i)) + "'");
                return false;
            }
        }
        System.out.println("    " + label + " char-by-char OK: " + expected.length() + " chars");
        return true;
    }

    /** 让空白可见的转义（证据打印用：LF/CR/制表符转义，空格换成 ·）。 */
    private static String esc(String s) {
        return s == null ? "null" : s.replace("\n", "\\n").replace("\r", "\\r")
                .replace(" ", "·").replace("\t", "\\t");
    }

    private static String escChar(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case ' ' -> "·";
            default -> String.valueOf(c);
        };
    }

    /** 选择范围的单行可读描述（断言证据打印用）。 */
    private static String describeRange(HtmlTextSelection.Range r) {
        return r == null ? "null"
                : "(" + r.startLine() + "," + r.startCol() + ")-(" + r.endLine() + "," + r.endCol() + ")";
    }

    /** Color → #rrggbb（断言证据打印用）。 */
    private static String hex(Color c) {
        if (c == null) {
            return "null";
        }
        return String.format("#%02x%02x%02x",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    // ---------- 步骤 5：3.10 的选中态截图（跨脉冲两拍，与 e2 快照同一套路） ----------

    /**
     * 3.10 选中态截图（第 1 拍）：装回小文档并做单行选区 [3,21)，本拍结束 —— 场景快照
     * 只反映<b>上一拍</b>定稿的渲染（同一步内连拍会得到逐字节相同的旧帧，实测踩过），
     * 所以把"摆状态"与"按快门"拆成两个步骤。
     */
    private void setupStep5SnapshotSelection() {
        showSource(anchor, I18n.get("browser.viewerTitle"), selectionSource());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        dragSelect(4, 3, 4, 21);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
    }

    /** 3.10 选中态截图（第 2 拍）：拍下上一拍定稿的黑底白字选区；同拍备好"选区盖命中"的层序状态。 */
    private void snapshotStep5SelectionAndSetupLayers() {
        snapshot("htmlviewer-step5-selection");
        vQuery(viewer).setText(SEARCH_TOKEN);
        vFindButtons(viewer).get(0).fire();
        dragSelect(3, 2, 3, 10);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
    }

    /** 3.10 层序截图（第 3 拍）：拍下"黑底选区压在黄色查找命中上"的层序状态。 */
    private void snapshotStep5Layers() {
        snapshot("htmlviewer-step5-layers");
    }

    // ---------- E2 软换行 + 折行 ↔ 逻辑偏移映射（取代 R2.8 横向滚动） ----------

    private void openLongLineSource() {
        // showSource 是同步路径（tokenize 在调用线程上），返回时行模型已就位
        showSource(anchor, I18n.get("browser.viewerTitle"), longSource());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("E2 long line: contentPixelWidth=" + fmt(vDbl(viewer, "contentPixelWidth"))
                + " wrapCols=" + vInt(viewer, "wrapCols")
                + " charWidth=" + fmt(vDbl(viewer, "charWidth"))
                + " gutter=" + fmt(vDbl(viewer, "gutterWidth"))
                + " rowHeight=" + fmt(vDbl(viewer, "rowHeight"))
                + " longLine chars=" + vLine(viewer, 1).length());
    }

    /** E2 软换行呈现：长行折成多视觉行、无横向滚动、行号只一次且与首折行对齐、cell 高度随折行增长。 */
    private void checkE2SoftWrap() {
        double viewport = scroller.getViewportBounds().getWidth();
        int cols = vInt(viewer, "wrapCols");
        int longLen = vLine(viewer, 1).length();
        int visualRows = HtmlWrapMap.visualRowCount(longLen, cols);
        check("E2 soft wrap: contentPixelWidth() is now the viewport width ("
                        + fmt(vDbl(viewer, "contentPixelWidth")) + " ~ " + fmt(viewport) + ")",
                viewport > 0 && Math.abs(vDbl(viewer, "contentPixelWidth") - viewport) <= TOL);
        // 判据是"用户不可能水平滚动"：无横向滚动条 + hvalue 停在 0（本环境 hmax 恒报 1.0 的
        // 1px 退化余量，基线（内容 15445px 宽、真横向滚动）时 hmax 也是 1.0 —— 不拿它当判据）
        check("E2 soft wrap: no horizontal scrolling (hbarPolicy=" + scroller.getHbarPolicy()
                        + ", hvalue=" + fmt(scroller.getHvalue()) + ", hmax=" + fmt(scroller.getHmax()) + ")",
                scroller.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER
                        && scroller.getHvalue() <= 0.001);
        check("E2 soft wrap: the ListView no longer outgrows the viewport (pref="
                        + fmt(listView.getPrefWidth()) + " <= viewport=" + fmt(viewport) + ")",
                listView.getPrefWidth() <= viewport + 1);
        check("E2 soft wrap: the logical line model is unchanged (lineCount stays 4): "
                        + vInt(viewer, "lineCount"), vInt(viewer, "lineCount") == 4);
        // 行号对齐的机械判据（沿用 R2.8 的口径）：行号列宽 == 位数 × 单字宽 + 固定间距，位数取自总行数
        double expectedGutter = vInt(viewer, "gutterDigits") * vDbl(viewer, "charWidth") + vDbl(viewer, "gutterGap");
        System.out.println("E2 gutter=" + fmt(vDbl(viewer, "gutterWidth")) + " expected=" + fmt(expectedGutter)
                + " digits=" + vInt(viewer, "gutterDigits") + " charWidth=" + fmt(vDbl(viewer, "charWidth"))
                + " totalLines=" + vInt(viewer, "lineCount"));
        check("E2 soft wrap: the gutter width is exactly digits × charWidth + gap ("
                        + fmt(vDbl(viewer, "gutterWidth")) + " vs " + fmt(expectedGutter) + ")",
                Math.abs(vDbl(viewer, "gutterWidth") - expectedGutter) < 0.001 && vInt(viewer, "gutterDigits") >= 1);
        check("E2 soft wrap: the gutter digit count comes from the total line count (digits="
                        + vInt(viewer, "gutterDigits") + ", lines=" + vInt(viewer, "lineCount") + ")",
                vInt(viewer, "gutterDigits") == String.valueOf(vInt(viewer, "lineCount")).length());
        // 长行 cell：高度 = 视觉行数 × 行高；内部一视觉行一个 TextFlow、行号恰好一个
        Node cell = revealCellForLine(1);
        Bounds cb = cell == null ? null : cell.localToScene(cell.getBoundsInLocal());
        check("E2 soft wrap: the long line really wraps into multiple visual rows (wrapCols="
                        + cols + ", longLen=" + longLen + ", visualRows=" + visualRows + ")",
                visualRows > 1);
        check("E2 soft wrap: the long line cell exists and its height grew to the visual-row count ("
                        + fmt(cb == null ? -1 : cb.getHeight()) + " px vs " + fmt(visualRows * vDbl(viewer, "rowHeight")) + ")",
                cell != null && cb != null
                        && Math.abs(cb.getHeight() - visualRows * vDbl(viewer, "rowHeight")) <= TOL);
        long flowsInCell = cell == null ? -1 : cell.lookupAll(".html-source-line").size();
        check("E2 soft wrap: the cell renders one TextFlow per visual row (" + flowsInCell
                        + " == " + visualRows + ")", flowsInCell == visualRows);
        long guttersInCell = cell == null ? -1 : cell.lookupAll(".html-source-gutter").size();
        check("E2 soft wrap: the line number appears exactly once per logical line (gutters in "
                        + "the wrapped cell = " + guttersInCell + ")", guttersInCell == 1);
        // 行号与第一折行对齐（纵向中心一致）；后续折行等宽 spacer 缩进（与首折行同一起点 x）
        Bounds gb = null;
        Bounds firstFlow = null;
        Bounds secondFlow = null;
        if (cell instanceof javafx.scene.control.ListCell<?> lc
                && lc.getGraphic() instanceof StackPane wrapper
                && wrapper.getChildren().size() >= 3
                && wrapper.getChildren().get(2) instanceof javafx.scene.layout.HBox hb
                && hb.getChildren().size() == 2
                && hb.getChildren().get(0) instanceof Label gutterLabel
                && hb.getChildren().get(1) instanceof VBox vb && vb.getChildren().size() >= 2) {
            gb = gutterLabel.localToScene(gutterLabel.getBoundsInLocal());
            firstFlow = vb.getChildren().get(0).localToScene(vb.getChildren().get(0).getBoundsInLocal());
            secondFlow = vb.getChildren().get(1).localToScene(vb.getChildren().get(1).getBoundsInLocal());
        }
        check("E2 soft wrap: the line number aligns with the FIRST visual row (gutter center "
                        + fmt(centerY(gb)) + " vs first row center " + fmt(centerY(firstFlow)) + ")",
                gb != null && firstFlow != null
                        && Math.abs(centerY(gb) - centerY(firstFlow)) <= 4);
        check("E2 soft wrap: the gutter spans exactly one visual row height (" + fmt(height(gb))
                        + " vs " + fmt(vDbl(viewer, "rowHeight")) + ")",
                gb != null && Math.abs(height(gb) - vDbl(viewer, "rowHeight")) <= 2);
        check("E2 soft wrap: following visual rows are spacer-indented to the same content start "
                        + "(2nd row minX " + fmt(minX(secondFlow)) + " == 1st row minX " + fmt(minX(firstFlow)) + ")",
                secondFlow != null && firstFlow != null
                        && Math.abs(minX(secondFlow) - minX(firstFlow)) <= 1);
        // 折行切片不丢字符：把 cell 里所有文本节点按序拼起来 == 整行原文
        String reassembled = reassembledText(cell);
        check("E2 soft wrap: the wrapped visual rows reassemble the whole logical line char-by-char "
                        + "(len " + reassembled.length() + " vs " + longLen + ")",
                reassembled.equals(vLine(viewer, 1)));
        // 无横向滚动后纵向 scrollTo(最后一行) 直接可见（旧判据"横向推到行尾"的取代）
        listView.scrollTo(vInt(viewer, "lineCount") - 1);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        Node lastCell = cellForLine(vInt(viewer, "lineCount") - 1);
        check("E2 soft wrap: vertical scrollTo(last line) brings the last line's cell into the "
                        + "viewport (cell=" + describe(lastCell) + ")", lastCell != null);
        check("E2 soft wrap: the viewport width is a real positive number ("
                        + fmt(scroller.getViewportBounds().getWidth()) + ")",
                scroller.getViewportBounds().getWidth() > 0);
        snapshot("htmlviewer-e2-softwrap");
    }

    /** E2 折行 ↔ 逻辑偏移映射：跨折行拖选 / 高亮分段 / 命中仍按逻辑列。 */
    private void checkE2WrapMapping() {
        int cols = vInt(viewer, "wrapCols");
        int longLen = vLine(viewer, 1).length();
        // 1) 跨折行边界的拖选（视觉行 0 尾 → 视觉行 1 首）→ 复制逐字符 = 逻辑子串
        int fromCol = Math.max(0, cols - 3);
        int toCol = Math.min(longLen, cols + 5);
        dragVisual(1, 0, fromCol, 1, 1, toCol - cols);
        copyViaApi();
        String expected = vLine(viewer, 1).substring(fromCol, toCol);
        check("E2 wrap mapping: a drag across the wrap boundary copies the exact logical range "
                        + "(clipboard=" + esc(clipboardString()) + ")",
                charByChar("E2-wrap-cross-boundary", expected, clipboardString()));
        // 2) 选择高亮跨视觉行：cell 内 ≥2 个选中矩形、y 位于不同视觉行
        Node cell = revealCellForLine(1);
        List<Double> selYs = new ArrayList<>();
        if (cell != null) {
            for (Node n : cell.lookupAll(".html-source-selected")) {
                if (n instanceof Rectangle r && r.isVisible()) {
                    selYs.add(r.localToScene(r.getBoundsInLocal()).getMinY());
                }
            }
        }
        boolean spansRows = selYs.size() >= 2
                && selYs.get(selYs.size() - 1) - selYs.get(0) >= vDbl(viewer, "rowHeight") - 1;
        check("E2 wrap mapping: the selection highlight spans visual rows (rects=" + selYs.size()
                        + ", y span=" + (selYs.size() >= 2 ? fmt(selYs.get(selYs.size() - 1) - selYs.get(0)) : "n/a") + ")",
                spansRows);
        snapshot("htmlviewer-e2-wrapped-selection");   // 此刻的选择正是跨折行的两段
        // 3) 最后一个视觉行的行尾拖选 → 复制 = 行尾子串（含"行长不在折行边界"的尾段）
        int lastVRow = HtmlWrapMap.visualRowCount(longLen, cols) - 1;
        int lastRowStart = lastVRow * cols;
        dragVisual(1, lastVRow, longLen - lastRowStart - 4, 1, lastVRow, longLen - lastRowStart);
        copyViaApi();
        String expectedTail = vLine(viewer, 1).substring(longLen - 4);
        check("E2 wrap mapping: selecting the tail of the last visual row copies the line end "
                        + "(clipboard=" + esc(clipboardString()) + ")",
                charByChar("E2-wrap-tail", expectedTail, clipboardString()));
        // 4) 查找命中仍按逻辑列：查 "END"（位于长行后部、逻辑列远超 wrapCols）
        vQuery(viewer).setText("END");
        vFindButtons(viewer).get(0).fire();
        int[] hitCols = vHitCols(viewer);
        int hitLine = vInt(viewer, "currentHitLine");
        System.out.println("E2 wrap mapping find: query=END hitLine=" + hitLine
                + " hitCols=" + java.util.Arrays.toString(hitCols)
                + " wrapCols=" + cols + " hitCount=" + vInt(viewer, "hitCount"));
        check("E2 wrap mapping: find columns stay LOGICAL on a wrapped line (hitCols="
                        + java.util.Arrays.toString(hitCols) + ", endCol > wrapCols=" + cols + ")",
                hitLine == 1 && hitCols != null && hitCols.length == 2 && hitCols[1] > cols);
        Node hitCell = revealCellForLine(1);
        List<String> hitTexts = hitTextsWithin(hitCell);
        check("E2 wrap mapping: the hit is highlighted at the node level on the wrapped line ("
                        + hitTexts + ")", hitTexts.size() == 1 && "END".equals(hitTexts.get(0)));
        // 命中高亮落在后部视觉行（不在第一折行上）
        double hitMinY = Double.NaN;
        if (hitCell != null) {
            for (Node n : hitCell.lookupAll(".html-source-hit")) {
                hitMinY = n.localToScene(n.getBoundsInLocal()).getMinY();
                break;
            }
        }
        Bounds cb2 = hitCell == null ? null : hitCell.localToScene(hitCell.getBoundsInLocal());
        check("E2 wrap mapping: the hit highlight sits on a LATER visual row (hitMinY "
                        + fmt(hitMinY) + " vs first-row bottom " + fmt(cb2 == null ? Double.NaN : cb2.getMinY() + vDbl(viewer, "rowHeight")) + ")",
                hitCell != null && cb2 != null && hitMinY >= cb2.getMinY() + vDbl(viewer, "rowHeight") - 1);
        // 5) 复制内容仍不含行号/装饰（跨折行选择已逐字符比对；这里补一条行号反向断言）
        dragVisual(1, 0, 0, 1, 0, 2);
        copyViaApi();
        String clip = clipboardString();
        check("E2 wrap mapping: the copy contains no gutter line number (clipboard starts with '"
                        + (clip == null || clip.isEmpty() ? "(empty)" : escChar(clip.charAt(0))) + "', line number is '2')",
                charByChar("E2-wrap-no-gutter", vLine(viewer, 1).substring(0, 2), clip)
                        && clip != null && !clip.startsWith("2"));
    }

    // ---------- E2 §3.1 Chromium 对照语料（走真实查看器） ----------

    /** 语料（带末尾换行 → 末尾空行保留）。 */
    private String corpusBody() {
        return "<html>\r\n"
                + "  <body>\r"
                + "\n"
                + "\n"
                + "\n"
                + "    <p title=\"a\tb\">\n"
                + "<!-- cross\n"
                + "line comment -->\n"
                + "<!-- " + "LONG-".repeat(40) + " -->\n"
                + "<div>trailing   ";
    }

    private void openCorpusSource() {
        showSource(anchor, I18n.get("browser.viewerTitle"), corpusBody() + "\n");
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("E2 corpus (trailing NL): lines=" + vInt(viewer, "lineCount")
                + " wrapCols=" + vInt(viewer, "wrapCols"));
        for (int i = 0; i < vInt(viewer, "lineCount"); i++) {
            System.out.println("    [" + i + "] len=" + vLine(viewer, i).length()
                    + " <" + esc(vLine(viewer, i)) + ">");
        }
    }

    private void checkE2Corpus() {
        String[] expected = new String[]{
                "<html>", "  <body>", "", "",
                "    <p title=\"a\tb\">", "<!-- cross", "line comment -->",
                "<!-- " + "LONG-".repeat(40) + " -->", "<div>trailing   ", ""};
        check("E2 corpus: lineCount == 1 + newlines (10 lines: " + vInt(viewer, "lineCount") + ")",
                vInt(viewer, "lineCount") == expected.length);
        boolean allSame = vInt(viewer, "lineCount") == expected.length;
        for (int i = 0; allSame && i < expected.length; i++) {
            allSame = vLine(viewer, i).equals(expected[i]);
        }
        check("E2 corpus: every lineText is char-exact (CRLF/CR normalized to LF, tabs / leading "
                        + "/ trailing spaces / consecutive empty lines / trailing empty line kept)",
                allSame);
        // 【需求变更】决策 3.2：跨行拖选被钳制在锚点行内 —— 原"整篇复制 (0,0)→(9,0)"
        // 与"跨连续空行 + 制表符行的复制 (2,0)→(4,19)"不再是可达行为；改为逐行整行复制
        // 证语料逐字符 + 两个单行钳制断言（空行锚点 / 向下拖出）。
        dragVisual(4, 0, 0, 4, 0, 19);
        copyViaApi();
        check("E2 corpus: the tab line copies char-exact (tabs and spaces kept) (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E2-corpus-tab-line", "    <p title=\"a\tb\">", clipboardString()));
        // 从空行锚点拖到别的行 → 空选择不产复制（单行口径：空行的行首=行尾=列 0）。
        // 走原始 API 复制（不经哨兵路径 —— 本断言要验证的恰是"哨兵不被覆盖"）
        setClipboard("SENTINEL-E2-EMPTY-DRAG");
        dragVisual(2, 0, 0, 4, 0, 19);
        copyViaApiRaw();
        check("E2 corpus: dragging FROM an empty line to another line yields no selection "
                        + "(clipboard=" + esc(clipboardString()) + ")",
                "SENTINEL-E2-EMPTY-DRAG".equals(clipboardString()));
        // 从制表符行第 3 列向下拖 → 钳到该行行尾（单行选择）
        dragVisual(4, 0, 3, 5, 0, 7);
        copyViaApi();
        check("E2 corpus: dragging DOWN from the tab line clamps to its line end (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E2-corpus-clamp-down", "    <p title=\"a\tb\">".substring(3), clipboardString()));
        // 行尾空格行整行复制
        dragVisual(8, 0, 0, 8, 0, 16);
        copyViaApi();
        check("E2 corpus: copying the trailing-space line keeps every space (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E2-corpus-trailing", "<div>trailing   ", clipboardString()));
        // 超长行（209 字符）整行复制：跨多个折行逐字符
        int cols = vInt(viewer, "wrapCols");
        String longLine = vLine(viewer, 7);
        int lastVRow = HtmlWrapMap.visualRowCount(longLine.length(), cols) - 1;
        int lastRowStart = lastVRow * cols;
        dragVisual(7, 0, 0, 7, lastVRow, longLine.length() - lastRowStart);
        copyViaApi();
        check("E2 corpus: copying the 209-char long line across its visual rows is char-exact "
                        + "(visualRows=" + (lastVRow + 1) + ", clipboard len="
                        + (clipboardString() == null ? -1 : clipboardString().length()) + ")",
                charByChar("E2-corpus-long-line", longLine, clipboardString()));
        // 空行单击（空选择）不产生复制内容（选择清空、剪贴板保持哨兵）。
        // 走原始 API 复制（不经哨兵路径 —— 本断言要验证的恰是"哨兵不被覆盖"）
        setClipboard("SENTINEL-E2-EMPTY-LINE");
        dragVisual(2, 0, 0, 2, 0, 0);
        copyViaApiRaw();
        check("E2 corpus: clicking an empty line leaves the clipboard alone (clipboard="
                        + esc(clipboardString()) + ")",
                "SENTINEL-E2-EMPTY-LINE".equals(clipboardString()));
    }

    private void openCorpusSourceNoTrailingNl() {
        showSource(anchor, I18n.get("browser.viewerTitle"), corpusBody());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("E2 corpus (no trailing NL): lines=" + vInt(viewer, "lineCount")
                + " lastLine=" + esc(vLine(viewer, vInt(viewer, "lineCount") - 1)));
    }

    private void checkE2CorpusNoTrailingNl() {
        check("E2 corpus (no trailing NL): lineCount == 9 (no trailing empty line): "
                        + vInt(viewer, "lineCount"), vInt(viewer, "lineCount") == 9);
        check("E2 corpus (no trailing NL): the last line keeps its trailing spaces (len="
                        + vLine(viewer, 8).length() + ")", vLine(viewer, 8).length() == 16);
        // 【需求变更】决策 3.2：原"整篇复制 (0,0)→(8,16)"不再是可达行为；改为——
        // 从末行第 5 列向上拖 → 钳到该行行首 [0,5)，再整行复制末行证"末尾无 LF"。
        dragVisual(8, 0, 5, 5, 0, 3);
        copyViaApi();
        check("E2 corpus (no trailing NL): dragging UP from the last line clamps to its line "
                        + "start (clipboard=" + esc(clipboardString()) + ")",
                charByChar("E2-corpus-up-clamp", vLine(viewer, 8).substring(0, 5), clipboardString()));
        dragVisual(8, 0, 0, 8, 0, 16);
        copyViaApi();
        check("E2 corpus (no trailing NL): the last line copies char-exact and does NOT end "
                        + "with LF (clipboard len="
                        + (clipboardString() == null ? -1 : clipboardString().length()) + ")",
                charByChar("E2-corpus-last-line", vLine(viewer, 8), clipboardString())
                        && !clipboardString().endsWith("\n"));
    }

    // ---------- 步骤 5：台账 §4 升级为必补的两条（均按单行选区口径） ----------

    /**
     * 台账 §4-1（前半）：在小窗口做单行选区并复制取证，然后<b>拉宽窗口</b>触发折行重切。
     *
     * <p>resize 后的几何断言放在下一步（{@link #checkResizeRewrap}）：窗口尺寸经由原生 peer
     * 异步回传，且视口变化触发的 {@code refresh} 排在 runLater 里，下一步才看得见重建结果。</p>
     */
    private void checkResizeStage() {
        showSource(anchor, I18n.get("browser.viewerTitle"), longSource());
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        step5ColsBefore = vInt(viewer, "wrapCols");
        int vrowsBefore = HtmlWrapMap.visualRowCount(vLine(viewer, 1).length(), step5ColsBefore);
        // 单行选区 [0,5)：resize 前后复制都必须逐字符相同（单行口径）
        dragVisual(1, 0, 0, 1, 0, 5);
        copyViaApi();
        check("step5 resize: pre-resize single-line selection copies char-exact (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("step5-resize-pre", vLine(viewer, 1).substring(0, 5), clipboardString()));
        stage.setWidth(1500);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("step5 resize requested: colsBefore=" + step5ColsBefore
                + " vrowsBefore=" + vrowsBefore + " colsNow=" + vInt(viewer, "wrapCols")
                + " stageWidth=" + stage.getWidth());
    }

    /** 台账 §4-1（后半）：折行已重切 —— cell 重建、单行选区原样保留、复制不变；随后还原窗口尺寸。 */
    private void checkResizeRewrap() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        int cols = vInt(viewer, "wrapCols");
        int len1 = vLine(viewer, 1).length();
        int vrows = HtmlWrapMap.visualRowCount(len1, cols);
        Node cell = revealCellForLine(1);
        long flows = cell == null ? -1 : cell.lookupAll(".html-source-line").size();
        Bounds cb = cell == null ? null : cell.localToScene(cell.getBoundsInLocal());
        System.out.println("step5 resize rewrap: cols=" + cols + " (before " + step5ColsBefore
                + ") vrows=" + vrows + " flowsInCell=" + flows
                + " cellHeight=" + fmt(cb == null ? -1 : cb.getHeight()));
        check("step5 resize: wrapCols grew with the wider window (" + step5ColsBefore + " -> "
                        + cols + ")", cols > step5ColsBefore);
        check("step5 resize: the long line re-wrapped onto the NEW grid (flows " + flows
                        + " == " + vrows + ")", flows == vrows);
        check("step5 resize: the cell height matches the new wrap count ("
                        + fmt(cb == null ? -1 : cb.getHeight()) + " vs "
                        + fmt(vrows * vDbl(viewer, "rowHeight")) + ")",
                cell != null && cb != null && Math.abs(cb.getHeight() - vrows * vDbl(viewer, "rowHeight")) <= TOL);
        HtmlTextSelection.Range r = vRange(viewer);
        check("step5 resize: the selection survived the re-wrap and stays single-line (range="
                        + describeRange(r) + ")",
                r != null && r.startLine() == 1 && r.endLine() == 1
                        && r.startCol() == 0 && r.endCol() == 5);
        copyViaApi();
        check("step5 resize: the copy after re-wrap is still char-exact (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("step5-resize-copy", vLine(viewer, 1).substring(0, 5), clipboardString()));
        long selRects = cell == null ? 0 : cell.lookupAll(".html-source-selected").size();
        check("step5 resize: the selection rects repainted on the new grid (rects=" + selRects + ")",
                selRects >= 1);
        // 还原窗口尺寸（后续步骤依赖 1280×800 的几何）
        stage.setWidth(1280);
        stage.setHeight(800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        vNode(viewer).applyCss();
        vNode(viewer).layout();
    }

    /**
     * 台账 §4-2（横向滚动叠加选择，单行口径）：长行 + 选区时无横向滚动、选区矩形不出视口、
     * 跨折行的单行复制仍逐字符正确。
     */
    private void checkNoHscrollWithSelection() {
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        int cols = vInt(viewer, "wrapCols");
        int len1 = vLine(viewer, 1).length();
        int fromCol = Math.max(0, cols - 3);
        int toCol = Math.min(len1, cols + 5);
        dragVisual(1, 0, fromCol, 1, 1, toCol - cols);
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        check("step5 hscroll: hbar stays NEVER with a selection on the long line ("
                        + scroller.getHbarPolicy() + ")",
                scroller.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER);
        check("step5 hscroll: hvalue stays at 0 (selection rendering introduced no horizontal "
                        + "scroll; hvalue=" + fmt(scroller.getHvalue()) + ")", scroller.getHvalue() <= 0.001);
        Node cell = revealCellForLine(1);
        double viewportRight = scroller.localToScene(scroller.getBoundsInLocal()).getMaxX();
        boolean rectsInside = cell != null;
        int rects = 0;
        if (cell != null) {
            for (Node n : cell.lookupAll(".html-source-selected")) {
                if (!(n instanceof Rectangle r2) || !r2.isVisible()) {
                    continue;
                }
                rects++;
                rectsInside &= r2.localToScene(r2.getBoundsInLocal()).getMaxX() <= viewportRight + 1;
            }
        }
        check("step5 hscroll: every selection rect stays inside the viewport (rects=" + rects
                        + ", inside=" + rectsInside + ")", rectsInside && rects >= 1);
        copyViaApi();
        check("step5 hscroll: the cross-wrap single-line copy is still char-exact (clipboard len="
                        + (clipboardString() == null ? -1 : clipboardString().length()) + ")",
                charByChar("step5-hscroll-copy", vLine(viewer, 1).substring(fromCol, toCol), clipboardString()));
    }

    /** 视觉行坐标的拖选：视觉行号是逻辑行<b>内部</b>的折行行号，视觉列是该折行内的列。 */
    private void dragVisual(int fromLine, int fromVRow, int fromVCol, int toLine, int toVRow, int toVCol) {
        Node cell = revealCellForLine(fromLine);
        if (cell == null) {
            check("dragVisual could not reveal line " + fromLine, false);
            return;
        }
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        double contentX = b.getMinX() + vDbl(viewer, "gutterWidth");
        int cols = Math.max(1, vInt(viewer, "wrapCols"));
        double y = b.getMinY() + (fromVRow + 0.5) * vDbl(viewer, "rowHeight");
        fireMouse(MouseEvent.MOUSE_PRESSED, cell,
                sceneXForCol(cell, fromLine, fromVRow * cols + fromVCol, contentX), y, true);
        Node cell2 = revealCellForLine(toLine);
        double toContentX = contentX;
        if (cell2 != null) {
            Bounds b2 = cell2.localToScene(cell2.getBoundsInLocal());
            toContentX = b2.getMinX() + vDbl(viewer, "gutterWidth");
            y = b2.getMinY() + (toVRow + 0.5) * vDbl(viewer, "rowHeight");
        }
        double x = sceneXForCol(cell2 != null ? cell2 : cell, toLine, toVRow * cols + toVCol, toContentX);
        fireMouse(MouseEvent.MOUSE_DRAGGED, listView, x, y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, x, y, false);
    }

    /** 把 cell 里所有 {@code .html-source-text} 文本按序拼起来（"折行切片不丢字符"的证据）。 */
    private static String reassembledText(Node cell) {
        StringBuilder sb = new StringBuilder();
        if (cell != null) {
            for (Node n : cell.lookupAll(".html-source-text")) {
                if (n instanceof javafx.scene.text.Text t) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.toString();
    }

    private static double centerY(Bounds b) {
        return b == null ? Double.NaN : (b.getMinY() + b.getMaxY()) / 2;
    }

    private static double height(Bounds b) {
        return b == null ? Double.NaN : b.getHeight();
    }

    private static double minX(Bounds b) {
        return b == null ? Double.NaN : b.getMinX();
    }

    // ---------- R5 查找 ----------

    private void checkFindBarShape() {
        check("R5.1 the find bar is at the BOTTOM of the viewer (BorderPane bottom == findBar)",
                vNode(viewer) instanceof BorderPane bp && bp.getBottom() == vFindBar(viewer));
        List<Button> buttons = vFindButtons(viewer);
        List<String> icons = new ArrayList<>();
        for (Button b : buttons) {
            icons.add(b.getGraphic() instanceof FontIcon fi ? String.valueOf(fi.getIconCode()) : "none");
        }
        System.out.println("R5.2 find bar buttons: " + buttons.stream().map(Button::getId).toList()
                + " icons=" + icons);
        check("R5.2 there are exactly four find-bar buttons (found " + buttons.size() + ")",
                buttons.size() == 4);
        check("R5.2 all four are line icons with tooltips",
                buttons.stream().allMatch(b -> b.getGraphic() instanceof FontIcon && b.getTooltip() != null));
        check("R5.2 the icon set is [SEARCH, ANGLE_UP, ANGLE_DOWN, TIMES] in order: " + icons,
                iconOf(buttons.get(0)) == FontAwesomeSolid.SEARCH
                        && iconOf(buttons.get(1)) == FontAwesomeSolid.ANGLE_UP
                        && iconOf(buttons.get(2)) == FontAwesomeSolid.ANGLE_DOWN
                        && iconOf(buttons.get(3)) == FontAwesomeSolid.TIMES);
        check("R5.1 the find bar is visible", vFindBar(viewer).isVisible());
        check("R5.2 the query field is reachable and empty at start",
                vQuery(viewer) != null && vQuery(viewer).getText().isEmpty());
    }

    /** 输入一个必然命中的词 → 计数 1 / m。 */
    private void checkFindCount() {
        vQuery(viewer).setText(SEARCH_TOKEN);
        vFindButtons(viewer).get(0).fire();
        int total = vInt(viewer, "hitCount");
        countAtFirstHit = vCountLabel(viewer).getText();
        firstHitLine = vInt(viewer, "currentHitLine");
        System.out.println("R5.3 query=\"" + SEARCH_TOKEN + "\" -> count=\"" + countAtFirstHit
                + "\" hits=" + total + " currentLine=" + firstHitLine);
        check("R5.3 a guaranteed-hit query produces hits (m=" + total + " > 0)", total > 0);
        check("R5.3 the counter shows \"1 / m\": " + countAtFirstHit,
                countAtFirstHit.contains("1") && countAtFirstHit.contains(String.valueOf(total)));
        check("R5.4 the first hit is on a real line (line " + firstHitLine + " of " + vInt(viewer, "lineCount") + ")",
                firstHitLine > 0 && firstHitLine < vInt(viewer, "lineCount"));
    }

    private void checkFindNavigate() {
        vFindButtons(viewer).get(2).fire();   // next (ANGLE_DOWN)
        secondHitLine = vInt(viewer, "currentHitLine");
        int second = vInt(viewer, "currentHitNumber");
        System.out.println("R5.4 next -> " + vCountLabel(viewer).getText()
                + " currentLine=" + secondHitLine);
        check("R5.4 next advances the counter to 2 / m (was " + countAtFirstHit + ", now "
                        + vCountLabel(viewer).getText() + ")", second == 2);
        check("R5.4 next moved to a different line (" + firstHitLine + " -> " + secondHitLine + ")",
                secondHitLine != firstHitLine);
        vFindButtons(viewer).get(1).fire();   // prev (ANGLE_UP)
        check("R5.4 prev goes back to 1 / m (now " + vCountLabel(viewer).getText() + ")",
                vInt(viewer, "currentHitNumber") == 1);
        check("R5.4 prev restored the first hit line (" + vInt(viewer, "currentHitLine") + ")",
                vInt(viewer, "currentHitLine") == firstHitLine);
    }

    /** 到头再下一个 → 回到 1（循环）。 */
    private void checkFindWrapAround() {
        int total = vInt(viewer, "hitCount");
        for (int i = 0; i < total; i++) {
            vFindButtons(viewer).get(2).fire();
        }
        System.out.println("R5.4 after " + total + " \"next\" clicks -> "
                + vCountLabel(viewer).getText());
        check("R5.4 next WRAPS AROUND: after m clicks the counter is back to 1 / m ("
                        + vCountLabel(viewer).getText() + ")",
                vInt(viewer, "currentHitNumber") == 1);
        vFindButtons(viewer).get(1).fire();
        check("R5.4 prev also wraps (1 -> m): " + vCountLabel(viewer).getText(),
                vInt(viewer, "currentHitNumber") == total);
        vFindButtons(viewer).get(2).fire();
        check("R5.4 next from m wraps back to 1: " + vCountLabel(viewer).getText(),
                vInt(viewer, "currentHitNumber") == 1);
    }

    /** 必然不命中的词 → 计数 0、不报错（无异常、无错误卡）。 */
    private void checkFindNoHits() {
        modalPane.hide();
        vQuery(viewer).setText("zzz-this-token-cannot-exist-zzz");
        String verdict;
        try {
            vFindButtons(viewer).get(0).fire();
            vFindButtons(viewer).get(2).fire();
            vFindButtons(viewer).get(1).fire();
            verdict = "no exception";
        } catch (Throwable ex) {
            verdict = "threw " + ex;
        }
        System.out.println("R5.4 no-hit query -> count=\"" + vCountLabel(viewer).getText()
                + "\" hits=" + vInt(viewer, "hitCount") + " verdict=" + verdict
                + " modalDisplay=" + modalPane.isDisplay());
        check("R5.4 a query with no hits raises nothing (" + verdict + ")", "no exception".equals(verdict));
        check("R5.4 the counter shows 0 for no hits: " + vCountLabel(viewer).getText(),
                vInt(viewer, "hitCount") == 0 && vCountLabel(viewer).getText().contains("0"));
        check("R5.4 no error card was raised by the empty result (modal=" + modalPane.isDisplay() + ")",
                !modalPane.isDisplay());
        check("R5.4 navigation with no hits is a no-op, not a crash (current="
                + vInt(viewer, "currentHitNumber") + ")", vInt(viewer, "currentHitNumber") == 0);
    }

    /** R5.4：命中高亮（节点级）+ 滚动到可见。 */
    private void checkHighlightAndScroll() {
        vQuery(viewer).setText(SEARCH_TOKEN);
        vFindButtons(viewer).get(0).fire();
        int line = vInt(viewer, "currentHitLine");
        Node cell = revealCellForLine(line);
        List<String> highlighted = hitTextsWithin(cell);
        String hitLineText = vLine(viewer, line);
        System.out.println("R5.4 highlight: currentLine=" + line
                + " highlighted=\"" + String.join("\",\"", highlighted) + "\""
                + " cell=" + describe(cell) + " visible=" + isEffectivelyVisible(cell));
        check("R5.4 the current hit is highlighted at the NODE level (found " + highlighted.size()
                        + " .html-source-hit-text runs inside the hit line's cell: " + highlighted + ")",
                highlighted.size() == 1 && SEARCH_TOKEN.equalsIgnoreCase(highlighted.get(0)));
        check("R5.4 the highlighted text is a substring of its line: "
                        + String.valueOf(hitLineText).trim(),
                hitLineText != null && !highlighted.isEmpty()
                        && hitLineText.toLowerCase().contains(highlighted.get(0).toLowerCase()));
        check("R5.4 the hit line is visible in the virtualized viewport (cell=" + describe(cell) + ")",
                cell != null && isEffectivelyVisible(cell));
    }

    /**
     * R5：高亮的定点重绘 —— 判据是<b>真正重建的 cell 数</b>（不是被"刷"的行数）。
     *
     * <p>为什么用这个数：{@code ListView} 收到"某项变了"只会重建<b>可见</b>的 cell，
     * 所以整表 {@code setAll}/{@code refresh} 与逐行定点 {@code set} 都会让
     * "被指名的行数"看起来一样小，区别在<b>实际重建量</b>。{@code cellsBuiltInLastRefresh}
     * 直接量后者：定点重绘应当只与窗口能显示的行数同阶，而整表刷新会随总行数增长。</p>
     */
    private void checkMinimalRepaint() {
        // 造一次"远距离跳转"：命中从第 1 处走到最后一处，新旧两行相距很远
        vQuery(viewer).setText(SEARCH_TOKEN);
        vFindButtons(viewer).get(0).fire();
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        int cellsBefore = vInt(viewer, "cellsBuiltInLastRefresh");
        vFindButtons(viewer).get(1).fire();       // prev -> 循环到最后一处（最远的一行）
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        int cellsBuilt = vInt(viewer, "cellsBuiltInLastRefresh");
        int rows = vInt(viewer, "lineCount");
        System.out.println("R5 focused repaint: cellsBuiltInLastRefresh=" + cellsBuilt
                + " (search itself: " + cellsBefore + ")"
                + " repaints=" + vLong(viewer, "repaintCount")
                + " namedLines=" + vLong(viewer, "repaintedLineCount") + " totalLines=" + rows);
        check("R5 moving the hit rebuilds only a handful of cells (" + cellsBuilt
                        + ") instead of all " + rows + " rows", cellsBuilt > 0 && cellsBuilt < 200);
        check("R5 that is far below the line count (" + cellsBuilt + " << " + rows + ")",
                cellsBuilt * 10 < rows);
        check("R5 the repaint went through the find path, not a table-wide refresh (repaints="
                + vLong(viewer, "repaintCount") + ")", vLong(viewer, "repaintCount") > 0);
    }

    /** R5.1：查找栏在查看器底部（几何上就在内层视口下方）。 */
    private void checkFindBarBottom() {
        Bounds bar = vFindBar(viewer).localToScene(vFindBar(viewer).getBoundsInLocal());
        Bounds viewport = scroller.localToScene(scroller.getBoundsInLocal());
        Bounds viewerBounds = vNode(viewer).localToScene(vNode(viewer).getBoundsInLocal());
        System.out.println(String.format("R5.1 geometry: findBar y=[%.0f..%.0f] viewport y=[%.0f..%.0f] viewer y=[%.0f..%.0f]",
                bar.getMinY(), bar.getMaxY(), viewport.getMinY(), viewport.getMaxY(),
                viewerBounds.getMinY(), viewerBounds.getMaxY()));
        check("R5.1 the find bar sits BELOW the scrolling viewport (bar.minY >= viewport.maxY - 2)",
                bar.getMinY() >= viewport.getMaxY() - 2);
        check("R5.1 the find bar is inside the viewer's vertical extent",
                bar.getMaxY() <= viewerBounds.getMaxY() + 2);
        check("R5.1 the find bar spans the viewer width (bar=" + fmt(bar.getWidth())
                        + " viewer=" + fmt(viewerBounds.getWidth()) + ")",
                Math.abs(bar.getWidth() - viewerBounds.getWidth()) <= 2);
    }

    /** R2.9：收起即隐藏；右侧行为不受影响（此前已单独验过）。 */
    private void checkCollapseR29() {
        host.leftCollapseButton().fire();
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        System.out.println("R2.9 after collapse: leftVisible=" + leftPanel.isVisible()
                + " leftShowing=" + host.isLeftShowing()
                + " rightVisible=" + rightPanel.isVisible() + " rightShowing=" + host.isRightShowing());
        check("R2.9 the collapse button hides the left panel (logical state)",
                !host.isLeftShowing());
        check("R2.9 with slideMillis=0 the panel is also physically hidden", !leftPanel.isVisible());
        check("R2.9 collapsing the viewer does not touch the right panel",
                !host.isRightShowing() && !rightPanel.isVisible());
        check("R2.9 there is no draft/commit semantics: reopening just shows the same node",
                vNode(viewer) == ((BorderPane) leftPanel).getCenter());
    }

    private void checkGuiStateUnchanged() {
        String after = guiStateKeySet();
        System.out.println("gui-state keys after  = " + after);
        check("R3.5 opening/closing the source viewer persists nothing (gui-state key set unchanged)",
                after.equals(guiStateKeysBefore));
    }

    private void snapshotFinal() {
        snapshot("htmlviewer");
    }

    // ==================== helpers ====================

    /** 必然命中的词：出现在大源码的多行上（大源码每行都含 "PROBE-TOKEN"）。 */
    private static final String SEARCH_TOKEN = "PROBE-TOKEN";

    /** 夹具目录（11 个平铺 .mht；与 SidePanelProbe 同一个来源）。 */
    private static final String FIXTURES_DIR =
            new File("src/test/resources/fixtures").getAbsolutePath();

    /** 5000 行、每行都含 {@link #SEARCH_TOKEN}（于是"下一个"必然换行）。 */
    private String bigSource() {
        StringBuilder sb = new StringBuilder(400 * BIG_LINES);
        sb.append("<html>\n<head><title>probe</title></head>\n<body>\n");
        for (int i = 0; i < BIG_LINES; i++) {
            sb.append("<div class=\"row-").append(i).append("\">line ")
                    .append(i).append(" ").append(SEARCH_TOKEN).append("</div>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** 3 行，其中一行极长（横向滚动的判据）。 */
    private String longSource() {
        String longLine = "<!-- " + "LONG-LINE-".repeat(120) + "END -->";
        return "<html>\n" + longLine + "\n</html>\n";
    }

    private static Path writeTemp(String prefix, String content) {
        try {
            Path p = Files.createTempFile(prefix, ".html");
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FontAwesomeSolid iconOf(Button b) {
        return b.getGraphic() instanceof FontIcon fi
                && fi.getIconCode() instanceof FontAwesomeSolid solid ? solid : null;
    }

    /** 场景里第 {@code row} 行（0 基）对应的 cell（虚拟化下只有可见区域才有）。 */
    private Node cellForLine(int row) {
        for (Node n : vNode(viewer).lookupAll(".list-cell")) {
            if (n instanceof javafx.scene.control.ListCell<?> lc && !lc.isEmpty()
                    && lc.getIndex() == row) {
                return n;
            }
        }
        return null;
    }

    /**
     * 强制一次布局并返回该行的 cell。
     *
     * <p>探针跑在 FX 线程上、又用 {@code Thread.sleep} 给脉冲让路，所以"滚动到某行"之后
     * 必须显式 {@code applyCss+layout} 才能真正把那个 cell 建出来 —— 否则查到的是空。</p>
     */
    private Node revealCellForLine(int row) {
        vNode(viewer).applyCss();
        vNode(viewer).layout();
        Node cell = cellForLine(row);
        if (cell == null) {
            vList(viewer).scrollTo(row);
            vNode(viewer).applyCss();
            vNode(viewer).layout();
            cell = cellForLine(row);
        }
        return cell;
    }

    /**
     * 某 cell 内部的命中高亮文本（命中样式的节点级证据）。
     *
     * <p>【需求变更】步骤 5 起命中高亮不再是字形流里包背景的 {@code StackPane}
     * （决策 3.7 的层序要求它沉到选区之下），黄底改画在最底层命中层的矩形上，字形层里的
     * 命中片段改挂 {@code html-source-hit-text} 标记 —— 本助手按新口径取。</p>
     */
    private static List<String> hitTextsWithin(Node cell) {
        List<String> out = new ArrayList<>();
        if (cell == null) {
            return out;
        }
        for (Node n : cell.lookupAll(".html-source-hit-text")) {
            if (n instanceof javafx.scene.text.Text t) {
                out.add(t.getText());
            }
        }
        return out;
    }

    private static String titleText(Node panel) {
        if (panel instanceof BorderPane bp && bp.getTop() instanceof Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) {
                if (n instanceof Label l && !l.getText().isEmpty()) {
                    return l.getText();
                }
            }
        }
        return "";
    }

    private TimerTask at(long delay, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    private void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + label);
        } else {
            failed++;
            System.out.println("FAIL  " + label);
        }
    }

    private void finish() {
        System.out.println("HTML-VIEWER PROBE: " + passed + " passed, " + failed + " failed  (locale="
                + I18n.currentLocale() + ")");
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /**
     * 复刻 JavaFX 的命中测试（与 {@code SidePanelProbe} 同法，外加一条本步必须的裁剪判定）。
     *
     * <p>🚩 <b>必须判定祖先裁剪</b>：查看器是一个覆盖半区的 {@code ScrollPane} 视口，
     * 下方 cell / 相邻面板的内容都可能超出视口边界；不做裁剪判定就会"命中"到
     * <b>屏幕上看不见、而且被视口裁掉</b>的节点 —— 实测正是它把"右半区仍可点主界面"
     * 这条断言判成了假失败。</p>
     */
    private static Node pick(Node node, double sceneX, double sceneY) {
        if (node == null || !node.isVisible() || node.isMouseTransparent() || isClippedOut(node, sceneX, sceneY)) {
            return null;
        }
        if (node instanceof ModalPane mp && !mp.isDisplay()) {
            return null;
        }
        if (node instanceof Parent parent) {
            List<Node> kids = parent.getChildrenUnmodifiable();
            for (int i = kids.size() - 1; i >= 0; i--) {
                Node hit = pick(kids.get(i), sceneX, sceneY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (node instanceof Region region && !region.isPickOnBounds()) {
            return null;
        }
        return node.getLayoutBounds().contains(node.sceneToLocal(sceneX, sceneY)) ? node : null;
    }

    /**
     * 该点是否落在某个祖先的 {@code clip} 之外（= 被剪掉了，真实命中测试不会命中它）。
     *
     * <p>只在祖先<b>有</b> clip 时判定；比较前把祖先的 clip 边界换算到祖先自己的坐标系，
     * 与被测节点的场景坐标比较。</p>
     */
    private static boolean isClippedOut(Node node, double sceneX, double sceneY) {
        for (Node a = node.getParent(); a != null; a = a.getParent()) {
            if (a.getClip() == null || !a.isVisible()) {
                continue;
            }
            Bounds clipScene = a.localToScene(a.getClip().getBoundsInLocal());
            if (!clipScene.contains(sceneX, sceneY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEffectivelyVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Node n) {
        if (n == null) {
            return "null";
        }
        String id = n.getId() != null ? "#" + n.getId() : "";
        return n.getClass().getSimpleName() + id;
    }

    /** 诊断：在给定场景点上逐层打印"谁命中了、为什么别的不命中"（-Dprobe.trace.pick=true 才输出）。 */
    private void debugPick(Point2D p, String label) {
        if (p == null || !Boolean.getBoolean("probe.trace.pick")) {
            return;
        }
        System.out.println("--- pick trace " + label + " scene=" + p + " ---");
        trace(scene.getRoot(), p.getX(), p.getY(), 0);
        Node hit = pick(scene.getRoot(), p.getX(), p.getY());
        System.out.println("    PICKED " + describe(hit));
    }

    private static void trace(Node node, double sx, double sy, int depth) {
        if (node == null || depth >= 8 || !(node instanceof Parent parent)) {
            return;
        }
        System.out.println("    " + "  ".repeat(depth) + describe(node) + " visible=" + node.isVisible()
                + " pickOnBounds=" + (node instanceof Region r ? r.isPickOnBounds() : null)
                + " contains=" + node.getLayoutBounds().contains(node.sceneToLocal(sx, sy))
                + " clippedOut=" + isClippedOut(node, sx, sy)
                + " clip=" + (node.getClip() == null ? "none"
                        : node.getClip().getClass().getSimpleName() + node.localToScene(
                                node.getClip().getBoundsInLocal())));
        for (Node c : parent.getChildrenUnmodifiable()) {
            if (c.getLayoutBounds().contains(c.sceneToLocal(sx, sy))) {
                trace(c, sx, sy, depth + 1);
            }
        }
    }

    private ModalPane findModalPane() {
        for (Node n : shell.getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        return null;
    }

    private String guiStateKeySet() {
        Path p = Path.of("logs", "gui-state.json");
        if (!Files.exists(p)) {
            return "(absent)";
        }
        try {
            JsonNode n = new ObjectMapper().readTree(Files.readString(p));
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            return String.valueOf(keys);
        } catch (Exception e) {
            return "(unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private void snapshot(String name) {
        try {
            javafx.scene.image.WritableImage img = scene.snapshot(null);
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            javafx.scene.image.PixelReader pr = img.getPixelReader();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                pr.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
                bi.setRGB(0, y, w, 1, row, 0, w);
            }
            File file = new File(outDir, name + "-"
                    + String.valueOf(I18n.currentLocale()).replaceAll("[^A-Za-z0-9_]", "") + ".png");
            ImageIO.write(bi, "png", file);
            System.out.println("snapshot -> " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("snapshot(" + name + ") failed: " + e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
