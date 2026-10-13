package com.mhtextractor.app;

import com.mhtextractor.app.ipc.*;
import com.mhtextractor.model.*;
import com.mhtextractor.uiport.*;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class IpcUiportTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final class Token implements ConfirmToken {
        final CompletableFuture<String> result = new CompletableFuture<>();
        public void answer(String action) { result.complete(action); }
        public void cancel() { result.complete(null); }
        public String await() throws InterruptedException {
            try { return result.get(); } catch (ExecutionException e) { throw new IllegalStateException(e); }
        }
    }
    private record Question(ConfirmRequest request, Token token) {}
    private static final class Cards implements Confirmer {
        final BlockingQueue<Question> questions = new LinkedBlockingQueue<>();
        public ConfirmToken confirm(ConfirmRequest request) {
            var token = new Token(); questions.add(new Question(request,token)); return token;
        }
        Question next() throws Exception {
            var question = questions.poll(8,TimeUnit.SECONDS); assertNotNull(question); return question;
        }
    }
    private Path root() throws Exception { return Files.createTempDirectory(Path.of("H:/TEST/mht"),"p5a-2-"); }
    private ProcessBuilder process(Path root, String main, String... args) {
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !new File(entry).getName().matches("(?i)(java"+"fx|atlantafx|ikonli).*"))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
                "--limit-modules","java.se","-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
                "-Dlogback.configurationFile=logback-headless.xml","-cp",cp,main));
        command.addAll(List.of(args));
        try { Files.write(root.resolve("command.txt"),command); } catch (Exception e) { throw new IllegalStateException(e); }
        return new ProcessBuilder(command).directory(root.toFile()).redirectError(root.resolve("stderr.txt").toFile());
    }
    private IpcUiport port(Path root, Cards cards, String main, String... args) {
        var port = new IpcUiport(cards,() -> new IpcUiport.RuntimeContext(false,null,root.resolve("output").toString(),false));
        port.failure().thenAccept(error -> System.out.println("IPC_FAILURE: kind="+error.kind()+" detail="+error.getMessage()));
        try { port.start(() -> process(root,main,args),Duration.ofSeconds(10)); return port; }
        catch (RuntimeException e) { port.close(); throw e; }
    }
    private IpcUiport real(Path root, Cards cards) { return port(root,cards,"com.mhtextractor.backend.BackendCli","--serve","--locale","en"); }
    private List<String> sources(Path root, int count) throws Exception {
        var paths = new ArrayList<String>();
        for (int i=0;i<count;i++) {
            Path path = root.resolve("source"+i+".html"); Files.writeString(path,"<html><body>content "+i+"</body></html>"); paths.add(path.toString());
        }
        return paths;
    }
    private ImportPlanVm plan(Path root, List<String> paths, boolean grouped) {
        return new ImportPlanVm(List.of(new ImportPlanVm.ImportBatchVm(root.toString(),"source",grouped,paths)),0,0,0,0,0,false,false);
    }
    private static Object comparable(PortEvent event) {
        if (event instanceof PortEvent.WorkspaceChanged e) return new PortEvent.WorkspaceChanged(e.kind());
        if (event instanceof PortEvent.ImportRound e) return List.of(e.generation(),e.plan(),e.newFiles(),e.newGroups(),e.madeListNonEmpty());
        return event;
    }

    @Test void detailMethodsAndSupplementalFieldsSurviveClientRoundTrip() throws Exception {
        Path root=root(); var paths=sources(root,2); var cards=new Cards();
        var local=new InProcessUiport(new WorkspaceModel(),cards);
        try (var ipc=real(root,cards)) {
            var events=new LinkedBlockingQueue<PortEvent>(); ipc.events().subscribe(events::add);
            for (var port : List.<Uiport>of(local,ipc)) {
                port.commands().applyImportPlan(plan(root,paths,false));
                port.commands().setChecked(paths,true);
                port.commands().setTitleOverride(paths.getFirst(),"  edited title  ");
                port.commands().applyParamsToTargets(false,new AppConfig(),List.of());
            }
            assertEquals(local.queries().workspace(),ipc.queries().workspace());
            assertEquals(local.queries().parameterState(false,List.of()),ipc.queries().parameterState(false,List.of()));
            assertEquals(2,ipc.queries().parameterState(false,List.of()).count());
            var runtime=new AppConfig(); runtime.setInputFolder(root.toString()); runtime.setOutputFolder(root.resolve("query-only").toString());
            assertEquals(local.queries().preStartState(runtime,true),ipc.queries().preStartState(runtime,true));
            assertFalse(Files.exists(root.resolve("query-only")));
            assertEquals(local.queries().presentation(paths,runtime),ipc.queries().presentation(paths,runtime));
            var title=events.stream().filter(e -> e instanceof PortEvent.WorkspaceChanged w && w.kind().equals("TITLE_CHANGED"))
                    .map(PortEvent.WorkspaceChanged.class::cast).findFirst().orElseThrow();
            assertEquals("edited title",title.snapshot().topLevelItems().getFirst().titleOverride());
            var a=local.queries().exportSource(paths.getFirst()); var b=ipc.queries().exportSource(paths.getFirst());
            assertEquals(a.status(),b.status()); assertEquals(a.detail(),b.detail());
            assertEquals(a.imageCount(),b.imageCount()); assertEquals(a.warningCount(),b.warningCount());
            assertArrayEquals(Files.readAllBytes(Path.of(a.htmlFile().path())),Files.readAllBytes(Path.of(b.htmlFile().path())));
            assertEquals(a.htmlFile().bytes(),b.htmlFile().bytes()); assertEquals(a.htmlFile().mediaType(),b.htmlFile().mediaType());
            ipc.commands().startPreparse(); until(events,PortEvent.PreparseFinished.class::isInstance);
            until(events,PortEvent.PreparseFinished.class::isInstance);
            var item=ipc.queries().workspace().topLevelItems().getFirst();
            assertEquals("READY",item.status()); assertEquals("",item.errorMsg()); assertNotNull(item.textFile());
            assertEquals("content 0",Files.readString(Path.of(item.textFile().path())));
            var presentation=ipc.queries().presentation(List.of(paths.getFirst()),runtime).getFirst();
            assertEquals(Files.readString(Path.of(item.textFile().path())),Files.readString(Path.of(presentation.textFile().path())));
            assertEquals("edited title",ipc.queries().workspace().topLevelItems().getFirst().titleOverride());
            assertFalse(ipc.failure().isDone());
        }
    }

    @Test void explicitParameterStateReturnsRequestedTargetWhileAnotherItemIsChecked() throws Exception {
        Path root=root(); var paths=sources(root,2); var cards=new Cards();
        var local=new InProcessUiport(new WorkspaceModel(),cards);
        try (var ipc=real(root,cards)) {
            var a=TargetId.ofItem(paths.getFirst()); var b=TargetId.ofItem(paths.getLast());
            for (boolean mode : List.of(false,true)) {
                var global=new AppConfig(); global.setStartMarker("global");
                var independent=new AppConfig(); independent.setStartMarker("B-independent");
                for (var port : List.<Uiport>of(local,ipc)) {
                    if (!mode) port.commands().applyImportPlan(plan(root,paths,false));
                    port.commands().setGlobalParams(mode,global);
                    port.commands().setItemParams(paths.getLast(),mode,independent);
                    port.commands().setChecked(List.of(paths.getFirst()),true);
                }
                var before=ipc.queries().workspace();
                var events=new ArrayList<PortEvent>();
                try (var subscription=ipc.events().subscribe(events::add)) {
                    var state=ipc.queries().parameterState(mode,List.of(b),TargetResolution.EXPLICIT);
                    assertEquals(List.of(b),state.targets(),"explicit must not resolve checked A instead of requested B");
                    assertEquals(1,state.count()); assertTrue(state.uniform()); assertTrue(state.hasEffective());
                    assertEquals(new ParameterStateVm.TargetState(b,true,true,"GROUP",independent),state.states().getFirst());
                    assertEquals(local.queries().parameterState(mode,List.of(b),TargetResolution.EXPLICIT),state);
                    assertEquals(local.queries().parameterState(mode,List.of(a,b),TargetResolution.EXPLICIT),
                            ipc.queries().parameterState(mode,List.of(a,b),TargetResolution.EXPLICIT));
                    assertEquals(before,ipc.queries().workspace(),"queries must not modify the authoritative workspace");
                    assertTrue(events.isEmpty(),"queries must not emit mutation events or advance revision");
                    assertEquals(List.of(a),ipc.queries().parameterState(mode,List.of(b)).targets());
                    assertEquals(ipc.queries().parameterState(mode,List.of(b)),
                            ipc.queries().parameterState(mode,List.of(b),TargetResolution.SELECTED_FIRST));
                }
            }
            System.out.println("Q10_EXPLICIT_PASS checked=A requested=B source=GROUP; text/image dual-path every field equal; read-only");
        }
    }

    @Test void explicitParameterStatePreservesOrderDeduplicatesAndDoesNotUseSelectionForEmptyInput() throws Exception {
        Path root=root(); var paths=sources(root,2); var cards=new Cards();
        var local=new InProcessUiport(new WorkspaceModel(),cards);
        try (var ipc=real(root,cards)) {
            var a=TargetId.ofItem(paths.getFirst()); var b=TargetId.ofItem(paths.getLast());
            for (var port : List.<Uiport>of(local,ipc)) {
                port.commands().applyImportPlan(plan(root,paths,false)); port.commands().setChecked(paths,true);
                var empty=port.queries().parameterState(false,List.of(),TargetResolution.EXPLICIT);
                assertEquals(0,empty.count()); assertTrue(empty.uniform()); assertFalse(empty.hasEffective());
                assertEquals(List.of(),empty.targets()); assertEquals(List.of(),empty.states());
                assertEquals(List.of(b,a),port.queries().parameterState(false,List.of(b,a,b),TargetResolution.EXPLICIT).targets());
            }
            assertEquals(local.queries().parameterState(false,List.of(b,a,b),TargetResolution.EXPLICIT),
                    ipc.queries().parameterState(false,List.of(b,a,b),TargetResolution.EXPLICIT));
            var error=assertThrows(IpcFailure.class,()->ipc.queries().parameterState(false,
                    List.of(TargetId.ofItem("missing")),TargetResolution.EXPLICIT));
            assertEquals("INVALID_PARAMS",error.code());
            assertThrows(IllegalArgumentException.class,()->local.queries().parameterState(false,
                    List.of(TargetId.ofItem("missing")),TargetResolution.EXPLICIT));
            assertFalse(ipc.failure().isDone());
        }
    }

    @Test void dualPathCommandsQueriesAndEventsMatchFieldForField() throws Exception {
        Path root = root(); List<String> paths = sources(root,3);
        Path imageFixture = imageSource(root);
        for (String path : paths) Files.copy(imageFixture, Path.of(path), StandardCopyOption.REPLACE_EXISTING);
        Cards cards = new Cards(); WorkspaceModel model = new WorkspaceModel();
        var global = new GlobalParameterCache();
        var previews = PreviewSession.create(root.resolve("inprocess-cache"));
        try (var ipc = real(root,cards)) {
            var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
                public List<Object> dataOrder() { return model.topLevel(); }
                public boolean imageMode() { return false; }
                public AppConfig textParamsCache() { return global.text(); }
                public AppConfig imageParamsCache() { return global.image(); }
                public String inputFolder() { return null; }
                public String outputFolder() { return root.resolve("output").toString(); }
                public boolean openFolderAfterComplete() { return false; }
            });
            var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
                public List<PreviewItem> items() { return model.allItems(); }
                public boolean textMode() { return true; }
                public AppConfig effectiveParams(PreviewItem item) { return plans.effectiveParams(item); }
                public AppConfig effectiveImageParams(PreviewItem item) { return plans.effectiveImageParams(item); }
            },new PreviewService(previews.lifecycle()),(image,index,config) -> new ThumbnailData("unused",0,0,null),Runnable::run);
            var clipboard = new ParamClipboard(model::push,target -> ParamScope.GLOBAL,(target,mode,config) -> {});
            var params = new WorkspaceParamCommandService(model,new WorkspaceParamService(previews.lifecycle()),global,clipboard::pushParamUndo);
            var local = new InProcessUiport(model,cards,plans,activities,params,
                    new WorkspaceImportCoordinator(model,cards,Runnable::run),null,
                    new WorkspaceParamService(previews.lifecycle()),global,previews);
            var localEvents = new ArrayList<PortEvent>(); var ipcEvents = new CopyOnWriteArrayList<PortEvent>();
            local.events().subscribe(localEvents::add); ipc.events().subscribe(ipcEvents::add);
            var sequence = new ArrayList<Consumer<WorkspaceCommands>>();
            sequence.add(c -> c.applyImportPlan(plan(root,paths,false)));
            sequence.add(c -> c.setChecked(Arrays.asList(paths.get(0),"missing",null),true));
            sequence.add(c -> c.group(paths.subList(0,2),"group"));
            sequence.add(c -> c.renameGroup(1,"renamed"));
            sequence.add(c -> c.toggleGroupExpanded(1));
            sequence.add(c -> c.toggleGroupExpanded(1));
            sequence.add(c -> c.applyDrop(TargetId.ofItem(paths.get(2)),TargetId.ofGroup(1),"INTO"));
            sequence.add(c -> c.ungroup(1));
            sequence.add(WorkspaceCommands::undo);
            sequence.add(c -> c.removeItems(List.of(paths.get(2))));
            sequence.add(WorkspaceCommands::undo);
            sequence.add(c -> c.removeGroup(1));
            sequence.add(WorkspaceCommands::undo);
            var config = new AppConfig(); config.setStartMarker("start");
            sequence.add(c -> c.setGlobalParams(false,config));
            sequence.add(c -> c.setGroupParams(1,true,config));
            sequence.add(c -> c.setItemParams(paths.get(0),false,config));
            sequence.add(c -> c.clearParams(List.of(TargetId.ofGroup(1)),false));
            sequence.add(c -> c.applyGlobalParams(TargetId.ofItem(paths.get(0)),false));
            sequence.add(c -> c.group(List.of("missing"),"none"));
            sequence.add(c -> c.renameGroup(999,"none"));
            sequence.add(c -> c.ungroup(999));
            sequence.add(c -> c.removeGroup(999));
            sequence.add(c -> c.setGroupParams(999,false,config));
            sequence.add(c -> c.setItemParams("missing",false,config));
            sequence.add(c -> c.setChecked(null,false));
            sequence.add(c -> c.group(null,"none"));
            sequence.add(c -> c.removeItems(null));
            sequence.add(c -> c.clearParams(null,false));
            int steps = 0;
            for (var operation : sequence) {
                operation.accept(local.commands()); operation.accept(ipc.commands());
                assertEquals(local.queries().workspace(),ipc.queries().workspace(),"Workspace at step "+steps);
                assertEquals(local.queries().canUndo(),ipc.queries().canUndo());
                assertEquals(local.queries().runtimeConfig(),ipc.queries().runtimeConfig());
                assertEquals(local.queries().outputUnitPlan(false),ipc.queries().outputUnitPlan(false));
                assertEquals(local.queries().outputUnitPlan(true),ipc.queries().outputUnitPlan(true));
                assertEquals(localEvents.stream().map(IpcUiportTest::comparable).toList(),ipcEvents.stream().map(IpcUiportTest::comparable).toList(),"Events at step "+steps++);
            }
            assertEquals(local.queries().paramSchema(false),ipc.queries().paramSchema(false));
            assertEquals(local.queries().paramSchema(true),ipc.queries().paramSchema(true));
            for (var event : ipcEvents) if (event instanceof PortEvent.WorkspaceChanged e) assertNotNull(e.snapshot());
            var targets = paths.stream().map(TargetId::ofItem).toList();
            var expectedGlobal = global.text().copyExtractionParams();
            assertNull(model.allItems().getFirst().textItemParams);
            var cached = new ArrayList<Path>();
            var remoteCached = new ArrayList<Path>();
            var previewService = new PreviewService(previews.lifecycle());
            for (var item : model.allItems()) {
                var c = plans.effectiveImageParams(item);
                var entry = previewService.ensureCachedLocked(previewService.prepare(Path.of(item.path), c), () -> false);
                cached.add(previews.store().imageFileFor(entry.meta(), 0));
                remoteCached.add(Path.of(ipc.queries().previewFiles(item.path).images().getFirst().path()));
            }
            for (var files : List.of(cached, remoteCached)) for (Path path : files) assertTrue(Files.exists(path));
            int depth = local.queries().workspace().undoDepth();
            local.commands().undo(); ipc.commands().undo();
            assertEquals(depth - 1, local.queries().workspace().undoDepth());
            assertEquals(local.queries().workspace(), ipc.queries().workspace());
            assertEquals(config.copyExtractionParams(), model.allItems().getFirst().textItemParams.copyExtractionParams(), "Apply-global before restored");
            assertEquals(expectedGlobal, global.text().copyExtractionParams());
            for (boolean mode : List.of(false, true)) assertEquals(local.queries().parameterState(mode,targets,TargetResolution.EXPLICIT),
                    ipc.queries().parameterState(mode,targets,TargetResolution.EXPLICIT));
            for (var files : List.of(cached, remoteCached)) for (Path path : files) assertTrue(Files.exists(path), "Text undo preserves image caches");
            // SET_ITEM(text) precedes SET_GROUP(image); both consume exactly one record.
            depth = local.queries().workspace().undoDepth();
            local.commands().undo(); ipc.commands().undo();
            assertEquals(depth - 1, local.queries().workspace().undoDepth());
            assertEquals(local.queries().workspace(), ipc.queries().workspace());
            assertNull(model.allItems().getFirst().textItemParams);
            depth = local.queries().workspace().undoDepth();
            local.commands().undo(); ipc.commands().undo();
            assertEquals(depth - 1, local.queries().workspace().undoDepth());
            assertNull(model.allItems().getFirst().parentGroup.getImageParamsCache());
            assertEquals(3, model.allItems().getFirst().parentGroup.getMembers().size());
            for (var files : List.of(cached, remoteCached)) for (Path path : files)
                assertFalse(Files.exists(path), "Image group undo invalidates all three members after drop");
            assertEquals(local.queries().workspace(), ipc.queries().workspace());
            for (boolean mode : List.of(false, true)) assertEquals(local.queries().parameterState(mode,targets,TargetResolution.EXPLICIT),
                    ipc.queries().parameterState(mode,targets,TargetResolution.EXPLICIT));
            local.commands().clearItems(); ipc.commands().clearItems();
            assertEquals(local.queries().workspace(),ipc.queries().workspace());
            System.out.println("DUAL_PATH_PASS steps="+steps+" events="+ipcEvents.size()+" workspace/config/units/schema exact; U2 restored/depth-1/cache-deleted");
        }
    }

    @Test void groupedImportUsesIndependentVmAndSubscriptionCloseStopsDelivery() throws Exception {
        Path root = root(); List<String> paths = sources(root,2);
        try (var ipc = real(root,new Cards())) {
            var events = new ArrayList<PortEvent>(); var handle = ipc.events().subscribe(events::add);
            ipc.commands().applyImportPlan(plan(root,paths,true));
            var local = new InProcessUiport(new WorkspaceModel(),new Cards());
            local.commands().applyImportPlan(plan(root,paths,true));
            var round = events.stream().filter(PortEvent.ImportRound.class::isInstance).map(PortEvent.ImportRound.class::cast).findFirst().orElseThrow();
            assertTrue(round.createdGroups().isEmpty()); assertEquals(ipc.queries().workspace().groups(),round.groupSnapshots());
            assertEquals(local.queries().workspace(),ipc.queries().workspace());
            assertEquals(local.queries().workspace().groups(),round.groupSnapshots());
            assertEquals(paths,round.groupSnapshots().getFirst().members().stream().map(ItemVm::id).toList());
            assertThrows(UnsupportedOperationException.class,() -> round.groupSnapshots().clear());
            int count=events.size(); handle.close(); handle.close(); ipc.commands().clearItems(); assertEquals(count,events.size());
            System.out.println("GROUP_VM_AND_UNSUBSCRIBE_PASS");
        }
    }

    @Test void paginationConsumesEveryFileAndRejectsBounds() throws Exception {
        Path root = root(); List<String> paths = sources(root,257);
        try (var ipc = real(root,new Cards())) {
            ipc.commands().applyImportPlan(plan(root,paths,false));
            var first = ipc.workspacePage(0,256); var last = ipc.workspacePage(256,256); var beyond = ipc.workspacePage(999,1);
            assertEquals(257,first.total()); assertEquals(256,first.items().size()); assertEquals(1,last.items().size()); assertTrue(beyond.items().isEmpty());
            assertEquals(first.snapshot(),last.snapshot()); assertEquals(first.snapshot(),ipc.queries().workspace());
            assertEquals(first.revision(),last.revision());
            for (int limit : List.of(0,257)) assertEquals("INVALID_PARAMS",assertThrows(IpcFailure.class,() -> ipc.workspacePage(0,limit)).code());
            assertEquals("INVALID_PARAMS",assertThrows(IpcFailure.class,() -> ipc.workspacePage(-1,1)).code());
            ipc.commands().setChecked(paths,true); // >256 uploads exactly one pathsFile RPC.
            assertEquals(TriState.ALL,ipc.queries().workspace().selection());
            assertTrue(ipc.metrics().heapPoolPeakBytes()>0);
            System.out.println("PAGINATION_PASS 256+1 beyond=0 bounds=rejected bulkUpload=257");
        }
    }

    private Path imageSource(Path root) throws Exception {
        Path path = root.resolve("image.html");
        Files.writeString(path,"<html><body>hello<img src=\"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIW2P4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==\"></body></html>");
        return path;
    }
    @Test void fileRefReleasedCacheImageCannotBeReadAgainEvenThoughFileRemains() throws Exception {
        Path root=root(); Path source=imageSource(root);
        try (var ipc=real(root,new Cards())) {
            ipc.commands().applyImportPlan(plan(root,List.of(source.toString()),false));
            var payload=ipc.previewFiles(source.toString()); assertEquals(1,payload.images().size());
            var image=payload.images().getFirst(); assertEquals("image/png",image.mediaType());
            var ref=new IpcFilesClient.FileRef(image.path(),image.bytes(),image.mediaType());
            assertTrue(Files.isRegularFile(Path.of(ref.path())),"Cache owner retains original after revocation");
            assertThrows(IllegalStateException.class,() -> ipc.files().read(ref));
            assertEquals("INVALID_PARAMS",assertThrows(IpcFailure.class,() -> ipc.fileInfo(ref)).code());
            assertTrue(Files.isRegularFile(Path.of(ref.path())));
            System.out.println("FILE_REF_PASS issued/read/released; retainedOriginal=true; reread=rejected; serverFileInfo=INVALID_PARAMS");
        }
    }

    @Test void asynchronousImportPreparseAndPreviewDeliverOrderedTypedEvents() throws Exception {
        Path root=root(); Path source=imageSource(root);
        try (var ipc=real(root,new Cards())) {
            var events=new LinkedBlockingQueue<PortEvent>(); ipc.events().subscribe(events::add);
            ipc.commands().importPaths(List.of(source.toString()),"IPC",false);
            var imported=until(events,e -> e instanceof PortEvent.ImportStateChanged s && !s.importing());
            assertInstanceOf(PortEvent.ImportStateChanged.class,imported.getFirst());
            assertTrue(imported.stream().anyMatch(PortEvent.ImportFinished.class::isInstance));
            ipc.commands().startPreparse();
            var parsed=until(events,PortEvent.PreparseFinished.class::isInstance);
            assertTrue(parsed.stream().anyMatch(PortEvent.PreparseStarted.class::isInstance));
            var result=((PortEvent.PreparseFinished)parsed.getLast()).result(); assertEquals("hello",result.text()); assertTrue(result.textAssigned());
            ipc.commands().startPreparseFor(List.of(source.toString()));
            until(events,PortEvent.PreparseFinished.class::isInstance);
            ipc.commands().loadPreview(source.toString(),true);
            var preview=until(events,PortEvent.PreviewLoaded.class::isInstance);
            var thumb=preview.stream().filter(PortEvent.PreviewThumbnail.class::isInstance).map(PortEvent.PreviewThumbnail.class::cast).findFirst().orElseThrow();
            assertNotNull(thumb.imageFile()); assertEquals("png",thumb.imageFile().extension()); assertNull(thumb.thumbnail().pixels());
            var loaded=(PortEvent.PreviewLoaded)preview.getLast(); assertEquals("hello",loaded.payload().text()); assertEquals(1,loaded.payload().images().size());
            assertEquals(new PortEvent.ImageFile.Dimensions(thumb.imageFile().width(),thumb.imageFile().height()),thumb.imageFile().dimensions());
            assertNotNull(thumb.imageFile().displayName());
            var runtime=new AppConfig(); runtime.setImageModeEnabled(true);
            var presented=ipc.queries().presentation(List.of(source.toString()),runtime).getFirst();
            assertEquals(1,presented.images().size());
            var shown=presented.images().getFirst();
            assertEquals(thumb.imageFile().dimensions(),shown.dimensions());
            assertEquals(shown.path(),shown.imagePath()); assertNotNull(shown.displayName());
            assertTrue(ipc.queries().cacheState().hasCachedEntries());
            // A data URI stays inline during source export; a CID part exercises copied companion files.
            Path mime=root.resolve("source-with-cid.mht");
            Files.writeString(mime,"MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=parts\r\n\r\n"
                    +"--parts\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n<html><body><img src=\"cid:pic\"></body></html>\r\n"
                    +"--parts\r\nContent-Type: image/png\r\nContent-ID: <pic>\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                    +"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIW2P4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==\r\n--parts--\r\n");
            var exported=ipc.queries().exportSource(mime.toString());
            assertEquals("success",exported.status(),exported.toString()); assertEquals(1,exported.imageCount());
            Path html=Path.of(exported.htmlFile().path());
            var document=org.jsoup.Jsoup.parse(Files.readString(html));
            assertEquals(1,document.select("img[src]").size());
            for (var img : document.select("img[src]")) assertTrue(Files.isRegularFile(html.getParent().resolve(img.attr("src"))),img.attr("src"));
            ipc.commands().awaitPreviewInFlight(2000); ipc.commands().cancelPreviewLoad(); ipc.commands().cancelWorkspaceActivity(); ipc.commands().refreshPreparseProgress();
            assertTrue(ipc.queries().activityGeneration()>0); assertTrue(ipc.queries().previewGeneration()>0); assertNull(ipc.queries().previewLoadingPath());
            assertFalse(ipc.failure().isDone()); System.out.println("ASYNC_EVENTS_PASS import/preparse/preview filePayload; generation/revision="+ipc.revision());
        }
    }
    private List<PortEvent> until(BlockingQueue<PortEvent> events, java.util.function.Predicate<PortEvent> terminal) throws Exception {
        var result=new ArrayList<PortEvent>(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime()<deadline) {
            var event=events.poll(1,TimeUnit.SECONDS); if (event==null) continue; result.add(event); if (terminal.test(event)) return result;
        }
        fail("Missing terminal event: "+result); return result;
    }

    @Test void unknownAndRegressingOrOverflowingEventsExplicitlyFail() throws Exception {
        for (String mode : List.of("unknown","regress","overflow")) {
            Path root=root();
            try (var ipc=port(root,new Cards(),IpcUiportWirePeer.class.getName(),mode)) {
                try { ipc.commands().cancelWorkspaceActivity(); } catch (IpcFailure expected) { }
                var failure=ipc.failure().get(5,TimeUnit.SECONDS);
                assertTrue(failure.getMessage().contains(mode.equals("unknown")?"Unknown backend event kind":"revision"),failure.toString());
                assertThrows(IpcFailure.class,() -> ipc.queries().workspace());
                System.out.println("ADVERSARIAL_EVENT_PASS mode="+mode+" detail="+failure.getMessage());
            }
        }
    }

    @Test void confirmationsRoundTripReplacementCancelsAndTimeoutCancels() throws Exception {
        for (String mode : List.of("replace","timeout")) {
            Path root=root(); Cards cards=new Cards();
            try (var ipc=port(root,cards,IpcUiportWirePeer.class.getName(),mode)) {
                ipc.commands().cancelWorkspaceActivity(); Question first=cards.next();
                assertNull(first.token.result.get(5,TimeUnit.SECONDS));
                if (mode.equals("replace")) { Question second=cards.next(); second.token.answer("continue"); }
                // Priority confirmation answers run independently from file-release RPCs and subscribers.
                ipc.commands().refreshPreparseProgress();
                Path peerRoot=Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                        .filter(p -> p.getFileName().toString().startsWith("ipc-client-peer-"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(peerRoot.resolve(mode.equals("replace")?"second.answer":"first.answer")) && System.nanoTime()<deadline) Thread.sleep(10);
                assertEquals("null",Files.readString(peerRoot.resolve("first.answer")));
                if (mode.equals("replace")) assertEquals("\"continue\"",Files.readString(peerRoot.resolve("second.answer")));
                System.out.println("CONFIRM_ROUNDTRIP_PASS mode="+mode+" first=null");
            }
        }
    }

    @Test void localConfirmerReplacesValidatesAndCloses() throws Exception {
        Cards cards=new Cards();
        try (var ipc=new IpcUiport(cards,() -> new IpcUiport.RuntimeContext(false,null,null,false))) {
            var request=new ConfirmRequest("test",Map.of(),List.of("yes"),2000);
            var first=ipc.confirmer().confirm(request); var second=ipc.confirmer().confirm(request);
            assertNull(first.await()); assertThrows(IllegalArgumentException.class,() -> second.answer("bad"));
            second.answer("yes"); second.cancel(); assertEquals("yes",second.await());
            var third=ipc.confirmer().confirm(request); ipc.close(); assertNull(third.await());
            assertEquals(IpcFailure.Kind.CLOSED,assertThrows(IpcFailure.class,() -> ipc.queries().workspace()).kind());
        }
    }

    @Test void backendRejectionAndUnavailableAreExplicit() throws Exception {
        Path root=root();
        try (var ipc=port(root,new Cards(),IpcUiportWirePeer.class.getName(),"reject")) {
            var error=assertThrows(IpcFailure.class,ipc.commands()::cancelWorkspaceActivity);
            assertEquals(IpcFailure.Kind.REMOTE,error.kind()); assertEquals("UNKNOWN_METHOD",error.code());
        }
        try (var ipc=new IpcUiport(new Cards(),() -> new IpcUiport.RuntimeContext(false,null,null,false))) {
            assertEquals(IpcFailure.Kind.UNAVAILABLE,assertThrows(IpcFailure.class,() -> ipc.start(
                    () -> new ProcessBuilder(root.resolve("missing.exe").toString()),Duration.ofSeconds(1))).kind());
            assertEquals(IpcFailure.Kind.UNAVAILABLE,ipc.failure().get(5,TimeUnit.SECONDS).kind());
        }
        try (var ipc=new IpcUiport(new Cards(),() -> new IpcUiport.RuntimeContext(false,null,null,false))) {
            assertEquals(IpcFailure.Kind.PROTOCOL,assertThrows(IpcFailure.class,() -> ipc.start(
                    () -> process(root,"com.mhtextractor.backend.BackendLauncherWireFixture","protocol"),Duration.ofSeconds(5))).kind());
            assertEquals(IpcFailure.Kind.PROTOCOL,ipc.failure().get(5,TimeUnit.SECONDS).kind());
        }
    }

    @Test void realExtractionConfirmationAnswerAndTimeoutCompleteWithoutBlockingEvents() throws Exception {
        for (String mode : List.of("answer","timeout","cancel")) {
            Path root=root(); Path source=root.resolve("deep.mht");
            String leaf="Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>truncated</body></html>";
            String body="Content-Type: multipart/mixed; boundary=leaf\r\n\r\n--leaf\r\n"+leaf+"\r\n--leaf\r\n"+leaf+"\r\n--leaf--\r\n";
            for(int i=98;i>=0;i--) body="Content-Type: multipart/mixed; boundary=b"+i+"\r\n\r\n--b"+i+"\r\n"+body+"\r\n--b"+i+"--\r\n";
            Files.writeString(source,"MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n--outer\r\n"+body+"\r\n--outer--\r\n");
            Cards cards=new Cards();
            try(var ipc=port(root,cards,"com.mhtextractor.backend.BackendIpcConfirmationFixture",mode.equals("timeout")?"350":"30000")) {
                var events=new LinkedBlockingQueue<PortEvent>(); ipc.events().subscribe(events::add);
                ipc.commands().applyImportPlan(plan(root,List.of(source.toString()),false)); events.clear();
                ipc.commands().startExtraction(false); Question first=cards.next();
                assertEquals("alert.recursionDepthHeader",first.request().key());
                if(mode.equals("timeout")) {
                    assertNull(first.token.result.get(5,TimeUnit.SECONDS)); cards.next().token.answer("continue");
                } else if(mode.equals("cancel")) ipc.commands().cancelExtraction();
                else first.token.answer("continue");
                var delivered=until(events,PortEvent.ExtractionTerminal.class::isInstance);
                assertTrue(delivered.stream().anyMatch(PortEvent.RunningChanged.class::isInstance));
                var terminal=(PortEvent.ExtractionTerminal)delivered.getLast();
                assertEquals(mode.equals("cancel")?PortEvent.ExtractionEnd.CANCELLED:PortEvent.ExtractionEnd.SUCCEEDED,terminal.end());
                if(mode.equals("cancel")) {
                    assertNull(terminal.stats()); var partial=until(events,PortEvent.ExtractionResult.class::isInstance);
                    assertNotNull(((PortEvent.ExtractionResult)partial.getLast()).stats());
                } else {
                    assertNotNull(terminal.stats()); assertFalse(terminal.stats().files().isEmpty());
                    assertEquals(source.toString(),terminal.stats().files().getFirst().path());
                    assertTrue(delivered.stream().anyMatch(PortEvent.ExtractionProgress.class::isInstance));
                    var snapshot=ipc.queries().workspace();
                    assertEquals(terminal.stats().files().getFirst().outcome().name(),snapshot.topLevelItems().getFirst().extractOutcome());
                    assertEquals(terminal.sessionOutputFolder(),snapshot.details().sessionOutputFolder());
                    assertNotNull(snapshot.details().lastStats());
                    assertEquals(terminal.stats().files().size(),JSON.readTree(Files.readString(Path.of(snapshot.details().lastStats().path()))).path("files").size());
                }
                ipc.commands().cancelExtraction(); assertFalse(ipc.failure().isDone());
                System.out.println("REAL_EXTRACTION_CONFIRM_PASS mode="+mode+" end="+terminal.end()+" stats="+terminal.stats());
            }
        }
    }

    @Test void subscriberCanQueryWithoutBlockingTheReadLoop() throws Exception {
        Path root=root(); var paths=sources(root,1);
        try(var ipc=real(root,new Cards())) {
            var observed=new CompletableFuture<Long>();
            ipc.events().subscribe(event -> {
                if(event instanceof PortEvent.WorkspaceChanged) observed.complete(ipc.queries().activityGeneration());
            });
            ipc.commands().applyImportPlan(plan(root,paths,false));
            assertEquals(0L,observed.get(5,TimeUnit.SECONDS)); assertFalse(ipc.failure().isDone());
            System.out.println("REENTRANT_SUBSCRIBER_QUERY_PASS");
        }
    }

    @Test void busyBulkReleaseIsBoundedAndConfirmationCanPassABlockedSubscriber() throws Exception {
        Path root=root(); Cards cards=new Cards();
        try(var ipc=port(root,cards,IpcUiportWirePeer.class.getName(),"bulk-busy")) {
            var count=new java.util.concurrent.atomic.AtomicInteger(); var queried=new CompletableFuture<WorkspaceVm>();
            ipc.events().subscribe(event -> {
                if(event instanceof PortEvent.PreparseFinished && count.incrementAndGet()==300) queried.complete(ipc.queries().workspace());
            });
            var command=CompletableFuture.runAsync(ipc.commands()::cancelWorkspaceActivity);
            cards.next().token.answer("continue");
            command.get(15,TimeUnit.SECONDS); assertNotNull(queried.get(5,TimeUnit.SECONDS));
            assertEquals(300,count.get()); assertFalse(ipc.failure().isDone());
            System.out.println("BUSY_RELEASE_PASS events=300 outstandingRelease<=1 confirmationPassedBlockedSubscriber=true");
        }
    }
}
