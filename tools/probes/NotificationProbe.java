import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.Styles;
import atlantafx.base.util.Animations;
import com.mhtextractor.App;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Read-only probe (scratch): where can a Notification live in THIS app's scene?
 *
 * -Dprobe.placement=corner|center|modalpane|control
 *    corner    : top-right VBox layer (previous experiment)
 *    center    : same layer, StackPane.setAlignment(CENTER) -- plain centered overlay
 *    modalpane : atlantafx.base.controls.ModalPane.show(notification) -- centered + dim backdrop
 *    control   : no overlay at all (control run for the robot click test)
 * -Dprobe.robot=true : Robot-clicks the BACKGROUND-TARGET button and reports whether it fired,
 *                      which answers "does this placement block interaction with the app behind it?"
 */
public class NotificationProbe extends Application {

    private final String placement = System.getProperty("probe.placement", "corner");
    private final VBox layer = new VBox(10);
    private Button bgTarget;
    private ModalPane modalPane;
    private Notification shown;
    private Stage stage;
    private Scene scene;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> ProbeRuntime.controller(loader));
        loader.setResources(I18n.bundle());
        Parent content = loader.load();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());

        StackPane shell = new StackPane(content);

        // a plain app button sitting UNDER the overlay -- the robot click target
        bgTarget = new Button("BACKGROUND-TARGET");
        bgTarget.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bgTarget.setOnAction(e -> System.out.println(">>> FIRED: BACKGROUND-TARGET (app button behind overlay)"));
        StackPane.setAlignment(bgTarget, Pos.BOTTOM_LEFT);
        shell.getChildren().add(bgTarget);

        if ("modalpane".equals(placement)) {
            modalPane = new ModalPane();
            modalPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            modalPane.setAlignment(Pos.CENTER);
            modalPane.setPersistent(true);   // isolate "does the backdrop block clicks" from "click closes it"
            shell.getChildren().add(modalPane);
        } else {
            layer.setPickOnBounds(false);
            shell.getChildren().add(layer);
        }

        scene = new Scene(shell);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(Double.parseDouble(System.getProperty("probe.w", "1280")));
        stage.setHeight(Double.parseDouble(System.getProperty("probe.h", "800")));
        stage.show();
        stage.toFront();
        stage.requestFocus();

        Timer timer = new Timer(true);
        timer.schedule(task(this::showOverlay), 900);
        timer.schedule(task(this::robotTest), 2100);
        timer.schedule(task(() -> { report(); Platform.exit(); }), 2700);
    }

    private TimerTask task(Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    private void showOverlay() {
        switch (placement) {
            case "control" -> System.out.println("placement=control (no overlay)");
            case "corner" -> {
                layer.setAlignment(Pos.TOP_RIGHT);
                StackPane.setAlignment(layer, Pos.TOP_RIGHT);
                layer.setPadding(new Insets(12));
                layer.getChildren().addAll(confirmNotification(this::removeFromLayer),
                        warningNotification(this::removeFromLayer));
            }
            case "center" -> {
                layer.setAlignment(Pos.CENTER);
                StackPane.setAlignment(layer, Pos.CENTER);
                layer.getChildren().add(confirmNotification(this::removeFromLayer));
            }
            case "modalpane" -> {
                Notification n = confirmNotification(x -> modalPane.hide());
                if (Boolean.getBoolean("probe.constrain")) {
                    n.setMaxHeight(Region.USE_PREF_SIZE);   // does this stop ModalPane stretching it?
                    System.out.println("applied ntf.setMaxHeight(USE_PREF_SIZE)");
                }
                shown = n;
                modalPane.show(n);
            }
            default -> throw new IllegalArgumentException("unknown placement: " + placement);
        }
    }

    private void removeFromLayer(Node n) {
        ((Pane) n.getParent()).getChildren().remove(n);
        System.out.println("dismissed notification; layer size = " + layer.getChildren().size());
    }

    /** Real OS-level click at the background button: only meaningful if our window has focus. */
    private void robotTest() {
        if (!Boolean.getBoolean("probe.robot")) {
            return;
        }
        if (!stage.isFocused()) {
            System.out.println("ROBOT TEST SKIPPED: probe window is not focused (cannot attribute a click)");
            return;
        }
        Point2D p = bgTarget.localToScreen(bgTarget.getBoundsInLocal().getCenterX(),
                bgTarget.getBoundsInLocal().getCenterY());
        System.out.printf("ROBOT: clicking BACKGROUND-TARGET at screen (%.0f, %.0f) with placement=%s%n",
                p.getX(), p.getY(), placement);
        Robot robot = new Robot();
        robot.mouseMove(p);
        robot.mouseClick(MouseButton.PRIMARY);
    }

    private Notification confirmNotification(Consumer<Node> onDecision) {
        Notification ntf = new Notification(
                I18n.get("list.removeAllConfirm", "count", 3) + I18n.get("group.removeNoteCollapsed"),
                new FontIcon(FontAwesomeSolid.QUESTION_CIRCLE));
        ntf.getStyleClass().add(Styles.ELEVATED_1);
        ntf.setPrefWidth(400);

        Button yes = new Button(I18n.get("param.ok"));
        Button no = new Button(I18n.get("param.cancel"));
        yes.setOnAction(e -> {
            System.out.println(">>> FIRED: notification YES");
            onDecision.accept(ntf);
        });
        no.setOnAction(e -> {
            System.out.println(">>> FIRED: notification NO");
            onDecision.accept(ntf);
        });

        ntf.setPrimaryActions(yes, no);
        ntf.setSecondaryActions(new MenuItem(I18n.get("param.cancel")), new MenuItem(I18n.get("param.ok")));
        ntf.setOnClose(e -> Animations.flash(ntf).playFromStart());
        return ntf;
    }

    private Notification warningNotification(Consumer<Node> onDecision) {
        Notification ntf = new Notification(
                I18n.get("cache.clearingBeforeExit"),
                new FontIcon(FontAwesomeSolid.EXCLAMATION_TRIANGLE));
        ntf.getStyleClass().addAll(Styles.WARNING, Styles.ELEVATED_1);
        ntf.setPrefWidth(400);
        Button ok = new Button(I18n.get("param.ok"));
        ok.setOnAction(e -> {
            System.out.println(">>> FIRED: notification OK");
            onDecision.accept(ntf);
        });
        ntf.setPrimaryActions(ok);
        ntf.setOnClose(e -> Animations.flash(ntf).playFromStart());
        return ntf;
    }

    private void report() {
        System.out.println("=========== placement=" + placement
                + " locale=" + I18n.currentLocale()
                + " scene=" + scene.getWidth() + "x" + scene.getHeight()
                + " layer=" + layer.getChildren().size()
                + " modalPaneShowing=" + (modalPane != null && modalPane.isDisplay())
                + " ===========");
        if (shown != null) {
            System.out.printf("notification box: %.1f x %.1f  (constrain=%s)%n",
                    shown.getWidth(), shown.getHeight(), System.getProperty("probe.constrain"));
        }
        try {
            WritableImage img = scene.snapshot(null);
            File out = new File(System.getProperty("probe.png",
                    ".claude-work/uiprobe/notification-" + placement + ".png"));
            out.getParentFile().mkdirs();
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            PixelReader pr = img.getPixelReader();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                pr.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
                bi.setRGB(0, y, w, 1, row, 0, w);
            }
            ImageIO.write(bi, "png", out);
            System.out.println("snapshot -> " + out.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
