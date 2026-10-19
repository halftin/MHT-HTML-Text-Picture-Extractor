import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.uiclient.IpcUiport;
import com.mhtextractor.gui.component.BackendEventPresenter;
import com.mhtextractor.gui.component.GuiConfirmer;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.component.SidePanelHost;
import com.mhtextractor.gui.controller.MainController;
import com.mhtextractor.gui.controller.ParamSidePanel;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.FrontendPort;
import com.mhtextractor.uicore.GuiStateManager;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uiport.PortEvent;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Production FXML/controller, IPC, presenters and close lifecycle; public APIs only.
 * No System.exit on success: the runner must observe natural process termination.
 * The same compiled source is used for JVM and native acquisition.
 */
public final class GuiE2eProbe {
    private final AtomicReference<AppConfig> context = new AtomicReference<>(new AppConfig());
    private IpcUiport ipc;
    private FrontendPort frontend;
    private Stage stage;
    private Scene scene;
    private TextArea forwarded;
    private Path work;
    private int backendLogs;
    private PortEvent.ExtractionTerminal extraction;
    private boolean ending;
    private final String mode = System.getProperty("probe.scenario", "e2e");

    public void start(Stage stage) throws Exception {
        this.stage = stage;
        work = Path.of("").toAbsolutePath();
        var saved = GuiStateManager.load();
        require(saved != null && saved.getLocale() != null, "seeded GUI locale exists");
        I18n.setLocale(I18n.parseLocale(saved.getLocale()));
        System.out.println("LOCALE=" + I18n.currentLocale() + " SCENARIO=" + mode);
        ipc = new IpcUiport(new GuiConfirmer(() -> scene == null ? null : (Label)scene.lookup("#statusLabel")),
                () -> { var c = context.get(); return new IpcUiport.RuntimeContext(
                        c.isImageModeEnabled(), pathOrNull(c.getInputFolder()), pathOrNull(c.getOutputFolder()), false); });
        frontend = new FrontendPort(ipc, Platform::runLater, context::set, true);
        Thread.ofPlatform().daemon().name("probe-startup").start(() -> {
            try {
                // 随包后端 exe：优先 -Dprobe.backend，其次环境变量 MHT_BACKEND_EXE，再次**仓库同级**的
                // mht-resource-extractor-dist/Backend（相对路径，不写死机器专有路径）；都找不到就如实报错 ——
                // 原来直接 ProcessBuilder(null, …) 会抛 NPE，看不出缺什么。
                String exe = System.getProperty("probe.backend");
                if (exe == null || exe.isBlank()) exe = System.getenv("MHT_BACKEND_EXE");
                if (exe == null || exe.isBlank()) {
                    var rel = java.nio.file.Path.of("..", "mht-resource-extractor-dist", "Backend",
                            "MhtExtractorBackend.exe").toAbsolutePath().normalize();
                    if (java.nio.file.Files.isRegularFile(rel)) exe = rel.toString();
                }
                if (exe == null || exe.isBlank()
                        || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(exe))) {
                    throw new IllegalStateException("GuiE2eProbe 需要随包后端 exe：请用 "
                            + "-Dprobe.backend=<MhtExtractorBackend.exe> 或环境变量 MHT_BACKEND_EXE 指定"
                            + "（默认还会查找仓库同级的 mht-resource-extractor-dist/Backend/）。");
                }
                var command = new ProcessBuilder(exe, "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                        "-Dmht.logs.root=" + work.resolve("logs"), "--serve", "--locale", saved.getLocale())
                        .directory(work.toFile()).redirectError(work.resolve("backend.stderr.log").toFile());
                System.out.println("BACKEND_COMMAND=" + command.command());
                ipc.start(() -> command, java.time.Duration.ofSeconds(20));
                frontend.bootstrap(context.get());
                Platform.runLater(() -> safely(this::show));
            } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
        });
    }

