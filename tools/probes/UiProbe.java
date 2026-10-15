import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.i18n.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

/**
 * 只读探针：按给定 locale/窗口尺寸加载界面，报告
 * (1) 被截断（文本宽于可用宽）的可见文本、(2) 仍含中文的界面文本、(3) 快照 PNG。
 * -Dprobe.dialog=true 时还会把参数表单本体（ParamFormPane，自加载 ParamDialog.fxml）
 * 放进临时 Stage 并同样体检（D 起模态参数窗口已删，参数编辑只有侧边栏一条路径）。
 * -Dprobe.reopen=true 时改为<b>悬浮圆钮几何体检</b>（步骤 4 · 决策 H：四档窗口 × zh/en
 * 断言圆钮不截断、不压住内容与滚动条；任一失败 exit 1）。
 */
public class UiProbe extends Application {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Override
    public void start(Stage stage) throws Exception {
        String loc = System.getProperty("probe.locale", "en_US");
        I18n.setLocale(I18n.parseLocale(loc));
        boolean reopen = Boolean.getBoolean("probe.reopen");

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        loader.setControllerFactory(_ -> ProbeRuntime.controller(loader));
        loader.setResources(I18n.bundle());
        Parent root = loader.load();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        Scene scene;
        if (reopen) {
            // 决策 H 的几何体检：装配与 App.start()/SidePanelProbe 逐句同构（生产层序）——
            // 圆钮是覆盖层的真节点、与侧边栏同层（决策 2.12）
            StackPane overlay = SidePanelHost.install(root);
            StackPane shell = new StackPane(overlay);
            scene = new Scene(shell);
            NotificationHost.install(scene, shell);
        } else {
            scene = new Scene(root);
        }
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(Double.parseDouble(System.getProperty("probe.w", "1100")));
        stage.setHeight(Double.parseDouble(System.getProperty("probe.h", "700")));
        stage.show();

        String input = System.getProperty("probe.input");
        if (input != null) {
            input = java.nio.file.Path.of(input).toString();
            ((javafx.scene.control.ToggleButton) scene.lookup("#textModeToggle")).setSelected(true);
            ((javafx.scene.control.TextField) scene.lookup("#inputFolderField")).setText(input);
            waitForSource(scene, input, System.currentTimeMillis() + 15000);
            return;
        }

        if (reopen) {
            runReopenCheck(scene);
        } else if (Boolean.getBoolean("probe.dialog")) {
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

    private void waitForSource(Scene scene, String input, long deadline) {
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(100));
        wait.setOnFinished(_ -> {
            try {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var sources = scene.getRoot().lookupAll(".preview-source").stream()
                        .filter(n -> n instanceof javafx.scene.control.Label label
                                && input.equals(label.getText()) && effectiveVisible(n)).toList();
                if (sources.isEmpty() && System.currentTimeMillis() < deadline) {
                    waitForSource(scene, input, deadline);
                    return;
                }
                if (sources.isEmpty()) {
                    writeSnapshot(scene, System.getProperty("probe.png"));
                    for (String id : List.of("previewHorizontalScroll", "fileListView", "previewColumnHeader")) {
                        Node node = scene.lookup("#" + id);
                        System.out.println("COLUMN_GEOMETRY " + id + " " + node.getLayoutBounds());
                    }
                    throw new IllegalStateException("No visible source matches input " + input);
                }
                var header = (Labeled) scene.lookup("#sourceColumnHeader");
                for (Node node : sources) {
                    var label = (javafx.scene.control.Label) node;
                    Bounds a = header.localToScene(header.getLayoutBounds());
                    Bounds b = label.localToScene(label.getLayoutBounds());
                    if (Math.abs(a.getMinX() - b.getMinX()) > 1 || Math.abs(a.getWidth() - b.getWidth()) > 1)
                        throw new IllegalStateException("Source/header misaligned: " + a + " / " + b);
                    if (label.getTooltip() == null || !input.equals(label.getTooltip().getText()))
                        throw new IllegalStateException("Source tooltip does not preserve full input");
                    System.out.println("SOURCE aligned x=" + b.getMinX() + " width=" + b.getWidth()
                            + " ellipsized=" + label.isTextTruncated() + " tooltip=" + label.getTooltip().getText());
                }
                report(scene, System.getProperty("probe.png"));
                if (Boolean.getBoolean("probe.resize")) {
                    checkResize(scene);
                    return;
                }
                Platform.exit();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
        wait.play();
    }

    /**
     * 决策 H：四档窗口 × zh/en 的圆钮几何体检。步序走真实用户路径：
     * ① 切文本模式并打开侧边栏（首次滑入）；② 面板展开时断言圆钮<b>隐藏</b>（不压住编辑内容），
     * 然后点收起（面板干净 → 不弹 toast）⇒ 圆钮显示；③ 给文本区灌长文本让它出<b>真实滚动条</b>
     * （不压住滚动条的断言不落空）；④ 体检几何：完整在窗口内（不截断/不出界）、不与任何可见
     * 滚动条重叠（不压住滚动条）、不与「提取参数设置」/模式行/文件列表重叠（不压住关键控件）。
     * 任一失败 → exit 1。
     */
    private void runReopenCheck(Scene scene) {
        List<String> problems = new ArrayList<>();
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    javafx.scene.control.ToggleButton text =
                            (javafx.scene.control.ToggleButton) scene.lookup("#textModeToggle");
                    if (text != null) {
                        text.setSelected(true);
                    }
                    javafx.scene.control.Button psb =
                            (javafx.scene.control.Button) scene.lookup("#paramSettingsButton");
                    if (psb != null) {
                        psb.fire();
                    }
                });
            }
        }, 800);
        timer.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    Node reopen = scene.lookup("#sidePanelReopen");
                    if (reopen != null && reopen.isVisible()) {
                        problems.add("reopen button visible WHILE the panel is open"
                                + " (must not press the editing content)");
                    }
                    javafx.scene.control.Button collapse =
                            (javafx.scene.control.Button) scene.lookup("#sidePanelCollapse");
                    if (collapse != null) {
                        collapse.fire();
                    }
                });
            }
        }, 1600);
        timer.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    javafx.scene.control.TextArea ta =
                            (javafx.scene.control.TextArea) scene.lookup("#textPreviewArea");
                    if (ta != null) {
                        ta.setText(String.join("\n", Collections.nCopies(300, "reopen-geometry-line")));
                    }
                });
            }
        }, 2400);
        timer.schedule(new TimerTask() {
            public void run() {
                Platform.runLater(() -> {
                    reportReopen(scene, System.getProperty("probe.png"), problems);
                    Platform.exit();
                    System.exit(problems.isEmpty() ? 0 : 1);
                });
            }
        }, 3600);
    }

    /** 圆钮几何体检本体：见 {@link #runReopenCheck} 的判据清单。 */
    private void reportReopen(Scene scene, String png, List<String> problems) {
        Node reopen = scene.lookup("#sidePanelReopen");
        // 覆盖层 = shell 的第一个子节点（SidePanelHost.install 的返回值，生产层序）
        Node overlay = scene.getRoot() instanceof StackPane sp && !sp.getChildren().isEmpty()
                ? sp.getChildren().get(0) : null;
        System.out.println("=========== REOPEN-BUTTON locale=" + I18n.currentLocale()
                + " window=" + scene.getWidth() + "x" + scene.getHeight() + " ===========");
        if (reopen == null || overlay == null) {
            problems.add("reopen button or overlay missing");
            problems.forEach(s -> System.out.println("  PROBLEM  " + s));
            return;
        }
        Bounds rb = reopen.localToScene(reopen.getBoundsInLocal());
        Bounds ob = overlay.localToScene(overlay.getBoundsInLocal());
        System.out.println("reopen bounds=" + fmtBounds(rb) + "  overlay bounds=" + fmtBounds(ob)
                + "  size=" + String.format("%.0fx%.0f",
                reopen.getLayoutBounds().getWidth(), reopen.getLayoutBounds().getHeight()));
        if (!reopen.isVisible()) {
            problems.add("reopen button not visible after collapse");
        }
        if (rb.getWidth() <= 1 || rb.getHeight() <= 1) {
            problems.add("reopen button has no usable size");
        }
        if (!ob.contains(rb)) {
            problems.add("reopen button not fully inside the overlay (TRUNCATED / out of bounds)");
        }
        // 不压住滚动条：与任何"有效可见"的滚动条节点重叠即失败（文本区已灌长文本 ⇒ 滚动条是真实的）
        for (Node n : allNodes(scene.getRoot())) {
            if (n.getStyleClass().contains("scroll-bar") && effectiveVisible(n)) {
                Bounds sb = n.localToScene(n.getBoundsInLocal());
                if (sb.getWidth() > 0 && sb.getHeight() > 0 && intersects(rb, sb)) {
                    problems.add("reopen button overlaps scroll-bar " + describe(n) + " " + fmtBounds(sb));
                }
            }
        }
        // 不压住关键控件
        for (String id : new String[]{"paramSettingsButton", "modeLabel", "fileListView"}) {
            Node c = scene.lookup("#" + id);
            if (c != null && effectiveVisible(c)) {
                Bounds cb = c.localToScene(c.getBoundsInLocal());
                if (intersects(rb, cb)) {
                    problems.add("reopen button overlaps #" + id + " " + fmtBounds(cb));
                }
            }
        }
        System.out.println("--- REOPEN PROBLEMS (" + problems.size() + ") ---");
        problems.forEach(s -> System.out.println("  PROBLEM  " + s));
        writeSnapshot(scene, png);
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return a.getMaxX() > b.getMinX() && b.getMaxX() > a.getMinX()
                && a.getMaxY() > b.getMinY() && b.getMaxY() > a.getMinY();
    }

    private static String fmtBounds(Bounds b) {
        return String.format("[%.0f..%.0f]x[%.0f..%.0f]", b.getMinX(), b.getMaxX(), b.getMinY(), b.getMaxY());
    }

    private void openDialog(Stage owner) {
        try {
            // D：模态参数窗口壳已删 —— 直接构造参数表单本体（公开、自加载 ParamDialog.fxml，
            // 与侧边栏同一份 ParamFormPane），按旧模态窗口同尺寸放进临时 Stage 体检其内容节点树。
            com.mhtextractor.gui.controller.ParamFormPane pane =
                    new com.mhtextractor.gui.controller.ParamFormPane(ProbeRuntime.port());
            pane.load(null, null, false, com.mhtextractor.uicore.ParamScope.GLOBAL);
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

    private static void requireColumn(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void checkDefaultColumns(Scene scene) {
        var saved = com.mhtextractor.uicore.GuiStateManager.load();
        var name = (Labeled) scene.lookup("#nameColumnHeader");
        var source = (Labeled) scene.lookup("#sourceColumnHeader");
        if (saved != null && saved.getNameColumnWidth() != null) {
            requireColumn(Math.abs(name.getWidth() - saved.getNameColumnWidth()) <= 1
                    && Math.abs(source.getWidth() - saved.getSourceColumnWidth()) <= 1,
                    "Restored column widths differ from JSON");
            System.out.println("COLUMN_RESTORED name=" + name.getWidth() + " source=" + source.getWidth());
            return;
        }
        requireColumn(name.getWidth() > 0 && Math.abs(name.getWidth() - source.getWidth()) <= 1,
                "Default columns must split available space 50/50: " + name.getWidth() + "/" + source.getWidth());
        var header = (javafx.scene.layout.HBox) scene.lookup("#previewColumnHeader");
        var scroll = (javafx.scene.control.ScrollPane) scene.lookup("#previewHorizontalScroll");
        double fixed = header.getPadding().getLeft() + header.getPadding().getRight();
        int managed = 0;
        for (Node child : header.getChildren()) {
            if (!child.isManaged()) continue;
            managed++;
            if (child != name.getParent() && child != source.getParent()) fixed += child.getLayoutBounds().getWidth();
        }
        double available = scroll.getViewportBounds().getWidth() - fixed - header.getSpacing() * (managed - 1);
        requireColumn(Math.abs(name.getWidth() - available * 0.5) <= 1
                && Math.abs(source.getWidth() - available * 0.5) <= 1,
                "Default columns must each occupy half the viewport's draggable area: " + available);
        System.out.println("COLUMN_DEFAULT name=" + name.getWidth() + " source=" + source.getWidth()
                + " available=" + available + " nameShare=" + name.getWidth() / available);
    }

    private static void columnLayout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static void checkAligned(Scene scene) {
        var list = (javafx.scene.control.ListView<?>) scene.lookup("#fileListView");
        int count = 0;
        for (Node node : list.lookupAll(".list-cell")) {
            if (!(node instanceof javafx.scene.control.ListCell<?> cell) || cell.isEmpty() || !cell.isVisible()) continue;
            for (String column : List.of("name", "source")) {
                var header = scene.lookup("#" + column + "ColumnHeader");
                var label = cell.lookup(".preview-" + column);
                requireColumn(label != null, "Missing column " + column);
                Bounds a = header.localToScene(header.getLayoutBounds());
                Bounds b = label.localToScene(label.getLayoutBounds());
                requireColumn(Math.abs(a.getMinX() - b.getMinX()) <= 1 && Math.abs(a.getWidth() - b.getWidth()) <= 1,
                        "Column alignment " + column + ": " + a + " / " + b);
            }
            count++;
        }
        requireColumn(count > 0, "No virtualized cells inspected");
        System.out.println("COLUMN_ALIGNMENT rows=" + count);
    }

    private static void mouse(Node node, javafx.event.EventType<javafx.scene.input.MouseEvent> type, double x) {
        javafx.event.Event.fireEvent(node, new javafx.scene.input.MouseEvent(type,
                2, 2, x, 100, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true,
                new javafx.scene.input.PickResult(node, 2, 2)));
    }

    private static void checkResize(Scene scene) {
        var scroll = (javafx.scene.control.ScrollPane) scene.lookup("#previewHorizontalScroll");
        var name = (Labeled) scene.lookup("#nameColumnHeader");
        var source = (Labeled) scene.lookup("#sourceColumnHeader");
        double initialName = name.getWidth(), initialSource = source.getWidth();
        var order = (Labeled) scene.lookup("#orderColumnHeader");
        var status = (Labeled) scene.lookup("#statusColumnHeader");
        double orderWidth = order.getWidth(), statusWidth = status.getWidth();
        for (String id : List.of("nameColumnResize", "sourceColumnResize")) {
            Node grip = scene.lookup("#" + id);
            requireColumn(grip.getCursor() == javafx.scene.Cursor.H_RESIZE, "Resize cursor " + id);
            mouse(grip, javafx.scene.input.MouseEvent.MOUSE_PRESSED, 100);
            mouse(grip, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, 700);
            mouse(grip, javafx.scene.input.MouseEvent.MOUSE_RELEASED, 700);
            columnLayout(scene);
        }
        requireColumn(Math.abs(name.getWidth() - initialName - 600) <= 1, "Name drag did not resize");
        requireColumn(Math.abs(source.getWidth() - initialSource - 600) <= 1, "Source drag did not resize");
        requireColumn(order.getWidth() == orderWidth && status.getWidth() == statusWidth,
                "Fixed columns changed width during drag");
        checkAligned(scene);
        boolean horizontal = scroll.lookupAll(".scroll-bar").stream().anyMatch(n ->
                n instanceof javafx.scene.control.ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                        && bar.isVisible() && bar.getMax() > bar.getMin());
        requireColumn(horizontal, "Overflow must show horizontal scrollbar");
        double x = name.localToScene(name.getLayoutBounds()).getMinX();
        scroll.setHvalue(0.65);
        columnLayout(scene);
        double shifted = name.localToScene(name.getLayoutBounds()).getMinX();
        double expected = (scroll.getContent().getLayoutBounds().getWidth() - scroll.getViewportBounds().getWidth()) * 0.65;
        requireColumn(expected > 0 && Math.abs((x - shifted) - expected) <= 1, "Header scroll offset mismatch");
        checkAligned(scene);
        var list = (javafx.scene.control.ListView<?>) scene.lookup("#fileListView");
        requireColumn(list.getItems().size() >= 50, "Resize probe requires at least 50 rows for virtualization");
        list.scrollTo(list.getItems().size() - 1);
        list.lookupAll(".scroll-bar").stream().filter(n -> n instanceof javafx.scene.control.ScrollBar bar
                && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                .map(n -> (javafx.scene.control.ScrollBar) n).forEach(bar -> bar.setValue(bar.getMax()));
        // VirtualFlow processes scrollTo on the next pulse; inspect the recycled cells afterwards.
        var pulse = new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
        pulse.setOnFinished(_ -> {
            try {
                columnLayout(scene);
                requireColumn(list.lookupAll(".list-cell").stream().anyMatch(n ->
                        n instanceof javafx.scene.control.ListCell<?> c && !c.isEmpty()
                                && c.getIndex() == list.getItems().size() - 1), "Last row was not virtualized into view: "
                        + list.lookupAll(".list-cell").stream().map(n -> {
                            var c = (javafx.scene.control.ListCell<?>) n;
                            return c.getIndex() + "/empty=" + c.isEmpty() + "/visible=" + c.isVisible();
                        }).toList());
                checkAligned(scene);
                ((javafx.scene.control.TextField) scene.lookup("#outputFolderField"))
                        .setText("H:/TEST/mht/p5b3/output");
                var saved = com.mhtextractor.uicore.GuiStateManager.load();
                requireColumn(saved != null && Math.abs(saved.getNameColumnWidth() - name.getWidth()) <= 1
                        && Math.abs(saved.getSourceColumnWidth() - source.getWidth()) <= 1, "Persisted columns differ from dragged widths");
                System.out.println("COLUMN_RESIZE name=" + name.getWidth() + " source=" + source.getWidth()
                        + " hvalue=" + scroll.getHvalue() + " headerDelta=" + (x - shifted) + " expectedDelta=" + expected
                        + " scrollbar=true persistence=true virtualReuse=true lastIndex=" + (list.getItems().size() - 1));
                Platform.exit();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
        pulse.play();
    }

    private void report(Scene scene, String png) {
        checkDefaultColumns(scene);
        List<String> truncated = new ArrayList<>();
        List<String> cjk = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<String> ellipsized = new ArrayList<>();

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
                        String detail = String.format("%-40s need=%.1f have=%.1f  \"%s\"", describe(n), need, have, text);
                        // Source paths deliberately elide; only a working ellipsis + full tooltip qualifies.
                        if (lab.getStyleClass().contains("preview-source") && lab.isTextTruncated()
                                && lab.getTextOverrun() == javafx.scene.control.OverrunStyle.ELLIPSIS
                                && lab.getTooltip() != null && text.equals(lab.getTooltip().getText())) {
                            ellipsized.add(detail);
                        } else {
                            truncated.add(detail);
                        }
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
        System.out.println("--- INTENTIONAL SOURCE ELLIPSIS (" + ellipsized.size() + ") ---");
        ellipsized.forEach(s -> System.out.println("  " + s));
        System.out.println("--- CJK-ON-SCREEN (" + cjk.size() + ") ---");
        cjk.forEach(s -> System.out.println("  " + s));
        rows.forEach(s -> System.out.println("  " + s));

        writeSnapshot(scene, png);
    }

    /** 场景快照落盘（主体检与圆钮体检共用）。 */
    private static void writeSnapshot(Scene scene, String png) {
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
