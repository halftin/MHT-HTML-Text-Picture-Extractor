import atlantafx.base.controls.Message;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.gui.component.NotificationHost;
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
import javafx.stage.WindowEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Acceptance probe for the NEW close flow (scratch, never shipped):
 *
 * <p>Part A/B: {@code NotificationHost.busy} — card appears (Message, zero interactive controls),
 * auto-hides on completion, {@code onDone} runs on the FX thread, and a THROWING worker still
 * reaches {@code onDone} (exit path must never hang).</p>
 *
 * <p>Part C: the REAL end-to-end window-close flow against the real MainController — seed a cache
 * entry, fire WINDOW_CLOSE_REQUEST, and check: wait card shown -> cache directory emptied ->
 * card gone -> stage closed, all without any click.</p>
 */
public class BusyCloseProbe extends Application {

    private Scene scene;
    private Parent root;
    private Stage stage;
    private int failures = 0;

    private final AtomicBoolean workDone = new AtomicBoolean();
    private final AtomicReference<String> workThread = new AtomicReference<>("(none)");
    private final AtomicBoolean onDoneRan = new AtomicBoolean();
    private final AtomicReference<String> onDoneThread = new AtomicReference<>("(none)");
    private final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);

    private Path CACHE_ROOT;
    private Path MARKER_DIR;
    private com.mhtextractor.uicore.FrontendPort frontend;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Platform.setImplicitExit(false);   // 关窗后还要继续断言，别让隐式退出把探针带走

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        frontend = ProbeRuntime.port();
        CACHE_ROOT = Path.of(frontend.queries().cacheState().root());
        MARKER_DIR = CACHE_ROOT.resolve("probe-marker");
        loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader, frontend,
                finished -> ProbeRuntime.close(frontend, finished)));
        loader.setResources(I18n.bundle());
        root = loader.load();

        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene = new Scene(root);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();

        Timer timer = new Timer(true);
        timer.schedule(at(600, this::startBusyOK), 600);
        timer.schedule(at(900, this::checkBusyVisible), 900);
        timer.schedule(at(1800, this::checkBusyFinished), 1800);
        timer.schedule(at(2100, this::startBusyThrowing), 2100);
        timer.schedule(at(2800, this::checkThrowingFinished), 2800);
        timer.schedule(at(3200, this::startRealCloseFlow), 3200);
        timer.schedule(at(3600, this::checkCardDuringClear), 3600);
        timer.schedule(at(5000, this::checkCloseFlowFinished), 5000);
        timer.schedule(at(5400, this::finish), 5400);
    }

    private TimerTask at(long delay, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    // ---------- Part A: normal work ----------

    private void startBusyOK() {
        NotificationHost.busy(root, "BUSY-TITLE", "LINE-ONE\nLINE-TWO",
                () -> {
                    // 闸门：断言完"卡片在显示"才放行，消除时序竞态（上一版就是被它骗成 FAIL）
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    workThread.set(Thread.currentThread().getName());
                    workDone.set(true);
                },
                () -> {
                    onDoneThread.set(Thread.currentThread().getName());
                    onDoneRan.set(true);
                });
        System.out.println("right after busy() returns: display=" + modalPane().isDisplay()
                + " opacity=" + modalPane().getOpacity() + " viewOrder=" + modalPane().getViewOrder());
    }

    private void checkBusyVisible() {
        check("busy(): ModalPane is showing", modalPane().isDisplay());
        Node content = modalPane().getContent();
        check("busy(): card is a Message (not Notification)", content instanceof Message);
        if (content instanceof Message msg) {
            check("busy(): title kept", "BUSY-TITLE".equals(msg.getTitle()));
            check("busy(): description kept", "LINE-ONE\nLINE-TWO".equals(msg.getDescription()));
            check("busy(): NO close button (onClose == null -> no CLOSEABLE)", msg.getOnClose() == null);
            check("busy(): NO action handler (zero interactive controls)", msg.getActionHandler() == null);
            check("busy(): rendered with a real height", msg.getHeight() > 20);
            check("busy(): not stretched to window height", msg.getHeight() < 400);
        }
        snapshot(".claude-work/uiprobe/busy-card.png");
        gate.countDown();   // 放行 worker
    }

    private void checkBusyFinished() {
        check("busy(): worker ran", workDone.get());
        check("busy(): worker ran OFF the FX thread (" + workThread.get() + ")",
                !"JavaFX Application Thread".equals(workThread.get()));
        check("busy(): onDone ran", onDoneRan.get());
        check("busy(): onDone ran ON the FX thread (" + onDoneThread.get() + ")",
                "JavaFX Application Thread".equals(onDoneThread.get()));
        check("busy(): card auto-hidden", !modalPane().isDisplay());
    }

    // ---------- Part B: throwing worker ----------

    private void startBusyThrowing() {
        onDoneRan.set(false);
        NotificationHost.busy(root, "THROW-TITLE", "worker will throw",
                () -> {
                    throw new IllegalStateException("probe: intentional failure");
                },
                () -> onDoneRan.set(true));
    }

    private void checkThrowingFinished() {
        check("busy(): THROWING worker still reaches onDone (exit path cannot hang)", onDoneRan.get());
        check("busy(): card hidden after failure", !modalPane().isDisplay());
    }

    // ---------- Part C: real window-close flow ----------

    private void startRealCloseFlow() {
        try {
            Files.createDirectories(MARKER_DIR);
            Files.writeString(MARKER_DIR.resolve("keep.txt"), "probe marker");
            frontend.bootstrap(new com.mhtextractor.model.AppConfig());
        } catch (Exception e) {
            e.printStackTrace();
            failures++;
        }
        System.out.println("cache root before close: " + describeCache());
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        // 关窗处理器是同步跑的 ⇒ 同一个 FX 任务里立刻断言，不存在"清理太快没看到卡片"的竞态
        check("close flow: wait card shown while clearing", modalPane().isDisplay());
        check("close flow: wait card is a Message", modalPane().getContent() instanceof Message);
        if (modalPane().getContent() instanceof Message msg) {
            System.out.println("wait card title=" + msg.getTitle());
            System.out.println("wait card description=" + msg.getDescription());
        }
    }

    private void checkCardDuringClear() {
        // 占位：真正的断言已挪到 startRealCloseFlow 的同帧检查里
        System.out.println("cache root ~400ms after close request: " + describeCache());
    }

    private void checkCloseFlowFinished() {
        check("close flow: cache directory emptied", describeCache().equals("(empty)"));
        check("close flow: wait card removed automatically", !modalPane().isDisplay());
        check("close flow: window closed automatically (no click)", !stage.isShowing());
    }

    // ---------- helpers ----------

    private String describeCache() {
        if (!Files.isDirectory(CACHE_ROOT)) {
            return "(root absent)";
        }
        try (Stream<Path> s = Files.list(CACHE_ROOT)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            return names.isEmpty() ? "(empty)" : names.toString();
        } catch (Exception e) {
            return "(list failed: " + e + ")";
        }
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
    }

    private ModalPane modalPane() {
        for (Node n : ((StackPane) scene.getRoot()).getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        throw new IllegalStateException("ModalPane not found");
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
            failures++;
        }
    }

    private void finish() {
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
