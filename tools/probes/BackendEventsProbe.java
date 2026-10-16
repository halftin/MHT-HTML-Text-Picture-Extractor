import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import com.mhtextractor.app.IpcUiport;
import com.mhtextractor.gui.component.BackendEventPresenter;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uicore.FrontendPort;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real JVM IPC -> IpcCodec -> FrontendPort -> presenter -> NotificationHost; no private access. */
public class BackendEventsProbe extends Application {
    private IpcUiport ipc;
    private FrontendPort frontend;
    private final AtomicBoolean finished = new AtomicBoolean();
    private TextArea logs;
    private StackPane shell;
    private PortEvent.CacheSizeAlert alert;
    private int backendLogs;
    private Stage stage;

    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        logs = new TextArea();
        var anchor = new Label("IPC");
        shell = new StackPane(new VBox(anchor, logs));
        var scene = new Scene(shell, 1100, 650);
        scene.getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        NotificationHost.install(scene, shell);
        stage.setScene(scene); stage.show();
        ipc = new IpcUiport(request -> { throw new AssertionError("Unexpected confirmation"); },
                () -> new IpcUiport.RuntimeContext(false, null, null, false));
        frontend = new FrontendPort(ipc, Platform::runLater, config -> { });
        frontend.events().subscribe(event -> {
            if (event instanceof PortEvent.BackendLog) backendLogs++;
            BackendEventPresenter.accept(event, anchor, logs);
            if (event instanceof PortEvent.CacheSizeAlert measured) {
                alert = measured;
                var pause = new PauseTransition(Duration.millis(250));
                pause.setOnFinished(e -> verify(anchor)); pause.play();
            }
        });
        var timeout = new PauseTransition(Duration.seconds(30));
        timeout.setOnFinished(e -> finish(new AssertionError("No cache IPC toast within 30 seconds"))); timeout.play();
        Thread.ofPlatform().name("backend-events-probe").daemon(true).start(() -> {
            try {
                var work = Path.of(System.getProperty("user.dir"));
                var cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                        .map(p -> Path.of(p).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
                ipc.start(() -> new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-Dlogback.configurationFile=logback-headless.xml",
                        "-cp", cp, "com.mhtextractor.backend.ProbeBackendEventsFixture").directory(work.toFile()),
                        java.time.Duration.ofSeconds(10));
                Path source = Files.writeString(work.resolve("input.html"), "<html><body>IPC toast</body></html>");
                ipc.commands().importPaths(List.of(source.toString()), "drag", false);
                ipc.queries().previewFiles(source.toString());
            } catch (Throwable failure) { Platform.runLater(() -> finish(failure)); }
        });
    }

    private void verify(Node anchor) {
        try {
            require(Platform.isFxApplicationThread(), "frontend delivery is on FX thread");
            require(alert.step() == 1 && alert.actualBytes() >= 10L * 1024 * 1024 * 1024 && alert.revision() > 0,
                    "actual IPC measurement and revision arrived");
            long count = shell.getChildren().stream().filter(n -> n instanceof VBox)
                    .map(n -> (VBox)n).flatMap(v -> v.getChildren().stream()).filter(n -> n instanceof Notification).count();
            require(count == 1, "exactly one real NotificationHost toast");
            String visible = visibleText(shell);
            require(visible.contains(alert.root()) && visible.contains("10.0"), "toast renders actual GiB and store root");
            require(!visible.contains("!cache.") && !visible.contains("{actual}"), "toast bundle placeholders resolved");
            require(logs.getText().contains("WARN_SENTINEL") && logs.getText().contains("ERROR_SENTINEL"), "backend WARN and ERROR entered UI");
            require(!logs.getText().contains("INFO_SENTINEL"), "backend INFO did not enter UI");
            require(backendLogs == 5, "all five WARN/ERROR diagnostics and no INFO were forwarded");
            require(logs.getText().contains(I18n.get("extract.region.noImages")), "nested warning renders in frontend locale");
            String before = logs.getText();
            BackendEventPresenter.accept(new PortEvent.BackendLog("INFO", "log.previewcachelifecycle.04",
                    Map.of("0", "FORGED_INFO"), 1, 1), anchor, logs);
            require(logs.getText().equals(before), "frontend independently rejects INFO");
            var snapshot = shell.getScene().snapshot(null);
            var pixels = new java.awt.image.BufferedImage((int)snapshot.getWidth(), (int)snapshot.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++)
                pixels.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            Path image = Path.of(System.getProperty("user.dir"), "toast.png");
            javax.imageio.ImageIO.write(pixels, "png", image.toFile());
            System.out.println("TOAST_SCREENSHOT=" + image);
            System.out.println("BACKEND_EVENTS: 10 passed, 0 failed locale=" + I18n.currentLocale());
            finish(null);
        } catch (Throwable failure) { finish(failure); }
    }

    private static String visibleText(Node node) {
        var result = new StringBuilder();
        if (node instanceof javafx.scene.control.Labeled label) result.append(label.getText()).append('\n');
        if (node instanceof javafx.scene.text.Text text) result.append(text.getText()).append('\n');
        if (node instanceof Parent parent) for (var child : parent.getChildrenUnmodifiable()) result.append(visibleText(child));
        return result.toString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    private void finish(Throwable failure) {
        if (!finished.compareAndSet(false, true)) return;
        if (failure != null) { failure.printStackTrace(); System.out.println("BACKEND_EVENTS: FAILED"); }
        if (frontend != null) frontend.close();
        stage.close();
        Thread.ofPlatform().daemon(false).start(() -> {
            int exit = failure == null ? 0 : 1;
            try {
                if (ipc != null) { ipc.close(); ipc.stopped().get(30, java.util.concurrent.TimeUnit.SECONDS); }
                if (alert != null) require(!Files.exists(Path.of(alert.root()).getParent()), "GUI session temporary directory really disappeared");
            } catch (Throwable cleanup) { cleanup.printStackTrace(); exit = 1; }
            Platform.exit(); System.exit(exit);
        });
    }
    public static void main(String[] args) { launch(args); }
}
