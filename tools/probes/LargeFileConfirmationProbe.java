import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.NordLight;
import com.mhtextractor.App;
import com.mhtextractor.app.*;
import com.mhtextractor.gui.component.NotificationHost;
import com.mhtextractor.gui.controller.PreviewLoader;
import com.mhtextractor.gui.controller.PreviewRenderer;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.PortEvent;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real PreviewLoader -> InProcessUiport -> WorkspaceActivityService -> PreviewService path.
 * Real NotificationHost card buttons, real HTML parsing and PNG decoding. No reflection,
 * test-source driver, injected parser, or replacement production class.
 * Fixtures: node .claude-work/p4-18.mjs fixtures (50 MiB + 1 byte and 1 MiB; dir overridable
 * via -Dprobe.fixtures). The batch runner is a local draft (.claude-work/), not a distributed tool.
 * Red controls alter probe input only: probe.large.bypass / probe.large.acceptCancel.
 */
public class LargeFileConfirmationProbe extends Application implements PreviewLoader.Host, PreviewRenderer.Host {
    private final Label hint = new Label(), status = new Label();
    private final Button effect = new Button();
    private final ProgressBar progress = new ProgressBar();
    private final TextArea text = new TextArea();
    private final FlowPane thumbnails = new FlowPane();
    private final ScrollPane images = new ScrollPane(thumbnails);
    private final AppConfig params = new AppConfig();
    private final AtomicInteger decodes = new AtomicInteger();
    private StackPane shell;
    private PreviewSession session;
    private PreviewRenderer renderer;
    private PreviewLoader loader;
    private InProcessUiport port;
    private PreviewItem current, large, small;
    private int failures, starts, confirmations;
    private boolean finished;

