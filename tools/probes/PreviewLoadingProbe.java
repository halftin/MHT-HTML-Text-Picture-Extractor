import com.mhtextractor.app.*;
import com.mhtextractor.gui.controller.PreviewLoader;
import com.mhtextractor.gui.controller.PreviewRenderer;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * Public production collaborators + real FX nodes; no test-source driver/reflection.
 * Queued worker delivery guarantees cancel/stale AFTER loading, BEFORE completion.
 * Host rendering follows MainController's cachedThumbs/loading/lazy branches.
 * Red control: compile PreviewLoader with BOTH updateContentArea calls removed
 * (pre-P4-15 behavior) and put that class first on this unchanged probe's classpath.
 * B/D label-reset assertions must fail; restore the class before the green run.
 */
public class PreviewLoadingProbe extends Application implements PreviewLoader.Host, PreviewRenderer.Host {
    private final Label hint = new Label(), status = new Label();
    private final Button effect = new Button();
    private final ProgressBar progress = new ProgressBar();
    private final TextArea text = new TextArea();
    private final FlowPane thumbnails = new FlowPane();
    private final ScrollPane images = new ScrollPane(thumbnails);
    private final ConcurrentLinkedQueue<Runnable> deliveries = new ConcurrentLinkedQueue<>();
    private PreviewSession session;
    private PreviewRenderer renderer;
    private PreviewLoader loading;
    private PreviewItem item;
    private AppConfig params = new AppConfig();
    private Path work;
    private int failures;

