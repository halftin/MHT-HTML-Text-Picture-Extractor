import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.ErrorCode;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/** Renders the per-purpose icon set with REAL production call sites (scratch, never shipped). */
public class IconShowcaseProbe extends Application {

    private Scene scene;
    private Parent root;

    @Override
    public void start(Stage stage) throws Exception {
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        loader.setResources(I18n.bundle());
        root = loader.load();
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene = new Scene(root);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();

        Timer t = new Timer(true);
        t.schedule(at(500, this::showToasts), 500);
        t.schedule(at(1200, () -> snapshot(".claude-work/uiprobe/icons-toasts.png")), 1200);
        t.schedule(at(1400, this::showError), 1400);
        t.schedule(at(1900, () -> {
            snapshot(".claude-work/uiprobe/icons-error.png");
            fire(0);
        }), 1900);
        t.schedule(at(2200, this::showConfirm), 2200);
        t.schedule(at(2700, () -> {
            snapshot(".claude-work/uiprobe/icons-confirm.png");
            fire(1);
        }), 2700);
        t.schedule(at(3000, this::showBusy), 3000);
        t.schedule(at(3600, () -> snapshot(".claude-work/uiprobe/icons-busy.png")), 3600);
        t.schedule(at(6000, () -> {
            System.out.println("SHOWCASE DONE");
            Platform.exit();
            System.exit(0);
        }), 6000);
    }

    private TimerTask at(long d, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    /** 4 张不同用途的 toast 叠在右上角 —— 一眼看出"图标跟用途走"。 */
    private void showToasts() {
        NotificationHost.toastWarning(root, ErrorCode.FILE_MISSING.message("path", "D:\\mht\\sample.mht"),
                FontAwesomeSolid.FILE);
        NotificationHost.toastWarning(root, ErrorCode.BROWSER_LAUNCH_FAILED.message("file", "view.html"),
                FontAwesomeSolid.GLOBE);
        NotificationHost.toastWarning(root, ErrorCode.FILE_DIR_MISSING.message("path", "D:\\mht"),
                FontAwesomeSolid.FOLDER_OPEN);
        NotificationHost.toastInfo(root, ErrorCode.KEEP_AT_LEAST_ONE.message(), FontAwesomeSolid.LIST);
    }

    private void showError() {
        NotificationHost.error(root, ErrorCode.IMPORT_FAILED.message(), FontAwesomeSolid.FILE_IMPORT);
    }

    private void showConfirm() {
        NotificationHost.confirm(root, I18n.get("extract.confirmParamsUnset"),
                FontAwesomeSolid.SLIDERS_H, ok -> System.out.println("confirm decision=" + ok));
    }

    private void showBusy() {
        NotificationHost.busy(root, I18n.get("cache.clearingTitle"),
                I18n.get("cache.clearingBeforeExit") + "\n"
                        + java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "mht-extractor-preview"),
                FontAwesomeSolid.BROOM,
                () -> sleep(2200),   // 必须比"截图时刻 - 显示时刻"长：卡片会自动收起（本探针第一版就踩了这个坑）
                () -> System.out.println("busy onDone"));
    }

    private void fire(int index) {
        ModalPane mp = modalPane();
        if (mp.getContent() instanceof Notification ntf && ntf.getPrimaryActions().size() > index) {
            ntf.getPrimaryActions().get(index).fire();
        }
    }

    private ModalPane modalPane() {
        for (Node n : ((StackPane) scene.getRoot()).getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        throw new IllegalStateException("no ModalPane");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void snapshot(String path) {
        try {
            WritableImage img = scene.snapshot(null);
            File out = new File(path);
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