    @Override public void start(Stage stage) throws Exception {
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        effect.setText(Texts.get("main.previewButton"));
        Path fixtureDir = Path.of(System.getProperty("probe.fixtures", "H:/TEST/mht/p4-18/fixtures"));
        Path largePath = fixtureDir.resolve("large.html"), smallPath = fixtureDir.resolve("small.html");
        long largeBytes = Files.size(largePath), smallBytes = Files.size(smallPath);
        System.out.println("FIXTURE large bytes=" + largeBytes + " small bytes=" + smallBytes);
        check("large-fixture-over-50MiB", largeBytes > 50L * 1024 * 1024);
        check("small-fixture-under-50MiB", smallBytes < 50L * 1024 * 1024);
        check("exact-50MiB-no-confirmation", !WorkspaceActivityService.requiresLargeFileConfirmation(50L * 1024 * 1024));
        check("50MiB-plus-one-confirmation", WorkspaceActivityService.requiresLargeFileConfirmation(50L * 1024 * 1024 + 1));
        large = item(largePath); small = item(smallPath); current = large;
        session = PreviewSession.create(Files.createTempDirectory("mht-extractor-large-probe-"));
        renderer = new PreviewRenderer(this);
        var model = new WorkspaceModel(); model.addItems(List.of(large, small));
        var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return false; }
            public AppConfig effectiveParams(PreviewItem ignored) { return params; }
            public AppConfig effectiveImageParams(PreviewItem ignored) { return params; }
        }, new PreviewService(session.lifecycle()), (image, index, config) -> {
            decodes.incrementAndGet(); return renderer.decodeThumb(image, index, config);
        }, Platform::runLater);
        port = new InProcessUiport(model, request -> { throw new AssertionError("Unexpected import confirmation"); }, activities);
        port.events().subscribe(event -> {
            if (event instanceof PortEvent.PreviewLoadStateChanged state && state.active()) starts++;
            if (event instanceof PortEvent.PreviewConfirmationRequired) confirmations++;
            if (event instanceof PortEvent.PreviewLoadFailed failed) {
                check("unexpected-load-failure " + failed.detail(), false);
            }
        });
        loader = new PreviewLoader(this, port);
        effect.setOnAction(ignored -> {
            if (Boolean.getBoolean("probe.large.bypass")) port.commands().loadPreview(current.path, true);
            else loader.load();
        });
        shell = new StackPane(new VBox(8, effect, hint, images, progress, status));
        Scene scene = new Scene(shell, 900, 650);
        NotificationHost.install(scene, shell);
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
        scene.getStylesheets().add(App.class.getResource("/custom.css").toExternalForm());
        stage.setScene(scene); stage.show();
        later(300, this::largeRequest);
        // Independent upper bound even if any assertion path throws on the FX thread.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(45000); } catch (InterruptedException ignored) { return; }
            System.err.println("FAIL large-probe-watchdog"); System.exit(1);
        }, "large-probe-watchdog");
        watchdog.setDaemon(true); watchdog.start();
    }

    private PreviewItem item(Path path) {
        return new PreviewItem(path.toString(), Texts.get("test.previewLoadingName"), LocalDateTime.now(), path.getParent().toString());
    }
    private ModalPane pane() {
        return shell.getChildren().stream().filter(n -> n instanceof ModalPane).map(n -> (ModalPane) n)
                .findFirst().orElseThrow();
    }
    private Notification card() { return (Notification) pane().getContent(); }
    private void largeRequest() {
        effect.fire();
        check("large-card-shown", pane().isDisplay() && confirmations == 1);
        check("large-no-loading-before-decision", !loader.isActive() && starts == 0 && decodes.get() == 0);
        if (!pane().isDisplay()) { finish(); return; }
        later(300, () -> {
            check("large-card-message", card().getMessage().contains(Texts.get("preview.largeFileConfirmHeader"))
                    && card().getMessage().contains(large.name));
            check("large-card-centered-visible", pane().getAlignment() == javafx.geometry.Pos.CENTER
                    && card().getWidth() > 0 && card().getHeight() > 0 && card().isVisible());
            check("large-confirmation-pending", NotificationHost.hasPendingDecision(status));
            check("large-two-explicit-buttons", card().getPrimaryActions().size() == 2
                    && card().getPrimaryActions().stream().noneMatch(Button::isDefaultButton));
            card().getPrimaryActions().get(Boolean.getBoolean("probe.large.acceptCancel") ? 0 : 1).fire();
            check("cancel-card-hidden", !pane().isDisplay() && !NotificationHost.hasPendingDecision(status));
            check("cancel-no-loading", !loader.isActive() && starts == 0);
            later(300, this::afterCancel);
        });
    }
    private void afterCancel() {
        check("cancel-no-decode-or-model-result", decodes.get() == 0 && large.cachedThumbs == null && large.cachedMeta == null);
        try { check("cancel-no-cache", session.store().listKeys().isEmpty()); }
        catch (Exception error) { error.printStackTrace(); check("cancel-cache-inspection", false); }
        if (failures > 0) { finish(); return; }
        // Same UI button -> same card -> real OK action. Complete the inexpensive PNG decode.
        effect.fire();
        check("second-large-request-card", pane().isDisplay() && confirmations == 2);
        card().getPrimaryActions().get(0).fire();
        check("accept-starts-loading", loader.isActive() && starts == 1 && !pane().isDisplay());
        waitFor("large-completion", () -> !loader.isActive() && large.cachedThumbs != null, () -> {
            check("accept-real-PNG-decoded", decodes.get() == 1 && large.cachedThumbs.size() == 1
                    && large.cachedThumbs.get(0).pixels() != null && large.cachedMeta != null);
            current = small;
            int before = confirmations;
            effect.fire();
            check("small-no-confirmation-card", !pane().isDisplay() && confirmations == before);
            check("small-load-started", loader.isActive() && starts == 2);
            waitFor("small-completion", () -> !loader.isActive() && small.cachedThumbs != null, () -> {
                check("small-real-PNG-decoded", decodes.get() == 2 && small.cachedThumbs.size() == 1
                        && small.cachedThumbs.get(0).pixels() != null);
                finish();
            });
        });
    }
    private void later(int millis, Runnable next) {
        var tick = new PauseTransition(Duration.millis(millis));
        tick.setOnFinished(ignored -> {
            if (finished) return;
            try { next.run(); } catch (Throwable error) { error.printStackTrace(); check("probe-step-exception", false); finish(); }
        }); tick.play();
    }
    private void waitFor(String name, BooleanSupplier condition, Runnable next) {
        poll(name, condition, next, System.nanoTime() + 20_000_000_000L);
    }
    private void poll(String name, BooleanSupplier condition, Runnable next, long deadline) {
        if (condition.getAsBoolean()) { next.run(); return; }
        if (System.nanoTime() >= deadline) { check(name + "-timeout", false); finish(); return; }
        later(20, () -> poll(name, condition, next, deadline));
    }
    private void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " starts=" + starts
                + " confirmations=" + confirmations + " decodes=" + decodes.get());
    }
    private void finish() {
        if (finished) return;
        finished = true; loader.cancel();
        // Never wait for worker delivery while blocking FX. Only join its computation off FX.
        Thread cleanup = new Thread(() -> {
            loader.awaitInFlight(10000);
            try { session.store().deleteAll(); }
            catch (Exception error) { error.printStackTrace(); failures++; }
            System.out.println("LARGE-FILE-GUI locale=" + I18n.currentLocale() + " failures=" + failures
                    + " starts=" + starts + " confirmations=" + confirmations + " decodes=" + decodes.get());
            System.exit(failures == 0 ? 0 : 1);
        }, "large-probe-cleanup"); cleanup.setDaemon(true); cleanup.start();
    }
    private final java.util.Map<String, com.mhtextractor.uicore.PreviewRow> displayRows = new java.util.HashMap<>();
    private com.mhtextractor.uicore.PreviewRow display(PreviewItem domain) {
        var row = displayRows.computeIfAbsent(domain.path, path -> new com.mhtextractor.uicore.PreviewRow(
                new com.mhtextractor.uiport.ItemVm(path, domain.name, domain.selected, domain.removed, 0,
                        com.mhtextractor.uiport.UpdateKind.NONE, domain.sourceFolder), ignored -> { }));
        row.cachedThumbs = domain.cachedThumbs;
        row.cachedMeta = domain.cachedMeta == null ? null : new com.mhtextractor.uiport.PresentationVm(
                domain.path, domain.name, false, true, "READY", "", "", null, List.of(), domain.name, null, null);
        return row;
    }
    public Object currentSelection() { return display(current); }
    public PreviewRenderer renderer() { return renderer; }
    public ProgressBar progressBar() { return progress; }
    public Label statusLabel() { return status; }
    public Label imageHintLabel() { return hint; }
    public Button previewEffectButton() { return effect; }
    public boolean extractionRunning() { return false; }
    public void setProgressBarActivity(String style) { }
    public void updateProgressStatus(long token) { }
    public void refreshList() { }
    public void updateContentArea(Object selection) { }
    public TextArea textPreviewArea() { return text; }
    public ScrollPane imageScrollPane() { return images; }
    public FlowPane thumbnailPane() { return thumbnails; }
    public AppConfig effectiveParams(Object item) { return params; }
    public int visibleCount() { return 2; }
    public int unitIndex(Object item) { return 0; }
    public static void main(String[] args) { launch(args); }
}
