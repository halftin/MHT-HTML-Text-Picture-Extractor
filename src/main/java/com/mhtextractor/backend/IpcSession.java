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

/** Backend host assembly. All model commands and callbacks run on the serial IPC host thread. */
final class IpcSession implements AutoCloseable {
    static final List<String> METHODS = List.of("importPaths", "group", "setParams", "extract",
            "workspacePage", "previewFiles", "fileInfo", "releaseFiles", "metrics", "confirmAnswer");
    static final List<String> STRUCTURE_METHODS = List.of(
            "ungroup", "renameGroup", "toggleGroupExpanded", "clearItems", "applyDrop", "removeItems", "removeGroup", "canUndo");
    static final List<String> ACTIVITY_METHODS = List.of("cancelWorkspaceActivity", "cancelPreviewLoad",
            "startPreparse", "startPreparseFor", "refreshPreparseProgress", "loadPreview",
            "resolvePreviewRegion", "awaitPreviewInFlight", "applyImportPlan",
            "activityGeneration", "previewGeneration", "previewLoadingPath");
    static final List<String> PARAM_METHODS = List.of("setGlobalParams", "setGroupParams", "setItemParams",
            "clearParams", "applyGlobalParams", "undo", "runtimeConfig", "outputUnitPlan", "paramSchema");
    private final ObjectMapper json;
    private final WorkspaceModel model = new WorkspaceModel();
    private final GlobalParameterCache global = new GlobalParameterCache();
    private final ArrayBlockingQueue<Runnable> callbacks = new ArrayBlockingQueue<>(256);
    private final Thread host = Thread.currentThread();
    private final IpcFiles files;
    private final PreviewSession previews;
    private final PreviewService previewService;
    private final WorkspaceExtractionCoordinator extractions;
    private final IpcConfirmations confirmations;
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
    private volatile boolean disconnecting;
    private boolean importing;
    private boolean applyingPlan;
    private boolean activityImageMode;
    private final java.util.Set<Thread> workers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.List<String> importChanges = new java.util.ArrayList<>();
    private final java.util.Map<Long, PreviewService.Prepared> previewInputs = new java.util.HashMap<>();
    private final java.util.Map<Long, java.util.List<PortEvent.PreviewNotice>> pendingNotices = new java.util.HashMap<>();
    private final IpcWorkspaceCodec codec;
    private final IpcParamCodec paramCodec;
    private Runnable inputPump = () -> { };
    private Runnable silentChange = () -> { };

