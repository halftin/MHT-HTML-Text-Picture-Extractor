package com.mhtextractor.uiclient;

import com.mhtextractor.model.ParamSchemaService;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.FrontendPort;
import com.mhtextractor.uicore.PortSnapshot;
import com.mhtextractor.uiport.*;
import com.mhtextractor.uiclient.ipc.IpcFailure;
import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Application lifetime owner. GUI uses IPC exclusively; in-process assembly belongs to backend tests. */
public final class UiportRuntime {
    private final AtomicReference<AppConfig> context=new AtomicReference<>(new AppConfig());
    private final Consumer<Runnable> ui;
    private FrontendPort port;
    private AutoCloseable resource;
    private String locale;
    private Confirmer confirmer;
    private final java.util.concurrent.atomic.AtomicBoolean rebuilding=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private UiportRuntime(Consumer<Runnable> ui){this.ui=ui;}
    public static void start(String locale,Confirmer confirmer,Consumer<Runnable> ui,
                             Consumer<UiportRuntime> ready,Consumer<Throwable> failed){
        var owner=new UiportRuntime(ui);
        Thread.ofPlatform().daemon().name("frontend-startup").start(()->{
            try{owner.open(locale,confirmer);ui.accept(()->ready.accept(owner));}
            catch(Throwable error){owner.dispose();ui.accept(()->failed.accept(error));}
        });
    }
    private void open(String locale,Confirmer confirmer) throws Exception {
        this.locale=locale;this.confirmer=confirmer;
        String mode="ipc";
        var implementation=newIpc();resource=implementation;
        try{startIpc(implementation);}catch(IpcFailure error){if(!backendUnavailable(error))throw error;}
        port=new FrontendPort(implementation,ui,context::set,true);
        try{port.bootstrap(context.get());}catch(IpcFailure error){
            if(!backendUnavailable(error))throw error;
            // A blank disconnected projection is not an in-process backend or a fallback.
            var workspace=new WorkspaceVm(List.of(),List.of(),false,0,TriState.NONE,List.of(),
                    new WorkspaceVm.Details(false,false,0,0,null,false,0,false,0,null,null));
            var params=new ParameterStateVm(false,null,false,null,List.of(),0,List.of(),true,false);
            port.bootstrapUnavailable(new PortSnapshot(workspace,params,params,
                    new CacheStateVm(Path.of(System.getProperty("java.io.tmpdir"),"mht-extractor-preview").toString(),0,false),
                    ParamSchemaService.schema(false),ParamSchemaService.schema(true),List.of(),Map.of(),List.of(),List.of()));
        }
        port.onUnavailable(UiportRuntime::backendUnavailable,()->{});
        System.out.println("GUI_PORT_IMPLEMENTATION="+implementation.getClass().getName()+" MODE="+mode);
    }
    private IpcUiport newIpc(){return new IpcUiport(confirmer,()->{
        var r=context.get();return new IpcUiport.RuntimeContext(r.isImageModeEnabled(),pathOrNull(r.getInputFolder()),pathOrNull(r.getOutputFolder()),r.isOpenFolderAfterComplete());
    });}
    private void startIpc(IpcUiport ipc){
        String transport=System.getProperty("mht.backend.transport","executable");
        BackendSession.Command command;
        if(transport.equals("executable"))command=()->BackendExecutable.command(locale);
        else if(transport.equals("jvm"))command=()->jvmCommand(locale);
        else throw new IllegalArgumentException("Unknown mht.backend.transport: "+transport);
        ipc.start(command,Duration.ofSeconds(20));
    }
    private static boolean backendUnavailable(Throwable error){
        while(error instanceof java.util.concurrent.CompletionException||error instanceof java.util.concurrent.ExecutionException)error=error.getCause();
        return error instanceof IpcFailure ipc&&ipc.kind()!=IpcFailure.Kind.REMOTE;
    }
    public void onUnavailable(Runnable action){port().onUnavailable(UiportRuntime::backendUnavailable,action);}
    /** One fresh BackendSession via IpcUiport.start, with no action replay and no fallback. */
    public void rebuild(Consumer<Boolean> finished){
        if(closed||!rebuilding.compareAndSet(false,true)){ui.accept(()->finished.accept(false));return;}
        Thread.ofPlatform().daemon().name("frontend-rebuild").start(()->{
            IpcUiport next=null;boolean success=false;
            try{
                if(!(resource instanceof IpcUiport))throw new IllegalStateException("Rebuild requires IPC");
                next=newIpc();startIpc(next);
                synchronized(this){
                    if(closed)throw new IllegalStateException("Runtime closed");
                    var previous=resource;resource=next;previous.close();
                }
                port.replaceBackend(next).get(30,java.util.concurrent.TimeUnit.SECONDS);
                success=!closed;
            }catch(Exception error){if(next!=null)next.close();}
            finally{rebuilding.set(false);boolean outcome=success;ui.accept(()->finished.accept(outcome));}
        });
    }
    private static ProcessBuilder jvmCommand(String locale) throws java.io.IOException {
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p->!new File(p).getName().matches("(?i)(javafx|atlantafx|ikonli).*"))
                .map(p->Path.of(p).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        Path root=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"mht-frontend-");
        var args=List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"--limit-modules","java.se",
                "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-Dfile.encoding=UTF-8","-Dlogback.configurationFile=logback-headless.xml",
                "-cp",cp,"com.mhtextractor.backend.BackendCli","--serve","--locale",locale);
        Files.write(root.resolve("command.txt"),args);
        return new ProcessBuilder(args).directory(root.toFile()).redirectError(root.resolve("stderr.txt").toFile());
    }
    public FrontendPort port(){return Objects.requireNonNull(port);}
    private static String pathOrNull(String value){return value==null||value.isBlank()?null:value;}
    public void closeAsync(Runnable finished){Thread.ofPlatform().daemon().name("frontend-shutdown").start(()->{dispose();ui.accept(finished);});}
    private synchronized void dispose(){
        closed=true;
        try{if(port!=null)port.close();if(resource!=null)resource.close();
            if(resource instanceof IpcUiport ipc)ipc.stopped().get(30,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception error){error.printStackTrace();}
    }
}
