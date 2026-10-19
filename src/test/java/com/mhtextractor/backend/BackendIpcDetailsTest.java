package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.InProcessUiport;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.app.UndoManager;
import com.mhtextractor.app.*;
import com.mhtextractor.engine.PreviewSession;
import com.mhtextractor.engine.PreviewService;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

class BackendIpcDetailsTest {
    @TempDir Path tmp;
    static ObjectNode object() { return JSON.createObjectNode(); }

    private final class Pair implements AutoCloseable {
        final WorkspaceModel model = new WorkspaceModel();
        final GlobalParameterCache global = new GlobalParameterCache();
        final InProcessUiport direct;
        final List<PortEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final IpcTestClient ipc;
        final Path bulk;
        final List<String> paths = new ArrayList<>();
        long revision;
        Pair() throws Exception {
            var previews = PreviewSession.create(tmp.resolve("direct-preview"));
            var preview = new PreviewService(previews.lifecycle());
            var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
                public List<Object> dataOrder() { return model.topLevel(); }
                public boolean imageMode() { return false; }
                public AppConfig textParamsCache() { return global.text(); }
                public AppConfig imageParamsCache() { return global.image(); }
                public String inputFolder() { return tmp.toString(); }
                public String outputFolder() { return tmp.resolve("output").toString(); }
                public boolean openFolderAfterComplete() { return false; }
            });
            var activities = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
                public List<com.mhtextractor.model.PreviewItem> items() { return model.allItems(); }
                public boolean textMode() { return true; }
                public AppConfig effectiveParams(com.mhtextractor.model.PreviewItem item) {
                    return com.mhtextractor.model.ParamScope.effectiveParams(item.parentGroup,item.textItemParams,item.imageItemParams,false,global.text(),global.image());
                }
                public AppConfig effectiveImageParams(com.mhtextractor.model.PreviewItem item) {
                    return com.mhtextractor.model.ParamScope.effectiveImageParams(item.parentGroup,item.imageItemParams,global.image());
                }
            },preview,IpcImages::thumbnail,Runnable::run);
            var caches = new WorkspaceParamService(previews.lifecycle());
            var clipboard = new ParamClipboard(model::push,target -> com.mhtextractor.model.ParamScope.GLOBAL,(target,mode,config) -> {});
            var commands = new WorkspaceParamCommandService(model,caches,global,clipboard::pushParamUndo);
            direct = new InProcessUiport(model,request -> null,plans,activities,commands,
                    new WorkspaceImportCoordinator(model,request -> null,Runnable::run),null,caches,global,previews);
            direct.events().subscribe(events::add);
            ipc = new IpcTestClient(tmp.resolve("child"), BackendCli.class.getName(), "--serve", "--locale", "en");
            var ready = ipc.receive();
            System.out.println("DETAIL_READY=" + ready);
            for (String method : IpcSession.DETAIL_METHODS) assertTrue(ready.path("data").path("methods").toString().contains('"'+method+'"'));
            var identity = BackendIdentity.current();
            var hello = call("hello", object().put("protocolVersion", identity.protocolVersion())
                    .put("version", identity.version()).put("buildId", identity.buildId()));
            bulk = Path.of(hello.path("bulkRoot").asText());
            for (int i=0; i<3; i++) paths.add(Files.writeString(tmp.resolve("source"+i+".html"),
                    "<html><title>source title</title><body>source body</body></html>").toString());
            var plan = new ImportPlanVm(List.of(new ImportPlanVm.ImportBatchVm(tmp.toString(), null, false, paths)),
                    0,0,0,0,0,false,false);
            direct.commands().applyImportPlan(plan);
            call("applyImportPlan", object().set("plan", upload(JSON.valueToTree(plan))));
            events.clear(); ipc.drainRequestEvents();
        }
        JsonNode call(String method, ObjectNode p) throws Exception {
            var frame = ipc.request(method, method, p);
            assertEquals("response", frame.path("type").asText(), frame.toString());
            revision = frame.path("revision").longValue();
            return frame.path("result");
        }
        ObjectNode upload(JsonNode content) throws Exception {
            Path path = Files.createTempFile(bulk,"client-",".json"); Files.write(path,JSON.writeValueAsBytes(content));
            return object().put("path",path.toString()).put("bytes",Files.size(path)).put("mediaType","application/json");
        }
        void same() throws Exception {
            assertEquals(canonical(JSON.valueToTree(direct.queries().workspace())), canonical(IpcTestClient.expandWorkspace(call("workspace",object()))));
        }
        public void close() throws Exception { ipc.finish(); ipc.close(); }
    }

    /** Compare every value and the bytes behind FileRefs, independent of owner-specific paths. */
    private static JsonNode canonical(JsonNode input) throws Exception {
        if (input.isIntegralNumber()) return JSON.readTree(input.toString());
        if (input.isObject() && input.has("path") && input.has("bytes") && input.has("mediaType")) {
            Path path = Path.of(input.path("path").asText());
            assertEquals(input.path("bytes").longValue(),Files.size(path));
            var value = object().put("bytes",Files.size(path)).put("mediaType",input.path("mediaType").asText());
            if(input.path("mediaType").asText().equals("application/json")) value.set("content",canonical(JSON.readTree(Files.readAllBytes(path))));
            else value.put("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            input.fields().forEachRemaining(f -> { if(!List.of("path","imagePath","bytes","mediaType").contains(f.getKey())) value.set(f.getKey(),f.getValue()); });
            return value;
        }
        if(input.isObject()) {
            var value=object(); var fields=input.fields();
            while(fields.hasNext()) {var f=fields.next(); value.set(f.getKey(),canonical(f.getValue()));}
            return value;
        }
        if(input.isArray()) {var value=JSON.createArrayNode();for(var entry:input)value.add(canonical(entry));return value;}
        return input;
    }
    private ObjectNode runtime(String input,String output,boolean mode) {
        return object().put("inputFolder",input).put("outputFolder",output).put("imageModeEnabled",mode).put("openFolderAfterComplete",false);
    }
    private static AppConfig runtimeConfig(ObjectNode r) {
        var c=new AppConfig(); c.setInputFolder(r.path("inputFolder").isNull()?null:r.path("inputFolder").asText());
        c.setOutputFolder(r.path("outputFolder").isNull()?null:r.path("outputFolder").asText()); c.setImageModeEnabled(r.path("imageModeEnabled").asBoolean());return c;
    }
    private static JsonNode contents(JsonNode file) throws Exception {return JSON.readTree(Files.readAllBytes(Path.of(file.path("path").asText())));}

    @Test void optionalTargetResolutionKeepsDefaultAndReturnsExplicitItemAndGroupWithoutRevisionChange() throws Exception {
        try (var p=new Pair()) {
            var a=TargetId.ofItem(p.paths.getFirst()); var b=TargetId.ofItem(p.paths.get(1));
            p.direct.commands().group(p.paths.subList(1,3),"B-group");
            p.call("group",object().put("namePrefix","B-group").set("paths",JSON.valueToTree(p.paths.subList(1,3))));
            var group=TargetId.ofGroup(1);
            var config=new AppConfig(); config.setStartMarker("group-source");
            p.direct.commands().setGroupParams(1,false,config);
            p.call("setGroupParams",object().put("groupNo",1).put("imageMode",false).set("params",JSON.valueToTree(config)));
            p.direct.commands().setChecked(List.of(p.paths.getFirst()),true);
            p.call("setChecked",object().put("checked",true).set("paths",JSON.valueToTree(List.of(p.paths.getFirst()))));
            long revision=p.revision;
            var requested=List.of(b,group);
            ObjectNode params=object().put("imageMode",false).put("revision",revision)
                    .set("fallbackTargets",p.upload(JSON.valueToTree(requested)));
            var original=contents(p.call("parameterState",params).path("state"));
            assertEquals(JSON.valueToTree(p.direct.queries().parameterState(false,requested)),original);
            assertEquals(JSON.valueToTree(List.of(a)),original.path("targets"));
            assertEquals(original,contents(p.call("parameterState",params.deepCopy().put("targetResolution","selectedFirst")).path("state")));
            var explicit=contents(p.call("parameterState",params.deepCopy().put("targetResolution","explicit")).path("state"));
            assertEquals(canonical(JSON.valueToTree(p.direct.queries().parameterState(false,requested,TargetResolution.EXPLICIT))),canonical(explicit));
            assertEquals(canonical(JSON.valueToTree(requested)),canonical(explicit.path("targets")));
            assertEquals("GROUP",explicit.path("states").get(0).path("source").asText());
            assertEquals("GROUP",explicit.path("states").get(1).path("source").asText());
            assertEquals(revision,p.revision,"Q10 must not advance revision");
            var defaultFields=new java.util.HashSet<String>(); original.fieldNames().forEachRemaining(defaultFields::add);
            var explicitFields=new java.util.HashSet<String>(); explicit.fieldNames().forEachRemaining(explicitFields::add);
            assertEquals(defaultFields,explicitFields,"explicit must not add response fields");
            for (var invalid : List.of(JSON.nullNode(),JSON.getNodeFactory().booleanNode(true),JSON.getNodeFactory().textNode("unknown"))) {
                var frame=p.ipc.request("invalid-resolution","parameterState",params.deepCopy().set("targetResolution",invalid));
                assertEquals("error",frame.path("type").asText());
                assertEquals("INVALID_PARAMS",frame.path("error").path("code").asText());
            }
            p.same();
            assertEquals(revision,p.revision,"invalid queries must not advance revision");
            System.out.println("Q10_WIRE_PASS omitted=selectedFirst; explicit item+group exact fields; revision="+revision+" unchanged; invalid=INVALID_PARAMS");
        }
    }

    @Test void parameterQueriesKeepUnconfirmedNullMixedSourcesAndCheckedPriority() throws Exception {
        try(var p=new Pair()) {
            var fallback=List.of(TargetId.ofItem(p.paths.getLast()));
            for(boolean mode:List.of(false,true)) {
                var direct=p.direct.queries().parameterState(mode,fallback);
                var wire=p.call("parameterState",object().put("imageMode",mode).put("revision",p.revision).set("fallbackTargets",p.upload(JSON.valueToTree(fallback))));
                assertEquals(JSON.valueToTree(direct),contents(wire.path("state")));
                assertFalse(direct.textConfirmed()); assertFalse(direct.imageConfirmed()); assertNull(direct.textGlobal());assertNull(direct.imageGlobal());
                assertTrue(direct.uniform()); assertFalse(direct.hasEffective());
            }
            var c=new AppConfig(); c.setStartMarker("configured");
            p.direct.commands().setItemParams(p.paths.getFirst(),false,c);
            p.call("setItemParams",object().put("path",p.paths.getFirst()).put("imageMode",false).set("params",JSON.valueToTree(c)));
            p.direct.commands().setChecked(p.paths.subList(0,2),true);
            p.call("setChecked",object().put("checked",true).set("paths",JSON.valueToTree(p.paths.subList(0,2))));
            var direct=p.direct.queries().parameterState(false,fallback);
            assertEquals(2,direct.count()); assertFalse(direct.uniform());assertTrue(direct.hasEffective());
            assertEquals("GROUP",direct.states().getFirst().source()); assertEquals("NONE",direct.states().getLast().source());
            assertEquals(JSON.valueToTree(direct),contents(p.call("parameterState",object().put("imageMode",false).put("revision",p.revision).set("fallbackTargets",p.upload(JSON.valueToTree(fallback)))).path("state")));
            assertEquals(p.paths.subList(0,2),direct.targets().stream().map(TargetId::path).toList());
        }
    }

    @Test void preStartDenialOrderAndQueriesNeverCreateOutputDirectories() throws Exception {
        try(var p=new Pair()) {
            String out=tmp.resolve("must-not-exist").toString();
            String[][] facts={{"","","error.INPUT_DIR_INVALID","WARNING"},{tmp.toString(),"","error.OUTPUT_DIR_INVALID","WARNING"},
                    {tmp.resolve("missing").toString(),out,"error.INPUT_DIR_MISSING","ERROR"},{tmp.toString(),out,"error.NO_CHECKED_ITEMS","ERROR"}};
            for(var fact:facts) {
                var runtime=runtime(fact[0],fact[1],false);
                var expected=p.direct.queries().preStartState(runtimeConfig(runtime),true);
                var result=(ObjectNode)p.call("preStartState",object().put("checkedOnly",true).put("revision",p.revision).set("runtime",runtime));result.remove("revision");
                assertEquals(JSON.valueToTree(expected),result);assertEquals(fact[2],expected.denial().key()); assertEquals(fact[3],expected.denial().level());
                assertFalse(result.has("draftDirty")); assertFalse(Files.exists(Path.of(out)));
            }
            p.direct.commands().setChecked(p.paths.subList(0,2),true);
            p.call("setChecked",object().put("checked",true).set("paths",JSON.valueToTree(p.paths.subList(0,2))));
            p.direct.commands().group(p.paths.subList(0,2),"gate");
            p.call("group",object().put("namePrefix","gate").set("paths",JSON.valueToTree(p.paths.subList(0,2))));
            var runtime=runtime(tmp.toString(),out,false);
            var direct=p.direct.queries().preStartState(runtimeConfig(runtime),true);
            assertNull(direct.denial());assertEquals(1,direct.defaultNamedGroups().size());assertTrue(direct.subsetMergedName());
            var wire=(ObjectNode)p.call("preStartState",object().put("checkedOnly",true).put("revision",p.revision).set("runtime",runtime));wire.remove("revision");
            assertEquals(JSON.readTree(JSON.writeValueAsString(direct)),wire); assertFalse(Files.exists(Path.of(out)));
        }
    }

    @Test void sourceExportBindsSessionAndRetainsAllThreeDiagnosticsWithoutImport() throws Exception {
        try(var p=new Pair()) {
            Path plain=Files.writeString(tmp.resolve("not-imported.mht"),"MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nplain only");
            for(String path:List.of(p.paths.getFirst(),plain.toString(),tmp.resolve("missing.html").toString())) {
                var direct=p.direct.queries().exportSource(path);
                var wire=(ObjectNode)p.call("exportSource",object().put("path",path));
                assertFalse(wire.path("sessionId").asText().isBlank());wire.remove(List.of("sessionId","revision"));
                assertEquals(canonical(JSON.valueToTree(direct)),canonical(wire));
            }
            assertEquals("unparseable",p.direct.queries().exportSource(plain.toString()).status());
            assertEquals("failed",p.direct.queries().exportSource(tmp.resolve("missing.html").toString()).status());
        }
    }

    @Test void realMhtSourceExportHasOwnedFileRefAndUnparseableHasNoneOnBothPaths() throws Exception {
        try (var p = new Pair()) {
            Path source = Files.copy(Path.of("src/test/resources/fixtures/single-image.mht"), tmp.resolve("real.mht"));
            Path plain = Files.writeString(tmp.resolve("plain.mht"),
                    "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nplain only");
            var direct = p.direct.queries().exportSource(source.toString());
            var wire = p.call("exportSource", object().put("path", source.toString()));
            assertEquals("success", direct.status());
            assertEquals("success", wire.path("status").asText());
            assertNotNull(direct.htmlFile());
            assertTrue(wire.path("htmlFile").isObject());
            Path directFile = Path.of(direct.htmlFile().path()).toRealPath();
            Path wireFile = Path.of(wire.path("htmlFile").path("path").asText()).toRealPath();
            assertTrue(directFile.startsWith(tmp.resolve("direct-preview/port-details/source-view").toRealPath()));
            assertTrue(wireFile.startsWith(p.bulk.toRealPath()));
            assertTrue(wireFile.getParent().getFileName().toString().startsWith("source-export-"));
            assertTrue(Files.isRegularFile(directFile));
            assertTrue(Files.isRegularFile(wireFile));
            assertTrue(Files.size(directFile) > 0);
            assertEquals(Files.size(directFile), direct.htmlFile().bytes());
            assertEquals(Files.size(wireFile), wire.path("htmlFile").path("bytes").longValue());
            assertArrayEquals(Files.readAllBytes(directFile), Files.readAllBytes(wireFile));
            var unparseable = p.direct.queries().exportSource(plain.toString());
            var wireUnparseable = p.call("exportSource", object().put("path", plain.toString()));
            assertEquals("unparseable", unparseable.status());
            assertNull(unparseable.htmlFile());
            assertEquals("unparseable", wireUnparseable.path("status").asText());
            assertTrue(wireUnparseable.has("htmlFile"));
            assertTrue(wireUnparseable.path("htmlFile").isNull());
            String missing = tmp.resolve("missing-source.html").toString();
            var failed = p.direct.queries().exportSource(missing);
            var wireFailed = p.call("exportSource", object().put("path", missing));
            assertEquals("failed", failed.status());
            assertNull(failed.htmlFile());
            assertEquals("failed", wireFailed.path("status").asText());
            assertTrue(wireFailed.path("htmlFile").isNull());
            assertEquals(failed.detailKey(), wireFailed.path("detailKey").asText());
            assertEquals(JSON.valueToTree(failed.detailParams()), wireFailed.path("detailParams"));
            var previousLocale = com.mhtextractor.i18n.I18n.currentLocale();
            try {
            for (var locale : List.of(java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.US)) {
                com.mhtextractor.i18n.I18n.setLocale(locale);
                var bundle = com.mhtextractor.i18n.I18n.bundle();
                assertTrue(bundle.containsKey(failed.detailKey()));
                String rendered = bundle.getString(failed.detailKey());
                for (var param : failed.detailParams().entrySet())
                    rendered = rendered.replace("{" + param.getKey() + "}", param.getValue());
                assertFalse(rendered.isBlank());
                assertFalse(rendered.contains("{"));
                assertFalse(rendered.contains("!"));
            }
            } finally { com.mhtextractor.i18n.I18n.setLocale(previousLocale); }
            p.call("releaseFiles", object().set("paths", JSON.valueToTree(List.of(wireFile.toString()))));
            assertFalse(Files.exists(wireFile));
        }
    }

    @Test void everyVersionedDetailQueryRejectsStaleRevisionBeforeReadingBulkFiles() throws Exception {
        try(var p=new Pair()) {
            for(String method:List.of("parameterState","preStartState","presentation")) {
                var response=p.ipc.request(method,method,object().put("revision",p.revision+1));
                assertEquals("INVALID_PARAMS",response.path("error").path("code").asText());
            }
        }
    }

    @Test void presentationKeepsLargeTextInFilesAndSharesImageDescriptorsWithPreviewEvents() throws Exception {
        try(var p=new Pair()) {
            String png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=";
            Files.writeString(Path.of(p.paths.getFirst()),"<html><body>"+"large text sentinel ".repeat(10000)+"<img src='data:image/png;base64,"+png+"'></body></html>");
            var config=new AppConfig();config.setEnableTemplate(true);config.setStartTemplate("head:{filename}");config.setEndTemplate("tail:{basename}");
            p.direct.commands().setGlobalParams(false,config);
            p.call("setGlobalParams",object().put("imageMode",false).set("params",JSON.valueToTree(config)));
            p.direct.commands().setGlobalParams(true,new AppConfig());
            p.call("setGlobalParams",object().put("imageMode",true).set("params",JSON.valueToTree(new AppConfig())));
            var runtime=runtime(tmp.toString(),tmp.resolve("output").toString(),false);
            p.events.clear();p.ipc.drainRequestEvents();
            p.direct.commands().startPreparseFor(p.paths);
            p.call("startPreparseFor",object().<ObjectNode>set("runtime",runtime).set("paths",p.upload(JSON.valueToTree(p.paths))));
            int finished=(int)p.ipc.drainRequestEvents().stream().filter(e -> e.path("event").asText().equals("preparseFinished")).count();
            while(finished<3) {var e=p.ipc.receive();if(e.path("event").asText().equals("preparseFinished"))finished++;}
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(p.events.stream().filter(e -> e instanceof PortEvent.PreparseFinished).count()<3 && System.nanoTime()<until) Thread.sleep(5);
            assertEquals(3,p.events.stream().filter(e -> e instanceof PortEvent.PreparseFinished).count());
            p.call("workspace",object());
            var direct=p.direct.queries().presentation(p.paths,runtimeConfig(runtime));
            assertFalse(direct.isEmpty());
            assertNotNull(direct.getFirst().baseName());
            assertNotNull(direct.getFirst().finalFileName());
            var wire=p.call("presentation",object().put("revision",p.revision).<ObjectNode>set("runtime",runtime).set("paths",p.upload(JSON.valueToTree(p.paths))));
            assertEquals(canonical(JSON.valueToTree(direct)),canonical(contents(wire.path("items"))));
            assertTrue(direct.getFirst().textFile().bytes()>100000);
            assertEquals("head:source0.html",direct.getFirst().head());assertEquals("tail:source0",direct.getFirst().tail());
            assertFalse(wire.toString().contains("large text sentinel"));
            runtime.put("imageModeEnabled",true);
            p.direct.commands().loadPreview(p.paths.getFirst(),true);
            p.direct.commands().awaitPreviewInFlight(10000);
            p.call("loadPreview",object().put("path",p.paths.getFirst()).put("largeFileConfirmed",true).set("runtime",runtime));
            var received=new ArrayList<>(p.ipc.drainRequestEvents());
            while(received.stream().noneMatch(e -> e.path("event").asText().equals("previewLoaded"))) received.add(p.ipc.receive());
            p.call("workspace",object());
            direct=p.direct.queries().presentation(p.paths,runtimeConfig(runtime));
            assertFalse(direct.isEmpty());
            assertNotNull(direct.getFirst().baseName());
            assertNull(direct.getFirst().extension());
            assertNull(direct.getFirst().finalFileName());
            wire=p.call("presentation",object().put("revision",p.revision).<ObjectNode>set("runtime",runtime).set("paths",p.upload(JSON.valueToTree(p.paths))));
            assertEquals(canonical(JSON.valueToTree(direct)),canonical(contents(wire.path("items"))));
            assertEquals(1,direct.getFirst().images().size());
            var image=direct.getFirst().images().getFirst();
            assertEquals("image/png",image.mediaType());assertEquals(1,image.width());assertEquals(1,image.height());assertNotNull(image.displayName());
            var thumbnail=received.stream().filter(e -> e.path("event").asText().equals("previewThumbnail")).findFirst().orElseThrow().path("data");
            assertEquals(image.displayName(),thumbnail.path("displayName").asText()); assertEquals(1,thumbnail.path("dimensions").path("width").intValue());
            var cache=p.call("cacheState",object()); assertTrue(cache.path("hasCachedEntries").asBoolean());
            System.out.println("Q12_TEXT_FILE_OK bytes="+direct.getFirst().textFile().bytes()+" imageCount="+direct.getFirst().images().size());
        }
    }

    @Test void titleWritesAreObservableAndDoNotNeedFrontendMutation() throws Exception {
        try (var p = new Pair()) {
            int depth = p.model.undoDepth();
            for (String title : new String[]{"  edited title  ", "   ", "final", null}) {
                p.direct.commands().setTitleOverride(p.paths.getFirst(),title);
                var request = object().put("path",p.paths.getFirst()).put("title",title).put("revision",p.revision);
                p.call("setTitleOverride",request);
                p.same();
                String expected = title == null || title.trim().isEmpty() ? null : title.trim();
                assertEquals(expected,p.direct.queries().workspace().topLevelItems().getFirst().titleOverride());
                assertEquals(depth,p.model.undoDepth());
                var changed = p.events.stream().filter(e -> e instanceof PortEvent.WorkspaceChanged).map(e -> (PortEvent.WorkspaceChanged)e).toList();
                assertFalse(changed.isEmpty()); assertEquals("TITLE_CHANGED",changed.getLast().kind());
                var wire = p.ipc.drainRequestEvents().stream().filter(e -> e.path("event").asText().equals("workspaceChanged")).toList();
                assertEquals(1,wire.size()); assertEquals("TITLE_CHANGED",wire.getFirst().path("data").path("kind").asText());
                JsonNode snapshot = JSON.readTree(Files.readAllBytes(Path.of(wire.getFirst().path("data").path("snapshot").path("path").asText())));
                assertEquals(wire.getFirst().path("data").path("revision"), snapshot.path("revision"));
                assertEquals(expected,snapshot.path("topLevelItems").get(0).path("titleOverride").isMissingNode() ? null : snapshot.path("topLevelItems").get(0).path("titleOverride").asText());
                p.events.clear();
            }
            // Simulate the former GUI direct write. A subsequent port command must own the final value.
            p.model.allItems().getFirst().titleOverride = "legacy frontend direct write";
            p.direct.commands().setTitleOverride(p.paths.getFirst(),"port authority");
            assertEquals("port authority",p.model.allItems().getFirst().titleOverride);
            System.out.println("C28_PORT_AUTHORITY_OK undoDepth="+depth);
        }
    }

    @Test void batchUsesCheckedTargetsAndExactlyOneWholeBeforeRecordRepeatedly() throws Exception {
        try (var p = new Pair()) {
            var checked = p.paths.subList(0,2);
            p.direct.commands().setChecked(checked,true);
            p.call("setChecked",object().put("checked",true).set("paths",JSON.valueToTree(checked)));
            for (int round=0; round<5; round++) {
                var config = new AppConfig(); config.setStartMarker("round"+round);
                var fallback = List.of(TargetId.ofItem(p.paths.getLast()));
                int depth = p.model.undoDepth();
                p.direct.commands().applyParamsToTargets(false,config,fallback);
                assertEquals(depth+1,p.model.undoDepth(),"Whole batch must add exactly one PARAM_SET");
                var record = p.model.popRecord(); assertEquals(UndoManager.RecordType.PARAM_SET,record.type());
                assertEquals(2,record.clearItemParamsBefore().size()); assertTrue(record.clearGroupParamsBefore().isEmpty());
                for (var path : checked) {
                    var before = record.clearItemParamsBefore().get(path);
                    assertEquals(round==0 ? null : "round"+(round-1),before==null ? null : before.getStartMarker());
                }
                p.model.push(record);
                p.call("applyParamsToTargets",object().put("imageMode",false).put("revision",p.revision)
                        .<ObjectNode>set("config",JSON.valueToTree(config)).set("fallbackTargets",p.upload(JSON.valueToTree(fallback))));
                assertNull(p.model.allItems().getLast().textItemParams); p.same();
                assertEquals(1,p.ipc.drainRequestEvents().stream().filter(e -> e.path("event").asText().equals("paramsChanged")).count());
            }
            int depth = p.model.undoDepth();
            p.direct.commands().applyParamsToTargets(false,p.model.allItems().getFirst().textItemParams,List.of());
            assertEquals(depth,p.model.undoDepth(),"Identical batch is a no-op");
            System.out.println("C29_BATCH_OK rounds=5 recordsPerChangedBatch=1");
        }
    }

    @Test void staleNewCommandsAreRejectedBeforeMutation() throws Exception {
        try (var p = new Pair()) {
            var before = p.call("workspace",object());
            var response = p.ipc.request("stale","setTitleOverride",object().put("path",p.paths.getFirst()).put("title","wrong").put("revision",p.revision+1));
            assertEquals("INVALID_PARAMS",response.path("error").path("code").asText());
            assertEquals(before,p.call("workspace",object()));
        }
    }
}
