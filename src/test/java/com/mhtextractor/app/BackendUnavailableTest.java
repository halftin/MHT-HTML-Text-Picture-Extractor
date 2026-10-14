package com.mhtextractor.app;

import com.mhtextractor.app.ipc.IpcFailure;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.FrontendPort;
import com.mhtextractor.uiport.Uiport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Public behavior: disconnected GUI, request failures, bounded queries, and one fresh IPC session. */
class BackendUnavailableTest {
    private static final class RuntimeFixture implements AutoCloseable {
        final String mode=System.getProperty("mht.uiport"),transport=System.getProperty("mht.backend.transport"),exe=System.getProperty("mht.backend.executable");
        final UiportRuntime runtime;
        RuntimeFixture() throws Exception {
            System.setProperty("mht.uiport","ipc");System.setProperty("mht.backend.transport","executable");
            System.setProperty("mht.backend.executable","H:/no-such-dir/MhtExtractorBackend.exe");
            var ready=new CompletableFuture<UiportRuntime>();
            UiportRuntime.start("en-US",request->{throw new AssertionError("Unexpected question");},Runnable::run,ready::complete,ready::completeExceptionally);
            runtime=ready.get(30,TimeUnit.SECONDS);
        }
        public void close() throws Exception {
            var done=new CompletableFuture<Void>();runtime.closeAsync(()->done.complete(null));done.get(35,TimeUnit.SECONDS);
            restore("mht.uiport",mode);restore("mht.backend.transport",transport);restore("mht.backend.executable",exe);
        }
        private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    }
    @Test void missingExecutableKeepsFrontendAndDoesNotRaiseD1UntilARequest() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var count=new AtomicInteger();fixture.runtime.onUnavailable(count::incrementAndGet);
            assertInstanceOf(IpcUiport.class,fixture.runtime.port().implementation());
            assertEquals(BackendSession.State.UNAVAILABLE,((IpcUiport)fixture.runtime.port().implementation()).status().state());
            assertTrue(fixture.runtime.port().queries().workspace().topLevelItems().isEmpty());
            assertFalse(fixture.runtime.port().queries().paramSchema(false).fields().isEmpty());
            assertEquals(0,count.get(),"Status transitions and cached UI queries must not open D1");
        }
    }
    @ParameterizedTest @ValueSource(strings={"import","extract","preview-cancel","query","stop"})
    void backendActionsShareTheUnavailableDecision(String action) throws Exception {
        try(var fixture=new RuntimeFixture()){
            var requested=new CompletableFuture<Void>();fixture.runtime.onUnavailable(()->requested.complete(null));
            var port=fixture.runtime.port();
            switch(action){
                case "import" -> port.commands().importPaths(List.of("H:/TEST/mht/input.mht"),"test",false);
                case "extract" -> port.commands().startExtraction(false);
                case "preview-cancel" -> port.commands().cancelPreviewLoad();
                case "stop" -> port.commands().cancelExtraction();
                case "query" -> port.execute(p->p.queries().workspace(),ignored->fail("Failed query cannot succeed"));
                default -> throw new AssertionError(action);
            }
            requested.get(5,TimeUnit.SECONDS);
        }
    }
    @Test void successfulRebuildReplacesTheBackendWithoutReplayingTheOriginalAction() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var runtime=fixture.runtime;var stable=runtime.port();var previous=stable.implementation();
            var unavailable=new CompletableFuture<Void>();runtime.onUnavailable(()->unavailable.complete(null));
            var attempts=new AtomicInteger();var completions=new AtomicInteger();
            stable.execute(p->{attempts.incrementAndGet();p.commands().cancelPreviewLoad();return null;},ignored->completions.incrementAndGet());
            unavailable.get(5,TimeUnit.SECONDS);
            System.setProperty("mht.backend.transport","jvm");
            var rebuilt=new CompletableFuture<Boolean>();runtime.rebuild(rebuilt::complete);
            assertTrue(rebuilt.get(60,TimeUnit.SECONDS));
            assertSame(stable,runtime.port());assertNotSame(previous,stable.implementation());
            var barrier=new CompletableFuture<Integer>();stable.execute(p->p.queries().workspace().topLevelItems().size(),barrier::complete);
            assertEquals(0,barrier.get(15,TimeUnit.SECONDS));
            assertEquals(1,attempts.get(),"D1a: original action must never be replayed");
            assertEquals(0,completions.get(),"Failed original action must not resume after reconnect");
            System.out.println("D1_NO_REPLAY attempts="+attempts+" completions="+completions);
        }
    }
    @Test void failedRebuildDeliversExactlyOneNegativeResult() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var result=new CompletableFuture<Boolean>();var count=new AtomicInteger();
            fixture.runtime.rebuild(ok->{count.incrementAndGet();result.complete(ok);});
            assertFalse(result.get(30,TimeUnit.SECONDS));assertEquals(1,count.get());
            assertInstanceOf(IpcUiport.class,fixture.runtime.port().implementation());
        }
    }
    @Test void explicitBackendRejectionDoesNotTriggerUnavailable() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var d1=new AtomicInteger();var error=new CompletableFuture<Throwable>();
            fixture.runtime.onUnavailable(d1::incrementAndGet);
            fixture.runtime.port().bindRuntime(AppConfig::new,error::complete);
            fixture.runtime.port().execute(p->{throw new IpcFailure(IpcFailure.Kind.REMOTE,"INVALID_REQUEST","rejected",null);},ignored->{});
            assertInstanceOf(IpcFailure.class,error.get(5,TimeUnit.SECONDS));assertEquals(0,d1.get());
        }
    }
    @Test void initializationRequestCannotBecomeAUserActionWhenTheViewBindsLater() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var started=new CompletableFuture<Void>();var release=new CountDownLatch(1);var count=new AtomicInteger();
            fixture.runtime.port().execute(p->{
                started.complete(null);
                try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                p.commands().cancelPreviewLoad();return null;
            },ignored->fail("Initialization request succeeded"));
            started.get(5,TimeUnit.SECONDS);fixture.runtime.onUnavailable(count::incrementAndGet);release.countDown();
            var barrier=new CompletableFuture<Throwable>();fixture.runtime.port().bindRuntime(AppConfig::new,barrier::complete);
            fixture.runtime.port().execute(p->{throw new IpcFailure(IpcFailure.Kind.REMOTE,"INVALID_REQUEST","barrier",null);},ignored->{});
            barrier.get(5,TimeUnit.SECONDS);
            assertEquals(0,count.get(),"Restoring GUI state before view binding is not a user click");
        }
    }
    @Test void selectionHighlightCleanupCannotTriggerD1() throws Exception {
        try(var fixture=new RuntimeFixture()){
            var count=new AtomicInteger();var barrier=new CompletableFuture<Throwable>();
            fixture.runtime.onUnavailable(count::incrementAndGet);
            fixture.runtime.port().bindRuntime(AppConfig::new,barrier::complete);
            fixture.runtime.port().cancelPreviewForSelection();
            fixture.runtime.port().execute(p->{throw new IpcFailure(IpcFailure.Kind.REMOTE,"INVALID_REQUEST","barrier",null);},ignored->{});
            barrier.get(5,TimeUnit.SECONDS);
            assertEquals(0,count.get(),"Pure selection highlight is not a backend-dependent user action");
        }
    }
    @Test void unansweredMetricsQueryTimesOutAndTriggersD1() throws Exception {
        Path root=Files.createTempDirectory(Path.of("H:/TEST/mht"),"d1-timeout-");
        String cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p->Path.of(p).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        try(var fixture=new RuntimeFixture();var ipc=new IpcUiport(request->{throw new AssertionError();},
                ()->new IpcUiport.RuntimeContext(false,null,null,false),Duration.ofSeconds(2))){
            ipc.start(()->new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
                    "-Djava.io.tmpdir=H:/TEST/temp","-cp",cp,"com.mhtextractor.app.IpcUiportWirePeer","stall")
                    .directory(root.toFile()).redirectError(root.resolve("stderr.txt").toFile()),Duration.ofSeconds(10));
            try(var frontend=new FrontendPort(ipc,Runnable::run,ignored->{})){
                frontend.bootstrapUnavailable(fixture.runtime.port().snapshot());
                var unavailable=new CompletableFuture<Void>();
                frontend.onUnavailable(e->e instanceof IpcFailure f&&f.kind()==IpcFailure.Kind.TIMEOUT,()->unavailable.complete(null));
                long started=System.nanoTime();
                frontend.execute(p->((IpcUiport.Queries)p.queries()).metrics(),ignored->fail("Unanswered query succeeded"));
                unavailable.get(6,TimeUnit.SECONDS);
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertTrue(elapsed>=1500&&elapsed<5500,"Explicit 2 second RPC timeout: "+elapsed);
                System.out.println("D1_QUERY_TIMEOUT elapsedMs="+elapsed);
            }
        }
    }
}
