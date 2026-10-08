import atlantafx.base.theme.NordLight;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.i18n.I18n;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native-safe (Platform.startup, no Application.launch): real app assembly, auto-shows a confirm card. */
public class AppCardProbe {
    static int passed = 0, failed = 0;
    static void check(String n, boolean ok, String d) {
        if (ok) { passed++; System.out.println("[PASS] " + n); }
        else { failed++; System.out.println("[FAIL] " + n + "  " + d); }
    }
    public static void main(String[] args) throws Exception {
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        System.out.println("[probe] locale=" + I18n.currentLocale());
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
                FXMLLoader loader = new FXMLLoader(AppCardProbe.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
                loader.setControllerFactory(_ -> new MainController(loader));
                loader.setResources(I18n.bundle());
                Parent root = loader.load();
                StackPane overlay = SidePanelHost.install(root);
                StackPane shell = new StackPane(overlay);
                Scene scene = new Scene(shell);
                NotificationHost.install(scene, shell);
                scene.getStylesheets().add(Objects.requireNonNull(AppCardProbe.class.getResource("/custom.css")).toExternalForm());
                Stage stage = new Stage();
                stage.setTitle("AppCardProbe");
                stage.setScene(scene);
                stage.setWidth(1400); stage.setHeight(900);
                stage.show();
                Node anchor0 = scene.lookup("#statusLabel");
                System.out.println("[probe] anchor=" + (anchor0 != null));
                final Node anchor = anchor0 != null ? anchor0 : shell;
                Timeline tl = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
                    NotificationHost.confirm(anchor, "PROBE-CONFIRM-TEXT", FontAwesomeSolid.TRASH_ALT, ok -> { });
                    check("pending decision registered", NotificationHost.hasPendingDecision(anchor), "false");
                }), new KeyFrame(Duration.seconds(4), e -> {
                    Node n = scene.getRoot().lookup(".notification");
                    check("card node present (.notification)", n != null, "lookup -> " + n);
                    if (n != null) {
                        double h = n.getBoundsInParent().getHeight(), w = n.getBoundsInParent().getWidth();
                        check("card w>5", w > 5, "w=" + w + " h=" + h);
                        check("card h>5", h > 5, "h=" + h);
                        check("card visible", n.isVisible(), "visible=" + n.isVisible());
                    }
                    done.countDown();
                }));
                tl.play();
            } catch (Throwable t) { t.printStackTrace(); done.countDown(); }
        });
        done.await(40, TimeUnit.SECONDS);
        System.out.println("RESULTS: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
