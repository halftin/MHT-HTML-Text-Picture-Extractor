package com.mhtextractor.uicore;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Serial business requests and atomic UI snapshots; progress and stop do not wait behind snapshot queries. */
public final class FrontendPort implements Uiport, AutoCloseable {
    private volatile Uiport backend;
    private final Consumer<Runnable> ui;
    private final Consumer<AppConfig> requestContext;
    private final boolean deferQueriesWhileBusy;
    private final ExecutorService requests=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"frontend-port");t.setDaemon(true);return t;});
    private final List<Consumer<PortEvent>> listeners=new CopyOnWriteArrayList<>();
    private AutoCloseable subscription;
    private volatile Predicate<Throwable> unavailable=error->false;
    private volatile Runnable unavailableAction=()->{};
    private final java.util.concurrent.atomic.AtomicLong connectionVersion=new java.util.concurrent.atomic.AtomicLong();
    private volatile PortSnapshot snapshot;
    private Supplier<AppConfig> runtime=AppConfig::new;
    private volatile AppConfig lastRuntime=new AppConfig();
    private Consumer<Throwable> failure=Throwable::printStackTrace;
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<com.mhtextractor.model.ExtractionStats> stats=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong eventVersion=new java.util.concurrent.atomic.AtomicLong();
    private PortSnapshot eventSnapshot;
    private long eventSnapshotVersion=-1;
    private volatile WorkspaceVm eventWorkspace;
    private volatile boolean importing,extracting,previewing,preparsing;
    private volatile long importGeneration,extractionGeneration,previewGeneration,activityGeneration;
    private volatile String loadingPath;

    public FrontendPort(Uiport backend, Consumer<Runnable> ui, Consumer<AppConfig> requestContext) {
        this(backend,ui,requestContext,false);
    }
    public FrontendPort(Uiport backend, Consumer<Runnable> ui, Consumer<AppConfig> requestContext,boolean deferQueriesWhileBusy) {
        this.backend=Objects.requireNonNull(backend);this.ui=ui;this.requestContext=requestContext;
        this.deferQueriesWhileBusy=deferQueriesWhileBusy;
        subscribe(backend);
    }
    private void subscribe(Uiport source) {
        subscription=source.events().subscribe(event->{if(closed.get()||backend!=source)return;
            if (event instanceof PortEvent.CacheSizeAlert || event instanceof PortEvent.BackendLog) {
                ui.accept(() -> { if (!closed.get() && backend == source)
                    for (var listener : listeners) listener.accept(event); });
                return;
            }
            boolean wasBusy=busy();observeActivity(event);eventVersion.incrementAndGet();
            if(event instanceof PortEvent.ExtractionProgress || event instanceof PortEvent.PreparseProgress){
                ui.accept(()->{if(!closed.get()&&backend==source)for(var listener:listeners)listener.accept(event);});
                if(wasBusy&&!busy())enqueue(this::refreshWhenIdle);
                return;
            }
            enqueue(()->{
            if(closed.get()||backend!=source)return;
            try { requestContext.accept(lastRuntime);
                var state=deferQueriesWhileBusy&&(busy()||event instanceof PortEvent.WorkspaceChanged) ? activitySnapshot()
                        : eventSnapshotVersion==eventVersion.get()?eventSnapshot:refreshSnapshot(lastRuntime);
                ui.accept(()->{if(closed.get()||backend!=source)return;snapshot=state;running.set(state.workspace().details().running());
                    if(event instanceof PortEvent.ExtractionTerminal end&&end.stats()!=null)stats.set(end.stats());
                    if(event instanceof PortEvent.ExtractionResult end&&end.stats()!=null)stats.set(end.stats());
                    for(var listener:listeners)listener.accept(event);});
            } catch(Throwable error){reportFailure(error,false);}
        });});
    }
    /** Called by the runtime's startup worker, before constructing the FXML controller. */
    public void bootstrap(AppConfig context) {lastRuntime=context;requestContext.accept(context);snapshot=refreshSnapshot(context);}
    /** A disconnected projection is UI data only; it never executes business commands locally. */
    public void bootstrapUnavailable(PortSnapshot empty) {snapshot=Objects.requireNonNull(empty);}
    public void onUnavailable(Predicate<Throwable> detection,Runnable action) {unavailable=detection;unavailableAction=action;}
    private void reportFailure(Throwable error,boolean requested) {
        reportFailure(error,requested,unavailableAction);
    }
    private void reportFailure(Throwable error,boolean requested,Runnable action) {
        if(closed.get())return;
        long version=connectionVersion.get();
        ui.accept(()->{if(closed.get()||version!=connectionVersion.get())return;
            if(unavailable.test(error)){if(requested)action.run();}
            else failure.accept(error);
        });
    }
    /** Keep the frontend handle and its subscribers; discard queued work from the old connection. */
    public CompletableFuture<Void> replaceBackend(Uiport replacement) {
        var done=new CompletableFuture<Void>();
        connectionVersion.incrementAndGet();
        if(closed.get()){done.completeExceptionally(new IllegalStateException("Frontend closed"));return done;}
        enqueue(()->{
            try {
                if(closed.get())throw new IllegalStateException("Frontend closed");
                subscription.close();backend=replacement;
                importing=extracting=previewing=preparsing=false;loadingPath=null;
                eventSnapshot=null;eventSnapshotVersion=-1;eventWorkspace=null;eventVersion.incrementAndGet();
                subscribe(replacement);var state=refreshSnapshot(lastRuntime);
                ui.accept(()->{if(closed.get()){done.completeExceptionally(new IllegalStateException("Frontend closed"));return;}
                    snapshot=state;running.set(state.workspace().details().running());stats.set(null);
                    for(var listener:listeners)listener.accept(new PortEvent.WorkspaceChanged("FRONTEND_REFRESH",state.workspace()));
                    done.complete(null);
                });
            }catch(Throwable error){done.completeExceptionally(error);}
        });
        return done;
    }
    public void bindRuntime(Supplier<AppConfig> runtime, Consumer<Throwable> failure) {this.runtime=runtime;this.failure=failure;}
    public Uiport implementation() {return backend;}
    public java.util.concurrent.atomic.AtomicBoolean runningView(){return running;}
    public java.util.concurrent.atomic.AtomicReference<com.mhtextractor.model.ExtractionStats> statsView(){return stats;}
    public PortSnapshot snapshot() {return Objects.requireNonNull(snapshot,"Port snapshot is not ready");}
    public <T> void execute(Function<Uiport,T> operation, Consumer<T> finished) {
        execute(operation,finished,true);
    }
    @FunctionalInterface public interface NamedConfigOperation<T> {
        T apply(Uiport port) throws java.io.IOException;
    }
    /** Named file operations share the serial queue but neither refresh nor mutate workspace snapshots. */
    public <T> void namedConfig(NamedConfigOperation<T> operation, Consumer<T> finished,
                                Consumer<Throwable> failed, boolean requested) {
        long version=connectionVersion.get();
        Runnable unavailableForRequest=unavailableAction;
        enqueue(()->{
            if(closed.get()||version!=connectionVersion.get())return;
            try {
                T result=operation.apply(backend);
                ui.accept(()->{
                    if(closed.get()||version!=connectionVersion.get())return;
                    try{finished.accept(result);}catch(Throwable error){failed.accept(error);}
                });
            } catch(Throwable error) {
                if(version!=connectionVersion.get())return;
                if(unavailable.test(error))reportFailure(error,requested,unavailableForRequest);
                else ui.accept(()->{if(!closed.get()&&version==connectionVersion.get())failed.accept(error);});
            }
        });
    }
    private <T> void execute(Function<Uiport,T> operation, Consumer<T> finished, boolean notify) {
        request(operation,finished,notify,true);
    }
    private <T> void request(Function<Uiport,T> operation, Consumer<T> finished, boolean notify,boolean refresh) {
        request(operation,finished,notify,refresh,true);
    }
    private <T> void request(Function<Uiport,T> operation, Consumer<T> finished, boolean notify,boolean refresh,boolean requested) {
        AppConfig frozen=runtime.get();lastRuntime=frozen;
        Runnable unavailableForRequest=unavailableAction;
        long version=connectionVersion.get();
        enqueue(()->{
            if(closed.get()||version!=connectionVersion.get())return;
            try {requestContext.accept(frozen);T result=operation.apply(backend);var state=refresh?refreshSnapshot(frozen):null;
                ui.accept(()->{if(closed.get()||version!=connectionVersion.get())return;
                    if(state!=null){snapshot=state;running.set(state.workspace().details().running());
                        if(notify)for(var listener:listeners)listener.accept(new PortEvent.WorkspaceChanged("FRONTEND_REFRESH",state.workspace()));}
                    finished.accept(result);});
            }catch(Throwable error){if(version==connectionVersion.get())reportFailure(error,requested,unavailableForRequest);}
        });
    }
    private void enqueue(Runnable task) {
        if(closed.get())return;
        try{requests.execute(task);}catch(RejectedExecutionException error){if(!closed.get())throw error;}
    }
    private void command(Consumer<WorkspaceCommands> operation) {execute(p->{operation.accept(p.commands());return null;},ignored->{});}
    private void activity(Consumer<WorkspaceCommands> operation) {
        request(p->{operation.accept(p.commands());return null;},ignored->{},false,false);
    }
    /** Selection highlight is local UI; its ordered cleanup must not open a backend decision card. */
    public void cancelPreviewForSelection() {
        request(p->{p.commands().cancelPreviewLoad();return null;},ignored->{},false,false,false);
    }

    private boolean busy(){return importing||extracting||previewing||preparsing;}
    private void observeActivity(PortEvent event){
        if(event instanceof PortEvent.WorkspaceChanged change&&change.snapshot()!=null)eventWorkspace=change.snapshot();
        if(event instanceof PortEvent.ImportStateChanged change){importing=change.importing();importGeneration=change.generation();}
        if(event instanceof PortEvent.RunningChanged change){extracting=change.running();extractionGeneration=change.generation();}
        if(event instanceof PortEvent.ExtractionTerminal)extracting=false;
        if(event instanceof PortEvent.PreviewLoadStateChanged change){previewing=change.active();previewGeneration=change.generation();loadingPath=change.active()?change.path():null;}
        if(event instanceof PortEvent.WorkspaceActivityChanged change){activityGeneration=change.generation();preparsing=false;}
        if(event instanceof PortEvent.PreparseStarted change){activityGeneration=change.generation();preparsing=true;}
        if(event instanceof PortEvent.PreparseProgress change&&change.generation()==activityGeneration&&change.done()>=change.total())preparsing=false;
    }
    private PortSnapshot activitySnapshot(){
        var base=eventSnapshot!=null?eventSnapshot:snapshot();
        var source=eventWorkspace!=null?eventWorkspace:base.workspace();var d=source.details();
        var facts=new WorkspaceVm.Details(d.textConfirmed(),d.imageConfirmed(),activityGeneration,previewGeneration,
                loadingPath,importing,importGeneration,extracting,extractionGeneration,d.lastStats(),d.sessionOutputFolder());
        var workspace=new WorkspaceVm(source.groups(),source.topLevelItems(),source.canUndo(),source.undoDepth(),
                source.selection(),source.topLevelOrder(),facts);
        return new PortSnapshot(workspace,base.text(),base.image(),base.cache(),base.textSchema(),base.imageSchema(),
                base.presentation(),base.textBodies(),base.allUnits(),base.checkedUnits());
    }
    private void refreshWhenIdle(){
        if(closed.get()||busy())return;
        try{var state=refreshSnapshot(lastRuntime);ui.accept(()->{if(closed.get())return;snapshot=state;
            for(var listener:listeners)listener.accept(new PortEvent.WorkspaceChanged("FRONTEND_REFRESH",state.workspace()));});}
        catch(Throwable error){reportFailure(error,false);}
    }

    private PortSnapshot refreshSnapshot(AppConfig runtime) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for(;;){
            if(deferQueriesWhileBusy&&busy()&&eventSnapshot!=null)return activitySnapshot();
            long version=eventVersion.get();
            try{
                var state=readSnapshot(runtime);
                if(eventVersion.get()!=version&&System.nanoTime()<deadline)continue;
                eventSnapshot=state;eventSnapshotVersion=version;
                if(eventVersion.get()==version){eventWorkspace=state.workspace();var d=state.workspace().details();
                    activityGeneration=d.activityGeneration();previewGeneration=d.previewGeneration();loadingPath=d.loadingPath();
                    importGeneration=d.importGeneration();extractionGeneration=d.extractionGeneration();}
                return state;
            }catch(RuntimeException error){
                // Drain earlier backend events before deciding whether this was a concurrent revision change.
                backend.queries().workspace();
                if(eventVersion.get()==version||System.nanoTime()>=deadline)throw error;
            }
        }
    }

    private void cancelExtractionNow() {
        if(closed.get())return;
        long version=connectionVersion.get();var source=backend;
        Runnable unavailableForRequest=unavailableAction;
        Thread.ofPlatform().daemon().name("frontend-stop").start(()->{
            try{if(version==connectionVersion.get())source.commands().cancelExtraction();}
            catch(Throwable error){if(version==connectionVersion.get())reportFailure(error,true,unavailableForRequest);}
        });
    }

    private PortSnapshot readSnapshot(AppConfig runtime) {
        try {
            var q=backend.queries();var workspace=q.workspace();
            var ids=new ArrayList<TargetId>();var paths=new ArrayList<String>();
            for(var group:workspace.groups()){ids.add(TargetId.ofGroup(group.no()));for(var item:group.members())if(!item.removed()){ids.add(TargetId.ofItem(item.id()));paths.add(item.id());}}
            for(var item:workspace.topLevelItems())if(!item.removed()){ids.add(TargetId.ofItem(item.id()));paths.add(item.id());}
            var text=q.parameterState(false,ids,TargetResolution.EXPLICIT);var image=q.parameterState(true,ids,TargetResolution.EXPLICIT);
            var presentation=q.presentation(paths,runtime);var bodies=new HashMap<String,String>();
            for(var entry:presentation)if(entry.textFile()!=null)bodies.put(entry.path(),Files.readString(Path.of(entry.textFile().path())));
            return new PortSnapshot(workspace,text,image,q.cacheState(),q.paramSchema(false),q.paramSchema(true),presentation,bodies,q.outputUnitPlan(false),q.outputUnitPlan(true));
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    @Override public Confirmer confirmer(){return backend.confirmer();}
    @Override public PortEvents events(){return listener->{listeners.add(listener);return ()->listeners.remove(listener);};}
    @Override public WorkspaceQueries queries(){return queries;}
    private final WorkspaceQueries queries=new WorkspaceQueries(){
        public WorkspaceVm workspace(){return snapshot().workspace();}
        public long activityGeneration(){return workspace().details().activityGeneration();}
        public long previewGeneration(){return workspace().details().previewGeneration();}
        public String previewLoadingPath(){return workspace().details().loadingPath();}
        public AppConfig runtimeConfig(){return lastRuntime;}
        public List<OutputUnitVm> outputUnitPlan(boolean checked){return checked?snapshot().checkedUnits():snapshot().allUnits();}
        public ParamSchemaVm paramSchema(boolean image){return image?snapshot().imageSchema():snapshot().textSchema();}
        public CacheStateVm cacheState(){return snapshot().cache();}
        public ParameterStateVm parameterState(boolean image,List<TargetId> fallback){return parameterState(image,fallback,TargetResolution.SELECTED_FIRST);}
        public ParameterStateVm parameterState(boolean image,List<TargetId> targets,TargetResolution resolution){
            var all=snapshot().parameters(image);var available=all.states().stream().collect(java.util.stream.Collectors.toMap(ParameterStateVm.TargetState::target,Function.identity()));
            var selected=new ArrayList<TargetId>();
            if(resolution==TargetResolution.SELECTED_FIRST){
                for(var item:workspace().topLevelItems())if(!item.removed()&&item.checked())selected.add(TargetId.ofItem(item.id()));
                for(var group:workspace().groups())for(var item:group.members())if(!item.removed()&&item.checked())selected.add(TargetId.ofItem(item.id()));
                if(selected.isEmpty()&&targets!=null)targets.stream().filter(available::containsKey).findFirst().ifPresent(selected::add);
            }else if(targets!=null)selected.addAll(targets.stream().distinct().filter(available::containsKey).toList());
            var states=selected.stream().map(available::get).toList();
            boolean uniform=states.isEmpty()||states.stream().allMatch(s->Objects.equals(s.config(),states.getFirst().config()));
            return new ParameterStateVm(all.textConfirmed(),all.textGlobal(),all.imageConfirmed(),all.imageGlobal(),selected,selected.size(),states,uniform,states.stream().anyMatch(ParameterStateVm.TargetState::effective));
        }
        public List<PresentationVm> presentation(List<String> paths,AppConfig runtime){return snapshot().presentation().stream().filter(p->paths.contains(p.path())).toList();}
        public PreStartStateVm preStartState(AppConfig runtime,boolean checked){throw new IllegalStateException("Run pre-start query with execute");}
        public ExportSourceVm exportSource(String path){throw new IllegalStateException("Run source query with execute");}
    };
    @Override public WorkspaceCommands commands(){return commands;}
    private final WorkspaceCommands commands=new WorkspaceCommands(){
        public void cancelWorkspaceActivity(){activity(WorkspaceCommands::cancelWorkspaceActivity);}
        public void cancelPreviewLoad(){activity(WorkspaceCommands::cancelPreviewLoad);}
        public void startPreparse(){command(WorkspaceCommands::startPreparse);}
        public void startPreparseFor(List<String> paths){command(c->c.startPreparseFor(List.copyOf(paths)));}
        public void refreshPreparseProgress(){activity(WorkspaceCommands::refreshPreparseProgress);}
        public void loadPreview(String path,boolean confirmed){command(c->c.loadPreview(path,confirmed));}
        public void resolvePreviewRegion(long generation,String path,boolean accepted){command(c->c.resolvePreviewRegion(generation,path,accepted));}
        public void awaitPreviewInFlight(long millis){command(c->c.awaitPreviewInFlight(millis));}
        public void setChecked(List<String> paths,boolean checked){command(c->c.setChecked(paths,checked));}
        public void group(List<String> paths,String name){command(c->c.group(paths,name));}
        public void ungroup(long no){command(c->c.ungroup(no));}
        public void renameGroup(long no,String name){command(c->c.renameGroup(no,name));}
        public void toggleGroupExpanded(long no){command(c->c.toggleGroupExpanded(no));}
        public void setGlobalParams(boolean image,AppConfig config){command(c->c.setGlobalParams(image,config));}
        public void setGroupParams(long no,boolean image,AppConfig config){command(c->c.setGroupParams(no,image,config));}
        public void setItemParams(String path,boolean image,AppConfig config){command(c->c.setItemParams(path,image,config));}
        public void clearParams(List<TargetId> ids,boolean image){command(c->c.clearParams(ids,image));}
        public void applyGlobalParams(TargetId id,boolean image){command(c->c.applyGlobalParams(id,image));}
        public void undo(){command(WorkspaceCommands::undo);}
        public void importPaths(List<String> paths,String source,boolean drag){command(c->c.importPaths(paths,source,drag));}
        public void applyImportPlan(ImportPlanVm plan){command(c->c.applyImportPlan(plan));}
        public void startExtraction(boolean checked){command(c->c.startExtraction(checked));}
        public void cancelExtraction(){cancelExtractionNow();}
        public void clearItems(){command(WorkspaceCommands::clearItems);}
        public void removeItems(List<String> paths){command(c->c.removeItems(paths));}
        public void removeGroup(long no){command(c->c.removeGroup(no));}
        public void setTitleOverride(String path,String title){command(c->c.setTitleOverride(path,title));}
        public void applyParamsToTargets(boolean image,AppConfig config,List<TargetId> ids){command(c->c.applyParamsToTargets(image,config,ids));}
    };
    @Override public void close(){if(!closed.compareAndSet(false,true))return;try{subscription.close();}catch(Exception error){throw new IllegalStateException(error);}finally{requests.shutdownNow();}}
}
