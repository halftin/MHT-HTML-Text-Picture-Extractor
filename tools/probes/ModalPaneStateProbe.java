import atlantafx.base.controls.Message;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Minimal state-machine probe (scratch): what does ModalPane actually do on show()/hide()/show()
 * with a Notification vs a Message? Prints (display, opacity, viewOrder, topViewOrder) each step.
 */
public class ModalPaneStateProbe extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        StackPane shell = new StackPane(new javafx.scene.control.Label("content"));
        ModalPane mp = new ModalPane();
        mp.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        mp.setPersistent(true);
        shell.getChildren().add(mp);
        stage.setScene(new Scene(shell, 600, 300));
        stage.show();

        Platform.runLater(() -> {
            System.out.println("--- Notification ---");
            Notification ntf = new Notification("NOTIF", null);
            ntf.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            step("before show", mp);
            mp.show(ntf);
            step("after show", mp);
            mp.hide();
            step("after hide", mp);
            mp.show(ntf);
            step("after show again", mp);

            System.out.println("--- Message ---");
            Message msg = new Message("TITLE", "LINE-ONE\nLINE-TWO", null);
            msg.setMaxWidth(400);
            mp.setContent(msg);
            step("after setContent(msg) [display was true]", mp);
            mp.show(msg);
            step("after show(msg)", mp);
            mp.hide();
            step("after hide", mp);
            mp.show(msg);
            step("after show(msg) again", mp);
            System.out.println("msg layout: " + msg.getWidth() + "x" + msg.getHeight()
                    + " opacity=" + msg.getOpacity() + " visible=" + msg.isVisible()
                    + " parent=" + (msg.getParent() == null ? "null" : msg.getParent().getClass().getSimpleName()));

            Platform.exit();
            System.exit(0);
        });
    }

    private static void step(String what, ModalPane mp) {
        System.out.printf("%-42s display=%-5s opacity=%.2f viewOrder=%.1f topViewOrder=%d contentParent=%s%n",
                what, mp.isDisplay(), mp.getOpacity(), mp.getViewOrder(), mp.getTopViewOrder(),
                mp.getContent() == null ? "null" : String.valueOf(mp.getContent().getParent() != null));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
