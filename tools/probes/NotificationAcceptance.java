import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.ModalPaneSkin;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acceptance probe for the PRODUCTION NotificationHost (scratch, never shipped).
 *
 * It deliberately does NOT pre-wrap the scene root: the point is to exercise the lazy self-attach path
 * (NotificationHost.of(anchor) when nothing was installed), then drive all four public entry points and
 * fire their buttons programmatically, asserting the callbacks actually deliver the user's choice.
 *
 * Runs headless-ish: needs a display, prints PASS/FAIL lines, writes PNGs, exits non-zero on failure.
 */
public class NotificationAcceptance extends Application {

    private Scene scene;
    private Parent root;
    private Stage stage;
    private int failures = 0;
    private int unverified = 0;
    private boolean escControlDelivered;
    private ModalPaneSkin escSkin;
    private final String escInput = System.getProperty("probe.esc.input", "scroll-pane");
    private final AtomicReference<String> decision = new AtomicReference<>("(none)");

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader));
        loader.setResources(I18n.bundle());
        root = loader.load();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene = new Scene(root);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();
        stage.toFront();
        stage.requestFocus();

        Timer timer = new Timer(true);
        timer.schedule(at(800, this::showToasts), 800);
        timer.schedule(at(1600, this::checkToastsAndShowError), 1600);
        timer.schedule(at(2400, this::checkErrorAndConfirm), 2400);
        timer.schedule(at(3200, this::checkConfirmYes), 3200);
        timer.schedule(at(4000, this::checkConfirmNo), 4000);
        timer.schedule(at(4600, this::pressEscControl), 4600);
        timer.schedule(at(5000, this::checkEscControl), 5000);
        timer.schedule(at(5600, this::pressEscPersistent), 5600);
        timer.schedule(at(6000, this::checkEscPersistent), 6000);
        timer.schedule(at(6500, this::finish), 6500);
    }

    private TimerTask at(long delay, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    // ---------- steps ----------

    private void showToasts() {
        System.out.println("scene root BEFORE any notification: " + scene.getRoot().getClass().getSimpleName());
        NotificationHost.toastWarning(root, "WARN-TOAST-TEXT");
        NotificationHost.toastInfo(root, "INFO-TOAST-TEXT");
    }

    private void checkToastsAndShowError() {
        System.out.println("scene root AFTER lazy attach: " + scene.getRoot().getClass().getSimpleName());
        check("lazy attach wrapped root in StackPane", scene.getRoot() instanceof StackPane);
        check("2 toasts stacked in layer", toastCount() == 2);
        snapshot(".claude-work/uiprobe/acceptance-toasts.png");

        NotificationHost.error(root, "ERROR-CARD-TEXT");
        System.out.println("modal showing after error(): " + modalPane().isDisplay()
                + " msg=" + modalMessage());
    }

    private void checkErrorAndConfirm() {
        check("error() shows ModalPane", modalPane().isDisplay());
        check("error() card carries the message", "ERROR-CARD-TEXT".equals(modalMessage()));
        check("error() card is centered (ModalPane alignment)",
                modalPane().getAlignment() != null && modalPane().getAlignment().name().contains("CENTER"));
        snapshot(".claude-work/uiprobe/acceptance-error.png");

        firePrimary(0);   // OK
        check("error() OK hides the card", !modalPane().isDisplay());

        NotificationHost.confirm(root, "CONFIRM-CARD-TEXT", ok -> decision.set(String.valueOf(ok)));
    }

    private void checkConfirmYes() {
        check("confirm() shows ModalPane", modalPane().isDisplay());
        check("confirm() has OK + Cancel", primaryCount() == 2);
        check("confirm() card is NOT stretched (maxHeight USE_PREF_SIZE)", modalContent().getHeight() < 300);
        snapshot(".claude-work/uiprobe/acceptance-confirm.png");

        decision.set("(none)");
        firePrimary(0);   // OK
        check("confirm() OK delivers true", "true".equals(decision.get()));
        check("confirm() OK hides the card", !modalPane().isDisplay());

        NotificationHost.confirm(root, "CONFIRM-CARD-TEXT-2", ok -> decision.set(String.valueOf(ok)));
    }

    private void checkConfirmNo() {
        decision.set("(none)");
        firePrimary(1);   // Cancel
        check("confirm() Cancel delivers false", "false".equals(decision.get()));
        check("confirm() Cancel hides the card", !modalPane().isDisplay());

        // The same live skin and ESC handler must dismiss with persistent=false.
        // Without this control, keeping the persistent card open is a vacuous pass.
        modalPane().setPersistent(false);
        NotificationHost.confirm(root, "ESC-CONTROL-CARD", ok -> { });
    }

    private void pressEscControl() {
        dispatchEsc();
    }

    private void checkEscControl() {
        escControlDelivered = !modalPane().isDisplay();
        check("ESC control: non-persistent live skin dismisses the card", escControlDelivered);
        modalPane().setPersistent(true);   // production setting
        NotificationHost.confirm(root, "ESC-PERSISTENT-CARD", ok -> { });
        // Negative control changes only the probe's live pane, never production code.
        if (Boolean.getBoolean("probe.esc.breakPersistent")) modalPane().setPersistent(false);
    }

    private void pressEscPersistent() {
        dispatchEsc();
    }

    /**
     * Real key events go to the focus owner; ESC only reaches the pane's handler if focus is inside it.
     * Without this, both the control and the assertion fail/vacuous (measured: neither dismissed).
     */
    private void focusInsideCard() {
        Button b = modalContent().getPrimaryActions().get(0);
        b.requestFocus();
        System.out.println("focus inside card, focusOwner=" + scene.getFocusOwner() + " (button=" + b.getText() + ")");
    }

    private void checkEscPersistent() {
        if (escControlDelivered) {
            boolean open = modalPane().isDisplay();
            check("ESC persistent: the same live skin keeps the card open", open);
            System.out.println("ESC " + escInput + ": " + (open ? "VERIFIED" : "FAILED")
                    + " by non-persistent/persistent control");
        } else {
            warn("ESC persistent: delivery control failed; keeping the card open proves nothing");
        }
        modalPane().setPersistent(true);
        firePrimary(1);   // Cancel, clean up
        check("cleanup: card hidden again", !modalPane().isDisplay());
    }

    /**
     * AtlantaFX 2.1.0 registerListeners() adds its KEY_PRESSED handler to the ScrollPane that is
     * a direct child of ModalPane. Find that node through the public scene graph and dispatch ESC
     * through its normal event chain. Firing at ModalPane would not visit this child handler.
     * This observes synthetic event routing, not OS keyboard delivery; Robot is a separate mode.
     */
    private void dispatchEsc() {
        var pane = modalPane();
        var skin = pane.getSkin();
        check("ESC targets the existing AtlantaFX ModalPaneSkin", skin instanceof ModalPaneSkin);
        if (!(skin instanceof ModalPaneSkin)) return;
        if (escSkin == null) escSkin = (ModalPaneSkin) skin;
        else {
            boolean same = skin == escSkin;
            check("ESC control and persistent check use the same skin instance", same);
            if (!same) return;
        }
        check("ESC starts with a displayed card", pane.isDisplay());
        if ("robot".equals(escInput)) {
            focusInsideCard();
            pressRealEsc();
            return;
        }
        if (!"scroll-pane".equals(escInput)) {
            check("supported ESC input mode: " + escInput, false);
            return;
        }
        var targets = pane.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof ScrollPane).toList();
        check("ESC route has exactly one direct ScrollPane child", targets.size() == 1);
        if (targets.size() != 1) return;
        Node target = targets.get(0);
        var event = new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false);
        boolean persistent = pane.getPersistent();
        boolean delivery = !Boolean.getBoolean("probe.esc.breakDelivery");
        if (delivery) Event.fireEvent(target, event);
        // Dispatch may copy the event; the delayed display assertions prove delivery and persistence.
        System.out.println("ESC INPUT=scroll-pane target=" + target.getClass().getName()
                + " persistent=" + persistent + " dispatched=" + delivery
                + " consumed=" + event.isConsumed() + " display=" + pane.isDisplay());
    }

    /** Real OS key press; this mode must pass its own non-persistent delivery control. */
    private void pressRealEsc() {
        if (!stage.isFocused()) {
            System.out.println("!! ESC TEST SKIPPED: probe window is not focused (result unverified)");
            failures++;
            return;
        }
        javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
        robot.keyPress(javafx.scene.input.KeyCode.ESCAPE);
        robot.keyRelease(javafx.scene.input.KeyCode.ESCAPE);
        System.out.println("pressed real ESC via Robot");
    }

    private void finish() {
        System.out.println("NOTIFICATION ACCEPTANCE: failures=" + failures + " unverified=" + unverified
                + " locale=" + I18n.currentLocale() + " escInput=" + escInput);
        System.out.println(failures == 0 && unverified == 0 ? "ALL CHECKS PASSED" :
                (failures + " CHECK(S) FAILED, " + unverified + " UNVERIFIED"));
        Platform.exit();
        System.exit(failures > 0 ? 1 : unverified > 0 ? 2 : 0);
    }

    // ---------- helpers ----------

    private void check(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
    }

    /** Observations that this harness cannot actually prove are reported, never counted as passes. */
    private void warn(String what) {
        unverified++;
        System.out.println("UNVERIFIED  " + what);
    }

    private ModalPane modalPane() {
        for (Node n : ((StackPane) scene.getRoot()).getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        throw new IllegalStateException("ModalPane not found in shell");
    }

    private Notification modalContent() {
        Node content = modalPane().getContent();
        if (content instanceof Notification ntf) {
            return ntf;
        }
        throw new IllegalStateException("modal content is not a Notification: " + content);
    }

    private String modalMessage() {
        return modalContent().getMessage();
    }

    private int primaryCount() {
        return modalContent().getPrimaryActions().size();
    }

    private void firePrimary(int index) {
        Button b = modalContent().getPrimaryActions().get(index);
        System.out.println("firing primary action " + index + " = " + b.getText());
        b.fire();
    }

    private int toastCount() {
        for (Node n : ((StackPane) scene.getRoot()).getChildren()) {
            if (n instanceof javafx.scene.layout.VBox box && box.getChildren().size() > 0
                    && box.getChildren().get(0) instanceof Notification) {
                return box.getChildren().size();
            }
        }
        return 0;
    }

    private void snapshot(String path) {
        try {
            WritableImage img = scene.snapshot(null);
            File out = new File(new File(System.getProperty("probe.png", "H:/TEST/mht/p4-10-notification/probe.png"))
                    .getAbsoluteFile().getParentFile(), new File(path).getName());
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
            failures++;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
