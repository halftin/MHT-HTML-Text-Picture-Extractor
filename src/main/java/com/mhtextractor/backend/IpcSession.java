package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.*;
import com.mhtextractor.core.SourceParsers;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Backend host assembly. All model commands and dispatched callbacks run on the IPC reader thread. */
final class IpcSession implements AutoCloseable {
    static final List<String> METHODS = List.of("importPaths", "group", "setParams", "extract",
            "workspacePage", "previewFiles", "fileInfo", "releaseFiles", "metrics");
    private final ObjectMapper json;
    private final WorkspaceModel model = new WorkspaceModel();
    private final GlobalParameterCache global = new GlobalParameterCache();
    private final ArrayBlockingQueue<Runnable> callbacks = new ArrayBlockingQueue<>(256);
    private final Thread host = Thread.currentThread();
    private final IpcFiles files;
    private final PreviewSession previews;
    private final PreviewService previewService;
    private final WorkspaceExtractionCoordinator extractions;
    final Uiport port;
    private Consumer<ObjectNode> events;
    private boolean importDone;
    private boolean importFailed;
    private PortEvent.ExtractionTerminal terminal;
    private boolean imageMode;
    private String outputFolder;
    private int progressBucket = -1;
    private long rawProgress;
    private long sentProgress;
    private volatile int queuePeak;
    private volatile boolean closing;

