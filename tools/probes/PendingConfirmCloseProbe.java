import atlantafx.base.controls.Message;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
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
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Risk probe (scratch): what happens when the user closes the window while a CONFIRM card is
 * waiting for an answer? (e.g. extraction 的递归深度确认 / 配额确认 —— 它们都在后台线程 join 用户的决定)
 *
 * <p>And: does that blocked background thread survive after the window closes?</p>
 */
public class PendingConfirmCloseProbe extends Application {

    private Path CACHE_ROOT;
    private com.mhtextractor.uicore.FrontendPort frontend;

    private Scene scene;
    private Parent root;
    private Stage stage;
    private final AtomicReference<String> decision = new AtomicReference<>("(none)");
    private final CountDownLatch answered = new CountDownLatch(1);
    private final AtomicReference<String> blockedThread = new AtomicReference<>("(none)");

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Platform.setImplicitExit(false);

        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        // P6-B：控制器要拿 FXML 命名空间（fx:id -> MainViewRefs），而 namespace 只有 loader 有 ——
        // 与 App.start() / TestMain 同一根线（controller factory 覆盖 fx:controller，时机逐字相同）。
        frontend = ProbeRuntime.port();
        CACHE_ROOT = Path.of(frontend.queries().cacheState().root());
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
        timer.schedule(at(600, this::startPendingConfirm), 600);
        timer.schedule(at(1200, this::closeWindow), 1200);
        timer.schedule(at(1800, this::checkStillOpen), 1800);
        timer.schedule(at(2200, this::answerAndCloseAgain), 2200);
        timer.schedule(at(3600, this::inspectAfterClose), 3600);
        // L2 专项：确认卡被"错误卡"顶掉时，必须按"取消"交付决定（否则等它的线程永久阻塞）
        timer.schedule(at(4000, this::startSecondPendingConfirm), 4000);
        timer.schedule(at(4400, this::replaceWithErrorCard), 4400);
        timer.schedule(at(4900, this::checkReplacementDelivered), 4900);
        timer.schedule(at(5400, this::finish), 5400);
    }

    private void startSecondPendingConfirm() {
        decision.set("(none)");
        NotificationHost.confirm(root, "SECOND-PENDING-CONFIRM", ok -> decision.set("confirm:" + ok));
        System.out.println("card: " + describeCard() + "  decision=" + decision.get());
    }

    private void replaceWithErrorCard() {
        System.out.println("pushing an error card on top of the pending confirm...");
        NotificationHost.error(root, "ERROR-REPLACES-CONFIRM");
    }

    private void checkReplacementDelivered() {
        System.out.println((decision.get().equals("confirm:false") ? "PASS  " : "FAIL  ")
                + "replaced pending confirm was delivered as cancel (L2): " + decision.get());
        System.out.println((describeCard().contains("ERROR-REPLACES-CONFIRM") ? "PASS  " : "FAIL  ")
                + "error card is now the visible one: " + describeCard());
    }

    private TimerTask at(long delay, Runnable r) {
        return new TimerTask() {
            public void run() {
                Platform.runLater(r);
            }
        };
    }

    /** 模拟 awaitQuotaConfirm / 递归确认：后台线程 join 用户决定 */
    private void startPendingConfirm() {
        Thread waiter = new Thread(() -> {
            NotificationHost.confirm(root, "PENDING-CONFIRM", ok -> {
                decision.set(String.valueOf(ok));
                answered.countDown();
            });
            blockedThread.set(Thread.currentThread().getName());
            // 就是生产代码里那条路径：后台线程等 FX 线程上的用户决定
            try {
                answered.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "extract-worker-like");
        waiter.setDaemon(false);   // 生产里提取线程就是非 daemon
        waiter.start();
    }

    private void closeWindow() {
        try {
            Files.createDirectories(CACHE_ROOT.resolve("probe-marker"));
            Files.writeString(CACHE_ROOT.resolve("probe-marker").resolve("keep.txt"), "x");
            frontend.bootstrap(new com.mhtextractor.model.AppConfig());
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("card before close: " + describeCard());
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        System.out.println("close request fired; card right after: " + describeCard());
    }

    /** 修复后的期望：有未回答的确认卡时，关窗请求必须被挡住（窗口还在、卡片还是那张确认卡）。 */
    private void checkStillOpen() {
        boolean stillOpen = stage.isShowing();
        String card = describeCard();
        System.out.println((stillOpen ? "PASS  " : "FAIL  ") + "window still open while a confirm awaits");
        System.out.println((card.contains("Notification") ? "PASS  " : "FAIL  ")
                + "pending confirm card was NOT replaced: " + card);
        System.out.println((decision.get().equals("(none)") ? "PASS  " : "FAIL  ")
                + "no decision delivered behind the user's back: " + decision.get());
    }

    /** 用户先回答确认卡，再关窗 → 这次应该顺利走 等待卡片 → 清空 → 关窗。 */
    private void answerAndCloseAgain() {
        ModalPane mp = modalPane();
        if (mp.getContent() instanceof Notification ntf && ntf.getPrimaryActions().size() >= 2) {
            System.out.println("answering the confirm with Cancel...");
            ntf.getPrimaryActions().get(1).fire();
        }
        System.out.println("decision after answering: " + decision.get());
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        System.out.println("second close request; card now: " + describeCard());
    }

    private void inspectAfterClose() {
        System.out.println("--- after close ---");
        System.out.println("card now        : " + describeCard());
        System.out.println("window showing  : " + stage.isShowing());
        System.out.println("decision        : " + decision.get() + "   (\"(none)\" = 回调从未触发)");
        System.out.println("cache emptied   : " + !Files.exists(CACHE_ROOT.resolve("probe-marker")));
        boolean blockedAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "extract-worker-like".equals(t.getName()) && t.isAlive() && !t.isDaemon());
        System.out.println("blocked thread  : " + blockedThread.get() + " alive&nonDaemon=" + blockedAlive);
    }

    private String describeCard() {
        ModalPane mp = modalPane();
        Node c = mp.getContent();
        return "display=" + mp.isDisplay()
                + " type=" + (c == null ? "null" : c.getClass().getSimpleName())
                + (c instanceof Notification n ? " msg=" + n.getMessage() : "")
                + (c instanceof Message m ? " title=" + m.getTitle() : "");
    }

    private ModalPane modalPane() {
        for (Node n : ((StackPane) scene.getRoot()).getChildren()) {
            if (n instanceof ModalPane mp) {
                return mp;
            }
        }
        throw new IllegalStateException("no ModalPane");
    }

    private void finish() {
        System.out.println("PROBE DONE (implicit exit was disabled on purpose)");
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