    private void show() throws Exception {
        var loader = new FXMLLoader(App.class.getResource("/com/mhtextractor/gui/view/MainView.fxml"));
        loader.setResources(I18n.bundle());
        loader.setControllerFactory(_ -> new MainController(loader, frontend, this::closeBackend));
        Parent root = loader.load();
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        var shell = new StackPane(SidePanelHost.install(root));
        scene = new Scene(shell, 1100, 720);
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        NotificationHost.install(scene, shell);
        ParamSidePanel.of(scene.lookup("#statusLabel"), frontend);
        forwarded = new TextArea();
        frontend.events().subscribe(event -> {
            if (event instanceof PortEvent.ExtractionTerminal terminal) extraction = terminal;
            if (event.getClass().getSimpleName().startsWith("Preview"))
                System.out.println("PREVIEW_EVENT=" + event.getClass().getSimpleName());
            // fixtures 里有一个**故意**解析失败的文件：bad-charset.mht（声明的字符集不存在 → FAILED，见台账 §83.3）。
            // 只放行这一条**已知**错误；其它任何预览失败仍然算红。
            if (event instanceof PortEvent.PreviewLoadFailed failed) {
                String detail = String.valueOf(failed.detail());
                if (detail.contains("x-nonexistent-charset")) {
                    System.out.println("EXPECTED_PREVIEW_FAILURE=" + detail);
                } else {
                    fail(new AssertionError("preview failed: " + detail));
                }
            }
            if (event instanceof PortEvent.BackendLog entry) safely(() -> {
                backendLogs++;
                Files.writeString(work.resolve("backend-events.txt"), entry.toString() + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                BackendEventPresenter.accept(entry, scene.lookup("#statusLabel"), forwarded);
                Files.writeString(work.resolve("forwarded-backend.log"), forwarded.getText());
                System.out.println("BACKEND_LOG_EVENT level=" + entry.level() + " key=" + entry.key());
            });
        });
        stage.setScene(scene);
        stage.setTitle("GUI parity probe");
        stage.setResizable(false);
        stage.show();
        stage.setOnHidden(_ -> {
            System.out.println("[PASS] production close hid window");
            System.out.println("PROBE_COMPLETE scenario=" + mode + " backendLogs=" + backendLogs);
        });
        after(700, () -> {
            scene.getRoot().applyCss(); scene.getRoot().layout();
            require(stage.isShowing() && scene.lookup("#statusLabel").isVisible()
                    && scene.lookup("#fileListView").isVisible(), "main window status and list visible");
            var name = (Label)scene.lookup("#nameColumnHeader");
            var source = (Label)scene.lookup("#sourceColumnHeader");
            System.out.println("COLUMN_WIDTH name=" + name.getPrefWidth() + " source=" + source.getPrefWidth());
            require(name.getPrefWidth() > 0 && Math.abs(name.getPrefWidth() - source.getPrefWidth()) < 0.01,
                    "default file name/source widths 50/50");
            if (mode.equals("screenshot")) {
                ProbePng.write(scene.snapshot(null), Path.of(System.getProperty("probe.png")));
                System.out.println("SCREENSHOT=" + System.getProperty("probe.png") + " size="
                        + scene.getWidth() + "x" + scene.getHeight() + " scale=" + stage.getOutputScaleX());
                closeWindow();
            } else importFixture();
        });
    }

    private void importFixture() {
        var imageMode = (ToggleButton)scene.lookup("#imageModeToggle");
        if (!imageMode.isSelected()) imageMode.fire();
        // 夹具目录：优先 -Dprobe.fixture，环境变量 MHT_FIXTURE_DIR 由 run-probe.ps1 转发；默认仓库内 fixtures。
        String input = System.getProperty("probe.fixture",
                new java.io.File("src/test/resources/fixtures").getAbsolutePath());
        if (input == null || input.isBlank() || !new java.io.File(input).isDirectory()) {
            throw new IllegalStateException("GuiE2eProbe 需要一个夹具目录：请用 -Dprobe.fixture=<dir>"
                    + "（或用 MHT_FIXTURE_DIR）指定；默认 src/test/resources/fixtures。");
        }
        ((TextField)scene.lookup("#inputFolderField")).setText(input);
        await("fixture directory imported into real list", () -> !list().getItems().isEmpty()
                && !frontend.queries().workspace().details().importing(), () -> {
            scene.getRoot().applyCss(); scene.getRoot().layout();
            var sources = scene.getRoot().lookupAll(".preview-source").stream()
                    .filter(n -> n instanceof Label).map(n -> (Label)n).toList();
            String expected = Boolean.getBoolean("probe.wrongSource") ? input + "-does-not-exist" : input;
            System.out.println("SOURCE expected=" + expected + " actual=" + sources.stream().map(Label::getText).toList());
            require(sources.stream().anyMatch(n -> n.isVisible() && expected.equals(n.getText())),
                    "source column text equals fixture directory");
            // The repository directory also contains intentional failures and text-only
            // fixtures. multi-image.mht has decodable GIF/JPEG images (single-image.mht's
            // PNG has invalid compressed data). Also support the native smoke's fixture.
            // List ordering is not a contract for which file can produce a thumbnail.
            Path fixture = Path.of(input).resolve("multi-image.mht");
            if (!Files.isRegularFile(fixture)) fixture = Path.of(input).resolve("fixture.mht");
            String previewPath = fixture.toAbsolutePath().normalize().toString();
            Object item = previewRow(previewPath);
            list().getSelectionModel().select(item);
            var params = new AppConfig();
            params.setEnableRegionExtraction(mode.equals("purity"));
            if (mode.equals("purity")) {
                params.setImageRegionStartMarker("MISSING_START");
                params.setImageRegionEndMarker("MISSING_END");
            }
            frontend.commands().setGlobalParams(true, params);
            await("preview button enabled after real parameters", () -> frontend.queries().workspace().details().imageConfirmed()
                    && !((Button)scene.lookup("#previewEffectButton")).isDisabled(), () -> {
                // Parameter refresh rebuilds the list projection. Select its current row,
                // rather than retaining an object from the previous projection.
                Object current = previewRow(previewPath);
                list().getSelectionModel().select(current);
                require(list().getSelectionModel().getSelectedItem() instanceof PreviewRow, "preview file selected in current projection");
                System.out.println("PREVIEW_FIXTURE=" + previewPath);
                System.out.println("PREVIEW_TRIGGER imageMode=" + ((ToggleButton)scene.lookup("#imageModeToggle")).isSelected()
                        + " runtimeMode=" + context.get().isImageModeEnabled() + " buttonDisabled="
                        + ((Button)scene.lookup("#previewEffectButton")).isDisabled() + " hint="
                        + ((Label)scene.lookup("#imageHintLabel")).getText());
                ((Button)scene.lookup("#previewEffectButton")).fire();
                await("preview contains decoded nonempty thumbnail", () -> scene.getRoot().lookupAll("#thumbnailPane ImageView").stream()
                        .anyMatch(n -> n instanceof ImageView view && view.getImage() != null
                                && !view.getImage().isError() && view.getImage().getWidth() > 0), () -> {
                    if (mode.equals("purity")) await("real WARN/ERROR backendLog received and rendered",
                            () -> backendLogs > 0 && !forwarded.getText().isBlank(), this::closeWindow);
                    else if (Boolean.getBoolean("probe.extract")) extractFixture();
                    else closeWindow();
                });
            });
        });
    }

    private void extractFixture() {
        Path output = work.resolve("extracted");
        ((TextField)scene.lookup("#outputFolderField")).setText(output.toString());
        await("extraction start enabled", () -> !((Button)scene.lookup("#startButton")).isDisabled(), () -> {
            ((Button)scene.lookup("#startButton")).fire();
            await("real extraction terminal received", () -> extraction != null, () -> safely(() -> {
                require(extraction.end() == PortEvent.ExtractionEnd.SUCCEEDED
                        && extraction.stats() != null && extraction.stats().successCount() > 0
                        && extraction.stats().failCount() == 0, "real extraction succeeded without failures");
                try (var files = Files.walk(output)) {
                    var products = files.filter(Files::isRegularFile).toList();
                    require(!products.isEmpty(), "real extraction wrote products");
                    for (Path product : products) require(Files.size(product) > 0, "extracted product is nonempty");
                    System.out.println("EXTRACTED_PRODUCTS=" + products);
                }
                closeWindow();
            }));
        });
    }

    @SuppressWarnings("unchecked") private ListView<Object> list() { return (ListView<Object>)scene.lookup("#fileListView"); }
    private PreviewRow previewRow(String path) {
        return list().getItems().stream().filter(n -> n instanceof PreviewRow)
                .map(n -> (PreviewRow)n)
                .filter(row -> Path.of(row.path).toAbsolutePath().normalize().toString().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("image preview fixture not imported: " + path));
    }
    private static String pathOrNull(String value) { return value == null || value.isBlank() ? null : value; }
    private void closeWindow() {
        System.out.println("CLOSE_REQUEST production WindowEvent.WINDOW_CLOSE_REQUEST");
        Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }
    private void closeBackend(Runnable done) {
        Thread.ofPlatform().daemon().name("probe-close").start(() -> {
            try {
                frontend.close(); ipc.close(); ipc.stopped().get(30, TimeUnit.SECONDS);
                System.out.println("[PASS] backend stopped after production close callback");
                Platform.runLater(done);
            } catch (Throwable error) { fail(error); }
        });
    }
    private void after(int millis, Checked action) {
        var pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(_ -> safely(action)); pause.play();
    }
    private void await(String name, BooleanSupplier condition, Checked next) {
        poll(name, condition, next, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    }
    private void poll(String name, BooleanSupplier condition, Checked next, long deadline) {
        if (condition.getAsBoolean()) { require(true, name); safely(next); }
        else if (System.nanoTime() >= deadline) fail(new AssertionError(name + " timed out"));
        else after(80, () -> poll(name, condition, next, deadline));
    }
    private static void require(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("[PASS] " + name);
    }
    private interface Checked { void run() throws Exception; }
    private void safely(Checked action) { try { action.run(); } catch (Throwable error) { fail(error); } }
    private synchronized void fail(Throwable error) {
        if (ending) return;
        ending = true;
        System.out.println("[FAIL] " + error.getMessage()); error.printStackTrace();
        Thread.ofPlatform().daemon(false).start(() -> {
            try { if (frontend != null) frontend.close(); if (ipc != null) { ipc.close(); ipc.stopped().get(30, TimeUnit.SECONDS); } }
            catch (Throwable cleanup) { cleanup.printStackTrace(); }
            System.exit(1);
        });
    }
    public static void main(String[] arguments) {
        // Application.launch(String...) discovers its caller by reflection. A probe must
        // not require extra production metadata; use the same public startup as AppCardProbe.
        Platform.startup(() -> {
            var probe = new GuiE2eProbe();
            try { probe.start(new Stage()); } catch (Throwable error) { probe.fail(error); }
        });
    }
}
