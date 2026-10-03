import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.HtmlSourceViewer;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.layout.BorderPane;import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
 *   <li>R2.8 a long line is not soft-wrapped (preferred width &gt; viewport), the content really
 *       scrolls horizontally to its tail, and the gutter stays aligned with the content;</li>
 *   <li>R5.2/R5.3/R5.4 find bar at the bottom, four line-icon buttons, "n / m" counter, next/prev
 *       wrap around, a query with no hits shows 0 and raises nothing;</li>
 *   <li>R5.4 hit highlight is a node-level fact and the hit line is scrolled into view;
 *       the highlight refresh touches only the active lines (far fewer than the line count);</li>
 *   <li>R2.9 collapsing hides the left panel without touching the right one.</li>
 *   <li>E1 character selection &amp; copy: mouse drag selects by (line, column), the platform
 *       copy key puts the raw source chars into the system clipboard (13 assertions, each one
 *       read back from the system clipboard and compared char-by-char).</li>
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
        viewer = HtmlSourceViewer.hostOf(anchor);
        System.out.println("HtmlSourceViewer.showFile returned in " + openMillis
                + " ms (FX thread not blocked by the file read)");
    }

    private void checkR21Shape() {
        leftPanel = scene.lookup("#sourceViewerPanel");
        rightPanel = scene.lookup("#sidePanel");
        Button collapse = (Button) scene.lookup("#sourceViewerCollapse");
        scroller = viewer.scroller();
        listView = viewer.listView();

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

        check("R2.1 the viewer is hosted in the left panel (center == viewer.node())",
                leftPanel instanceof BorderPane bp && bp.getCenter() == viewer.node());
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
        check("R2.7/R2.8 the viewer has a ListView inside a ScrollPane",
                listView != null && scroller != null && scroller.getContent() == listView);
        check("R2.8 the outer ScrollPane is not fitting the content to its own width",
                !scroller.isFitToWidth());
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
        for (Button b : viewer.findButtons()) {
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
        long before = viewer.loadCount();
        viewSource.fire();
        awaitCondition("unparseable fixture", 8_000, this::errorCardShown, expired -> {
            System.out.println("R2 control group (" + name + "): loadCount " + before + " -> "
                    + viewer.loadCount() + " errorCard=" + errorCardShown()
                    + " (timedOut=" + expired + ")");
            check("R2 control group: the unparseable item does NOT load anything into the viewer "
                            + "(loadCount " + before + " -> " + viewer.loadCount() + ")",
                    viewer.loadCount() == before);
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
        long loadBefore = viewer.loadCount();
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
                () -> HtmlSourceViewer.isShowing(anchor) && viewer.loadCount() > loadBefore,
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
        String firstLine = viewer.lineText(0);
        System.out.println("R2 " + label + ": showing=" + HtmlSourceViewer.isShowing(anchor)
                + " loadCount " + loadBefore + " -> " + viewer.loadCount()
                + " lineCount=" + viewer.lineCount()
                + " firstLine=" + String.valueOf(firstLine).trim());
        check("R2 the real 「查看HTML源码」 entry opened the IN-APP left viewer (" + label + ", ready="
                + ready + ")", ready);
        check("R2 that entry really loaded NEW content (loadCount " + loadBefore + " -> "
                + viewer.loadCount() + ")", viewer.loadCount() > loadBefore);
        check("R2 the viewer is the left panel's content (not a browser tab)",
                leftPanel instanceof BorderPane bp && bp.getCenter() == viewer.node());
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
     * 否则"计数 / 循环 / 高亮 / 定点重绘"全部会落在一个 4 行文档上，判据失去意义。</p>
     */
    private final List<Runnable> BIG_STEPS = List.of(
            this::checkR27Virtualization,
            this::checkFindBarShape, this::checkFindCount, this::checkFindNavigate,
            this::checkFindWrapAround, this::checkFindNoHits,
            this::checkHighlightAndScroll, this::checkMinimalRepaint, this::checkFindBarBottom,
            this::checkSelectionAndCopy,
            this::openLongLineSource,
            this::checkR28NoSoftWrap, this::checkR28ScrollToTail,
            this::checkCollapseR29, this::checkGuiStateUnchanged,
            this::snapshotFinal, this::finish);

    /**
     * 等到 {@code viewer.lineCount() == expected} 再回到步骤驱动；超时则如实打印并继续。
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
                    boolean ok = viewer.lineCount() == expected;
                    if (!ok && System.currentTimeMillis() <= deadline) {
                        return;   // 还没好，等下一拍
                    }
                    cancel();
                    loadMillis = (System.nanoTime() - loadStartNanos) / 1_000_000;
                    System.out.println("awaitLines(" + label + "): " + (ok ? "ready" : "TIMED OUT")
                            + " lineCount=" + viewer.lineCount() + " expected=" + expected
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
        viewer.node().applyCss();
        viewer.node().layout();
        int cells = viewer.node().lookupAll(".list-cell").size();
        int texts = viewer.node().lookupAll(".html-source-text").size();
        int rows = viewer.lineCount();
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
     * E1：选择/复制的 13 条断言，作为一步排在 R2.8 之前（之后由 {@link #openLongLineSource()}
     * 切回长行源，确保 R2.8 的既有断言不受影响）。
     *
     * <p>先用自己的小组装源码（{@link #showSource} 同步路径）验 #1..#11 与 #13，
     * 再同步装上 5000 行大源码验 #12（虚拟化）；每条复制断言都从<b>系统剪贴板读回</b>
     * 并<b>逐字符</b>比对。</p>
     */
    private void checkSelectionAndCopy() {
        HtmlSourceViewer.showSource(anchor, I18n.get("browser.viewerTitle"), selectionSource());
        viewer.node().applyCss();
        viewer.node().layout();
        System.out.println("E1 selection source: lines=" + viewer.lineCount());
        for (int i = 0; i < viewer.lineCount(); i++) {
            System.out.println("    [" + i + "] <" + esc(viewer.lineText(i)) + ">");
        }
        // 坐标几何诊断：cell 边界 / 行号栏 / 内容流 / 第一个 Text 的场景 x，四个应逐级衔接
        Node diag = revealCellForLine(4);
        if (diag != null) {
            Bounds cb = diag.localToScene(diag.getBoundsInLocal());
            double derived = cb.getMinX() + viewer.gutterWidth();
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
                    && wrapper.getChildren().size() >= 1
                    && wrapper.getChildren().get(0) instanceof javafx.scene.layout.HBox hb
                    && hb.getChildren().size() >= 2
                    && hb.getChildren().get(1) instanceof javafx.scene.text.TextFlow tf) {
                flowMinX = tf.localToScene(tf.getBoundsInLocal()).getMinX();
            }
            System.out.println("E1 geometry: cellMinX=" + fmt(cb.getMinX())
                    + " gutterWidth=" + fmt(viewer.gutterWidth())
                    + " derivedContentX=" + fmt(derived)
                    + " firstTextSceneMinX=" + fmt(textMinX)
                    + " flowBoundsSceneMinX=" + fmt(flowMinX)
                    + " textDelta=" + fmt(textMinX - derived)
                    + " flowDelta=" + fmt(flowMinX - derived));
        }
        // #13 的行模型快照（断言前先记下）
        int linesBefore = viewer.lineCount();
        List<String> modelBefore = new ArrayList<>();
        for (int i = 0; i < linesBefore; i++) {
            modelBefore.add(viewer.lineText(i));
        }

        // #1 单行内拖选 → 剪贴板 == 原始子串（API 复制路径，逐字符）
        dragSelect(4, 3, 4, 21);
        copyViaApi();
        String expected1 = viewer.lineText(4).substring(3, 21);
        check("E1#1 single-line drag copies the exact source substring (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#1", expected1, clipboardString()));

        // #2 跨语法 token（注释 → 标签 → 正文）→ 剪贴板 == 原始字符（无标记注入）
        dragSelect(3, 0, 4, 21);
        copyViaKey();
        String expected2 = viewer.lineText(3) + "\n" + viewer.lineText(4).substring(0, 21);
        check("E1#2 cross-token (comment→tag→text) copy is the raw source chars (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#2", expected2, clipboardString()));

        // #3 跨逻辑行 → LF 连接、含中间整行
        dragSelect(3, 4, 5, 2);
        copyViaKey();
        String expected3 = viewer.lineText(3).substring(4) + "\n" + viewer.lineText(4)
                + "\n" + viewer.lineText(5).substring(0, 2);
        check("E1#3 cross-line copy joins with LF and keeps the middle line whole (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#3", expected3, clipboardString()));

        // #4 范围内含空行 → 连续 LF 保留
        dragSelect(1, 2, 3, 8);
        copyViaKey();
        String expected4 = viewer.lineText(1).substring(2) + "\n" + viewer.lineText(2)
                + "\n" + viewer.lineText(3).substring(0, 8);
        check("E1#4 an empty line inside the range keeps the consecutive LFs (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#4", expected4, clipboardString()) && expected4.contains("\n\n"));

        // #5 行首缩进 + 行尾空格保留（行 1 = 4 个前导空格 + 4 个行尾空格）
        dragSelect(1, 0, 1, viewer.lineText(1).length());
        copyViaKey();
        check("E1#5 indent and trailing spaces survive char-by-char (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#5", viewer.lineText(1), clipboardString()));

        // #6 复制文本不含行号（逐字符 equals 已证；再加反向断言：不以行号字符串开头）
        String clip6 = clipboardString();
        String first6 = clip6 == null || clip6.isEmpty() ? "(empty)" : escChar(clip6.charAt(0));
        check("E1#6 the copy contains no gutter line number (starts with '" + first6
                        + "', not the line number '2')",
                charByChar("E1#6", viewer.lineText(1), clip6) && clip6 != null && !clip6.startsWith("2"));

        // #7 平台复制键（Ctrl/Cmd+C）触发复制（与 #1 的 API 路径对照：这里只有按键）
        setClipboard("SENTINEL-7");
        dragSelect(4, 5, 4, 10);
        copyViaKey();
        String expected7 = viewer.lineText(4).substring(5, 10);
        check("E1#7 the platform copy key (Ctrl/Cmd+C) copies the selection (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#7", expected7, clipboardString()));

        // #8 无选择时复制键不覆盖剪贴板 / 不抛异常（单击 = 清空选择）
        dragSelect(0, 0, 0, 0);
        setClipboard("SENTINEL-8-NO-SELECTION");
        String verdict8;
        try {
            copyViaKey();
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
        String expected9 = viewer.lineText(4).substring(0, 21);
        check("E1#9 a drag starting in the gutter does not copy line numbers (clipboard="
                        + esc(clipboardString()) + ")",
                charByChar("E1#9", expected9, clipboardString()));

        // #10 选择后 find 命中数不变（查找未被破坏）
        viewer.queryField().setText(SEARCH_TOKEN);
        viewer.findButtons().get(0).fire();
        int hitsBefore = viewer.hitCount();
        dragSelect(2, 0, 4, 10);
        check("E1#10 find hits are unchanged by a selection (before=" + hitsBefore
                        + " after=" + viewer.hitCount() + ")",
                hitsBefore > 0 && viewer.hitCount() == hitsBefore);

        // #11 选择后语法高亮仍在 + html-source-selected 出现 + find 命中样式并存
        viewer.node().applyCss();
        viewer.node().layout();
        long textNodes = viewer.node().lookupAll(".html-source-text").size();
        long selectedVisible = viewer.node().lookupAll(".html-source-selected").stream()
                .filter(Node::isVisible).count();
        long hitNodes = viewer.node().lookupAll(".html-source-hit").size();
        check("E1#11 syntax highlight survives and the selection class shows while the find hit "
                        + "coexists (textNodes=" + textNodes + " selectedVisible=" + selectedVisible
                        + " hitNodes=" + hitNodes + ")",
                textNodes > 0 && selectedVisible > 0 && hitNodes > 0);
        snapshot("htmlviewer-selection");

        // #13 选择不改变行模型（lineCount / lineText 不变）
        boolean modelSame = viewer.lineCount() == linesBefore;
        for (int i = 0; modelSame && i < linesBefore; i++) {
            modelSame = viewer.lineText(i).equals(modelBefore.get(i));
        }
        check("E1#13 selection never changes the line model (lines=" + viewer.lineCount()
                        + " vs " + linesBefore + ", every lineText identical=" + modelSame + ")",
                modelSame);

        // #12 5000 行大文件上选择后 .list-cell 数仍远小于行数（虚拟化保持）
        HtmlSourceViewer.showSource(anchor, I18n.get("browser.viewerTitle"), bigSource());
        viewer.node().applyCss();
        viewer.node().layout();
        dragSelect(2, 5, 4, 30);
        int cells = viewer.node().lookupAll(".list-cell").size();
        int rowsBig = viewer.lineCount();
        System.out.println("E1#12 big source after selection: lines=" + rowsBig + " cells=" + cells);
        check("E1#12 on the " + rowsBig + "-line source a selection keeps virtualization (cells="
                        + cells + " << lines)", cells > 0 && cells * 10 < rowsBig);
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
        double contentX = b.getMinX() + viewer.gutterWidth();
        double cw = viewer.charWidth();
        double y = b.getMinY() + b.getHeight() / 2;
        fireMouse(MouseEvent.MOUSE_PRESSED, cell, contentX + fromCol * cw, y, true);
        Node cell2 = revealCellForLine(toLine);
        if (cell2 != null) {
            Bounds b2 = cell2.localToScene(cell2.getBoundsInLocal());
            y = b2.getMinY() + b2.getHeight() / 2;
        }
        fireMouse(MouseEvent.MOUSE_DRAGGED, listView, contentX + toCol * cw, y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, contentX + toCol * cw, y, false);
    }

    /** 在行号区（gutter）按下再拖到内容列 {@code toCol}（#9：行号不得进复制）。 */
    private void dragSelectFromGutter(int line, int toCol) {
        Node cell = revealCellForLine(line);
        if (cell == null) {
            check("dragSelectFromGutter could not reveal line " + line, false);
            return;
        }
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        double contentX = b.getMinX() + viewer.gutterWidth();
        double y = b.getMinY() + b.getHeight() / 2;
        double cw = viewer.charWidth();
        fireMouse(MouseEvent.MOUSE_PRESSED, cell, b.getMinX() + 2, y, true);
        fireMouse(MouseEvent.MOUSE_DRAGGED, listView, contentX + toCol * cw, y, true);
        fireMouse(MouseEvent.MOUSE_RELEASED, listView, contentX + toCol * cw, y, false);
    }

    private static void fireMouse(EventType<MouseEvent> type, Node target,
                                  double sceneX, double sceneY, boolean primary) {
        Event.fireEvent(target, new MouseEvent(type, sceneX, sceneY, sceneX, sceneY,
                MouseButton.PRIMARY, 1, false, false, false, false,
                primary, false, false, false, false, true, null));
    }

    /** 平台复制键（Windows 下 = Ctrl+C，经 {@code KeyEvent.isShortcutDown()} 判定）。 */
    private void copyViaKey() {
        Event.fireEvent(listView, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                false, true, false, false));
    }

    /** 经查看器的公开 API 复制（与按键路径对照用）。 */
    private void copyViaApi() {
        viewer.copySelection();
    }

    private static void setClipboard(String s) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private static String clipboardString() {
        return Clipboard.getSystemClipboard().getString();
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

    // ---------- R2.8 横向滚动 ----------

    private void openLongLineSource() {
        // showSource 是同步路径（tokenize 在调用线程上），返回时行模型已就位
        HtmlSourceViewer.showSource(anchor, I18n.get("browser.viewerTitle"), longSource());
        viewer.node().applyCss();
        viewer.node().layout();
        // 视口宽度（横向滚动的判据：内容宽 > 视口宽）
        System.out.println("R2.8 long line: contentWidth=" + fmt(viewer.contentPixelWidth())
                + " viewport=" + fmt(scroller.getViewportBounds().getWidth())
                + " scrollerWidth=" + fmt(scroller.getWidth())
                + " line0 chars=" + viewer.lineText(0).length());
    }

    private void checkR28NoSoftWrap() {
        double viewport = scroller.getViewportBounds().getWidth();
        double content = viewer.contentPixelWidth();
        check("R2.8 the content column is wider than the viewport (content=" + fmt(content)
                        + " viewport=" + fmt(viewport) + ")",
                viewport > 0 && content > viewport + 10);
        check("R2.8 the ListView's own pref width follows the longest line (pref="
                        + fmt(listView.getPrefWidth()) + " > viewport=" + fmt(viewport) + ")",
                listView.getPrefWidth() > viewport);
        check("R2.8 the long line is one single row model (no soft wrap -> lineCount stays 4): "
                        + viewer.lineCount(), viewer.lineCount() == 4);
        // R2.8 行号对齐的机械判据：行号列宽 == 位数 × 单字宽 + 固定间距，且位数取自总行数
        double expectedGutter = viewer.gutterDigits() * viewer.charWidth() + viewer.gutterGap();
        System.out.println("R2.8 gutter=" + fmt(viewer.gutterWidth()) + " expected=" + fmt(expectedGutter)
                + " digits=" + viewer.gutterDigits() + " charWidth=" + fmt(viewer.charWidth())
                + " totalLines=" + viewer.lineCount());
        check("R2.8 the gutter width is exactly digits × charWidth + gap (" + fmt(viewer.gutterWidth())
                        + " vs " + fmt(expectedGutter) + ") ⇒ 行号列固定、滚动时与内容不错位",
                Math.abs(viewer.gutterWidth() - expectedGutter) < 0.001 && viewer.gutterDigits() >= 1);
        check("R2.8/R2.8 the gutter digit count comes from the total line count (digits="
                        + viewer.gutterDigits() + ", lines=" + viewer.lineCount() + ")",
                viewer.gutterDigits() == String.valueOf(viewer.lineCount()).length());
    }

    private void checkR28ScrollToTail() {
        Bounds before = scroller.getViewportBounds();
        double h0 = scroller.getHvalue();
        scroller.setHvalue(scroller.getHmax());
        viewer.node().applyCss();
        viewer.node().layout();
        double h1 = scroller.getHvalue();
        System.out.println("R2.8 hvalue " + fmt(h0) + " -> " + fmt(h1)
                + " (hmax=" + fmt(scroller.getHmax()) + ")");
        check("R2.8 the outer ScrollPane really scrolls horizontally (hvalue reached the max)",
                h1 > h0 && h1 >= scroller.getHmax() - 0.001);
        scroller.setHvalue(0);
        viewer.node().applyCss();
        viewer.node().layout();
        check("R2.8 scrolling back to the left restores hvalue 0", scroller.getHvalue() <= 0.001);
        check("R2.8 the viewport width is a real positive number (" + fmt(before.getWidth()) + ")",
                before.getWidth() > 0);
    }

    // ---------- R5 查找 ----------

    private void checkFindBarShape() {
        check("R5.1 the find bar is at the BOTTOM of the viewer (BorderPane bottom == findBar)",
                viewer.node() instanceof BorderPane bp && bp.getBottom() == viewer.findBar());
        List<Button> buttons = viewer.findButtons();
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
        check("R5.1 the find bar is visible", viewer.findBar().isVisible());
        check("R5.2 the query field is reachable and empty at start",
                viewer.queryField() != null && viewer.queryField().getText().isEmpty());
    }

    /** 输入一个必然命中的词 → 计数 1 / m。 */
    private void checkFindCount() {
        viewer.queryField().setText(SEARCH_TOKEN);
        viewer.findButtons().get(0).fire();
        int total = viewer.hitCount();
        countAtFirstHit = viewer.countLabel().getText();
        firstHitLine = viewer.currentHitLine();
        System.out.println("R5.3 query=\"" + SEARCH_TOKEN + "\" -> count=\"" + countAtFirstHit
                + "\" hits=" + total + " currentLine=" + firstHitLine);
        check("R5.3 a guaranteed-hit query produces hits (m=" + total + " > 0)", total > 0);
        check("R5.3 the counter shows \"1 / m\": " + countAtFirstHit,
                countAtFirstHit.contains("1") && countAtFirstHit.contains(String.valueOf(total)));
        check("R5.4 the first hit is on a real line (line " + firstHitLine + " of " + viewer.lineCount() + ")",
                firstHitLine > 0 && firstHitLine < viewer.lineCount());
    }

    private void checkFindNavigate() {
        viewer.findButtons().get(2).fire();   // next (ANGLE_DOWN)
        secondHitLine = viewer.currentHitLine();
        int second = viewer.currentHitNumber();
        System.out.println("R5.4 next -> " + viewer.countLabel().getText()
                + " currentLine=" + secondHitLine);
        check("R5.4 next advances the counter to 2 / m (was " + countAtFirstHit + ", now "
                        + viewer.countLabel().getText() + ")", second == 2);
        check("R5.4 next moved to a different line (" + firstHitLine + " -> " + secondHitLine + ")",
                secondHitLine != firstHitLine);
        viewer.findButtons().get(1).fire();   // prev (ANGLE_UP)
        check("R5.4 prev goes back to 1 / m (now " + viewer.countLabel().getText() + ")",
                viewer.currentHitNumber() == 1);
        check("R5.4 prev restored the first hit line (" + viewer.currentHitLine() + ")",
                viewer.currentHitLine() == firstHitLine);
    }

    /** 到头再下一个 → 回到 1（循环）。 */
    private void checkFindWrapAround() {
        int total = viewer.hitCount();
        for (int i = 0; i < total; i++) {
            viewer.findButtons().get(2).fire();
        }
        System.out.println("R5.4 after " + total + " \"next\" clicks -> "
                + viewer.countLabel().getText());
        check("R5.4 next WRAPS AROUND: after m clicks the counter is back to 1 / m ("
                        + viewer.countLabel().getText() + ")",
                viewer.currentHitNumber() == 1);
        viewer.findButtons().get(1).fire();
        check("R5.4 prev also wraps (1 -> m): " + viewer.countLabel().getText(),
                viewer.currentHitNumber() == total);
        viewer.findButtons().get(2).fire();
        check("R5.4 next from m wraps back to 1: " + viewer.countLabel().getText(),
                viewer.currentHitNumber() == 1);
    }

    /** 必然不命中的词 → 计数 0、不报错（无异常、无错误卡）。 */
    private void checkFindNoHits() {
        modalPane.hide();
        viewer.queryField().setText("zzz-this-token-cannot-exist-zzz");
        String verdict;
        try {
            viewer.findButtons().get(0).fire();
            viewer.findButtons().get(2).fire();
            viewer.findButtons().get(1).fire();
            verdict = "no exception";
        } catch (Throwable ex) {
            verdict = "threw " + ex;
        }
        System.out.println("R5.4 no-hit query -> count=\"" + viewer.countLabel().getText()
                + "\" hits=" + viewer.hitCount() + " verdict=" + verdict
                + " modalDisplay=" + modalPane.isDisplay());
        check("R5.4 a query with no hits raises nothing (" + verdict + ")", "no exception".equals(verdict));
        check("R5.4 the counter shows 0 for no hits: " + viewer.countLabel().getText(),
                viewer.hitCount() == 0 && viewer.countLabel().getText().contains("0"));
        check("R5.4 no error card was raised by the empty result (modal=" + modalPane.isDisplay() + ")",
                !modalPane.isDisplay());
        check("R5.4 navigation with no hits is a no-op, not a crash (current="
                + viewer.currentHitNumber() + ")", viewer.currentHitNumber() == 0);
    }

    /** R5.4：命中高亮（节点级）+ 滚动到可见。 */
    private void checkHighlightAndScroll() {
        viewer.queryField().setText(SEARCH_TOKEN);
        viewer.findButtons().get(0).fire();
        int line = viewer.currentHitLine();
        Node cell = revealCellForLine(line);
        List<String> highlighted = hitTextsWithin(cell);
        String hitLineText = viewer.lineText(line);
        System.out.println("R5.4 highlight: currentLine=" + line
                + " highlighted=\"" + String.join("\",\"", highlighted) + "\""
                + " cell=" + describe(cell) + " visible=" + isEffectivelyVisible(cell));
        check("R5.4 the current hit is highlighted at the NODE level (found " + highlighted.size()
                        + " .html-source-hit inside the hit line's cell: " + highlighted + ")",
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
        viewer.queryField().setText(SEARCH_TOKEN);
        viewer.findButtons().get(0).fire();
        viewer.node().applyCss();
        viewer.node().layout();
        int cellsBefore = viewer.cellsBuiltInLastRefresh();
        viewer.findButtons().get(1).fire();       // prev -> 循环到最后一处（最远的一行）
        viewer.node().applyCss();
        viewer.node().layout();
        int cellsBuilt = viewer.cellsBuiltInLastRefresh();
        int rows = viewer.lineCount();
        System.out.println("R5 focused repaint: cellsBuiltInLastRefresh=" + cellsBuilt
                + " (search itself: " + cellsBefore + ")"
                + " repaints=" + viewer.repaintCount()
                + " namedLines=" + viewer.repaintedLineCount() + " totalLines=" + rows);
        check("R5 moving the hit rebuilds only a handful of cells (" + cellsBuilt
                        + ") instead of all " + rows + " rows", cellsBuilt > 0 && cellsBuilt < 200);
        check("R5 that is far below the line count (" + cellsBuilt + " << " + rows + ")",
                cellsBuilt * 10 < rows);
        check("R5 the repaint went through the find path, not a table-wide refresh (repaints="
                + viewer.repaintCount() + ")", viewer.repaintCount() > 0);
    }

    /** R5.1：查找栏在查看器底部（几何上就在内层视口下方）。 */
    private void checkFindBarBottom() {
        Bounds bar = viewer.findBar().localToScene(viewer.findBar().getBoundsInLocal());
        Bounds viewport = scroller.localToScene(scroller.getBoundsInLocal());
        Bounds viewerBounds = viewer.node().localToScene(viewer.node().getBoundsInLocal());
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
        viewer.node().applyCss();
        viewer.node().layout();
        System.out.println("R2.9 after collapse: leftVisible=" + leftPanel.isVisible()
                + " leftShowing=" + host.isLeftShowing()
                + " rightVisible=" + rightPanel.isVisible() + " rightShowing=" + host.isRightShowing());
        check("R2.9 the collapse button hides the left panel (logical state)",
                !host.isLeftShowing());
        check("R2.9 with slideMillis=0 the panel is also physically hidden", !leftPanel.isVisible());
        check("R2.9 collapsing the viewer does not touch the right panel",
                !host.isRightShowing() && !rightPanel.isVisible());
        check("R2.9 there is no draft/commit semantics: reopening just shows the same node",
                viewer.node() == ((BorderPane) leftPanel).getCenter());
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
        for (Node n : viewer.node().lookupAll(".list-cell")) {
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
        viewer.node().applyCss();
        viewer.node().layout();
        Node cell = cellForLine(row);
        if (cell == null) {
            viewer.listView().scrollTo(row);
            viewer.node().applyCss();
            viewer.node().layout();
            cell = cellForLine(row);
        }
        return cell;
    }

    /** 某 cell 内部的命中高亮文本（命中样式的节点级证据）。 */
    private static List<String> hitTextsWithin(Node cell) {
        List<String> out = new ArrayList<>();
        if (cell == null) {
            return out;
        }
        for (Node n : cell.lookupAll(".html-source-hit")) {
            if (n instanceof javafx.scene.layout.StackPane sp && sp.getChildren().size() == 1
                    && sp.getChildren().get(0) instanceof javafx.scene.text.Text t) {
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
     * <p>🚩 <b>必须判定祖先裁剪</b>：查看器的内层 {@code ListView} 会被撑到"最长行"那么宽
     * （横向滚动的实现方式），于是每个 cell 的 {@code layoutBounds} 有 15000+ px 宽，
     * 而 {@code ScrollPane} 的视口只有 631px。不做裁剪判定就会"命中"到远处那些
     * <b>屏幕上看不见、而且被视口裁掉</b>的 cell —— 实测正是它把"右半区仍可点主界面"
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
            ImageIO.write(bi, "png", new File(outDir, name + "-"
                    + String.valueOf(I18n.currentLocale()).replaceAll("[^A-Za-z0-9_]", "") + ".png"));
        } catch (Exception e) {
            System.out.println("snapshot(" + name + ") failed: " + e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