    IpcSession(ObjectMapper json) throws IOException {
        this.json = json;
        confirmations = new IpcConfirmations(json, this::dispatch, data -> emit("confirmationRequested", data),
                data -> emit("confirmationExpired", data));
        files = new IpcFiles(json);
        codec = new IpcWorkspaceCodec(json, files);
        paramCodec = new IpcParamCodec(json);
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
        Confirmer confirmer = confirmations;
        var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
            public List<PreviewItem> items() { return model.allItems(); }
            public boolean textMode() { return !activityImageMode; }
            public AppConfig effectiveParams(PreviewItem item) { return params(item, activityImageMode); }
            public AppConfig effectiveImageParams(PreviewItem item) { return params(item, true); }
        }, previewService, IpcImages::thumbnail, this::dispatch);
        var clipboard = new ParamClipboard(model::push, target -> ParamScope.GLOBAL, (target, mode, config) -> { });
        var paramCommands = new WorkspaceParamCommandService(model, new WorkspaceParamService(previews.lifecycle()),
                global, clipboard::pushParamUndo);
        // A server import round is bounded to 20k inputs; continuing requires a client decision.
        var imports = new WorkspaceImportCoordinator(model, confirmer, this::dispatch, 200, 20000);
        extractions = new WorkspaceExtractionCoordinator(new WorkspaceExtractionService(previews.lifecycle()),
                WorkspaceExtractionCoordinator.productionRequests(plans,
                        () -> model.allItems().stream().filter(item -> !item.removed).toList(), confirmer), this::dispatch);
        port = new InProcessUiport(model, confirmer, plans, activities, paramCommands, imports, extractions,
                new WorkspaceParamService(previews.lifecycle()));
        port.events().subscribe(this::onEvent);
    }

    void events(Consumer<ObjectNode> sink) { events = sink; }

    IpcFiles files() { return files; }

    void silentChanges(Runnable changed) { silentChange = changed; }

    void drainCallbacks() {
        for (int i = 0; i < 256; i++) {
            var callback = callbacks.poll();
            if (callback == null) return;
            callback.run();
        }
    }

    void inputPump(Runnable pump) { inputPump = pump; }

    void disconnect() {
        if (disconnecting) return;
        disconnecting = true;
        try {
            // Mark the run cancelled before waking its confirmation waiter; otherwise it may win a natural-completion race.
            port.commands().cancelExtraction();
        } finally {
            confirmations.disconnect(); // Also release import confirmations, even if terminal delivery broke stdout.
            if (port.queries().previewLoadingPath() != null
                    || workers.stream().anyMatch(t -> t.isAlive() && t.getName().equals("preview-preparse")))
                port.commands().cancelWorkspaceActivity();
        }
    }

    private AppConfig params(PreviewItem item, boolean mode) {
        AppConfig config = ParamScope.effectiveParams(item.parentGroup, item.textItemParams,
                item.imageItemParams, mode, global.text(), global.image());
        return config == null ? new AppConfig() : config;
    }

    private void dispatch(Runnable callback) {
        if (closing) return;
        if (Thread.currentThread() == host) { callback.run(); return; }
        String workerName = Thread.currentThread().getName();
        if (workerName.startsWith("preview-") || workerName.startsWith("mht-import-") || workerName.startsWith("extraction-"))
            workers.add(Thread.currentThread());
        boolean interrupted = false;
        try {
            while (!closing) {
                try {
                    if (callbacks.offer(callback, 25, TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException e) { interrupted = true; }
            }
            queuePeak = Math.max(queuePeak, callbacks.size());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void start(Runnable command) {
        var before = Thread.getAllStackTraces().keySet();
        command.run();
        // Existing app coordinators create these workers synchronously before returning.
        // Keep their handles so shutdown also joins a parser that has not delivered a callback yet.
        Thread.getAllStackTraces().keySet().stream().filter(t -> !before.contains(t))
                .filter(t -> t.getName().equals("preview-preparse") || t.getName().equals("preview-image-load")
                        || t.getName().equals("mht-import-scan")).forEach(workers::add);
    }

    boolean busy() { return importing || !pendingNotices.isEmpty() || port.queries().previewLoadingPath() != null
            || workers.stream().anyMatch(t -> t.isAlive() && t.getName().equals("preview-preparse")); }

    private void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        // Synchronous methods finish their activity before responding; no worker touches the model afterward.
        while (!done.getAsBoolean()) {
            inputPump.run();
            Runnable callback = callbacks.poll(25, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
    }

    private void emit(String name, ObjectNode data) {
        ObjectNode frame = json.createObjectNode().put("v", 1).put("type", "event").put("event", name);
        frame.set("data", data);
        events.accept(frame);
    }

    private void onEvent(PortEvent event) {
        try { encodeEvent(event); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private void encodeEvent(PortEvent event) throws IOException {
        switch (event) {
            case PortEvent.WorkspaceActivityChanged changed -> emit("workspaceActivityChanged", generation(changed.generation()));
            case PortEvent.PreparseStarted started -> emit("preparseStarted", generation(started.generation()));
            case PortEvent.PreparseProgress progress -> emit("preparseProgress", generation(progress.generation())
                    .put("done", progress.done()).put("total", progress.total()));
            case PortEvent.PreparseFinished finished -> {
                var result = finished.result();
                var value = json.createObjectNode().put("path", result.path()).put("status", result.status().name())
                        .put("errorKey", result.errorKey()).put("errorDetail", result.errorDetail())
                        .put("text", result.text()).put("textAssigned", result.textAssigned())
                        .put("title", result.title()).put("titleAssigned", result.titleAssigned());
                emit("preparseFinished", generation(finished.generation()).set("result", codec.file(value)));
            }
            case PortEvent.WorkspaceChanged changed -> {
                if (importing || applyingPlan) importChanges.add(changed.kind());
                else emit("workspaceChanged", json.createObjectNode().put("kind", changed.kind()));
            }
            case PortEvent.ImportStateChanged state -> {
                importing = state.importing();
                if (!importing) flushImportChanges();
                emit("importStateChanged", generation(state.generation()).put("importing", state.importing()));
            }
            case PortEvent.ImportRound round -> {
                flushImportChanges();
                var groups = json.createArrayNode();
                var snapshot = port.queries().workspace();
                for (var group : round.createdGroups()) groups.add(codec.group(snapshot.groups().stream()
                        .filter(vm -> vm.no() == group.getGroupNo()).findFirst().orElseThrow()));
                var data = generation(round.generation()).put("newFiles", round.newFiles()).put("newGroups", round.newGroups())
                        .put("madeListNonEmpty", round.madeListNonEmpty());
                data.set("plan", codec.file(json.valueToTree(round.plan())));
                data.set("createdGroups", codec.file(groups));
                emit("importRound", data);
            }
            case PortEvent.ImportFinished finished -> {
                importDone = true;
                emit("importFinished", generation(finished.generation())
                        .put("count", finished.accumulatedPaths().size()).put("failed", importFailed)
                        .set("accumulatedPaths", codec.file(json.valueToTree(finished.accumulatedPaths()))));
            }
            case PortEvent.Notice notice -> {
                importFailed = true;
                emit("notice", json.createObjectNode().put("key", notice.key()).put("level", notice.level().name())
                        .set("namedParams", json.valueToTree(notice.namedParams())));
            }
            case PortEvent.PreviewLoadStateChanged state -> {
                if (state.active()) {
                    var item = item(state.path());
                    previewInputs.put(state.generation(), previewService.prepare(Path.of(state.path()), params(item, activityImageMode)));
                }
                emit("previewLoadStateChanged", generation(state.generation()).put("path", state.path()).put("active", state.active()));
            }
            case PortEvent.PreviewConfirmationRequired required -> emit("previewConfirmationRequired", json.createObjectNode()
                    .put("path", required.path()).put("name", required.name()).put("sizeMiB", required.sizeMiB()));
            case PortEvent.PreviewLoadFailed failed -> {
                previewInputs.remove(failed.generation());
                emit("previewLoadFailed", generation(failed.generation()).put("path", failed.path())
                        .put("fileInfo", failed.fileInfo()).put("detail", failed.detail()));
            }
            case PortEvent.PreviewThumbnail thumbnail -> {
                var prepared = previewInputs.get(thumbnail.generation());
                if (prepared == null) throw new IOException("Missing preview input");
                var meta = previews.store().load(prepared.cacheKey()).orElseThrow(() -> new IOException("Missing preview cache"));
                var data = image(meta, thumbnail.index(), thumbnail.thumbnail());
                data.put("generation", thumbnail.generation()).put("path", thumbnail.path()).put("index", thumbnail.index());
                emit("previewThumbnail", data);
            }
            case PortEvent.PreviewLoaded loaded -> {
                var data = generation(loaded.generation()).put("path", loaded.path());
                var item = item(loaded.path());
                if (item.cachedMeta != null && loaded.generation() == port.queries().previewGeneration()) {
                    exportLoaded(loaded, item, data);
                } else {
                    data.putNull("manifest");
                    emit("previewLoaded", data);
                }
                previewInputs.remove(loaded.generation());
            }
            case PortEvent.PreviewRegionConfirmationRequired required -> emit("previewRegionConfirmationRequired",
                    generation(required.generation()).put("path", required.path()));
            case PortEvent.PreviewNotice notice -> {
                if (pendingNotices.containsKey(notice.generation())) {
                    pendingNotices.get(notice.generation()).add(notice);
                    return;
                }
                var args = json.createObjectNode();
                var meta = item(notice.path()).cachedMeta;
                if (meta != null) meta.warnings().stream().filter(w -> w.key().equals(notice.key()))
                        .findFirst().ifPresent(w -> w.args().forEach(args::put));
                emit("previewNotice", generation(notice.generation()).put("path", notice.path()).put("key", notice.key())
                        .set("namedParams", args));
            }
            case PortEvent.ParamStateChanged changed -> {
                // The GUI host already resets derived preview state after parameter events.
                // This host must also discard in-memory references to invalidated cache files.
                var affected = new java.util.HashSet<>(changed.affectedPaths());
                for (var item : model.allItems()) if (affected.contains(item.path)) item.resetPreviewState();
                emit("paramsChanged", json.createObjectNode()
                        .put("kind", changed.kind().name()).put("affectedCount", changed.affectedPaths().size())
                        .put("imageMode", changed.imageMode())
                        .set("affectedPaths", codec.file(json.valueToTree(changed.affectedPaths()))));
            }
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
            default -> { } // Extraction/result additions remain P5a-1d; never send thumbnail pixels.
        }
    }

    private ObjectNode generation(long value) { return json.createObjectNode().put("generation", value); }

    private void flushImportChanges() {
        var changes = List.copyOf(importChanges);
        importChanges.clear();
        for (var kind : changes) emit("workspaceChanged", json.createObjectNode().put("kind", kind));
    }

    private PreviewItem item(String path) {
        return model.allItems().stream().filter(it -> it.path.equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Imported item required"));
    }

    private ObjectNode image(com.mhtextractor.cache.PreviewCacheMeta meta, int index, ThumbnailData thumbnail) throws IOException {
        Path path = previews.store().imageFileFor(meta, index);
        String ext = IpcImages.extension(Files.readAllBytes(path));
        var ref = files.borrowImage(path, IpcImages.mediaType(ext));
        return ref.put("imagePath", path.toString()).put("extension", ext).put("coreName", thumbnail.coreName())
                .put("width", thumbnail.width()).put("height", thumbnail.height());
    }

    private void exportLoaded(PortEvent.PreviewLoaded loaded, PreviewItem item, ObjectNode data) throws IOException {
        var manifest = json.createObjectNode();
        var thumbnails = manifest.putArray("thumbnails");
        for (int i = 0; i < item.cachedMeta.images().size(); i++) {
            var thumb = item.cachedThumbs.get(i);
            thumbnails.add(image(item.cachedMeta, i, thumb).put("name", thumb.coreName()).put("index", i));
        }
        AppConfig textParams = params(item, false).copyExtractionParams();
        pendingNotices.put(loaded.generation(), new java.util.ArrayList<>());
        Thread worker = new Thread(() -> {
            Path textFile = null;
            Path htmlFile = null;
            Throwable failed = null;
            try {
                var text = SourceParseService.parse(item.path, textParams, true);
                var parsed = SourceParsers.forPath(item.path).parse(item.path, true, () -> disconnecting, null, null);
                textFile = Files.createTempFile(files.root, "bulk-", ".txt");
                htmlFile = Files.createTempFile(files.root, "bulk-", ".html");
                Files.writeString(textFile, text.text() == null ? "" : text.text(), StandardCharsets.UTF_8);
                Files.writeString(htmlFile, parsed.getHtmlContent() == null ? "" : parsed.getHtmlContent(), StandardCharsets.UTF_8);
            } catch (Throwable e) { failed = e; }
            Path textPath = textFile, htmlPath = htmlFile;
            Throwable failure = failed;
            dispatch(() -> {
                try {
                    if (failure != null || loaded.generation() != port.queries().previewGeneration() || disconnecting) {
                        data.putNull("manifest");
                        if (failure != null) data.put("exportError", failure.toString());
                        if (textPath != null) Files.deleteIfExists(textPath);
                        if (htmlPath != null) Files.deleteIfExists(htmlPath);
                    } else {
                        manifest.set("text", files.issue(textPath, "text/plain; charset=utf-8"));
                        manifest.set("html", files.issue(htmlPath, "text/html; charset=utf-8"));
                        data.set("manifest", codec.file(manifest));
                    }
                    emit("previewLoaded", data);
                    var notices = pendingNotices.remove(loaded.generation());
                    if (notices != null) for (var notice : notices) onEvent(notice);
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                finally { pendingNotices.remove(loaded.generation()); }
            });
        }, "preview-payload-export");
        workers.add(worker);
        worker.setDaemon(true);
        worker.start();
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
            case "setGlobalParams", "setGroupParams", "setItemParams" -> {
                boolean mode = bool(p, "imageMode");
                AppConfig config = paramCodec.readConfig(p.path("params"));
                switch (method) {
                    case "setGlobalParams" -> port.commands().setGlobalParams(mode, config);
                    case "setGroupParams" -> port.commands().setGroupParams(integer(p, "groupNo"), mode, config);
                    case "setItemParams" -> port.commands().setItemParams(string(p, "path"), mode, config);
                }
                return json.nullNode();
            }
            case "clearParams" -> {
                boolean mode = bool(p, "imageMode");
                var values = files.readUpload(p.path("fallbackTargets"));
                if (!values.isArray()) throw new IllegalArgumentException("TargetId array required");
                var targets = new java.util.ArrayList<TargetId>();
                for (var value : values) targets.add(target(value, false));
                port.commands().clearParams(targets, mode);
                return json.nullNode();
            }
            case "applyGlobalParams" -> {
                boolean mode = bool(p, "imageMode");
                port.commands().applyGlobalParams(target(p.get("target"), false), mode);
                return json.nullNode();
            }
            case "undo" -> {
                int depth = port.queries().workspace().undoDepth();
                port.commands().undo();
                if (depth > 0 && port.queries().workspace().undoDepth() == depth) {
                    // U2: the model preserves unsupported parameter records. Never report false success.
                    throw new UnsupportedOperationException("U2: parameter undo is not supported; record retained");
                }
                return json.nullNode();
            }
            case "paramSchema" -> {
                return json.createObjectNode().set("schema", paramCodec.schema(port.queries().paramSchema(bool(p, "imageMode"))));
            }
            case "runtimeConfig" -> {
                return json.createObjectNode().set("config", paramCodec.runtimeConfig(requestPlans(p).runtimeConfig()));
            }
            case "outputUnitPlan" -> {
                boolean checked = bool(p, "checkedOnly");
                return json.createObjectNode().set("units", codec.file(paramCodec.units(requestPlans(p).outputUnitPlan(checked))));
            }
            case "cancelWorkspaceActivity" -> { port.commands().cancelWorkspaceActivity(); return json.nullNode(); }
            case "cancelPreviewLoad" -> {
                long old = port.queries().previewGeneration();
                boolean hadLoad = port.queries().previewLoadingPath() != null;
                port.commands().cancelPreviewLoad();
                if (!hadLoad && old != port.queries().previewGeneration()) silentChange.run();
                return json.nullNode();
            }
            case "startPreparse", "startPreparseFor" -> {
                List<String> targets = method.equals("startPreparseFor") ? paths(p, "paths", null, false)
                        : model.allItems().stream().filter(it -> !it.removed).map(it -> it.path).toList();
                for (String path : targets) IpcFiles.input(path);
                boolean mode = runtime(p);
                activityImageMode = mode;
                start(() -> {
                    if (method.equals("startPreparse")) port.commands().startPreparse();
                    else port.commands().startPreparseFor(targets);
                });
                return json.nullNode();
            }
            case "refreshPreparseProgress" -> { port.commands().refreshPreparseProgress(); return json.nullNode(); }
            case "loadPreview" -> {
                String path = string(p, "path");
                boolean confirmed = bool(p, "largeFileConfirmed");
                boolean mode = runtime(p);
                if (item(path).removed) throw new IllegalArgumentException("Removed preview item");
                IpcFiles.input(path);
                activityImageMode = mode;
                start(() -> port.commands().loadPreview(path, confirmed));
                return json.nullNode();
            }
            case "resolvePreviewRegion" -> {
                long generation = integer(p, "generation");
                String path = string(p, "path");
                boolean accepted = bool(p, "accepted");
                IpcFiles.input(path);
                start(() -> port.commands().resolvePreviewRegion(generation, path, accepted));
                return json.nullNode();
            }
            case "awaitPreviewInFlight" -> {
                long millis = integer(p, "millis");
                long duration = TimeUnit.MILLISECONDS.toNanos(millis);
                long started = System.nanoTime();
                do {
                    inputPump.run();
                    drainCallbacks();
                    // Short joins retain the port's worker/cleanup semantics without starving input or delivery.
                    port.commands().awaitPreviewInFlight(Math.min(5, millis));
                    if (workers.stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("preview-"))) break;
                } while (System.nanoTime() - started < duration && !disconnecting);
                drainCallbacks();
                return json.nullNode();
            }
            case "activityGeneration" -> { return generation(port.queries().activityGeneration()); }
            case "previewGeneration" -> { return generation(port.queries().previewGeneration()); }
            case "previewLoadingPath" -> { return json.createObjectNode().put("path", port.queries().previewLoadingPath()); }
            case "applyImportPlan" -> {
                if (importing) throw new IllegalArgumentException("Import already active");
                ImportPlanVm plan = plan(files.readUpload(p.path("plan")));
                applyingPlan = true;
                try { port.commands().applyImportPlan(plan); }
                finally { applyingPlan = false; flushImportChanges(); }
                return json.nullNode();
            }
            case "confirmAnswer" -> {
                String id = string(p, "confirmationId");
                JsonNode action = p.get("actionKey");
                if (action == null || (!action.isNull() && !action.isTextual())) {
                    throw new IllegalArgumentException("actionKey string or null required");
                }
                return json.createObjectNode().put("accepted", confirmations.answer(id, action.isNull() ? null : action.asText()));
            }
            case "importPaths" -> {
                List<String> paths = paths(p, "paths", "pathsFile", false);
                if (paths.isEmpty() || importing) throw new IllegalArgumentException("Nonempty paths and idle importer required");
                if (p.has("sourceDesc") && !p.path("sourceDesc").isTextual()) throw new IllegalArgumentException("sourceDesc string required");
                String source = p.has("sourceDesc") ? p.path("sourceDesc").textValue() : "IPC";
                boolean fromDrag = p.has("fromDrag") && bool(p, "fromDrag");
                boolean async = p.has("async") && bool(p, "async");
                for (String path : paths) IpcFiles.input(path);
                importDone = false;
                importFailed = false;
                start(() -> port.commands().importPaths(paths, source, fromDrag));
                if (async) return json.nullNode();
                await(() -> importDone && !importing);
                return json.createObjectNode().put("count", model.allItems().size()).put("failed", importFailed);
            }
            case "group" -> {
                List<String> paths = paths(p, "paths", "pathsFile", false);
                String prefix = string(p, "namePrefix");
                port.commands().group(paths, prefix);
                return json.nullNode();
            }
            case "ungroup", "renameGroup", "toggleGroupExpanded", "removeGroup" -> {
                long no = integer(p, "groupNo");
                switch (method) {
                    case "ungroup" -> port.commands().ungroup(no);
                    case "renameGroup" -> {
                        if (!p.path("newName").isTextual()) throw new IllegalArgumentException("newName string required");
                        port.commands().renameGroup(no, p.path("newName").textValue());
                    }
                    case "toggleGroupExpanded" -> port.commands().toggleGroupExpanded(no);
                    case "removeGroup" -> port.commands().removeGroup(no);
                }
                return json.nullNode();
            }
            case "clearItems" -> { port.commands().clearItems(); return json.nullNode(); }
            case "removeItems" -> {
                port.commands().removeItems(paths(p, "paths", null, false));
                return json.nullNode();
            }
            case "canUndo" -> {
                return json.createObjectNode().put("canUndo", port.queries().canUndo())
                        .put("undoDepth", port.queries().workspace().undoDepth());
            }
            case "applyDrop" -> {
                // A parent change can start a parser; revalidate before any structure mutation.
                for (var item : model.allItems()) if (!item.removed) IpcFiles.input(item.path);
                var drop = port.commands().applyDrop(target(p.get("dragged"), false),
                        target(p.get("target"), true), string(p, "zone"));
                var affected = json.createArrayNode();
                drop.affectedPaths().forEach(affected::add);
                var result = json.createObjectNode().put("changed", drop.changed());
                result.set("affectedPaths", files.write(".json", json.writeValueAsBytes(affected), "application/json"));
                return result;
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
                        .put("callbackQueueSize", callbacks.size()).put("activityWorkers", workers.stream().filter(Thread::isAlive).count())
                        .put("rawProgress", rawProgress).put("sentProgress", sentProgress);
            }
            default -> throw new IllegalArgumentException("Unknown extension method");
        }
    }

    private ObjectNode preview(JsonNode p) throws Exception {
        if (p.has("pngThumbnails")) {
            if (!p.path("pngThumbnails").isBoolean() || p.path("pngThumbnails").booleanValue())
                throw new IllegalArgumentException("PNG transcoding is not supported; use real encoded image files");
        }
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
            byte[] bytes = Files.readAllBytes(image);
            String ext = IpcImages.extension(bytes);
            var metadata = IpcImages.thumbnail(new PreviewImageData(result.images().get(i).originalName(),
                    result.images().get(i).sourceId(), bytes), i, params(item, true));
            ObjectNode ref = files.borrowImage(image, IpcImages.mediaType(ext));
            ref.put("name", result.thumbs().get(i).coreName()).put("index", i).put("imagePath", image.toString())
                    .put("coreName", metadata.coreName()).put("width", metadata.width()).put("height", metadata.height()).put("extension", ext);
            thumbs.add(ref); // Encoded original: client decodes/scales, never ARGB/base64 over stdio.
        }
        return json.createObjectNode().put("imageCount", thumbs.size())
                .set("manifest", files.write(".json", json.writeValueAsBytes(manifest), "application/json"));
    }

    private boolean runtime(JsonNode p) throws IOException {
        JsonNode r = p.path("runtime");
        if (!r.isObject()) throw new IllegalArgumentException("RuntimeContext required");
        for (String key : List.of("inputFolder", "outputFolder")) {
            if (!r.has(key) || (!r.path(key).isNull() && !r.path(key).isTextual())) throw new IllegalArgumentException("Runtime path required");
            if (r.path(key).isTextual()) {
                if (key.equals("outputFolder")) IpcFiles.checkOutput(r.path(key).textValue());
                else IpcFiles.input(r.path(key).textValue());
            }
        }
        bool(r, "openFolderAfterComplete");
        return bool(r, "imageModeEnabled");
    }

    /** Synchronous request-local context; never overwrite the legacy script mode/output directory. */
    private ExtractionPlanService requestPlans(JsonNode p) throws IOException {
        boolean mode = runtime(p);
        JsonNode runtime = p.path("runtime");
        String input = runtime.path("inputFolder").isNull() ? null : runtime.path("inputFolder").textValue();
        String output = runtime.path("outputFolder").isNull() ? null : runtime.path("outputFolder").textValue();
        boolean open = bool(runtime, "openFolderAfterComplete");
        return new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return mode; }
            public AppConfig textParamsCache() { return global.text(); }
            public AppConfig imageParamsCache() { return global.image(); }
            public String inputFolder() { return input; }
            public String outputFolder() { return output; }
            public boolean openFolderAfterComplete() { return open; }
        });
    }

    private ImportPlanVm plan(JsonNode value) throws IOException {
        if (!value.isObject() || !value.path("batches").isArray()) throw new IllegalArgumentException("ImportPlanVm required");
        var batches = new java.util.ArrayList<ImportPlanVm.ImportBatchVm>();
        for (var b : value.path("batches")) {
            String dir = string(b, "dirPath");
            IpcFiles.input(dir);
            if (!b.has("folderName") || (!b.path("folderName").isTextual() && !b.path("folderName").isNull()))
                throw new IllegalArgumentException("folderName required");
            if (!b.path("filePaths").isArray()) throw new IllegalArgumentException("filePaths required");
            var paths = new java.util.ArrayList<String>();
            for (var path : b.path("filePaths")) {
                if (!path.isTextual() || path.textValue().isBlank()) throw new IllegalArgumentException("File path required");
                IpcFiles.input(path.textValue()); paths.add(path.textValue());
            }
            batches.add(new ImportPlanVm.ImportBatchVm(dir, b.path("folderName").isNull() ? null : b.path("folderName").textValue(),
                    bool(b, "grouped"), paths));
        }
        return new ImportPlanVm(batches, count(value, "ignoredNonMht"), count(value, "hiddenDirs"), count(value, "emptyDirs"),
                count(value, "unreadable"), count(value, "depthLimited"), bool(value, "quotaHit"), bool(value, "hasMore"));
    }

    private static int count(JsonNode value, String key) {
        long n = integer(value, key);
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("Count overflow");
        return (int) n;
    }

    static String string(JsonNode p, String key) {
        if (!p.path(key).isTextual() || p.path(key).asText().isBlank()) throw new IllegalArgumentException(key + " string required");
        return p.path(key).asText();
    }

    static long integer(JsonNode p, String key) {
        if (!p.path(key).isIntegralNumber() || !p.path(key).canConvertToLong() || p.path(key).longValue() < 0) {
            throw new IllegalArgumentException(key + " nonnegative integer required");
        }
        return p.path(key).longValue();
    }

    private static TargetId target(JsonNode value, boolean nullable) {
        if (nullable && value != null && value.isNull()) return null;
        if (value == null || !value.isObject() || !value.has("path") || !value.has("groupNo")) {
            throw new IllegalArgumentException("TargetId required");
        }
        if (value.path("path").isNull()) return TargetId.ofGroup(integer(value, "groupNo"));
        if (!value.path("groupNo").isNull()) throw new IllegalArgumentException("One target identity required");
        return TargetId.ofItem(string(value, "path"));
    }

    List<String> paths(JsonNode p, String key, String alternate, boolean nullable) throws IOException {
        JsonNode values;
        if (alternate == null) values = files.readUpload(p.path(key));
        else {
            if (p.has(key) == p.has(alternate)) throw new IllegalArgumentException("Exactly one paths source required");
            values = p.has(alternate) ? files.readUpload(p.path(alternate)) : p.path(key);
        }
        if (!values.isArray()) throw new IllegalArgumentException("Paths array required");
        var result = new java.util.ArrayList<String>();
        for (var value : values) {
            if (nullable && value.isNull()) { result.add(null); continue; }
            if (!value.isTextual() || (!nullable && value.textValue().isBlank())) throw new IllegalArgumentException("Path string required");
            // Legacy checked unknown IDs remain harmless, but relative/unauthorized real path inputs are rejected.
            if (!value.textValue().isBlank() && (alternate == null || p.has(alternate))) {
                Path path = Path.of(value.textValue());
                if (path.isAbsolute()) IpcFiles.input(value.textValue());
                else if (!nullable) throw new IllegalArgumentException("Absolute path required");
            }
            result.add(value.textValue());
        }
        return result;
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

    void shutdownWorkers() throws IOException {
        if (closing) return;
        disconnect();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                drainCallbacks();
                port.commands().awaitPreviewInFlight(5);
                for (Thread worker : List.copyOf(workers)) {
                    if (worker == host) continue;
                    worker.join(5);
                }
                if (workers.stream().noneMatch(Thread::isAlive) && callbacks.isEmpty() && !importing) break;
                if (System.nanoTime() >= deadline) throw new IOException("IPC workers did not finish during shutdown");
            } while (true);
            var channel = extractions.channel();
            if (channel != null) channel.result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Extraction worker did not finish during shutdown", e);
        } finally {
            closing = true;
            events = event -> { };
            callbacks.clear();
        }
    }

    private boolean filesClosed;

    @Override public void close() throws IOException {
        if (filesClosed) return;
        try { shutdownWorkers(); }
        finally {
            filesClosed = true;
            previews.lifecycle().onShutdown(() -> { });
            files.close();
        }
    }
}