    public void start(Stage stage) throws Exception {
        I18n.setLocale(I18n.parseLocale(System.getProperty("probe.locale", "zh_CN")));
        work = Files.createTempDirectory("mht-extractor-loading-probe-");
        Path source = work.resolve("empty.html");
        Files.writeString(source, "<html><body>START END</body></html>");
        item = new PreviewItem(source.toString(), Texts.get("test.previewLoadingName"),
                LocalDateTime.now(), work.toString());
        session = PreviewSession.create(work.resolve("cache"));
        renderer = new PreviewRenderer(this);
        WorkspaceModel model = new WorkspaceModel();
        model.addItems(List.of(item));
        var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return false; }
            public AppConfig effectiveParams(PreviewItem ignored) { return params; }
            public AppConfig effectiveImageParams(PreviewItem ignored) { return params; }
        }, new PreviewService(session.lifecycle()), renderer::decodeThumb, deliveries::add);
        var port = new InProcessUiport(model, request -> {
            throw new AssertionError("Empty HTML should require no confirmation");
        }, activities);
        loading = new PreviewLoader(this, port);
        effect.setOnAction(ignored -> loading.load());
        stage.setScene(new Scene(new VBox(8, hint, effect, images, progress, status), 700, 400));
        stage.show();
        updateContentArea(item);
        effect.fire();
        check("B-entered-loading", loading.isActive() && loadingText());
        loading.cancel(); // same FX frame; completion is still behind the delivery gate
        check("B-cancel-label-reset", !loading.isActive() && lazyResult());
        barrier(() -> {
            drain();
            effect.fire();
            waitFor("B-result", () -> { drain(); return !loading.isActive(); }, () -> {
                check("B-result-noImages", emptyResult());
                stale();
            });
        });
    }

    private void stale() {
        item.resetPreviewState();
        updateContentArea(item);
        effect.fire();
        check("D-entered-loading", loading.isActive() && loadingText());
        AppConfig changed = new AppConfig();
        changed.setImageNameStartMarker("changed-while-loading");
        params = changed; // effective fingerprint changes before any completion can be delivered
        waitFor("D-stale", () -> { drain(); return !loading.isActive(); }, () -> {
            check("D-stale-result-discarded", item.cachedThumbs == null && item.cachedMeta == null);
            check("D-stale-label-reset", lazyResult());
            barrier(() -> {
                effect.fire();
                waitFor("D-result", () -> { drain(); return !loading.isActive(); }, () -> {
                    check("D-result-noImages", emptyResult());
                    finish();
                });
            });
        });
    }

    private void barrier(Runnable next) {
        Thread thread = new Thread(() -> {
            loading.awaitInFlight(10000); // off FX, public worker/cleanup barrier
            Platform.runLater(next);
        }, "probe-worker-barrier");
        thread.setDaemon(true);
        thread.start();
    }
    private boolean loadingText() { return hint.getText().equals(Texts.get("preview.imageLoading")); }
    private boolean lazyResult() {
        return !loadingText() && hint.isVisible()
                && hint.getText().equals(Texts.get("preview.imageLazyHint"));
    }
    private boolean emptyResult() {
        return item.cachedThumbs != null && item.cachedThumbs.isEmpty() && hint.isVisible()
                && hint.getText().equals(Texts.get("preview.noImages"))
                && !images.isVisible() && !effect.isVisible();
    }
    private void drain() { Runnable task; while ((task = deliveries.poll()) != null) task.run(); }
    private void waitFor(String name, BooleanSupplier condition, Runnable next) {
        poll(name, condition, next, System.nanoTime() + 10_000_000_000L);
    }
    private void poll(String name, BooleanSupplier condition, Runnable next, long deadline) {
        try {
            if (condition.getAsBoolean()) { next.run(); return; }
            if (System.nanoTime() >= deadline) { check(name + "-timeout", false); finish(); return; }
            var tick = new PauseTransition(Duration.millis(20));
            tick.setOnFinished(ignored -> poll(name, condition, next, deadline));
            tick.play();
        } catch (Throwable error) { error.printStackTrace(); failures++; finish(); }
    }
    private void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " active=" + loading.isActive()
                + " label=" + hint.getText());
    }
    private void finish() {
        loading.cancel();
        loading.awaitInFlight(10000);
        try {
            session.store().deleteAll();
            Files.deleteIfExists(work.resolve("empty.html"));
            Files.deleteIfExists(work.resolve("cache"));
            Files.deleteIfExists(work);
        } catch (Exception error) { error.printStackTrace(); failures++; }
        System.out.println("PREVIEW-GUI locale=" + I18n.currentLocale() + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private final java.util.Map<String, com.mhtextractor.uicore.PreviewRow> displayRows = new java.util.HashMap<>();
    private com.mhtextractor.uicore.PreviewRow display(PreviewItem domain) {
        var row = displayRows.computeIfAbsent(domain.path, path -> new com.mhtextractor.uicore.PreviewRow(
                new com.mhtextractor.uiport.ItemVm(path, domain.name, domain.selected, domain.removed, 0,
                        com.mhtextractor.uiport.UpdateKind.NONE, domain.sourceFolder), ignored -> { }));
        row.cachedThumbs = domain.cachedThumbs;
        row.cachedMeta = domain.cachedMeta == null ? null : new com.mhtextractor.uiport.PresentationVm(
                domain.path, domain.name, false, true, "READY", "", "", null, List.of());
        return row;
    }
    public Object currentSelection() { return display(item); }
    public PreviewRenderer renderer() { return renderer; }
    public ProgressBar progressBar() { return progress; }
    public Label statusLabel() { return status; }
    public Label imageHintLabel() { return hint; }
    public Button previewEffectButton() { return effect; }
    public boolean extractionRunning() { return false; }
    public void setProgressBarActivity(String styleClass) { }
    public void updateProgressStatus(long token) { }
    public void refreshList() { }
    public void updateContentArea(Object selection) {
        if (item.cachedThumbs != null) renderer.renderThumbnails(display(item));
        else if (loading.isLoading(display(item))) hint.setText(Texts.get("preview.imageLoading"));
        else renderer.resetImageArea(null, true);
    }
    public TextArea textPreviewArea() { return text; }
    public ScrollPane imageScrollPane() { return images; }
    public FlowPane thumbnailPane() { return thumbnails; }
    public AppConfig effectiveParams(Object selection) { return params; }
    public int visibleCount() { return 1; }
    public int unitIndex(Object selection) { return 0; }
    public static void main(String[] args) { launch(args); }
}
