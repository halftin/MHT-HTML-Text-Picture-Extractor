import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.NordLight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.App;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.gui.controller.ParamFormPane;
import com.mhtextractor.gui.controller.ParamSidePanel;
import com.mhtextractor.gui.controller.PreviewRenderer;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.util.ParamScope;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Q6a side-panel probe: measures the overlay side panel that now hosts the extraction parameters.
 *
 * <p>It reproduces App.start()'s wiring exactly (SidePanelHost.install(root) -&gt; StackPane shell -&gt;
 * NotificationHost.install(scene, shell)) so the layer order it measures is the production one, and
 * then drives the real user entry point ({@code #paramSettingsButton}) instead of poking internals.</p>
 *
 * <p>Asserted (see the q6a prompt, section 5.3):</p>
 * <ol>
 *   <li>R1.1 the panel width is 50% of the main area, and it truly <i>overlays</i>: the pick at the
 *       same point in the left half resolves to the SAME node before and after opening;</li>
 *   <li>R3.4 the overlay clip exists and is bound to the main area;</li>
 *   <li>R3.3 the left half still picks into the main area, the right half picks into the panel;</li>
 *   <li>R3.2 layer order: overlay(main area + panel) &lt; toast layer &lt; modal layer, and the toast /
 *       error card stay visible, laid out, pickable and clickable while the panel is open;</li>
 *   <li>R1.3 a click elsewhere in the main area does NOT collapse (control group: the collapse
 *       button DOES collapse);</li>
 *   <li>R1.5 folding keeps the draft (edit a field -&gt; collapse -&gt; reopen the same target -&gt; still
 *       dirty, value kept, same ParamFormPane instance);</li>
 *   <li>R3.5 nothing new is persisted (GuiState has no side-panel field; logs/gui-state.json key
 *       set unchanged across open/collapse).</li>
 * </ol>
 *
 * <p>Exit code 0 = all assertions passed, 1 = at least one failed.</p>
 */
public class SidePanelProbe extends Application {

    private static final double TOL = 2.0;
    private static final String TOAST_MSG = "SIDE-PANEL-PROBE-TOAST";
    private static final String CARD_MSG = "SIDE-PANEL-PROBE-CARD";

    private Stage stage;
    private Scene scene;
    private StackPane shell;
    private StackPane overlay;
    private Parent mainRoot;
    private Button paramSettingsButton;
    private Node anchor;
    private Object controller;

    private SidePanelHost host;
    private BorderPane panel;
    private ParamFormPane pane;
    private Button collapseButton;
    private Button startButton;
    private javafx.scene.control.ListView<?> fileListView;
    private ModalPane modalPane;

    /** Q6b：最近一次 markDirty() 之后「分离文件」开关应有的值（断言"提交真的应用了参数"时用）。 */
    private boolean lastToggleValue;
    /** 步骤 4：折叠前的草稿值（圆钮再展开/取消丢弃后必须按此对账）。 */
    private boolean draftToggleWas;
    /** 步骤 4：折叠前记下的 pane 实例 —— 断言圆钮重新展开的是同一个实例（草稿状态机保留）。 */
    private ParamFormPane draftPane;
    /** 步骤 4：右侧悬浮圆钮节点（#sidePanelReopen；收起后显示、点击再展开）。 */
    private Button reopenButton;

    private Point2D leftPoint;
    private Point2D rightPoint;
    private Point2D controlPoint;
    private Node leftPickClosed;
    private Node leftPickOpen;
    private Node rightPickOpen;
    private Node controlPickClosed;

    private String guiStateKeysBefore;
    private String outDir;

    private int passed;
    private int failed;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Platform.setImplicitExit(false);
        seedCleanInputFolder();

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
        startButton = (Button) scene.lookup("#startButton");
        fileListView = (javafx.scene.control.ListView<?>) scene.lookup("#fileListView");
        modalPane = findModalPane();

        guiStateKeysBefore = guiStateKeySet();
        System.out.println("locale=" + I18n.currentLocale()
                + "  window=" + stage.getWidth() + "x" + stage.getHeight()
                + "  gui-state keys before = " + guiStateKeysBefore);
        if (paramSettingsButton == null || anchor == null || modalPane == null) {
            check("probe can resolve #paramSettingsButton / #statusLabel / ModalPane", false);
            finish();
            return;
        }

        Timer timer = new Timer(true);
        timer.schedule(at(300, this::selectTextMode), 300);
        timer.schedule(at(900, this::openPanel), 900);
        timer.schedule(at(1600, this::checkOpenPanel), 1600);
        timer.schedule(at(2300, this::seedToast), 2300);
        timer.schedule(at(3000, this::checkToastOverPanel), 3000);
        timer.schedule(at(3500, this::seedCard), 3500);
        timer.schedule(at(4100, this::checkCardOverPanel), 4100);
        timer.schedule(at(4500, this::editDraftThenCollapse), 4500);
        timer.schedule(at(5200, this::checkCollapsed), 5200);
        timer.schedule(at(5700, this::reopenPanel), 5700);
        timer.schedule(at(6400, this::checkReopened), 6400);
        timer.schedule(at(6900, this::confirmPanel), 6900);
        timer.schedule(at(7500, this::checkCommitted), 7500);
        timer.schedule(at(8000, this::reopenPanel), 8000);
        timer.schedule(at(8600, this::checkPanelTruncation), 8600);
        timer.schedule(at(9200, this::checkEnterHijack), 9200);
        timer.schedule(at(9700, this::reopenPanel), 9700);
        timer.schedule(at(10200, this::shrinkToMinWidth), 10200);
        // Q6b：R1.7 / R1.6 / R1.8 / R3.6 六组断言（含两个控制组）。
        // 用"步骤链"而不是一串裸 schedule 时间戳：步与步之间恒有 STEP_MS 间隔，
        // 加/删一步不会打乱后面所有步骤的时序（Q6a 的裸 schedule 清单已到维护极限）。
        timer.schedule(at(10900, this::checkMinWidthTruncation), 10900);
    }

    /** 步骤链间隔：够 200ms 滑入动画播完，又不至于让探针跑太久。 */
    private static final long STEP_MS = 600;

    /**
     * Q6b/步骤 4 步骤链（R1.6/R1.7/R1.8/R3.6 + 功能二会话状态机 + 决策 C 门顺序 + 左侧回归）；
     * 最后一步自己收尾。状态机 8 行逐行落成断言：
     * 首次打开（checkCleanStartState）→ 改动（makeDirty/checkStartEnabledWhileDirty）→
     * 收起（collapseWithDirty/checkToastAndReopenShown）→ 点圆钮（clickReopenButton/checkReopenKeptDraft）→
     * 取消（cancelDiscardsDraft/checkCancelEndedSession）→ 确定（commitViaConfirm/checkUnnamedGateAfterCommit 的前置）→
     * 切换+确认丢弃（confirmSwitch/checkTargetSwitchedAndClean）→ 切换+取消（cancelSwitch/checkTargetUnchanged）。
     */
    private java.util.List<Runnable> q6bSteps() {
        return new ArrayList<>(List.of(
                this::importFixtures, this::waitForImport, this::checkImportDone,
                this::openParamOnNothing, this::checkCleanStartState,
                this::makeDirty, this::checkStartEnabledWhileDirty, this::clickStartWithDraft,
                this::checkDraftBlocksCard, this::dismissDraftCard, this::checkCardDismissedOnly,
                this::collapseWithDirty, this::checkToastAndReopenShown, this::clickReopenButton,
                this::checkReopenKeptDraft, this::cancelDiscardsDraft, this::checkCancelEndedSession,
                this::reopenAfterCancel, this::checkReopenAfterCancel,
                this::makeGroupsAndDirtyForGateOrder, this::clickStartWithDraft,
                this::checkDraftGatePrecedesUnnamedGate, this::dismissDraftCard,
                this::commitViaConfirm, this::checkUnnamedGateAfterCommit, this::openGroupA,
                this::checkTargetA, this::makeDirty, this::switchToGroupB, this::checkSwitchCard,
                this::cancelSwitch, this::checkTargetUnchanged, this::makeDirty,
                this::switchToGroupB, this::checkSwitchCard, this::confirmSwitch,
                this::checkTargetSwitchedAndClean, this::switchToGlobalClean,
                this::leftViewerRegression, this::openGroupA, this::makeDirty,
                this::switchToGroupB, this::checkCardBlocksClose, this::clickCardCancel,
                this::collapsePanelForCloseTests, this::checkDirtyAllowsClose,
                this::resetDraftForCloseControl, this::checkCleanAllowsClose,
                this::e12PreviewStateCheck, this::closeWindowForReal));
    }

    /** 关窗控制组的前置：先把草稿提交干净（走唯一提交路径 #confirmButton），再让窗口重新显示。 */
    private void resetDraftForCloseControl() {
        Button confirm = (Button) livePane().lookup("#confirmButton");
        if (confirm != null) {
            confirm.fire();
        }
        prepareCloseGate();
    }

    /** 关窗分支 1/控制组的前置：卡片先按「取消」收掉，再让清缓存分支走完、窗口显示出来。 */
    private void collapsePanelForCloseTests() {
        clickCardCancel();
        collapseButton.fire();          // 面板还是 dirty（草稿保留），收起不影响草稿
        prepareCloseGate();
    }

    private void runQ6bChain() {
        java.util.List<Runnable> steps = q6bSteps();
        chainNext(steps, 0);
    }

    private void chainNext(java.util.List<Runnable> steps, int index) {
        if (index >= steps.size()) {
            return;
        }
        Timer t = new Timer(true);
        t.schedule(at(STEP_MS, () -> {
            try {
                steps.get(index).run();
            } catch (Throwable ex) {
                check("Q6b step " + index + " (" + steps.get(index) + ") threw: " + ex, false);
                ex.printStackTrace();
            } finally {
                chainNext(steps, index + 1);
            }
        }), STEP_MS);
    }

    // ==================== steps ====================

    /**
     * 启动前播种：清空 gui-state.json 的 inputFolder。
     *
     * <p>🚩 为什么必须：{@code MainController.initialize → restoreGuiState} 会把上次的输入目录
     * 写进目录框 → textProperty 监听器 → <b>异步自动导入</b>该目录。探针跑过一次之后
     * （{@code clickStartWithDraft} 会持久化目录框内容），gui-state 里就带着 fixtures 目录，
     * 下次启动 Q6a 中途列表被填充并自动选中条目 —— 探针"无选中 = GLOBAL 目标"的前提被环境污染
     * （实测：reopen 时弹出 R1.6 切换卡、checkReopened 假失败）。空目录走
     * {@code onInputFolderChanged} 的空值分支（只存状态、不导入），正好中性化。</p>
     */
    private void seedCleanInputFolder() {
        try {
            Path p = Path.of("logs", "gui-state.json");
            if (!Files.exists(p)) {
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode n = mapper.readTree(Files.readString(p));
            if (n instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                obj.put("inputFolder", "");
                Files.writeString(p, mapper.writeValueAsString(obj));
                System.out.println("seeded gui-state: inputFolder cleared (auto-import neutralized)");
            }
        } catch (Exception e) {
            System.out.println("seedCleanInputFolder failed: " + e);
        }
    }

    private void selectTextMode() {
        ToggleButton textMode = (ToggleButton) scene.lookup("#textModeToggle");
        ToggleButton imageMode = (ToggleButton) scene.lookup("#imageModeToggle");
        if (textMode != null) {
            textMode.setSelected(true);
        }
        System.out.println("mode: text=" + (textMode != null && textMode.isSelected())
                + "  image=" + (imageMode != null && imageMode.isSelected()));
    }

    /** 开面板之前先记下"同一个点上的命中结果"（控制组：覆盖式面板不该改变它）。 */
    private void openPanel() {
        double w = overlay.getWidth();
        double h = overlay.getHeight();
        leftPoint = overlay.localToScene(w * 0.25, h * 0.5);
        rightPoint = overlay.localToScene(w * 0.75, h * 0.5);
        controlPoint = leftPartOf(scene.lookup("#inputFolderField"));
        leftPickClosed = pick(scene.getRoot(), leftPoint.getX(), leftPoint.getY());
        controlPickClosed = controlPoint == null ? null
                : pick(scene.getRoot(), controlPoint.getX(), controlPoint.getY());
        System.out.println("points:  left(empty)=" + leftPoint + "  left(control)=" + controlPoint
                + "  right(empty)=" + rightPoint + "  overlayW=" + overlay.getWidth());
        System.out.println("pick before open:  left(empty)  = " + describe(leftPickClosed));
        System.out.println("pick before open:  left(control)= " + describe(controlPickClosed));
        System.out.println("pick before open:  right(empty) = "
                + describe(pick(scene.getRoot(), rightPoint.getX(), rightPoint.getY())));
        paramSettingsButton.fire();
        System.out.println("fired #paramSettingsButton (the real user entry point)");
    }

    private void checkOpenPanel() {
        Node panelNode = scene.lookup("#sidePanel");
        check("panel node exists in the scene (#sidePanel)", panelNode instanceof BorderPane);
        if (!(panelNode instanceof BorderPane bp)) {
            return;
        }
        panel = bp;
        pane = panel.getCenter() instanceof ParamFormPane p ? p : null;
        collapseButton = (Button) scene.lookup("#sidePanelCollapse");
        reopenButton = (Button) scene.lookup("#sidePanelReopen");
        host = SidePanelHost.of(anchor);
        check("step 4 the round button node exists with the fixed id #sidePanelReopen",
                reopenButton != null);
        check("step 4 the round button is HIDDEN while the panel is open (state machine row 1)",
                reopenButton != null && !reopenButton.isVisible());
        check("panel content is a ParamFormPane (R1.10: same pane as the modal shell)", pane != null);
        check("collapse button exists with an icon graphic + tooltip (R1.2)",
                collapseButton != null && collapseButton.getGraphic() != null
                        && collapseButton.getTooltip() != null);
        check("R1.2 SidePanelHost.collapseButton() IS the #sidePanelCollapse node",
                host != null && host.collapseButton() == collapseButton);
        check("panel is visible right after opening", panel.isVisible());
        check("R1.1 host reports the panel as open (logical state)", host != null && host.isRightShowing());

        double overlayW = overlay.getWidth();
        double overlayH = overlay.getHeight();
        double panelW = panel.getWidth();
        check(String.format("R1.1 panel width = 50%% of main area (panel=%.1f overlay=%.1f tol=%.0f)",
                        panelW, overlayW, TOL),
                Math.abs(panelW - overlayW * 0.5) <= TOL);
        check(String.format("R3.3 panel occupies only the right half (layoutX=%.1f expected=%.1f)",
                        panel.getLayoutX(), overlayW - panelW),
                Math.abs(panel.getLayoutX() - (overlayW - panelW)) <= TOL);
        check("R3.4 overlay clip is set and tracks the main area size",
                overlay.getClip() instanceof Rectangle r
                        && Math.abs(r.getWidth() - overlayW) <= TOL
                        && Math.abs(r.getHeight() - overlayH) <= TOL);
        check(String.format("R3.4 slide animation finished at translateX=0 (translateX=%.2f)",
                        panel.getTranslateX()),
                Math.abs(panel.getTranslateX()) <= 0.01);

        // ---- R1.1 / R3.3：覆盖式面板不得改动也不得吞掉左半区 ----
        leftPickOpen = pick(scene.getRoot(), leftPoint.getX(), leftPoint.getY());
        rightPickOpen = pick(scene.getRoot(), rightPoint.getX(), rightPoint.getY());
        System.out.println("pick after open:   left  = " + describe(leftPickOpen));
        System.out.println("pick after open:   right = " + describe(rightPickOpen));
        if (Boolean.getBoolean("probe.trace")) {
            System.out.println("--- trace right point (" + rightPoint + ") ---");
            trace(scene.getRoot(), rightPoint.getX(), rightPoint.getY(), 0);
            System.out.println("--- trace left point (" + leftPoint + ") ---");
            trace(scene.getRoot(), leftPoint.getX(), leftPoint.getY(), 0);
            System.out.println("--- trace control point (" + controlPoint + ") ---");
            if (controlPoint != null) {
                trace(scene.getRoot(), controlPoint.getX(), controlPoint.getY(), 0);
                System.out.println("panel scene bounds=" + panel.localToScene(panel.getBoundsInLocal())
                        + " layoutX=" + panel.getLayoutX() + " w=" + panel.getWidth()
                        + " local(control)=" + panel.sceneToLocal(controlPoint)
                        + " contains=" + panel.contains(panel.sceneToLocal(controlPoint)));
            }
            System.out.println("pickOnBounds: shell=" + shell.isPickOnBounds() + " overlay=" + overlay.isPickOnBounds()
                    + " mainRoot=" + mainRoot.isPickOnBounds() + " panel=" + panel.isPickOnBounds());
        }
        check("R1.1 left-half pick is the SAME node as before opening (overlay changed no layout): "
                        + "closed=" + describe(leftPickClosed) + " open=" + describe(leftPickOpen),
                leftPickOpen != null && leftPickOpen == leftPickClosed);
        check("R3.3 left-half pick is inside the main area, not the panel",
                leftPickOpen != null && isDescendantOf(leftPickOpen, mainRoot)
                        && !isDescendantOf(leftPickOpen, panel));
        check("R3.3 right-half pick is inside the panel", rightPickOpen != null
                && isDescendantOf(rightPickOpen, panel));
        check("R3.3 the overlay itself is not a full-size click catcher (pickOnBounds=false)",
                !overlay.isPickOnBounds());

        // 控制组：左半区真实控件（输入目录框）在开面板前后必须命中同一个节点
        Node controlPickOpen = controlPoint == null ? null
                : pick(scene.getRoot(), controlPoint.getX(), controlPoint.getY());
        check("R1.1/R3.3 left-half real control still picks the SAME node: "
                        + describe(controlPickClosed) + " -> " + describe(controlPickOpen),
                controlPickOpen != null && controlPickOpen == controlPickClosed
                        && isDescendantOf(controlPickOpen, mainRoot)
                        && !isDescendantOf(controlPickOpen, panel));
        // 控制组：右半区、面板内部的真实控件必须命中面板内部的节点
        Point2D rightControl = centerOf(pane.lookup("#configComboBox"));
        Node rightControlPick = rightControl == null ? null
                : pick(scene.getRoot(), rightControl.getX(), rightControl.getY());
        check("R3.3 right-half control inside the panel (#configComboBox) picks into the panel: "
                        + describe(rightControlPick),
                rightControlPick != null && isDescendantOf(rightControlPick, panel));

        // ---- R1.3：主区里的点击不得收起面板 ----
        click(leftPickOpen, leftPoint.getX(), leftPoint.getY());
        check("R1.3 a click in the main area does NOT collapse the panel",
                panel.isVisible() && host.isRightShowing());
    }

    /** 先只发 toast：模态错误卡一旦显示，它的整幅遮挡层会盖住 toast（这是设计如此），不能混在一起验。 */
    private void seedToast() {
        NotificationHost.toastInfo(anchor, TOAST_MSG, FontAwesomeSolid.INFO_CIRCLE);
        System.out.println("seeded: 1 toast (top-right) with the panel open");
    }

    private void checkToastOverPanel() {
        Node toast = findNotification(TOAST_MSG);
        check("R3.2 the toast node exists and is effectively visible",
                toast != null && isEffectivelyVisible(toast));

        // ---- R3.7 几何：toast 与 50% 侧边栏重叠 ----
        Bounds pb = panel.localToScene(panel.getBoundsInLocal());
        Bounds tb = toast == null ? null : toast.localToScene(toast.getBoundsInLocal());
        double[] toastOverlap = tb == null ? null : overlap(pb, tb);
        System.out.println(String.format("panel scene x=[%.0f..%.0f]  toast scene x=[%.0f..%.0f] y=[%.0f..%.0f]",
                pb.getMinX(), pb.getMaxX(),
                tb == null ? 0 : tb.getMinX(), tb == null ? 0 : tb.getMaxX(),
                tb == null ? 0 : tb.getMinY(), tb == null ? 0 : tb.getMaxY()));
        check("R3.7 the toast overlaps the side panel (overlap " + fmt(toastOverlap) + ")",
                toastOverlap != null && toastOverlap[2] > 1 && toastOverlap[3] > 1);

        // ---- shell 层序：覆盖层（主界面 + 侧边栏）< toast 层 < 模态层 ----
        int iOverlay = shell.getChildren().indexOf(overlay);
        int iToast = shellIndexOf(toast);
        int iModal = shell.getChildren().indexOf(modalPane);
        System.out.println("shell children=" + shell.getChildren().size()
                + "  index(overlay)=" + iOverlay + "  index(toast branch)=" + iToast
                + "  index(modalPane)=" + iModal + "  index(panel branch)=" + shellIndexOf(panel));
        check("R3.2 overlay (main area + panel) is shell's first child", iOverlay == 0);
        check("R3.2 layer order main area < toast layer < modal (" + iOverlay + " < " + iToast + " < " + iModal + ")",
                iOverlay < iToast && iToast < iModal);
        check("R3.2 the panel is a descendant of that first child, so no reminder layer can be under it",
                isDescendantOf(panel, overlay) && shellIndexOf(panel) == 0);

        // ---- 可点到：toast 上的最上层节点属于 toast（提醒层在侧边栏之上）----
        Node pickedToast = tb == null ? null : pick(scene.getRoot(), tb.getCenterX(), tb.getCenterY());
        check("R3.2 the toast is pickable while the panel is open (picked " + describe(pickedToast) + ")",
                pickedToast != null && isDescendantOf(pickedToast, toast));

        snapshot("sidepanel-toast");
        System.out.println("screenshot (toast overlapping the panel, R3.7) -> "
                + new File(outDir, "sidepanel-toast-" + localeTag() + ".png").getAbsolutePath());

        // ---- 可交互：toast 的 ✕ 在面板展开时仍然响应 ----
        if (toast instanceof Notification toastNtf && toastNtf.getOnClose() != null) {
            toastNtf.getOnClose().handle(new ActionEvent());
        }
        check("R3.2 the toast close (X) still works while the panel is open",
                findNotification(TOAST_MSG) == null);
    }

    private void seedCard() {
        NotificationHost.error(anchor, CARD_MSG, FontAwesomeSolid.EXCLAMATION_CIRCLE);
        System.out.println("seeded: 1 centered error card with the panel open");
    }

    private void checkCardOverPanel() {
        Node card = modalPane.getContent();
        check("R3.2 the error card is displayed (ModalPane.display)", modalPane.isDisplay());
        check("R3.2 the error card content is mounted and laid out (h>0): "
                        + (card == null ? "null" : String.format("%.1fx%.1f",
                        card.getBoundsInLocal().getWidth(), card.getBoundsInLocal().getHeight())),
                card != null && card.getBoundsInLocal().getHeight() > 0);

        // ---- shell 层序：覆盖层（主界面 + 侧边栏）< toast 层 < 模态层 ----
        int iPanelBranch = shellIndexOf(panel);
        int iModal = shell.getChildren().indexOf(modalPane);
        System.out.println("shell children=" + shell.getChildren().size()
                + "  index(panel branch)=" + iPanelBranch + "  index(modalPane)=" + iModal);
        check("R3.2 the modal layer is the LAST shell child and above the panel branch ("
                + iPanelBranch + " < " + iModal + ")", iPanelBranch >= 0 && iPanelBranch < iModal);
        check("R3.2 the panel is a descendant of the overlay (shell's first child), "
                        + "so no reminder layer can be under it",
                isDescendantOf(panel, overlay) && shell.getChildren().indexOf(overlay) == 0);

        // ---- 几何：错误卡确实与 50% 侧边栏重叠 ----
        Bounds pb = panel.localToScene(panel.getBoundsInLocal());
        Bounds cb = card.localToScene(card.getBoundsInLocal());
        double[] cardOverlap = overlap(pb, cb);
        System.out.println(String.format("panel scene x=[%.0f..%.0f]  card x=[%.0f..%.0f]  card y=[%.0f..%.0f]",
                pb.getMinX(), pb.getMaxX(), cb.getMinX(), cb.getMaxX(), cb.getMinY(), cb.getMaxY()));
        check("the centered error card overlaps the side panel (overlap " + fmt(cardOverlap) + ")",
                cardOverlap != null && cardOverlap[2] > 1 && cardOverlap[3] > 1);

        // ---- 可点到：重叠处的最上层节点必须属于模态层（提醒卡永远最上层）----
        Node pickedCard = cardOverlap == null ? null
                : pick(scene.getRoot(), cardOverlap[0] + cardOverlap[2] / 2, cardOverlap[1] + cardOverlap[3] / 2);
        check("R3.2 the error card WINS over the panel at their overlap (picked " + describe(pickedCard) + ")",
                pickedCard != null && isDescendantOf(pickedCard, modalPane));

        // ---- 可交互：确定按钮在面板展开时仍然响应 ----
        if (card instanceof Notification cardNtf && !cardNtf.getPrimaryActions().isEmpty()) {
            cardNtf.getPrimaryActions().get(0).fire();
        }
        check("R3.2 the error card OK button still works while the panel is open", !modalPane.isDisplay());
    }

    private void editDraftThenCollapse() {
        ToggleSwitch draftToggle = (ToggleSwitch) pane.lookup("#separateFilesCheck");
        check("R1.5 the draft field is reachable inside the panel", draftToggle != null);
        if (draftToggle == null) {
            return;
        }
        draftToggleWas = !draftToggle.isSelected();
        draftToggle.setSelected(draftToggleWas);
        check("R1.5 editing a field makes the panel dirty (pane.isDirty=true)", pane.isDirty());
        check("R1.5 the draft is queryable from outside via the R1.7 seam ParamSidePanel.isDirty(anchor)",
                ParamSidePanel.isDirty(anchor));
        // Button.fire() 就是用户点这个按钮时触发的那个动作（FeatureExercise 也这么驱控件）
        collapseButton.fire();
        // 🚩 步骤 4（决策 2.6）：dirty 时点收起**不再弹居中卡** —— toast 提示 + 直接收起 + 圆钮出现。
        check("R1.8 (step 4) a DIRTY collapse no longer shows a centered card (pendingDecision="
                        + NotificationHost.hasPendingDecision(anchor) + ")",
                !cardShown() && !NotificationHost.hasPendingDecision(anchor));
        check("R1.8 (step 4) the hint toast appears (exact collapseDraftHint message)",
                findNotification(I18n.get("sidepanel.collapseDraftHint")) != null);
        System.out.println("fired #sidePanelCollapse (dirty -> toast + collapse, no card)");
    }

    private void checkCollapsed() {
        check("R1.3 the collapse button collapses the panel (visible=false once the slide finished)",
                !panel.isVisible());
        check("R1.3 the host reports collapsed", host != null && !host.isRightShowing());
        check("R1.5 the draft survives the collapse (isDirty still true)", ParamSidePanel.isDirty(anchor));
        check("R1.5 the edited value survives the collapse",
                ((ToggleSwitch) pane.lookup("#separateFilesCheck")).isSelected() == draftToggleWas);
        check("step 4 the round button is VISIBLE after collapse (way back into the session)",
                reopenVisible());
    }

    private void reopenPanel() {
        paramSettingsButton.fire();
        System.out.println("fired #paramSettingsButton again (same target: nothing selected -> GLOBAL)"
                + "  -> showing=" + host.isRightShowing() + " translateX=" + panel.getTranslateX()
                + " visible=" + panel.isVisible() + " reopenVisible=" + reopenVisible()
                + " buttonDisabled=" + paramSettingsButton.isDisabled()
                + " cardShown=" + cardShown() + " pending=" + NotificationHost.hasPendingDecision(anchor)
                + " loadedTarget=" + targetTag() + " dirty=" + ParamSidePanel.isDirty(anchor));
    }

    private void checkReopened() {
        check("R1.5 the panel is visible again (translateX=" + panel.getTranslateX()
                        + " visible=" + panel.isVisible() + " showing=" + host.isRightShowing() + ")",
                panel.isVisible() && Math.abs(panel.getTranslateX()) <= 0.01);
        check("R1.5 the reopened panel is the SAME ParamFormPane instance (draft state machine kept)",
                panel.getCenter() == pane);
        check("R1.5 the draft is still there after collapse+reopen (no load()/commitDraft() on fold)",
                ParamSidePanel.isDirty(anchor));
        check("R1.5 the edited value is still there",
                ((ToggleSwitch) pane.lookup("#separateFilesCheck")).isSelected() == draftToggleWas);
        check("step 4 reopening via #paramSettingsButton hides the round button again", !reopenVisible());

        // ---- R3.5：侧边栏开合不落盘 ----
        String after = guiStateKeySet();
        System.out.println("gui-state keys after  = " + after);
        check("R3.5 gui-state.json key set unchanged (" + guiStateKeysBefore + ")",
                after.equals(guiStateKeysBefore));
        List<String> offending = new ArrayList<>();
        for (Field f : GuiState.class.getDeclaredFields()) {
            if (f.getName().toLowerCase().matches(".*(panel|sidebar|collaps|expand).*")) {
                offending.add(f.getName());
            }
        }
        check("R3.5 GuiState has no side-panel field " + offending, offending.isEmpty());

        snapshot("sidepanel");
    }

    /** R1.9：只有「确认」提交 —— 点它应当把草稿写进控制器（全局参数缓存），并收起面板。 */
    private void confirmPanel() {
        Button confirm = (Button) pane.lookup("#confirmButton");
        check("the panel confirm button is reachable (#confirmButton)", confirm != null);
        if (confirm != null) {
            confirm.fire();
        }
        System.out.println("fired #confirmButton (R1.9: only confirm commits)");
    }

    private void checkCommitted() {
        check("R1.9 confirming collapses the panel", !panel.isVisible() && !host.isRightShowing());
        check("R1.9 confirming clears the dirty flag (commitDraft ran)", !ParamSidePanel.isDirty(anchor));
        Object params = fieldDeep(controller, "paramDomain", "textParamsCache");
        boolean separate = false;
        try {
            separate = (Boolean) params.getClass().getMethod("isSeparateFiles").invoke(params);
        } catch (Exception e) {
            System.out.println("      (reading the committed params failed: " + e + ")");
        }
        check("R1.9 the confirmed draft reached MainController.textParamsCache "
                        + "(isSeparateFiles=" + separate + ", expected=" + draftToggleWas + ")",
                params != null && separate == draftToggleWas);
    }

    /** 把窗口缩到最小支持尺寸（900x650）：50% 面板此时只有 ~442px 宽，最容易截断（en 文案更长）。 */
    private void shrinkToMinWidth() {
        stage.setWidth(900);
        stage.setHeight(650);
        System.out.println("resized to 900x650 (min supported); overlayW=" + overlay.getWidth()
                + " panelW=" + panel.getWidth());
    }

    /** 默认窗口尺寸下的面板文本截断体检（硬断言）。 */
    private void checkPanelTruncation() {
        List<String> truncated = truncatedIn(panel);
        truncated.forEach(s -> System.out.println("      TRUNCATED  " + s));
        check("panel at " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                        + ": no truncated Labeled inside the panel (count=" + truncated.size()
                        + ", panelW=" + panel.getWidth() + ")",
                truncated.isEmpty());
        snapshot("sidepanel-default");
    }

    /**
     * ENTER 不得变成"提交参数"：ParamDialog.fxml 给「确定」设了 {@code defaultButton="true"}，
     * 而 {@code defaultButton} 是<b>场景级</b>的 —— 表单嵌进主窗口后，主窗口任何位置按 ENTER
     * 都会触发它（静默提交 + 收起面板）。侧边栏实例已用 {@code setDefaultButton(false)} 关掉。
     *
     * <p>判据用<b>同步</b>可见量：确认路径会同步走 {@code onExit → collapse → hideRight}，
     * 所以 {@code isRightShowing()} 在 ENTER 派发返回时就该是 true（不会被动画时序骗到）。</p>
     */
    private void checkEnterHijack() {
        boolean before = host.isRightShowing();
        Node focus = scene.getFocusOwner();
        Event.fireEvent(scene, key(KeyEvent.KEY_PRESSED));
        Event.fireEvent(scene, key(KeyEvent.KEY_RELEASED));
        boolean after = host.isRightShowing();
        System.out.println("ENTER fired on the scene: focusOwner=" + describe(focus)
                + "  rightShowing " + before + " -> " + after + "  (confirmButton.defaultButton="
                + String.valueOf(((Button) pane.lookup("#confirmButton")).isDefaultButton()) + ")");
        check("ENTER in the main window does NOT fire the embedded panel's default button "
                + "(rightShowing " + before + " -> " + after + ")", before && after);
    }

    /** 最小窗口尺寸下的面板文本截断体检（硬断言）：50% 面板此时只有 ~442px 宽，最容易被压出省略号。 */
    private void checkMinWidthTruncation() {
        List<String> truncated = truncatedIn(panel);
        truncated.forEach(s -> System.out.println("      TRUNCATED  " + s));
        check("panel at min window " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                        + ": no truncated Labeled inside the panel (count=" + truncated.size()
                        + ", panelW=" + panel.getWidth() + ")",
                truncated.isEmpty());
        snapshot("sidepanel-min");
        // Q6b：R1.7/R1.6/R1.8/R3.6 从这里开始（前面的 Q6a 步骤已全部跑完）
        runQ6bChain();
    }

    // ==================== Q6b：R1.7 / R1.6 / R1.8 / R3.6 ====================

    /** R1.7 的驱动样本：11 个夹具平铺导入（无子目录 ⇒ 不建组，条目路由成全局目标）。 */
    private void importFixtures() {
        fileListView.getSelectionModel().clearSelection();
        // 🚩 走生产路径：设置输入目录框 → textProperty 监听器 → onInputFolderChanged → 异步导入。
        // 顺带把目录框预先置成 FIXTURES_DIR：后面的 clickStartWithDraft 再 setText 同一值不会触发
        // 变更事件，也就不会"先开一次导入会话 → 禁用开始按钮 → fire() 静默失效"。
        // 不 sleep：导入是异步的（后台扫描 + FX 回调应用），而探针步骤跑在 FX 线程上，
        // Thread.sleep 会挡住导入自己的回调（实测 visibleCount 恒 0）。等待交给后面的 tick。
        javafx.scene.control.TextField in =
                (javafx.scene.control.TextField) scene.lookup("#inputFolderField");
        if (in != null) {
            in.setText(FIXTURES_DIR);
        }
        System.out.println("Q6b: inputFolderField set to fixtures (async import via the production listener)");
    }

    /** 让出一个 tick 给异步导入完成（本步不碰任何状态，只为等导入的 FX 回调排空）。 */
    private void waitForImport() {
        System.out.println("Q6b: one tick for the async import to apply...");
    }

    private void checkImportDone() {
        check("Q6b can resolve #startButton", startButton != null);
        check("Q6b can resolve #fileListView", fileListView != null);
        System.out.println("Q6b: imported fixtures -> visibleCount=" + visibleCount()
                + " groups=" + topLevelGroups().size());
        check("Q6b fixture import put items in the list (visibleCount>0)", visibleCount() > 0);
    }

    /** 干净状态下开始按钮**可用**（R1.7 步骤 4 的对照基线：禁用的只有其它条件，与草稿无关）。 */
    private void checkCleanStartState() {
        check("R1.7 (step 4) clean panel: #startButton is ENABLED (baseline for the dirty case)",
                startButton != null && !startButton.isDisable());
        check("step 4 state machine row 1 (first open): the round button is HIDDEN",
                reopenButton != null && !reopenVisible());
        check("step 4 state machine row 1 (first open): the panel is open with a clean baseline",
                host.isRightShowing() && !ParamSidePanel.isDirty(anchor));
    }

    /**
     * 把面板改脏：把「分离文件」开关设成"与当前基线相反"的值，并**派发一次真实用户交互事件**。
     *
     * <p>为什么必须派发事件：R1.7 的触发端刻意<b>不是</b>控件属性监听（那才是 Q5b 拒绝的
     * "每敲一个字符就重算签名"），而是面板级的 {@code MOUSE_CLICKED} / {@code KEY_RELEASED}。
     * 只调 {@code setSelected(...)} 相当于"程序化改值"，产品里不会发生，也测不到触发链。
     * 这里按真实的用户路径走：先给出目标值，再发一次 {@code MOUSE_CLICKED}（该事件就是
     * {@code ButtonBase.fire()} 派发的那个）让面板重算开始按钮。</p>
     */
    private void makeDirty() {
        ToggleSwitch draftToggle = (ToggleSwitch) livePane().lookup("#separateFilesCheck");
        if (draftToggle == null) {
            check("R1.7 draft field #separateFilesCheck is reachable", false);
            return;
        }
        draftToggle.setSelected(true);
        if (!livePane().isDirty()) {
            draftToggle.setSelected(false);
        }
        lastToggleValue = draftToggle.isSelected();
        dirtyChanged(draftToggle);
        System.out.println("Q6b: set #separateFilesCheck=" + lastToggleValue
                + " + MOUSE_CLICKED  livePane().isDirty=" + livePane().isDirty()
                + "  ParamSidePanel.isDirty=" + ParamSidePanel.isDirty(anchor));
    }

    /**
     * 补一次真实交互事件（面板级触发端；见 {@link #makeDirty()} 的说明）。
     *
     * <p>🚩 事件派发到<b>面板本身</b>、而不是派发到子控件：实测 {@code Event.fireEvent(控件, MOUSE_CLICKED)}
     * 只会走该控件自己那条链，<b>不会</b>回溯到父容器的 {@code addEventHandler}（面板收不到）；
     * 而 {@code ButtonBase.fire()} 派发的正是"事件从按钮自己出发"的那种事件，真实用户点击时
     * JavaFX 的命中测试会把 target 定成最深的节点、再沿父链冒泡。要让"面板级监听"这条设计被真实触发，
     * 派发的 target 必须是面板（等于把命中测试的结果告诉事件系统），本探针按这个口径驱动。</p>
     */
    private void dirtyChanged(Node control) {
        ParamFormPane target = pane;
        Point2D p = control == null ? null : centerOf(control);
        double x = p == null ? 0 : p.getX();
        double y = p == null ? 0 : p.getY();
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_CLICKED, target, x, y));
    }

    /**
     * R1.7（步骤 4 · 决策 2.5 语义变更）：改完字段按钮**仍可点** ——
     * 不再禁用（拦截从"按钮态"改成 ExtractionTaskCoordinator 的草稿门弹卡）。
     */
    private void checkStartEnabledWhileDirty() {
        check("R1.7 (step 4) editing a field NO LONGER disables #startButton (button="
                        + (startButton != null && !startButton.isDisable() ? "enabled" : "disabled") + ")",
                startButton != null && !startButton.isDisable());
        check("step 4 state machine row 2 (user edit): the round button stays hidden",
                !reopenVisible());
    }

    /**
     * 点「开始提取」的前置：deny 链在草稿门之前，输入/输出目录必须合法才能走到门
     * （输入目录 = 夹具目录、输出目录 = 临时目录）；随后真的 fire #startButton。
     */
    private void clickStartWithDraft() {
        javafx.scene.control.TextField in = (javafx.scene.control.TextField) scene.lookup("#inputFolderField");
        javafx.scene.control.TextField out = (javafx.scene.control.TextField) scene.lookup("#outputFolderField");
        check("step 4 pre: the input/output folder fields are reachable", in != null && out != null);
        if (in != null) {
            in.setText(FIXTURES_DIR);
        }
        if (out != null) {
            out.setText(new File(System.getProperty("java.io.tmpdir"), "sidepanel-probe-out").getPath());
        }
        startButton.fire();
    }

    /** R1.7（步骤 4）：点开始时草稿未提交 → 居中提醒卡（决策 D：只有一个「确定」）。 */
    private void checkDraftBlocksCard() {
        check("R1.7 (step 4) starting with a dirty draft shows the centered blocking card (card="
                + cardShown() + ")", cardShown());
        String msg = cardMessage();
        check("R1.7 (step 4) the card message is sidepanel.draftBlocksStart: " + msg.replace('\n', '|'),
                I18n.get("sidepanel.draftBlocksStart").equals(msg));
        List<Button> actions = modalPane.getContent() instanceof Notification n
                ? n.getPrimaryActions() : List.of();
        check("R1.7 (step 4) the card has exactly ONE button (decision D; buttons="
                + actions.stream().map(Button::getText).toList() + ")", actions.size() == 1);
        check("R1.7 (step 4) the card button does NOT set defaultButton",
                actions.stream().noneMatch(Button::isDefaultButton));
        check("R1.7 (step 4) the card is informational (no pending decision registered)",
                !NotificationHost.hasPendingDecision(anchor));
        check("R1.7 (step 4) no extraction started behind the card (running=" + extractionRunning() + ")",
                !extractionRunning());
        check("R1.7 (step 4) the panel stays OPEN and dirty behind the card",
                host.isRightShowing() && ParamSidePanel.isDirty(anchor));
    }

    /** 点 R1.7 卡片的「确定」（唯一按钮）。 */
    private void dismissDraftCard() {
        check("R1.7 (step 4) the card exposes its single OK button", clickCardButton(0));
    }

    /** 微决策：「确定」只关卡片 —— 不展开/不收起侧边栏、不改变草稿、不启动提取。 */
    private void checkCardDismissedOnly() {
        check("R1.7 (step 4) OK only closes the card (panel still open, draft still dirty)",
                !cardShown() && host.isRightShowing() && ParamSidePanel.isDirty(anchor));
        check("R1.7 (step 4) OK did not start extraction (running=" + extractionRunning() + ")",
                !extractionRunning());
        check("R1.7 (step 4) the start button stays ENABLED (no state changed by the card)",
                startButton != null && !startButton.isDisable());
    }

    private void commitViaConfirm() {
        Button confirm = (Button) livePane().lookup("#confirmButton");
        if (confirm != null) {
            confirm.fire();
        }
    }

    private void openGroupA() {
        selectGroup(0);
        paramSettingsButton.fire();
        System.out.println("Q6b openGroupA: expect=" + expectGroupTag(0) + " actual=" + targetTag()
                + " dirty=" + livePane().isDirty() + " showing=" + host.isRightShowing());
    }

    private void checkTargetA() {
        check("R1.6 panel is open on " + expectGroupTag(0) + " and reports no draft (fresh load);"
                        + " actual=" + targetTag() + " dirty=" + livePane().isDirty(),
                host.isRightShowing() && targetTag().equals(expectGroupTag(0)) && !livePane().isDirty());
    }

    // ---------- 步骤 4：折叠 = 会话继续（toast + 圆钮）；R1.8 降级 ----------

    private void collapseWithDirty() {
        ToggleSwitch t = (ToggleSwitch) livePane().lookup("#separateFilesCheck");
        draftToggleWas = t != null && t.isSelected();   // 折叠前的草稿值（圆钮再展开后必须原样还在）
        draftPane = livePane();                          // 折叠前的 pane 实例（再展开必须是同一个）
        collapseButton.fire();
    }

    /** R1.8（步骤 4）：dirty 收起 → 无居中卡、toast 提示 + 面板收起 + 草稿保留 + 圆钮显示。 */
    private void checkToastAndReopenShown() {
        check("R1.8 (step 4) collapsing a dirty panel shows NO centered card (downgraded to toast)",
                !cardShown() && !NotificationHost.hasPendingDecision(anchor));
        Node toast = findNotification(I18n.get("sidepanel.collapseDraftHint"));
        check("R1.8 (step 4) the hint toast appears with the exact round-button message",
                toast != null);
        check("R1.8 (step 4) the hint toast is a TOP-RIGHT toast above the overlay (shellIndexOf="
                        + (toast == null ? -1 : shellIndexOf(toast)) + ")",
                toast != null && shellIndexOf(toast) > 0);
        check("R1.8 (step 4) panel collapsed AND draft kept (isDirty still true)",
                !host.isRightShowing() && ParamSidePanel.isDirty(anchor));
        check("R1.8 (step 4) the edited value survives the collapse",
                ((ToggleSwitch) livePane().lookup("#separateFilesCheck")).isSelected() == draftToggleWas);
        check("step 4 state machine row 3 (collapse): the round button is VISIBLE", reopenVisible());
        check("step 4 the round button is a real node with size and a line icon",
                reopenButton != null && reopenButton.getWidth() > 0 && reopenButton.getHeight() > 0
                        && reopenButton.getGraphic() != null);
        check("step 4 the round button tooltip is sidepanel.reopen",
                reopenButton != null && reopenButton.getTooltip() != null
                        && I18n.get("sidepanel.reopen").equals(reopenButton.getTooltip().getText()));
    }

    private void clickReopenButton() {
        check("step 4 the typed accessor returns the #sidePanelReopen node (host.reopenButton(RIGHT))",
                reopenButton != null && host != null
                        && host.reopenButton(SidePanelHost.Side.RIGHT) == reopenButton);
        Node picked = centerOf(reopenButton) == null ? null
                : pick(scene.getRoot(), centerOf(reopenButton).getX(), centerOf(reopenButton).getY());
        check("step 4 the round button is pickable at its center (picked " + describe(picked) + ")",
                picked != null && isDescendantOf(picked, reopenButton));
        if (reopenButton != null) {
            reopenButton.fire();
        }
    }

    /** 状态机「点圆钮」行：滑入展开、同一 pane 实例、草稿原样保留、圆钮隐藏、不弹卡。 */
    private void checkReopenKeptDraft() {
        check("step 4 state machine row 4 (round button): the panel re-expands (visible, translateX=0)",
                panel.isVisible() && host.isRightShowing() && Math.abs(panel.getTranslateX()) <= 0.01);
        check("step 4 re-expanding uses the SAME pane instance (draft state machine kept)",
                livePane() == draftPane);
        check("step 4 the draft survives the round-button reopen (no load, isDirty still true)",
                ParamSidePanel.isDirty(anchor));
        check("step 4 the edited value survives the round-button reopen",
                ((ToggleSwitch) livePane().lookup("#separateFilesCheck")).isSelected() == draftToggleWas);
        check("step 4 the round button hides again while the panel is open", !reopenVisible());
        check("step 4 no card was shown during reopen (R1.6 NOT re-asked; target unchanged)",
                !cardShown());
    }

    // ---------- 步骤 4：取消 = 会话结束（草稿丢弃） ----------

    private void cancelDiscardsDraft() {
        ToggleSwitch t = (ToggleSwitch) livePane().lookup("#separateFilesCheck");
        draftToggleWas = t != null && t.isSelected();   // 取消前的草稿值（≠基线，取消后必须复位）
        Button cancel = (Button) livePane().lookup("#cancelButton");
        check("step 4 pre: the panel cancel button is reachable", cancel != null);
        if (cancel != null) {
            cancel.fire();
        }
    }

    /** 状态机「点取消」行（决策 2.4）：会话结束 —— 收起、圆钮隐藏、草稿丢弃（控件复位到基线）。 */
    private void checkCancelEndedSession() {
        check("step 4 state machine row 5 (cancel): panel collapsed AND round button hidden",
                !host.isRightShowing() && !reopenVisible());
        check("step 4 CANCEL discards the draft (isDirty=false after reload)",
                !ParamSidePanel.isDirty(anchor));
        check("step 4 the discarded edits are reset to the target's baseline value",
                ((ToggleSwitch) livePane().lookup("#separateFilesCheck")).isSelected() != draftToggleWas);
    }

    private void reopenAfterCancel() {
        paramSettingsButton.fire();   // 同一目标（GLOBAL）重开：干净基线直接展开
    }

    private void checkReopenAfterCancel() {
        check("step 4 reopening after cancel shows the clean baseline (discard persisted)",
                host.isRightShowing() && !ParamSidePanel.isDirty(anchor) && !reopenVisible());
    }

    // ---------- 步骤 4：决策 C 门顺序（草稿门 → 未命名组门） ----------

    /**
     * 门顺序测试前置：造一个<b>手工</b>组（合并为组 → defaultNamed=true —— 未命名组门的唯一
     * 触发样本；目录导入的组自带名字，不算"未命名"）+ 把面板改脏（草稿门会拦）。
     */
    private void makeGroupsAndDirtyForGateOrder() {
        // 与 FeatureExercise 的驱动方式同：勾选两个顶层条目 → updateListButtons → fire #groupButton
        List<com.mhtextractor.model.PreviewItem> items = allVisibleItems().stream()
                .filter(i -> i.parentGroup == null && !i.removed).toList();
        check("step 4 gate-order pre: at least two top-level items to merge (items=" + items.size() + ")",
                items.size() >= 2);
        if (items.size() >= 2) {
            for (com.mhtextractor.model.PreviewItem i : allVisibleItems()) {
                i.setSelected(false);
            }
            items.get(0).setSelected(true);
            items.get(1).setSelected(true);
            invoke(controller, "updateListButtons", new Class<?>[]{});
            javafx.scene.control.Button groupButton =
                    (javafx.scene.control.Button) scene.lookup("#groupButton");
            check("step 4 gate-order pre: the merge button is enabled",
                    groupButton != null && !groupButton.isDisabled());
            if (groupButton != null && !groupButton.isDisabled()) {
                groupButton.fire();
            }
        }
        check("step 4 gate-order pre: a default-named manual group now exists",
                manualGroupName() != null);
        makeDirty();
    }

    /** 决策 C：草稿门在未命名组门之前 —— 两个门都命中时先看到草稿卡。 */
    private void checkDraftGatePrecedesUnnamedGate() {
        check("step 4 gate order (decision C): with a dirty draft AND a default-named group, "
                        + "the DRAFT card comes first", cardShown()
                && I18n.get("sidepanel.draftBlocksStart").equals(cardMessage()));
        String expectedUnnamed = I18n.get("group.unnamedConfirm", "names", manualGroupName());
        check("step 4 gate order: the unnamed-group card did NOT preempt the draft card",
                !expectedUnnamed.equals(cardMessage()));
    }

    /** 草稿提交后（草稿门放行），未命名组门照常拦截 —— 证明顺序是串行、前者不吞后者。 */
    private void checkUnnamedGateAfterCommit() {
        startButton.fire();   // 链是 FX 同步的：deny → R1.6 参数卡 → 草稿门（已干净，放行）→ 未命名组门
        String expected = I18n.get("group.unnamedConfirm", "names", manualGroupName());
        check("step 4 gate order: after the draft is committed, the unnamed-group gate fires (card="
                + cardShown() + ")", cardShown() && expected.equals(cardMessage()));
        check("step 4 gate order: the unnamed card is a CONFIRM (pending decision registered)",
                NotificationHost.hasPendingDecision(anchor));
        snapshot("unnamed-group-card");
        check("step 4 gate order: cancel on the unnamed card", clickCardCancel());
        check("step 4 gate order: canceling the unnamed card aborts extraction (running="
                + extractionRunning() + ")", !extractionRunning());
    }

    /** 默认名（未命名）组的名字；没有 → null。 */
    private String manualGroupName() {
        for (Object g : topLevelGroups()) {
            try {
                if (Boolean.TRUE.equals(g.getClass().getMethod("isDefaultNamed").invoke(g))) {
                    return groupName(g);
                }
            } catch (Exception ignored) {
                // 继续找下一个
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<com.mhtextractor.model.PreviewItem> allVisibleItems() {
        Object r = invoke(controller, "allVisibleItems", new Class<?>[]{});
        return r instanceof List<?> l ? (List<com.mhtextractor.model.PreviewItem>) l : List.of();
    }

    // ---------- R1.6：切换目标确认卡 ----------

    private void switchToGroupB() {
        selectGroup(1);
        paramSettingsButton.fire();
    }

    private void checkSwitchCard() {
        check("R1.6 switching target with a dirty draft shows the confirmation card (card="
                + cardShown() + ")", cardShown());
        check("R1.6 the panel did NOT switch behind the card (target still " + expectGroupTag(0)
                        + ", actual=" + targetTag() + ")",
                targetTag().equals(expectGroupTag(0)));
        check("step 4 (decision 2.7) the panel STAYS OPEN during the R1.6 card (no pre-collapse)",
                host.isRightShowing() && panel.isVisible());
    }

    private void cancelSwitch() {
        check("R1.6 card exposes a cancel button", clickCardCancel());
    }

    private void checkTargetUnchanged() {
        check("R1.6 CANCEL: target unchanged (still " + expectGroupTag(0) + ", actual=" + targetTag() + ")",
                targetTag().equals(expectGroupTag(0)));
        check("R1.6 CANCEL: draft unchanged (isDirty still true)", ParamSidePanel.isDirty(anchor));
        check("R1.7 (step 4) cancel switching leaves #startButton enabled (draft no longer disables it)",
                startButton != null && !startButton.isDisable());
        check("step 4 (decision 2.7) state machine row 8 (switch + cancel): the panel stays open",
                host.isRightShowing() && panel.isVisible() && !reopenVisible());
        check("R1.6 CANCEL: no card left and the panel stays open",
                !cardShown() && host.isRightShowing());
    }

    /**
     * R1.6 的**控制组**：面板干净时把选择清空（全局目标）再发起配置 ⇒ 应当**直接切换、不弹卡**。
     * 它紧跟在「确定切换」之后跑（那一刻草稿刚好被丢弃、面板干净），用来证明"弹卡"不是恒真。
     */
    private void switchToGlobalClean() {
        System.out.println("Q6b control: before clean switch -> dirty=" + livePane().isDirty()
                + " target=" + targetTag());
        fileListView.getSelectionModel().clearSelection();
        paramSettingsButton.fire();
        System.out.println("Q6b control: clean switch to GLOBAL -> card=" + cardShown()
                + " target=" + targetTag() + " dirty=" + ParamSidePanel.isDirty(anchor));
        check("R1.6 control: switching target while CLEAN shows NO card (card=" + cardShown()
                + ", dirtyBefore=false)", !cardShown());
        check("R1.6 control: the clean switch actually happened (target=" + targetTag() + ")",
                "GLOBAL".equals(targetTag()));
        check("R1.7 control: a clean panel leaves #startButton enabled",
                startButton != null && !startButton.isDisable());
    }

    private void confirmSwitch() {
        check("R1.6 card exposes an OK button", clickCardOk());
        // 决策 2.7/2.8：确认后才原地换内容 —— 同一帧内面板保持展开、translateX 不重放（无滑入动画）
        check("step 4 (decision 2.7/2.8) after confirming the switch the panel stays open IN PLACE "
                        + "(translateX=" + panel.getTranslateX() + ")", host.isRightShowing()
                && panel.isVisible() && Math.abs(panel.getTranslateX()) <= 0.01);
    }

    private void checkTargetSwitchedAndClean() {
        check("R1.6 OK: target switched to B (expected " + expectGroupTag(1)
                + ", actual=" + targetTag() + ")", targetTag().equals(expectGroupTag(1)));
        check("R1.6 OK: draft discarded (load() re-baselined, isDirty=false)",
                !ParamSidePanel.isDirty(anchor));
        check("step 4 (decision 2.7) state machine row 7 (switch + confirm): the panel is STILL OPEN "
                        + "after the in-place content replacement", host.isRightShowing()
                && panel.isVisible() && Math.abs(panel.getTranslateX()) <= 0.01);
        check("step 4 the round button stays hidden during an open-panel switch", !reopenVisible());
    }

    // ---------- R3.6：两条独立分支 ----------

    /**
     * 关窗测试的前置（**这一步决定了断言是否真的在测守卫**）：
     *
     * <ol>
     *   <li><b>把窗口显示出来</b>：{@code MainController} 是在
     *       {@code scene.windowProperty()} 变化时才 {@code stage.setOnCloseRequest(...)} 的，
     *       窗口没显示过就根本没有处理器，戳下去只会"什么都没发生"——那是假通过。</li>
     *   <li><b>把清缓存路径设为"已完成"</b>（{@code shutdownCacheCleared=true}）：它不是本步要测的东西，
     *       但它是 {@code onWindowCloseRequest} 里另一个会 consume 的分支。不清掉这层噪声，
     *       就无法区分"守卫拦住了"与"清缓存流程拦住了"。</li>
     * </ol>
     *
     * <p>🚩 <b>判据只能是 {@code stage.isShowing()}，不能是 {@code event.isConsumed()}</b> ——
     * 后者在 Stage 上恒为 true：JavaFX 的 {@code WindowCloseRequestHandler}
     * （{@code com.sun.javafx.stage}，事件链的<b>最后一个</b>调度器）对每一个没被用户处理器消费的
     * {@code WINDOW_CLOSE_REQUEST} 都会执行 {@code window.hide(); event.consume();}。
     * 实测（控制组）：自定义处理器运行时看到 {@code isConsumed()==false}，返回后立刻变成 true。
     * 故"事件被消费"什么也证明不了，"窗口还在"才是守卫真的消费了它的唯一证据。</p>
     *
     * <p>C4 起清缓存路径已外迁到 {@code WorkspaceLifecycleController}：反射先取
     * {@code controller.workspaceLifecycle} 字段，再在该对象上调用/读字段。</p>
     */
    private void prepareCloseGate() {
        // 走完清缓存分支、让窗口显示出来；注意顺序：清缓存是异步的，但 test.mode 下同步走完
        Object lifecycle = field(controller, "workspaceLifecycle");
        invoke(lifecycle, "clearPreviewCache", new Class<?>[]{});
        if (!stage.isShowing()) {
            stage.show();
            sleep(300);
        }
        check("R3.6 setup: the window is on screen and the cache branch is finished ("
                        + "showing=" + stage.isShowing()
                        + ", shutdownCacheCleared=" + field(lifecycle, "shutdownCacheCleared") + ")",
                stage.isShowing() && Boolean.TRUE.equals(field(lifecycle, "shutdownCacheCleared")));
    }

    /** 分支 1：dirty 草稿**不**拦截关窗（不弹卡、窗口照常关闭）。 */
    private void checkDirtyAllowsClose() {
        boolean dirty = ParamSidePanel.isDirty(anchor);
        boolean pending = NotificationHost.hasPendingDecision(anchor);
        System.out.println("R3.6 branch 1 precondition: dirty=" + dirty + " pendingDecision=" + pending);
        check("R3.6 branch 1 precondition: the panel is DIRTY and there is NO card",
                dirty && !pending && !cardShown());
        WindowEvent e = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
        Event.fireEvent(stage, e);
        System.out.println("R3.6 branch 1: dirty=" + dirty + " pendingDecision=" + pending
                + " consumed=" + e.isConsumed() + " showing=" + stage.isShowing()
                + "  (consumed is always true on a Stage - see prepareCloseGate)");
        check("R3.6 branch 1: a dirty draft does NOT block the close (the window really went away:"
                        + " showing=" + stage.isShowing() + ")",
                dirty && !pending && !stage.isShowing());
    }

    /** 控制组：干净面板 + 无未答决策 ⇒ 同样不拦截（证明"不拦截"不是恒真的一种表现）。 */
    private void checkCleanAllowsClose() {
        boolean dirty = ParamSidePanel.isDirty(anchor);
        boolean pending = NotificationHost.hasPendingDecision(anchor);
        WindowEvent e = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
        Event.fireEvent(stage, e);
        check("R3.6 control: clean panel + no pending decision also lets the close through "
                        + "(dirty=" + dirty + ", pending=" + pending
                        + ", showing=" + stage.isShowing() + ")",
                !dirty && !pending && !stage.isShowing());
    }

    /** 分支 2：R1.6 的切换确认卡**显示中**时，关窗被**既有** hasPendingDecision 守卫拦住。 */
    private void checkCardBlocksClose() {
        boolean pending = NotificationHost.hasPendingDecision(anchor);
        check("R3.6 branch 2 precondition: the R1.6 switch card is showing as an UNANSWERED decision",
                cardShown() && pending);
        WindowEvent e = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
        Event.fireEvent(stage, e);
        System.out.println("R3.6 branch 2: pending=" + pending + " consumed=" + e.isConsumed()
                + " showing=" + stage.isShowing() + " card=" + describeCard());
        check("R3.6 branch 2: a pending switch confirmation BLOCKS the window close "
                        + "(the window is still showing: " + stage.isShowing() + ")",
                stage.isShowing());
        check("R3.6 branch 2: the pending card was NOT replaced or answered behind the user's back",
                cardShown() && NotificationHost.hasPendingDecision(anchor));
    }

    /**
     * E1.2 —— 「清预览态」收敛的运行时观测（决策 #11）：
     * (a) 真实主界面装配拿到 {@code PreviewRenderer} 与一个可见条目；
     * (b) {@code cachedMeta == null} 时 {@code openCachedImage} 必须安全返回
     *     （不抛异常、不启动 open-cached-image 线程）；
     * (c) 生产清空路径 {@code ParamDomainController.refreshAfterTargetedParamsChange} 驱动后
     *     {@code cachedMeta == null} 且预览区不再有可点击缩略图节点（meta==null 且有点击处理器的状态不可达）。
     * 反射只经本探针已登记的 helper（field / invoke / invokeOn），不新增反射原语。
     */
    private void e12PreviewStateCheck() {
        // 切到图片模式：updateContentArea 只在图片模式触碰缩略图区（文本模式不动 thumbnailPane）。
        // 本步已处链路末端（关窗前），模式切换不影响后续步骤。
        ToggleButton imageMode = (ToggleButton) scene.lookup("#imageModeToggle");
        if (imageMode != null) {
            imageMode.setSelected(true);
        }
        Object item = null;
        for (Object row : fileListView.getItems()) {
            if (row instanceof PreviewItem p && !p.removed) {
                item = p;
                break;
            }
        }
        if (item == null) {
            check("E1.2 (a): a visible PreviewItem exists in #fileListView", false);
            return;
        }
        selectRow(item);
        Object renderer = field(controller, "previewRenderer");
        check("E1.2 (a): field(mc, previewRenderer) resolves", renderer != null);
        if (renderer == null) {
            return;
        }
        Object pane = invokeOn(PreviewRenderer.Host.class, field(renderer, "host"),
                "thumbnailPane", new Class<?>[]{});
        if (!(pane instanceof javafx.scene.layout.Pane pn)) {
            check("E1.2 (a): renderer host exposes the thumbnail pane", false);
            return;
        }

        // (b) meta == null → openCachedImage 安全返回。invoke helper 吞异常并打印
        // "(reflect ... failed"：捕获 System.out 断言无该标记；再以"未启动 open-cached-image
        // 线程"作第二个可观测信号（成功路径会在检查后同步 spawn 该线程）。
        ((PreviewItem) item).cachedMeta = null;
        int threadsBefore = countThreads("open-cached-image");
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(captured, true));
        try {
            invoke(renderer, "openCachedImage", new Class<?>[]{PreviewItem.class, int.class}, item, 0);
        } finally {
            System.setOut(originalOut);
        }
        String openOut = captured.toString();
        check("E1.2 (b): openCachedImage(meta=null) returned without exception"
                        + (openOut.isBlank() ? "" : "  output=" + openOut.replace('\r', ' ').replace('\n', '|')),
                !openOut.contains("openCachedImage failed"));
        check("E1.2 (b): no open-cached-image thread spawned for meta==null (safe early return)",
                countThreads("open-cached-image") == threadsBefore);

        // 播种"有 meta + 可点击缩略图"的已渲染状态（生产渲染入口 appendThumb；meta 为 public 字段直接赋值）
        ((PreviewItem) item).cachedMeta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(((PreviewItem) item).path, 1L, 2L, ""), "probe-fp", true,
                List.of(new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "0001.png", "png", List.of())),
                List.of());
        ((PreviewRenderer) renderer).appendThumb((PreviewItem) item,
                new ThumbnailData("0001.png", 2, 2,
                        new int[]{0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF}), 0);
        long clickableBefore = countClickableThumbs(pn);
        check("E1.2 (c) precondition: a clickable thumbnail node exists before the clearing",
                clickableBefore > 0);

        // (c) 生产清空路径：ParamDomainController.refreshAfterTargetedParamsChange(affected=[item])
        Object paramDomain = field(controller, "paramDomain");
        invoke(paramDomain, "refreshAfterTargetedParamsChange", new Class<?>[]{List.class}, List.of(item));

        boolean metaCleared = ((PreviewItem) item).cachedMeta == null;
        long clickableAfter = countClickableThumbs(pn);
        check("E1.2 (c): production clearing path nulls cachedMeta (the previously leaked field)",
                metaCleared);
        check("E1.2 (c): no clickable thumbnail nodes remain in the preview area "
                        + "(before=" + clickableBefore + " after=" + clickableAfter + ")",
                clickableAfter == 0);
    }

    /** 缩略图区里带点击处理器的节点数（buildThumbBox 把 onClick 挂在盒子节点上）。 */
    private static long countClickableThumbs(javafx.scene.layout.Pane pane) {
        long n = 0;
        for (javafx.scene.Node child : pane.getChildren()) {
            if (child.getOnMouseClicked() != null) {
                n++;
            }
        }
        return n;
    }

    /** 指定名字的存活线程数（E1.2 (b) 的第二个可观测信号）。 */
    private static int countThreads(String name) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) {
                n++;
            }
        }
        return n;
    }

    private void closeWindowForReal() {
        stage.close();
        check("R3.6: with no pending decision the window really closes (no stuck non-daemon thread)",
                !stage.isShowing());
        finish();
    }

    // ==================== Q6b helpers ====================

    /** Q6b 的夹具目录（11 个平铺 .mht）。 */
    private static final String FIXTURES_DIR =
            new File("src/test/resources/fixtures").getAbsolutePath();

    /** 在<b>全新</b>的临时目录里造两个组（sub1 / sub2）各放一个 .mht —— R1.6 需要两个可路由的组目标。 */
    private void createFixtureGroups() {
        try {
            Path root = Files.createTempDirectory("sidepanel-probe-groups");
            Path a = Files.createDirectories(root.resolve("sub1"));
            Path b = Files.createDirectories(root.resolve("sub2"));
            Files.copy(Path.of(FIXTURES_DIR, "simple-text.mht"), a.resolve("g1.mht"));
            Files.copy(Path.of(FIXTURES_DIR, "titles.mht"), b.resolve("g2.mht"));
            Object plan = invokeStatic("com.mhtextractor.util.MhtImportPlanner", "scanner",
                    new Class<?>[]{List.class, int.class, int.class, int.class},
                    List.of(root.toString()), 6, 50, 5000);
            Object next = invoke(plan, "nextRound", new Class<?>[]{});
            invoke(controller, "applyImportPlan",
                    new Class<?>[]{Class.forName("com.mhtextractor.util.MhtImportPlanner$Plan")}, next);
            sleep(400);
            System.out.println("Q6b: created fixture groups -> groups=" + topLevelGroups().size()
                    + " visibleCount=" + visibleCount());
        } catch (Exception e) {
            check("Q6b could create two fixture groups: " + e, false);
            e.printStackTrace();
        }
    }

    /**
     * 选中第 i 个顶层组（i<0 = 清空选择 ⇒ 条目路由成全局目标）。
     *
     * <p>🚩 每次都<b>重新取</b> {@code topLevelGroups()}：适配器在预解析收尾时会把模型<b>重新投影</b>，
     * {@code GroupItem} 实例会被换掉（实测同一逻辑组在两步之间换了对象），
     * 缓存下来的列表引用会失效，导致"选中了旧实例、路由到旧目标"。</p>
     */
    private void selectGroup(int i) {
        if (fileListView == null) {
            return;
        }
        if (i < 0) {
            fileListView.getSelectionModel().clearSelection();
            return;
        }
        List<Object> groups = topLevelGroups();
        if (i < groups.size()) {
            selectRow(groups.get(i));
        } else {
            check("Q6b group index " + i + " exists (groups=" + groups.size() + ")", false);
        }
    }

    /**
     * 选中列表里的某个对象。
     *
     * <p>🚩 不能用 {@code getSelectionModel().select(item)}：{@code fileListView} 的静态类型是
     * {@code ListView<?>}，捕获通配符让 {@code select} 的参数类型不可表达（编译期 CAP 错误）；
     * 反射 {@code getDeclaredMethod("select", Object.class)} 也拿不到 —— 泛型擦除生成的桥方法
     * 与具体实现类都不可达（实测 {@code ListViewBitSetSelectionModel} 上两者都查不到）。
     * 唯一稳的入口是<b>接口</b>{@code MultipleSelectionModel} 上的 {@code select(Object)}
     * （{@code getMethod} 拿接口/公有成员，不受实现类可见性影响），本探针按此驱动。</p>
     */
    private void selectRow(Object item) {
        javafx.collections.ObservableList<?> rows = fileListView.getItems();
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == item) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            check("Q6b row for " + item + " exists in #fileListView (rows=" + rows.size() + ")", false);
            return;
        }
        invokeOn(javafx.scene.control.MultipleSelectionModel.class,
                fileListView.getSelectionModel(), "select", new Class<?>[]{Object.class}, item);
        Object now = fileListView.getSelectionModel().getSelectedItem();
        System.out.println("Q6b: row " + index + " select -> selectedItem=" + now
                + " (sameAsItem=" + (now == item) + ")");
        if (now != item) {
            check("Q6b could select row " + index + " (" + item + ")", false);
        }
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

    /** 无选中项时发起「提取参数配置」⇒ GLOBAL 目标（R1.7 的驱动样本）。 */
    private void openParamOnNothing() {
        if (fileListView != null) {
            fileListView.getSelectionModel().clearSelection();
        }
        paramSettingsButton.fire();
    }

    /**
     * 硬边界回归：{@code SidePanelHost} 左右共用（左侧是 HTML 源码查看器）⇒
     * 左侧收起/展开必须回归；左侧圆钮本期<b>预留</b>（决策 2.2：API 已按 side 参数化，不显示）。
     */
    private void leftViewerRegression() {
        BorderPane dummy = new BorderPane(new Label("left-viewer-regression"));
        host.showLeft(dummy, "viewer", null);
        check("left regression: showLeft expands the left panel (logical state)", host.isLeftShowing());
        check("left regression: the left panel holds the new content and is visible",
                host.leftPanelNode().isVisible()
                        && host.leftPanelNode() instanceof BorderPane lb && lb.getCenter() == dummy);
        check("left regression: the right side is untouched (independent nodes, R3.1)",
                host.isRightShowing());
        host.hideLeft();
        check("left regression: hideLeft collapses the left panel (logical state)", !host.isLeftShowing());
        host.showLeft(dummy, "viewer", null);   // 折叠后再次展开仍走滑入（与右侧决策 2.9 同构）
        check("left regression: showLeft re-expands after a fold", host.isLeftShowing());
        check("left regression: no left round button yet (reserved, decision 2.2)",
                !host.isReopenShowing(SidePanelHost.Side.LEFT)
                        && !host.reopenButton(SidePanelHost.Side.LEFT).isVisible());
        host.hideLeft();
        check("left regression: left collapsed for the close tests", !host.isLeftShowing());
    }

    /** 圆钮当前是否显示（每次经 #sidePanelReopen 现查，不缓存可见态）。 */
    private boolean reopenVisible() {
        Node n = scene.lookup("#sidePanelReopen");
        return n != null && n.isVisible();
    }

    /** 提取是否在跑（经 MainController.extractionCoordinator 的 running 标记；探针在默认包，反射读）。 */
    private boolean extractionRunning() {
        Object coord = field(controller, "extractionCoordinator");
        Object running = coord == null ? null : field(coord, "running");
        try {
            return running != null && (Boolean) running.getClass().getMethod("get").invoke(running);
        } catch (Exception e) {
            return false;
        }
    }

    /** 目标标签：全局 = "GLOBAL"，组 = "group:<组名>"，条目 = "item:<路径>"，未载入 = "null"。 */
    private String targetTag() {
        Object loaded = ParamSidePanel.of(anchor).loadedTarget();
        if (loaded == null) {
            return "null";
        }
        if (loaded instanceof ParamScope.GlobalTarget) {
            return "GLOBAL";
        }
        if (loaded instanceof ParamScope.GroupTarget gt) {
            return "group:" + gt.group().getName();
        }
        if (loaded instanceof ParamScope.ItemTarget<?> it
                && it.item() instanceof com.mhtextractor.model.PreviewItem pi) {
            return "item:" + pi.path;
        }
        return loaded.getClass().getSimpleName();
    }

    /**
     * R1.6 断言用的目标标签。
     *
     * <p>按<b>名字</b>而不是实例身份比较：适配器重新投影会换掉 {@code GroupItem} 实例，
     * 探针手里缓存的引用会失效（"同一个组、不同对象"）。产品里的目标同一性口径是实例身份
     * （{@code ParamSidePanel.sameTarget}，见其 javadoc），那是"同一个目标"的语义，
     * 与探针"目标有没有换"的观测不是一回事。</p>
     */
    private String expectGroupTag(int index) {
        List<Object> groups = topLevelGroups();
        return index < groups.size() ? "group:" + groupName(groups.get(index)) : "group:?";
    }

    private static String groupName(Object group) {
        try {
            return String.valueOf(group.getClass().getMethod("getName").invoke(group));
        } catch (Exception e) {
            return "?";
        }
    }

    /** 当前居中卡片是否为 {@code Notification}（= 确认卡 / 错误卡，而不是等待卡片 Message）。 */
    private boolean cardShown() {
        return modalPane != null && modalPane.isDisplay()
                && modalPane.getContent() instanceof Notification;
    }

    private String cardMessage() {
        return modalPane != null && modalPane.getContent() instanceof Notification n
                ? String.valueOf(n.getMessage()) : "";
    }

    private String describeCard() {
        Node c = modalPane == null ? null : modalPane.getContent();
        return "display=" + (modalPane != null && modalPane.isDisplay())
                + " type=" + (c == null ? "null" : c.getClass().getSimpleName())
                + (c instanceof Notification n ? " msg=" + String.valueOf(n.getMessage()).replace('\n', '|') : "");
    }

    /** 点卡片上的「确定 / 确认并提交」（第一个主按钮）。 */
    private boolean clickCardOk() {
        return clickCardButton(0);
    }

    /** 点卡片上的「取消 / 稍后」（第二个主按钮）。 */
    private boolean clickCardCancel() {
        return clickCardButton(1);
    }

    private boolean clickCardButton(int index) {
        if (!(modalPane != null && modalPane.getContent() instanceof Notification ntf)) {
            System.out.println("      no Notification card to click (index=" + index + ")");
            return false;
        }
        List<Button> actions = ntf.getPrimaryActions();
        System.out.println("      card buttons = " + actions.stream().map(Button::getText).toList()
                + "  -> clicking index " + index);
        if (index >= actions.size()) {
            return false;
        }
        Button b = actions.get(index);
        System.out.println("      clicking \"" + b.getText() + "\" (defaultButton=" + b.isDefaultButton() + ")");
        b.fire();
        System.out.println("      after click: display=" + modalPane.isDisplay()
                + " showing=" + host.isRightShowing()
                + " pending=" + NotificationHost.hasPendingDecision(anchor));
        return true;
    }

    // ---------- 反射（探针在默认包，读不到包级/私有成员） ----------

    /** 反射调用实例方法（包级/私有均可）。 */
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // 继续沿父链找（如 selectIndex 声明在 MultipleSelectionModelBase，实现类是它子类）
            } catch (Exception e) {
                System.out.println("      (reflect " + name + " failed: " + e + ")");
                return null;
            }
        }
        System.out.println("      (reflect " + name + " failed: NoSuchMethodException in the hierarchy)");
        return null;
    }

    private static Object invokeStatic(String className, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> c = Class.forName(className);
            java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            System.out.println("      (reflect static " + className + "." + name + " failed: " + e + ")");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> topLevelGroups() {
        Object r = invoke(controller, "topLevelGroups", new Class<?>[]{});
        return r instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private long visibleCount() {
        Object r = invoke(controller, "visibleCount", new Class<?>[]{});
        return r instanceof Long l ? l : -1L;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 面板内部的文本截断清单 —— 判据与 {@code tools/probes/UiProbe.java} 逐条相同
     * （可见的 {@code Labeled}、非 wrapText、文本宽+图标+内边距 > 控件宽 即判为截断）。
     * UiProbe 看不到侧边栏（它按 App.start 之前的老结构自建场景），所以这条必须在这里做。
     */
    private static List<String> truncatedIn(Parent root) {
        List<String> truncated = new ArrayList<>();
        for (Node n : allNodes(root)) {
            if (!(n instanceof javafx.scene.control.Labeled lab) || lab.getText() == null
                    || lab.getText().isEmpty()) {
                continue;
            }
            if (lab instanceof javafx.scene.control.Label l2 && l2.isWrapText()) {
                continue;
            }
            if (!isEffectivelyVisible(lab)) {
                continue;
            }
            javafx.scene.text.Text probe = new javafx.scene.text.Text(lab.getText());
            probe.setFont(lab.getFont());
            double need = probe.getLayoutBounds().getWidth();
            if (lab.getGraphic() != null) {
                need += lab.getGraphic().getLayoutBounds().getWidth() + lab.getGraphicTextGap();
            }
            if (lab.getPadding() != null) {
                need += lab.getPadding().getLeft() + lab.getPadding().getRight();
            }
            double have = lab.getWidth();
            if (have > 0 && need > have + 1.0) {
                truncated.add(String.format("%s need=%.1f have=%.1f \"%s\"",
                        describe(n), need, have, lab.getText()));
            }
        }
        return truncated;
    }

    private static List<Node> allNodes(Parent root) {
        List<Node> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Node n, List<Node> out) {
        out.add(n);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                walk(c, out);
            }
        }
    }

    /** 反射读主控制器里的包级字段（探针在默认包，读不到包级成员；这是唯一的观测点）。 */
    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            System.out.println("      (reflect " + name + " failed: " + e + ")");
            return null;
        }
    }

    /** 反射读主控制器参数域协作者里的包级字段（步骤 1：两套参数缓存搬进 ParamDomainController）。 */
    private static Object fieldDeep(Object target, String owner, String name) {
        try {
            Object ownerObj = field(target, owner);
            if (ownerObj == null) return null;
            Field f = ownerObj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(ownerObj);
        } catch (Exception e) {
            System.out.println("      (reflect " + owner + "." + name + " failed: " + e + ")");
            return null;
        }
    }

    // ==================== helpers ====================

    private TimerTask at(long delay, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    /**
     * 总是取<b>当前</b>挂在侧边栏里的面板实例。
     *
     * <p>🚩 不要缓存 Q6a 阶段抓到的那个 {@code pane}：{@code ParamSidePanel} 按宿主缓存面板，
     * 而宿主是按"覆盖层"登记的 —— 主界面重新装配/场景根变化时会出现<b>两个</b>宿主与两个面板，
     * 于是"探针手里的 pane 脏了、产品在用的那个面板并不脏"（实测踩过：R1.6/R1.7 断言全假失败）。</p>
     */
    private ParamFormPane livePane() {
        Node center = panel == null ? null : panel.getCenter();
        if (center instanceof ParamFormPane p && p != pane) {
            System.out.println("      NOTE: the side panel now holds a DIFFERENT ParamFormPane instance"
                    + " (cached=" + System.identityHashCode(pane) + " live=" + System.identityHashCode(p) + ")");
        }
        return center instanceof ParamFormPane p ? p : pane;
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
        System.out.println("SIDE-PANEL PROBE: " + passed + " passed, " + failed + " failed  (locale="
                + I18n.currentLocale() + ")");
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * 复刻 JavaFX 的命中测试：先子后父（后加入的兄弟在上）。
     *
     * <p>两条刻意与 {@code Node.contains()} 不同的地方，都是量出来的：</p>
     * <ul>
     *   <li><b>命中形状用 {@code getLayoutBounds()}</b>，不用 {@code contains()}：后者把
     *       {@code -fx-effect} 的投影也算进 bounds —— 侧边栏的 dropshadow 有 22px 阴影，
     *       用 contains 会把面板左边界外 22px 也判成"面板"，于是左半区紧贴面板的控件被误判成被盖住。</li>
     *   <li><b>{@code ModalPane} 只要 {@code display=false} 就整幅不参与命中</b>：它是铺满窗口的
     *       Region，若不排除，任何点都会命中它 —— 那等于"主界面一个点击都收不到"，与"应用能正常
     *       点列表"这一事实矛盾（{@code ModalPaneStateProbe} 记录它的显示状态机）。</li>
     *   <li>{@code pickOnBounds=false} 的 Region（如 {@code toastLayer} 与覆盖层）空区域不参与命中 ——
     *       这正是 {@code NotificationHost} 里那句 {@code setPickOnBounds(false)} 的语义。</li>
     * </ul>
     */
    private static Node pick(Node node, double sceneX, double sceneY) {
        if (node == null || !node.isVisible() || node.isMouseTransparent()) {
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

    private static void click(Node target, double sceneX, double sceneY) {
        if (target == null) {
            return;
        }
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_PRESSED, target, sceneX, sceneY));
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_RELEASED, target, sceneX, sceneY));
        Event.fireEvent(target, mouse(MouseEvent.MOUSE_CLICKED, target, sceneX, sceneY));
    }

    /** ENTER 键事件（用于验"嵌入面板的 defaultButton 会不会劫持主窗口的 ENTER"）。 */
    private static KeyEvent key(EventType<KeyEvent> type) {
        return new KeyEvent(type, "", "", KeyCode.ENTER, false, false, false, false);
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, Node target, double sx, double sy) {        Point2D local = target.sceneToLocal(sx, sy);
        return new MouseEvent(type, local.getX(), local.getY(), sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true,
                new PickResult(target, sx, sy));
    }

    /** 节点在场景坐标里的中心（不可见/为 null → null）。 */
    private static Point2D centerOf(Node n) {
        if (n == null || !n.isVisible()) {
            return null;
        }
        Bounds b = n.localToScene(n.getBoundsInLocal());
        return new Point2D(b.getCenterX(), b.getCenterY());
    }

    /**
     * 节点<b>靠左</b>的一点（垂直居中、横向离左边界 15%）。
     *
     * <p>为什么不用中心：en_US 的文案更长，左栏更宽，输入目录框的中心可能已经落到 50% 侧边栏底下
     * （实测 en 下中心 x≈640 > 面板左边界 632），那样这个"左半区控制点"就落进面板里了。
     * 取靠左的点在两个语言下都稳在左半区。</p>
     */
    private static Point2D leftPartOf(Node n) {
        if (n == null || !n.isVisible()) {
            return null;
        }
        Bounds b = n.localToScene(n.getBoundsInLocal());
        return new Point2D(b.getMinX() + b.getWidth() * 0.15, b.getCenterY());
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {        for (Node n = node; n != null; n = n.getParent()) {
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

    private int shellIndexOf(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getParent() == shell) {
                return shell.getChildren().indexOf(n);
            }
        }
        return -1;
    }

    private ModalPane findModalPane() {
        for (Node n : shell.getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        return null;
    }

    private Node findNotification(String message) {
        return findNotification(shell, message);
    }

    private static Node findNotification(Node node, String message) {
        if (node instanceof Notification n && message.equals(n.getMessage())) {
            return n;
        }
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Node hit = findNotification(c, message);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static String describe(Node n) {
        if (n == null) {
            return "null";
        }
        String id = n.getId() != null ? "#" + n.getId() : "";
        return n.getClass().getSimpleName() + id;
    }

    /** 诊断用：打印该点上的命中链（-Dprobe.trace=true 才输出）。 */
    private static void trace(Node node, double sx, double sy, int depth) {
        if (depth >= 3 || !(node instanceof Parent p)) {
            return;
        }
        System.out.println("  ".repeat(depth) + describe(node) + "  visible=" + node.isVisible()
                + " pickOnBounds=" + (node instanceof Region r ? r.isPickOnBounds() : null)
                + " layoutBoundsContains=" + node.getLayoutBounds().contains(node.sceneToLocal(sx, sy)));
        for (Node c : p.getChildrenUnmodifiable()) {
            System.out.println("  ".repeat(depth + 1) + "child " + describe(c) + " contains="
                    + c.getLayoutBounds().contains(c.sceneToLocal(sx, sy)));
        }
        for (Node c : p.getChildrenUnmodifiable()) {
            if (c.getLayoutBounds().contains(c.sceneToLocal(sx, sy))) {
                trace(c, sx, sy, depth + 1);
            }
        }
    }

    private static String fmt(double[] r) {
        return r == null ? "(no overlap)" : String.format("%.0fx%.0f", r[2], r[3]);
    }

    /** 两个 Bounds 的交集（x, y, w, h）；不相交 → null（javafx 的 Bounds 没有 intersection）。 */
    private static double[] overlap(Bounds a, Bounds b) {
        double minX = Math.max(a.getMinX(), b.getMinX());
        double minY = Math.max(a.getMinY(), b.getMinY());
        double maxX = Math.min(a.getMaxX(), b.getMaxX());
        double maxY = Math.min(a.getMaxY(), b.getMaxY());
        return (maxX - minX <= 0 || maxY - minY <= 0)
                ? null : new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    // ==================== gui-state / snapshot ====================

    private String guiStateKeySet() {
        Path p = Path.of("logs", "gui-state.json");
        if (!Files.exists(p)) {
            return "(absent)";
        }
        try {
            JsonNode n = new ObjectMapper().readTree(Files.readString(p));
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            return String.valueOf(keys);
        } catch (Exception e) {
            return "(unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private String localeTag() {
        return String.valueOf(I18n.currentLocale()).replaceAll("[^A-Za-z0-9_]", "");
    }

    private void snapshot(String name) {
        try {
            WritableImage img = scene.snapshot(null);
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            PixelReader pr = img.getPixelReader();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                pr.getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
                bi.setRGB(0, y, w, 1, row, 0, w);
            }
            ImageIO.write(bi, "png", new File(outDir, name + "-" + localeTag() + ".png"));
        } catch (Exception e) {
            System.out.println("snapshot(" + name + ") failed: " + e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
