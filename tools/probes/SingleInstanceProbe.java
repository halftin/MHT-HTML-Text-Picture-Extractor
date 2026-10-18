import com.mhtextractor.App;
import atlantafx.base.controls.Notification;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Runs the production App.start path and observes only public scene graph state. */
public final class SingleInstanceProbe extends Application {
    private boolean observed;

    @Override
    public void start(Stage stage) throws Exception {
        new App().start(stage);
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(100), _ -> {
            if (observed || !stage.isShowing() || stage.getScene() == null) return;
            if (stage.getScene().lookup("#statusLabel") != null) {
                observed = true;
                System.out.println("MAIN_VISIBLE");
                // The harness requests a regular window close after its concurrent checks.
                Thread close = new Thread(() -> {
                    try { System.in.read(); }
                    catch (java.io.IOException error) { error.printStackTrace(); }
                    Platform.runLater(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
                }, "single-instance-probe-close");
                close.setDaemon(true);
                close.start();
            } else if (containsNotice(stage.getScene().getRoot())) {
                observed = true;
                System.out.println("NOTICE_VISIBLE: " + com.mhtextractor.i18n.Texts.get("app.alreadyRunning"));
            }
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
    }

    private static boolean containsNotice(Node node) {
        if (node instanceof Notification notice && notice.isVisible()
                && notice.getWidth() > 0 && notice.getHeight() > 0 && notice.localToScreen(notice.getBoundsInLocal()) != null
                && com.mhtextractor.i18n.Texts.get("app.alreadyRunning").equals(notice.getMessage())) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) if (containsNotice(child)) return true;
        }
        return false;
    }

    public static void main(String[] args) { launch(args); }
}
