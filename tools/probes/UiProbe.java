import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

/**
 * 只读探针：按给定 locale/窗口尺寸加载界面，报告
 * (1) 被截断（文本宽于可用宽）的可见文本、(2) 仍含中文的界面文本、(3) 快照 PNG。
 * -Dprobe.dialog=true 时还会把参数表单本体（ParamFormPane，自加载 ParamDialog.fxml）
 * 放进临时 Stage 并同样体检（D 起模态参数窗口已删，参数编辑只有侧边栏一条路径）。
 */
public class UiProbe extends Application {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Override
    public void start(Stage stage) throws Exception {
        String loc = System.getProperty("probe.locale", "en_US");
        I18n.setLocale(I18n.parseLocale(loc));

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        Scene scene = new Scene(root);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(Double.parseDouble(System.getProperty("probe.w", "1100")));
        stage.setHeight(Double.parseDouble(System.getProperty("probe.h", "700")));
        stage.show();

        if (Boolean.getBoolean("probe.dialog")) {
            new Timer(true).schedule(new TimerTask() {
                public void run() {
                    Platform.runLater(() -> openDialog(stage));
                }
            }, 1200);
        } else {
            Platform.runLater(() -> Platform.runLater(() -> {
                report(scene, System.getProperty("probe.png"));
                Platform.exit();
            }));
        }
    }

    private void openDialog(Stage owner) {
        try {
            // D：模态参数窗口壳已删 —— 直接构造参数表单本体（公开、自加载 ParamDialog.fxml，
            // 与侧边栏同一份 ParamFormPane），按旧模态窗口同尺寸放进临时 Stage 体检其内容节点树。
            com.mhtextractor.gui.controller.ParamFormPane pane =
                    new com.mhtextractor.gui.controller.ParamFormPane();
            pane.load(null, null, false, com.mhtextractor.util.ParamScope.GLOBAL);
            Stage dialog = new Stage();
            dialog.initOwner(owner);
            dialog.setTitle(I18n.get("dialog.title"));
            dialog.setWidth(640);
            dialog.setHeight(620);
            dialog.setMinWidth(520);
            dialog.setMinHeight(480);
            dialog.setScene(new Scene(pane));
            dialog.show();
        } catch (Throwable t) {
            t.printStackTrace();
            Platform.exit();
            return;
        }
        Platform.runLater(() -> Platform.runLater(() -> {
            int i = 0;
            for (Window w : Window.getWindows()) {
                if (w != owner && w.isShowing() && w.getScene() != null) {
                    report(w.getScene(), System.getProperty("probe.png", "shot.png")
                            .replace(".png", "-dialog" + (i++) + ".png"));
                }
            }
            Platform.exit();
        }));
    }

    private void report(Scene scene, String png) {
        List<String> truncated = new ArrayList<>();
        List<String> cjk = new ArrayList<>();
        List<String> rows = new ArrayList<>();

        for (Node n : allNodes(scene.getRoot())) {
            if (n instanceof Labeled lab && lab.getText() != null && !lab.getText().isEmpty()) {
                String text = lab.getText();
                if (CJK.matcher(text).find()) {
                    cjk.add(describe(n) + " = " + text);
                }
                // wrapText 的标签按多行排布，不适用"文本宽 > 可用宽即截断"这一判据
                if (effectiveVisible(lab) && !(lab instanceof javafx.scene.control.Label l2 && l2.isWrapText())) {
                    Text probe = new Text(text);
                    probe.setFont(lab.getFont());
                    double need = probe.getLayoutBounds().getWidth();
                    if (lab.getGraphic() != null) {
                        need += lab.getGraphic().getLayoutBounds().getWidth() + lab.getGraphicTextGap();
                    }
                    if (lab.getPadding() != null) {
                        Insets in = lab.getPadding();
                        need += in.getLeft() + in.getRight();
                    }
                    double have = lab.getWidth();
                    if (have > 0 && need > have + 1.0) {
                        truncated.add(String.format("%-40s need=%.1f have=%.1f  \"%s\"",
                                describe(n), need, have, text));
                    }
                }
            }
            if (n.getId() != null && n.getId().equals("undoButton")) {
                Parent p = n.getParent();
                rows.add(String.format("button-row container %s width=%.1f",
                        p.getClass().getSimpleName(), p.getLayoutBounds().getWidth()));
            }
        }

        System.out.println("=========== " + scene.getRoot().getClass().getSimpleName()
                + " locale=" + I18n.currentLocale()
                + " window=" + scene.getWidth() + "x" + scene.getHeight() + " ===========");
        System.out.println("--- TRUNCATED (" + truncated.size() + ") ---");
        truncated.forEach(s -> System.out.println("  " + s));
        System.out.println("--- CJK-ON-SCREEN (" + cjk.size() + ") ---");
        cjk.forEach(s -> System.out.println("  " + s));
        rows.forEach(s -> System.out.println("  " + s));

        try {
            WritableImage img = scene.snapshot(null);
            File out = new File(png);
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

    private static boolean effectiveVisible(Node n) {
        for (Node c = n; c != null; c = c.getParent()) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Node n) {
        String id = n.getId() != null ? "#" + n.getId() : "";
        String txt = n instanceof Labeled l && l.getText() != null ? "[" + l.getText() + "]" : "";
        return n.getClass().getSimpleName() + id + txt;
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

    public static void main(String[] args) {
        launch(args);
    }
}
