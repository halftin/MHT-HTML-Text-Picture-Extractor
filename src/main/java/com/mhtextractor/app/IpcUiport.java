package com.mhtextractor.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.ipc.*;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.mhtextractor.app.ipc.IpcCodec.*;

/** Client-side four-channel adapter. It owns the existing BackendSession, never a second transport. */
public final class IpcUiport implements Uiport, AutoCloseable {
    public record RuntimeContext(boolean imageModeEnabled, String inputFolder, String outputFolder,
                                 boolean openFolderAfterComplete) {}
    public record WorkspacePage(long revision, int total, int offset, int groupCount, List<ItemVm> items,
                                WorkspaceVm snapshot) {
        public WorkspacePage { items = List.copyOf(items); }
    }
    public record Metrics(long heapPoolPeakBytes, int callbackQueuePeak, int callbackQueueSize,
                          long activityWorkers, long rawProgress, long sentProgress) {}
    /** Typed IPC-only queries supplement the shared in-process query contract. */
    public interface Queries extends WorkspaceQueries {
        WorkspacePage workspacePage(int offset, int limit);
        IpcFilesClient.FileRef fileInfo(IpcFilesClient.FileRef ref);
        Metrics metrics();
        PortEvent.PreviewPayload previewFiles(String path);
    }

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ipc-uiport-events").daemon(true).factory());
    private final ExecutorService controls = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ipc-uiport-controls").daemon(true).factory());
    private final Object releaseLock = new Object();
    private CompletableFuture<JsonNode> releaseTail = CompletableFuture.completedFuture(null);
    private volatile Thread deliveryThread;
    private final List<Consumer<PortEvent>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<IpcFailure> failure = new CompletableFuture<>();
    private final BackendSession session;
    private final IpcConfirmer confirmer;
    private final IpcFilesClient files;
    private final IpcCodec codec;
    private final Supplier<RuntimeContext> context;
    private final Duration requestTimeout;
    private volatile BackendSession.Status status = new BackendSession.Status(BackendSession.State.CONNECTING, "Not started");
    private volatile boolean closed;
    private Path bulkRoot;

