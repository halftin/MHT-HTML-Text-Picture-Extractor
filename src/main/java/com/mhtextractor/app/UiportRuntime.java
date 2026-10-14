package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.FrontendPort;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.Uiport;
import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Application lifetime owner. IPC is the default; fallback is selected only by an explicit property. */
public final class UiportRuntime {
    private final AtomicReference<AppConfig> context=new AtomicReference<>(new AppConfig());
    private final Consumer<Runnable> ui;
    private FrontendPort port;
    private AutoCloseable resource;
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
        String mode=System.getProperty("mht.uiport","ipc");
        Uiport implementation;
        if(mode.equals("inprocess")){
            var workspace=new BackendWorkspace(confirmer,context::get,Runnable::run);resource=workspace;implementation=workspace.port();
        }else if(mode.equals("ipc")){
            var ipc=new IpcUiport(confirmer,()->{
                var r=context.get();return new IpcUiport.RuntimeContext(r.isImageModeEnabled(),pathOrNull(r.getInputFolder()),pathOrNull(r.getOutputFolder()),r.isOpenFolderAfterComplete());
            });resource=ipc;
            String transport=System.getProperty("mht.backend.transport","executable");
            BackendSession.Command command;
            if(transport.equals("executable"))command=()->BackendExecutable.command(locale);
            else if(transport.equals("jvm"))command=()->jvmCommand(locale);
            else throw new IllegalArgumentException("Unknown mht.backend.transport: "+transport);
            ipc.start(command,Duration.ofSeconds(20));implementation=ipc;
        }else throw new IllegalArgumentException("Unknown mht.uiport: "+mode);
        port=new FrontendPort(implementation,ui,context::set,mode.equals("ipc"));port.bootstrap(context.get());
        System.out.println("GUI_PORT_IMPLEMENTATION="+implementation.getClass().getName()+" MODE="+mode);
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
    private void dispose(){
        try{if(port!=null)port.close();if(resource!=null)resource.close();
            if(resource instanceof IpcUiport ipc)ipc.stopped().get(30,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception error){error.printStackTrace();}
    }
}
