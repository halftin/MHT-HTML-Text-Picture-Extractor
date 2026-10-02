import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.NordLight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.App;
import com.mhtextractor.config.GuiState;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.gui.controller.ParamFormPane;
import com.mhtextractor.gui.controller.ParamSidePanel;
import com.mhtextractor.i18n.I18n;
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
    private ModalPane modalPane;

    private Point2D leftPoint;
    private Point2D rightPoint;
    private Point2D controlPoint;
    private Node leftPickClosed;
    private Node leftPickOpen;
    private Node rightPickOpen;
    private Node controlPickClosed;

    private boolean draftToggleWas;
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
        timer.schedule(at(10900, this::checkMinWidthTruncation), 10900);
    }

    // ==================== steps ====================

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
        host = SidePanelHost.of(anchor);
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
        System.out.println("fired #sidePanelCollapse (control group: the collapse entry DOES collapse)");
    }

    private void checkCollapsed() {
        check("R1.3 the collapse button collapses the panel (visible=false once the slide finished)",
                !panel.isVisible());
        check("R1.3 the host reports collapsed", host != null && !host.isRightShowing());
        check("R1.5 the draft survives the collapse (isDirty still true)", ParamSidePanel.isDirty(anchor));
        check("R1.5 the edited value survives the collapse",
                ((ToggleSwitch) pane.lookup("#separateFilesCheck")).isSelected() == draftToggleWas);
    }

    private void reopenPanel() {
        paramSettingsButton.fire();
        System.out.println("fired #paramSettingsButton again (same target: nothing selected -> GLOBAL)");
    }

    private void checkReopened() {
        check("R1.5 the panel is visible again (translateX=0)",
                panel.isVisible() && Math.abs(panel.getTranslateX()) <= 0.01);
        check("R1.5 the reopened panel is the SAME ParamFormPane instance (draft state machine kept)",
                panel.getCenter() == pane);
        check("R1.5 the draft is still there after collapse+reopen (no load()/commitDraft() on fold)",
                ParamSidePanel.isDirty(anchor));
        check("R1.5 the edited value is still there",
                ((ToggleSwitch) pane.lookup("#separateFilesCheck")).isSelected() == draftToggleWas);

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
        Object params = field(controller, "textParamsCache");
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
        finish();
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

    // ==================== helpers ====================

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