    public IpcUiport(Confirmer frontend, Supplier<RuntimeContext> context) {
        this(frontend,context,Duration.ofSeconds(30));
    }
    public IpcUiport(Confirmer frontend, Supplier<RuntimeContext> context, Duration requestTimeout) {
        this.context = Objects.requireNonNull(context);
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) throw new IllegalArgumentException("Positive request timeout required");
        this.requestTimeout = requestTimeout;
        confirmer = new IpcConfirmer(frontend);
        session = new BackendSession(this::enqueueControl, new BackendSession.View() {
            public void statusChanged(BackendSession.Status next) {
                status = next;
                if (next.state() == BackendSession.State.UNAVAILABLE || next.state() == BackendSession.State.DISCONNECTED) {
                    fail(next.state() == BackendSession.State.UNAVAILABLE ? startupFailure(next.detail(),null)
                            : new IpcFailure(IpcFailure.Kind.DISCONNECTED,null,next.detail(),null));
                }
            }
            public void confirmationRequested(String id, ConfirmRequest request, Consumer<String> answer) {
                confirmer.request(id,request,answer,IpcUiport.this::fail);
            }
            public void confirmationFinished(String id, BackendSession.End reason) { confirmer.finish(id); }
            public void confirmationFailed(String id, Throwable error) { fail(error); }
        });
        files = new IpcFilesClient(json,this::release);
        codec = new IpcCodec(json,files);
        session.subscribeEvents(frame -> enqueue(() -> decodeEvent(frame)));
    }

    /** Explicit startup is independent of UI wiring. No in-process fallback is installed. */
    public void start(BackendSession.Command command, Duration handshakeTimeout) {
        session.start(command,handshakeTimeout);
        try { await(session.connected()); }
        catch (IpcFailure e) {
            var problem = startupFailure(e.getMessage(),e);
            fail(problem); throw problem;
        }
        JsonNode response = raw("workspace",json.createObjectNode().put("bulk",true));
        bulkRoot = Path.of(text(response.path("manifest"),"path")).getParent();
        files.root(bulkRoot);
        decode(() -> codec.workspace(files.consumeJson(response.path("manifest"))));
        barrier();
    }
    public BackendSession.Status status() { return status; }
    public CompletableFuture<IpcFailure> failure() { return failure.copy(); }
    public long revision() { return session.revision(); }
    public IpcFilesClient files() { return files; }

    private void enqueue(Runnable work) {
        if (closed) return;
        try { delivery.execute(() -> { deliveryThread = Thread.currentThread(); if (!closed && !failure.isDone()) work.run(); }); }
        catch (RejectedExecutionException e) { if (!closed) fail(e); }
    }
    private void enqueueControl(Runnable work) {
        if (closed) return;
        try { controls.execute(() -> { if (!closed && !failure.isDone()) work.run(); }); }
        catch (RejectedExecutionException e) { if (!closed) fail(e); }
    }
    private CompletableFuture<JsonNode> release(String method, JsonNode params) {
        synchronized (releaseLock) {
            // The frozen server defers releaseFiles while busy. Only one such RPC may be outstanding.
            var next = releaseTail.thenCompose(ignored -> session.request(method,params));
            releaseTail = next;
            next.whenComplete((result,error) -> { if (error != null && !closed) fail(error); });
            return next;
        }
    }
    private CompletableFuture<JsonNode> releases() {
        synchronized (releaseLock) { return releaseTail.copy(); }
    }
    private void decodeEvent(JsonNode frame) {
        try {
            PortEvent event = decode(() -> codec.event(text(frame,"event"),frame.path("data")));
            if (event != null) for (var listener : listeners) listener.accept(event);
        } catch (RuntimeException e) { fail(e); }
    }
    private void fail(Throwable error) {
        IpcFailure problem = classify(error);
        if (failure.complete(problem)) {
            status = new BackendSession.Status(problem.kind() == IpcFailure.Kind.UNAVAILABLE
                    ? BackendSession.State.UNAVAILABLE : BackendSession.State.DISCONNECTED,problem.getMessage());
            files.invalidate(); confirmer.close(); session.close();
        }
    }
    private IpcFailure classify(Throwable e) {
        while ((e instanceof ExecutionException || e instanceof CompletionException) && e.getCause() != null) e = e.getCause();
        if (e instanceof IpcFailure f) return f;
        if (e instanceof BackendSession.RemoteError r) return new IpcFailure(IpcFailure.Kind.REMOTE,r.code(),r.getMessage(),r);
        return new IpcFailure(e instanceof TimeoutException ? IpcFailure.Kind.TIMEOUT : IpcFailure.Kind.PROTOCOL,null,e.getMessage(),e);
    }
    private IpcFailure startupFailure(String detail, Throwable cause) {
        boolean mismatch = detail != null && (detail.contains("mismatch") || detail.contains("required capabilities")
                || detail.contains("Malformed") || detail.contains("Expected backend") || detail.contains("handshake rejected"));
        return new IpcFailure(mismatch ? IpcFailure.Kind.PROTOCOL : IpcFailure.Kind.UNAVAILABLE,null,detail,cause);
    }
    private <T> T await(CompletableFuture<T> future) {
        try { return future.get(requestTimeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw classify(e); }
        catch (ExecutionException | TimeoutException e) { throw classify(e); }
    }
    private void check() {
        if (closed) throw new IpcFailure(IpcFailure.Kind.CLOSED,null,"IPC port closed",null);
        if (failure.isDone()) throw failure.join();
    }
    private JsonNode raw(String method, JsonNode params) {
        check();
        try { return await(session.request(method,params)); }
        catch (IpcFailure e) {
            if (e.kind() != IpcFailure.Kind.REMOTE) fail(e);
            throw e;
        }
    }
    private JsonNode call(String method, JsonNode params) {
        check();
        if (!List.of("cancelWorkspaceActivity","cancelPreviewLoad","resolvePreviewRegion",
                "activityGeneration","previewGeneration","previewLoadingPath","startPreparse","startPreparseFor",
                "refreshPreparseProgress","loadPreview","awaitPreviewInFlight","startExtraction","cancelExtraction").contains(method)) {
            // Never ask for a cached snapshot until earlier FileRefs have been revoked on the server.
            await(releases());
        }
        JsonNode result = raw(method,params); barrier(); check(); return result;
    }
    private void barrier() {
        if (Thread.currentThread() != deliveryThread && !closed) await(CompletableFuture.runAsync(() -> {},delivery));
    }
    @FunctionalInterface private interface Decoding<T> { T read() throws IOException; }
    private <T> T decode(Decoding<T> work) {
        files.beginConsumption();
        try { return work.read(); }
        catch (IOException | IllegalArgumentException e) { throw classify(e); }
        finally { files.endConsumption(); }
    }
    private ObjectNode p() { return json.createObjectNode(); }
    private ObjectNode runtime(ObjectNode params) {
        var r = Objects.requireNonNull(context.get(),"RuntimeContext");
        params.putObject("runtime").put("imageModeEnabled",r.imageModeEnabled()).put("inputFolder",r.inputFolder())
                .put("outputFolder",r.outputFolder()).put("openFolderAfterComplete",r.openFolderAfterComplete());
        return params;
    }
    private ObjectNode runtime(ObjectNode params, AppConfig r) {
        Objects.requireNonNull(r,"runtime");
        params.putObject("runtime").put("imageModeEnabled",r.isImageModeEnabled()).put("inputFolder",r.getInputFolder())
                .put("outputFolder",r.getOutputFolder()).put("openFolderAfterComplete",r.isOpenFolderAfterComplete());
        return params;
    }
    private void command(String method, ObjectNode params) {
        JsonNode result = call(method,params);
        if (!result.isNull()) throw new IpcFailure(IpcFailure.Kind.PROTOCOL,null,"Expected null command response: " + method,null);
    }
    private void upload(String method, ObjectNode params, String key, JsonNode content) {
        JsonNode result = uploadCall(method,params,key,content);
        if (!result.isNull()) throw new IpcFailure(IpcFailure.Kind.PROTOCOL,null,"Expected null command response: " + method,null);
    }
    private JsonNode uploadCall(String method, ObjectNode params, String key, JsonNode content) {
        check();
        Path path = null;
        try {
            path = Files.createTempFile(Objects.requireNonNull(bulkRoot,"Start IPC port first"),"client-",".json");
            Files.write(path,json.writeValueAsBytes(content));
            params.set(key,p().put("path",path.toString()).put("bytes",Files.size(path)).put("mediaType","application/json"));
            return call(method,params);
        } catch (IOException e) { throw classify(e); }
        finally {
            if (path != null) try { Files.deleteIfExists(path); } catch (IOException e) { fail(e); }
        }
    }
    private JsonNode targets(List<TargetId> targets) {
        var values = json.createArrayNode();
        if (targets != null) targets.stream().filter(Objects::nonNull).forEach(t -> values.add(codec.target(t)));
        return values;
    }
    private void pathsCommand(String method, ObjectNode params, List<String> paths) {
        if (paths == null && List.of("setChecked","group","removeItems").contains(method)) paths = List.of();
        if (List.of("removeItems","startPreparseFor").contains(method)) {
            upload(method,params,"paths",json.valueToTree(paths));
        } else if (paths != null && paths.size() > 256 && List.of("setChecked","group","importPaths").contains(method)) {
            upload(method,params,"pathsFile",json.valueToTree(paths));
        } else { params.set("paths",json.valueToTree(paths)); command(method,params); }
    }

    @Override public WorkspaceCommands commands() { return commands; }
    @Override public Queries queries() { return queries; }
    @Override public PortEvents events() { return listener -> {
        check(); listeners.add(Objects.requireNonNull(listener)); return () -> listeners.remove(listener);
    }; }
    @Override public Confirmer confirmer() { return confirmer; }

    private final WorkspaceCommands commands = new WorkspaceCommands() {
        public void setTitleOverride(String path, String title) {
            command("setTitleOverride",p().put("path",path).put("title",title).put("revision",revision()));
        }
        public void applyParamsToTargets(boolean mode, AppConfig config, List<TargetId> fallback) {
            var params = p().put("imageMode",mode).put("revision",revision());
            params.set("params",json.valueToTree(config));
            upload("applyParamsToTargets",params,"fallbackTargets",targets(fallback));
        }
        public void cancelWorkspaceActivity() { command("cancelWorkspaceActivity",p()); }
        public void cancelPreviewLoad() { command("cancelPreviewLoad",p()); }
        public void startPreparse() { command("startPreparse",runtime(p())); }
        public void startPreparseFor(List<String> paths) { pathsCommand("startPreparseFor",runtime(p()),paths); }
        public void refreshPreparseProgress() { command("refreshPreparseProgress",p()); }
        public void loadPreview(String path, boolean confirmed) { command("loadPreview",runtime(p().put("path",path).put("largeFileConfirmed",confirmed))); }
        public void resolvePreviewRegion(long generation, String path, boolean accepted) { command("resolvePreviewRegion",p().put("generation",generation).put("path",path).put("accepted",accepted)); }
        public void awaitPreviewInFlight(long millis) { command("awaitPreviewInFlight",p().put("millis",millis)); }
        public void setChecked(List<String> paths, boolean checked) { pathsCommand("setChecked",p().put("checked",checked),paths); }
        public void group(List<String> paths, String name) { pathsCommand("group",p().put("namePrefix",name),paths); }
        public void ungroup(long no) { command("ungroup",p().put("groupNo",no)); }
        public void renameGroup(long no, String name) { command("renameGroup",p().put("groupNo",no).put("newName",name)); }
        public void toggleGroupExpanded(long no) { command("toggleGroupExpanded",p().put("groupNo",no)); }
        public void setGlobalParams(boolean mode, AppConfig config) { command("setGlobalParams",p().put("imageMode",mode).set("params",json.valueToTree(config))); }
        public void setGroupParams(long no, boolean mode, AppConfig config) { command("setGroupParams",p().put("groupNo",no).put("imageMode",mode).set("params",json.valueToTree(config))); }
        public void setItemParams(String path, boolean mode, AppConfig config) { command("setItemParams",p().put("path",path).put("imageMode",mode).set("params",json.valueToTree(config))); }
        public void clearParams(List<TargetId> targets, boolean mode) {
            var values = json.createArrayNode();
            if (targets != null) targets.stream().filter(Objects::nonNull).forEach(t -> values.add(codec.target(t)));
            upload("clearParams",p().put("imageMode",mode),"fallbackTargets",values);
        }
        public void applyGlobalParams(TargetId target, boolean mode) { command("applyGlobalParams",p().put("imageMode",mode).set("target",codec.target(target))); }
        public void undo() { command("undo",p()); }
        public void importPaths(List<String> paths, String source, boolean drag) {
            pathsCommand("importPaths",p().put("async",true).put("sourceDesc",source).put("fromDrag",drag),paths);
        }
        public void applyImportPlan(ImportPlanVm plan) { upload("applyImportPlan",p(),"plan",codec.plan(plan)); }
        public void startExtraction(boolean checked) { command("startExtraction",runtime(p().put("checkedOnly",checked))); }
        public void cancelExtraction() { command("cancelExtraction",p()); }
        public void clearItems() { command("clearItems",p()); }
        public void removeItems(List<String> paths) { pathsCommand("removeItems",p(),paths); }
        public void removeGroup(long no) { command("removeGroup",p().put("groupNo",no)); }
        public DropVm applyDrop(TargetId dragged, TargetId target, String zone) {
            var params = p().put("zone",zone).put("revision",revision());
            params.set("dragged",codec.target(dragged)); params.set("target",target == null ? json.nullNode() : codec.target(target));
            JsonNode result = call("applyDrop",params);
            return decode(() -> new DropVm(bool(result,"changed"),strings(files.consumeJson(result.path("affectedPaths")))));
        }
    };
    private final Queries queries = new Queries() {
        public CacheStateVm cacheState() {
            var n = call("cacheState",p());
            return new CacheStateVm(text(n,"root"),number(n,"totalBytes"),bool(n,"hasCachedEntries"));
        }
        public ParameterStateVm parameterState(boolean mode, List<TargetId> fallback) {
            var n = uploadCall("parameterState",p().put("imageMode",mode).put("revision",revision()),"fallbackTargets",targets(fallback));
            return decode(() -> codec.parameterState(files.consumeJson(n.path("state"))));
        }
        public ParameterStateVm parameterState(boolean mode, List<TargetId> targets, TargetResolution resolution) {
            java.util.Objects.requireNonNull(resolution, "targetResolution");
            if (resolution == TargetResolution.SELECTED_FIRST) return parameterState(mode, targets);
            var n = uploadCall("parameterState",p().put("imageMode",mode).put("revision",revision())
                    .put("targetResolution",resolution.wireValue()),"fallbackTargets",targets(targets));
            return decode(() -> codec.parameterState(files.consumeJson(n.path("state"))));
        }
        public PreStartStateVm preStartState(AppConfig runtime, boolean checkedOnly) {
            return codec.preStart(call("preStartState",runtime(p().put("checkedOnly",checkedOnly).put("revision",revision()),runtime)));
        }
        public List<PresentationVm> presentation(List<String> paths, AppConfig runtime) {
            var n = uploadCall("presentation",runtime(p().put("revision",revision()),runtime),"paths",json.valueToTree(paths));
            return decode(() -> codec.presentation(files.consumeJson(n.path("items"))));
        }
        public ExportSourceVm exportSource(String path) {
            var n = call("exportSource",p().put("path",path));
            if (session.sessionId() == null || !session.sessionId().equals(text(n,"sessionId")))
                throw new IpcFailure(IpcFailure.Kind.PROTOCOL,null,"Source export session mismatch",null);
            return decode(() -> codec.exportSource(n));
        }
        public WorkspacePage workspacePage(int offset,int limit) { return IpcUiport.this.workspacePage(offset,limit); }
        public IpcFilesClient.FileRef fileInfo(IpcFilesClient.FileRef ref) { return IpcUiport.this.fileInfo(ref); }
        public Metrics metrics() { return IpcUiport.this.metrics(); }
        public PortEvent.PreviewPayload previewFiles(String path) { return IpcUiport.this.previewFiles(path); }
        public long activityGeneration() { return number(call("activityGeneration",p()),"generation"); }
        public long previewGeneration() { return number(call("previewGeneration",p()),"generation"); }
        public String previewLoadingPath() { return nullable(call("previewLoadingPath",p()),"path"); }
        public WorkspaceVm workspace() {
            JsonNode result = call("workspace",p().put("bulk",true));
            return decode(() -> codec.workspace(files.consumeJson(result.path("manifest"))));
        }
        public boolean canUndo() { return bool(call("canUndo",p()),"canUndo"); }
        public AppConfig runtimeConfig() { JsonNode result = call("runtimeConfig",runtime(p())); return decode(() -> codec.config(result.path("config"))); }
        public List<OutputUnitVm> outputUnitPlan(boolean checked) {
            JsonNode result = call("outputUnitPlan",runtime(p().put("checkedOnly",checked)));
            return decode(() -> codec.units(files.consumeJson(result.path("units"))));
        }
        public ParamSchemaVm paramSchema(boolean mode) { return codec.schema(call("paramSchema",p().put("imageMode",mode)).path("schema")); }
    };
    public WorkspacePage workspacePage(int offset, int limit) {
        JsonNode result = call("workspacePage",p().put("offset",offset).put("limit",limit));
        return decode(() -> new WorkspacePage(number(result,"revision"),integer(result,"total"),integer(result,"offset"),integer(result,"groupCount"),
                list(result.path("items"),codec::item),codec.workspace(files.consumeJson(result.path("snapshotFile")))));
    }
    public IpcFilesClient.FileRef fileInfo(IpcFilesClient.FileRef ref) {
        JsonNode result = call("fileInfo",p().put("path",ref.path()));
        return files.accept(result);
    }
    public Metrics metrics() {
        JsonNode n = call("metrics",p());
        return new Metrics(number(n,"heapPoolPeakBytes"),integer(n,"callbackQueuePeak"),integer(n,"callbackQueueSize"),
                number(n,"activityWorkers"),number(n,"rawProgress"),number(n,"sentProgress"));
    }
    public PortEvent.PreviewPayload previewFiles(String path) {
        JsonNode result = call("previewFiles",p().put("path",path));
        return decode(() -> codec.preview(result.path("manifest")));
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        status = new BackendSession.Status(BackendSession.State.CLOSED,"");
        listeners.clear(); files.invalidate(); confirmer.close(); session.close(); delivery.shutdownNow(); controls.shutdownNow();
    }
    public java.util.concurrent.CompletableFuture<Void> stopped() { return session.stopped(); }
}
