package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.Uiport;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit in-process assembly for tests and the opt-in fallback. Owns all business state. */
public final class BackendWorkspace implements AutoCloseable {
    private final WorkspaceModel model = new WorkspaceModel();
    private final GlobalParameterCache globals = new GlobalParameterCache();
    private final PreviewSession previews;
    private final WorkspaceActivityService activities;
    private final WorkspaceExtractionCoordinator extractions;
    private final WorkspaceParamService caches;
    private final InProcessUiport port;

    public BackendWorkspace(Confirmer confirmer, Supplier<AppConfig> runtime, Consumer<Runnable> dispatcher) {
        previews = PreviewSession.create(Path.of(System.getProperty("java.io.tmpdir"),
                "mht-extractor-preview", "workspace-" + UUID.randomUUID()));
        previews.startupCleanup();
        caches = new WorkspaceParamService(previews.lifecycle());
        var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return runtime.get().isImageModeEnabled(); }
            public AppConfig textParamsCache() { return globals.text(); }
            public AppConfig imageParamsCache() { return globals.image(); }
            public String inputFolder() { return runtime.get().getInputFolder(); }
            public String outputFolder() { return runtime.get().getOutputFolder(); }
            public boolean openFolderAfterComplete() { return runtime.get().isOpenFolderAfterComplete(); }
        });
        activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return !runtime.get().isImageModeEnabled(); }
            public AppConfig effectiveParams(PreviewItem item) { return plans.effectiveParams(item); }
            public AppConfig effectiveImageParams(PreviewItem item) { return plans.effectiveImageParams(item); }
        }, new PreviewService(previews.lifecycle()), (image, index, config) -> {
            String ext = PreviewNamingService.detectExtension(image.data(), "jpg");
            int[] size = InProcessUiport.imageDimensions(image.data(), ext);
            return new ThumbnailData(PreviewNamingService.imageFileName(image, ext,
                    config.isEnableBatchRenaming(), index + 1, ""), size[0], size[1], null);
        }, dispatcher::accept);
        var clipboard = new ParamClipboard(model::push, target -> ParamScope.GLOBAL,
                (target, mode, config) -> { throw new UnsupportedOperationException("Use parameter commands"); });
        var parameters = new WorkspaceParamCommandService(model, caches, globals, clipboard::pushParamUndo);
        extractions = new WorkspaceExtractionCoordinator(new WorkspaceExtractionService(previews.lifecycle()),
                WorkspaceExtractionCoordinator.productionRequests(plans,
                        () -> model.allItems().stream().filter(item -> !item.removed).toList(), confirmer), dispatcher);
        port = new InProcessUiport(model, confirmer, plans, activities, parameters,
                new WorkspaceImportCoordinator(model, confirmer, dispatcher), extractions, caches, globals, previews);
    }

    public Uiport port() { return port; }

    @Override public void close() {
        activities.cancelWorkspaceActivity();
        activities.cancelPreviewLoad();
        extractions.cancelExtraction();
        var run=extractions.channel();
        if(run!=null)try{run.result.get(20,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception error){throw new IllegalStateException("Extraction worker did not finish before cleanup",error);}
        activities.awaitPreviewInFlight(2_000);
        caches.clearAllCaches();
    }
}