    IpcSession(ObjectMapper json) throws IOException {
        this.json = json;
        files = new IpcFiles(json);
        previews = PreviewSession.create(files.root.resolve("preview-cache"));
        previewService = new PreviewService(previews.lifecycle());
        var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return imageMode; }
            public AppConfig textParamsCache() { return global.text(); }
            public AppConfig imageParamsCache() { return global.image(); }
            public String inputFolder() { return null; }
            public String outputFolder() { return outputFolder; }
            public boolean openFolderAfterComplete() { return false; }
        });
        Confirmer confirmer = request -> new ConfirmToken() {
            public void answer(String actionKey) { throw new UnsupportedOperationException(); }
            public void cancel() { }
            public String await() { return null; } // Unsupported confirmations conservatively stop.
        };
        var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return !imageMode; }
            public AppConfig effectiveParams(PreviewItem item) { return params(item, imageMode); }
            public AppConfig effectiveImageParams(PreviewItem item) { return params(item, true); }
        }, previewService, (image, index, config) -> new ThumbnailData("", 0, 0, null), this::dispatch);
        var clipboard = new ParamClipboard(model::push, target -> ParamScope.GLOBAL, (target, mode, config) -> { });
        var paramCommands = new WorkspaceParamCommandService(model, new WorkspaceParamService(previews.lifecycle()),
                global, clipboard::pushParamUndo);
        // A server import round is bounded to 20k inputs; quota confirmations are deferred to P4-3.
        var imports = new WorkspaceImportCoordinator(model, confirmer, this::dispatch, 200, 20000);
        extractions = new WorkspaceExtractionCoordinator(new WorkspaceExtractionService(previews.lifecycle()),
                WorkspaceExtractionCoordinator.productionRequests(plans,
                        () -> model.allItems().stream().filter(item -> !item.removed).toList(), confirmer), this::dispatch);
        port = new InProcessUiport(model, confirmer, plans, activities, paramCommands, imports, extractions);
        port.events().subscribe(this::onEvent);
    }

    void events(Consumer<ObjectNode> sink) { events = sink; }

    private AppConfig params(PreviewItem item, boolean mode) {
        AppConfig config = ParamScope.effectiveParams(item.parentGroup, item.textItemParams,
                item.imageItemParams, mode, global.text(), global.image());
        return config == null ? new AppConfig() : config;
    }

    private void dispatch(Runnable callback) {
        if (closing) return;
        if (Thread.currentThread() == host) { callback.run(); return; }
        try {
            while (!closing && !callbacks.offer(callback, 100, TimeUnit.MILLISECONDS)) { }
            queuePeak = Math.max(queuePeak, callbacks.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Host dispatch interrupted", e);
        }
    }

    private void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        // Synchronous methods finish their activity before responding; no worker touches the model afterward.
        while (!done.getAsBoolean()) {
            Runnable callback = callbacks.poll(1, TimeUnit.SECONDS);
            if (callback != null) callback.run();
        }
    }

    private void emit(String name, ObjectNode data) {
        ObjectNode frame = json.createObjectNode().put("v", 1).put("type", "event").put("event", name);
        frame.set("data", data);
        events.accept(frame);
    }

    private void onEvent(PortEvent event) {
        switch (event) {
            case PortEvent.ImportRound round -> emit("importRound", json.createObjectNode()
                    .put("generation", round.generation()).put("newFiles", round.newFiles()).put("newGroups", round.newGroups()));
            case PortEvent.ImportFinished finished -> {
                importDone = true;
                emit("importFinished", json.createObjectNode().put("generation", finished.generation())
                        .put("count", finished.accumulatedPaths().size()).put("failed", importFailed));
            }
            case PortEvent.Notice notice -> {
                importFailed = true;
                emit("notice", json.createObjectNode().put("key", notice.key()));
            }
            case PortEvent.ParamStateChanged changed -> emit("paramsChanged", json.createObjectNode()
                    .put("kind", changed.kind().name()).put("affectedCount", changed.affectedPaths().size()));
            case PortEvent.ExtractionProgress progress -> {
                rawProgress++;
                int bucket = (int) (Math.clamp(progress.overall(), 0, 1) * 100);
                if (bucket > progressBucket) {
                    progressBucket = bucket;
                    sentProgress++;
                    emit("extractionProgress", json.createObjectNode().put("generation", progress.generation())
                            .put("overall", progress.overall()));
                }
            }
            case PortEvent.ExtractionTerminal end -> {
                terminal = end;
                emit("extractionTerminal", terminalSummary(end));
            }
            default -> { } // No automatic workspace snapshots, per-item payloads, or thumbnail pixels.
        }
    }

    private ObjectNode terminalSummary(PortEvent.ExtractionTerminal end) {
        ObjectNode summary = json.createObjectNode().put("generation", end.generation()).put("end", end.end().name())
                .put("outputFolder", end.sessionOutputFolder());
        if (end.stats() != null) summary.put("count", end.stats().files().size()).put("failCount", end.stats().failCount())
                .put("skipCount", end.stats().skipCount()).put("warnCount", end.stats().warnCount());
        return summary;
    }

    JsonNode call(String method, JsonNode p) throws Exception {
        switch (method) {
            case "importPaths" -> {
                List<String> paths = strings(p, "paths");
                for (String path : paths) IpcFiles.input(path);
                importDone = false;
                importFailed = false;
                port.commands().importPaths(paths, "IPC", false);
                await(() -> importDone);
                return json.createObjectNode().put("count", model.allItems().size()).put("failed", importFailed);
            }
            case "group" -> {
                List<String> paths = strings(p, "paths");
                String prefix = string(p, "namePrefix");
                port.commands().group(paths, prefix);
                return json.nullNode();
            }
            case "setParams" -> {
                boolean mode = bool(p, "imageMode");
                if (!p.path("config").isObject()) throw new IllegalArgumentException("config object required");
                AppConfig config = json.treeToValue(p.path("config"), AppConfig.class);
                port.commands().setGlobalParams(mode, config);
                imageMode = mode;
                return json.nullNode();
            }
            case "extract" -> {
                boolean checked = bool(p, "checkedOnly");
                String out = string(p, "outputFolder");
                if (model.allItems().stream().noneMatch(item -> !item.removed && (!checked || item.selected))) {
                    throw new IllegalArgumentException("No extraction targets");
                }
                // Revalidate imported ids immediately before the parser opens them.
                for (PreviewItem item : model.allItems()) if (!item.removed) IpcFiles.input(item.path);
                outputFolder = IpcFiles.output(out).toString();
                terminal = null;
                progressBucket = -1;
                port.commands().startExtraction(checked);
                await(() -> terminal != null);
                var result = terminalSummary(terminal);
                if (terminal.stats() != null) {
                    var outcomes = json.createArrayNode();
                    for (var outcome : terminal.stats().files()) outcomes.addObject().put("path", outcome.path())
                            .put("outcome", outcome.outcome().name()).put("message", outcome.message());
                    result.set("resultsFile", files.write(".json", json.writeValueAsBytes(outcomes), "application/json"));
                }
                // Use the lifecycle's batch deletion for completed inputs (same deletion semantics as rule b).
                previews.lifecycle().onItemsRemoved(model.allItems().stream().map(item -> item.path).toList());
                return result;
            }
            case "previewFiles" -> { return preview(p); }
            case "fileInfo" -> {
                Path path = files.validateIssued(string(p, "path"));
                return files.issue(path, "application/octet-stream");
            }
            case "releaseFiles" -> {
                List<String> paths = strings(p, "paths");
                for (String path : paths) files.validateIssued(path); // Atomic validation before deletion.
                for (String path : paths.stream().distinct().toList()) files.release(path);
                return json.nullNode();
            }
            case "metrics" -> {
                long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                        .mapToLong(pool -> Math.max(0, pool.getPeakUsage().getUsed())).sum();
                return json.createObjectNode().put("heapPoolPeakBytes", peak).put("callbackQueuePeak", queuePeak)
                        .put("rawProgress", rawProgress).put("sentProgress", sentProgress);
            }
            default -> throw new IllegalArgumentException("Unknown extension method");
        }
    }

    private ObjectNode preview(JsonNode p) throws Exception {
        String id = string(p, "path");
        PreviewItem item = model.allItems().stream().filter(it -> it.path.equals(id) && !it.removed).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Preview requires an imported item id"));
        Path source = IpcFiles.input(id);
        var text = SourceParseService.parse(id, params(item, false), true);
        var parsed = SourceParsers.forPath(id).parse(id, true, () -> false, null, null);
        var result = previewService.ensureCachedLocked(previewService.prepare(source, params(item, true)), () -> false);
        ObjectNode manifest = json.createObjectNode();
        manifest.set("text", files.write(".txt", (text.text() == null ? "" : text.text()).getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"));
        manifest.set("html", files.write(".html", (parsed.getHtmlContent() == null ? "" : parsed.getHtmlContent())
                .getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        var thumbs = manifest.putArray("thumbnails");
        for (int i = 0; i < result.images().size(); i++) {
            Path image = previews.store().imageFileFor(result.meta(), i);
            ObjectNode ref = files.issue(image, "image/" + result.meta().images().get(i).detectedExt());
            ref.put("name", result.thumbs().get(i).coreName()).put("index", i);
            thumbs.add(ref); // Encoded original: client decodes/scales, never ARGB/base64 over stdio.
        }
        return json.createObjectNode().put("imageCount", thumbs.size())
                .set("manifest", files.write(".json", json.writeValueAsBytes(manifest), "application/json"));
    }

    static String string(JsonNode p, String key) {
        if (!p.path(key).isTextual() || p.path(key).asText().isBlank()) throw new IllegalArgumentException(key + " string required");
        return p.path(key).asText();
    }

    static boolean bool(JsonNode p, String key) {
        if (!p.path(key).isBoolean()) throw new IllegalArgumentException(key + " boolean required");
        return p.path(key).booleanValue();
    }

    static List<String> strings(JsonNode p, String key) {
        if (!p.path(key).isArray() || p.path(key).isEmpty()) throw new IllegalArgumentException(key + " nonempty array required");
        var paths = new java.util.ArrayList<String>();
        for (JsonNode value : p.path(key)) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(key + " string entries required");
            paths.add(value.asText());
        }
        return paths;
    }

    @Override public void close() throws IOException {
        closing = true;
        events = event -> { };
        callbacks.clear();
        port.commands().cancelWorkspaceActivity();
        port.commands().cancelExtraction();
        try {
            var channel = extractions.channel();
            if (channel != null) channel.result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Extraction worker did not finish during shutdown", e);
        } finally {
            previews.lifecycle().onShutdown(() -> { });
            files.close();
        }
    }
}
