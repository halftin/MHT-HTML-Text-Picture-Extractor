package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.*;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uiport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** 1b contracts through public in-process ports and a real --serve child process. */
class BackendIpcActivityTest {
    @TempDir Path tmp;
    private long revision;
    private Path bulkRoot;
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=";
    // The valid 1x1 JPEG also used by the repository's MIME product fixtures.
    private static final byte[] JPEG = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ObjectNode runtime(boolean images) {
        return object().putNull("inputFolder").putNull("outputFolder").put("imageModeEnabled", images).put("openFolderAfterComplete", false);
    }
    private static ObjectNode context(boolean images) { return object().set("runtime", runtime(images)); }
    private Path html(String name, String body) throws Exception {
        return Files.writeString(tmp.resolve(name), "<html><body>" + body + "</body></html>");
    }
    private IpcTestClient client(String name) throws Exception {
        return new IpcTestClient(tmp.resolve(name), BackendIpcActivityFixture.class.getName());
    }
    private void handshake(IpcTestClient ipc) throws Exception {
        JsonNode ready = ipc.receive();
        for (String name : List.of("cancelWorkspaceActivity", "cancelPreviewLoad", "startPreparse", "startPreparseFor",
                "refreshPreparseProgress", "loadPreview", "resolvePreviewRegion", "awaitPreviewInFlight", "applyImportPlan",
                "activityGeneration", "previewGeneration", "previewLoadingPath"))
            assertTrue(ready.path("data").path("methods").toString().contains('"' + name + '"'), name);
        var identity = BackendIdentity.current();
        var welcome = ipc.request("hello", "hello", object().put("protocolVersion", identity.protocolVersion())
                .put("version", identity.version()).put("buildId", identity.buildId())).path("result");
        assertEquals(ready.path("data").path("sessionId"), welcome.path("sessionId"));
        bulkRoot = Path.of(welcome.path("bulkRoot").asText()); revision = 0;
    }
    private static JsonNode file(JsonNode ref) throws Exception {
        Path path = Path.of(ref.path("path").asText());
        assertTrue(path.isAbsolute()); assertEquals(Files.size(path), ref.path("bytes").longValue());
        assertEquals("application/json", ref.path("mediaType").asText());
        return JSON.readTree(Files.readAllBytes(path));
    }
    private ObjectNode upload(JsonNode content) throws Exception {
        Path path = Files.createTempFile(bulkRoot, "client-", ".json");
        Files.write(path, JSON.writeValueAsBytes(content));
        return object().put("path", path.toString()).put("bytes", Files.size(path)).put("mediaType", "application/json");
    }
    private List<JsonNode> events(IpcTestClient ipc) {
        var events = ipc.drainRequestEvents(); checkRevisions(events); return new ArrayList<>(events);
    }
    private void checkRevisions(List<JsonNode> events) {
        for (var event : events) {
            assertEquals("event", event.path("type").asText());
            assertTrue(event.path("data").path("revision").isIntegralNumber());
            long next = event.path("data").path("revision").longValue();
            assertEquals(revision + 1, next, "Every event must own one sequence number"); revision = next;
        }
    }
    private List<JsonNode> until(IpcTestClient ipc, String name) throws Exception {
        var values = events(ipc);
        while (values.stream().noneMatch(e -> e.path("event").asText().equals(name))) {
            JsonNode event = ipc.receive(); checkRevisions(List.of(event)); values.add(event);
        }
        return values;
    }
    private static List<String> names(List<JsonNode> events) { return events.stream().map(e -> e.path("event").asText()).toList(); }
    private void params(IpcTestClient ipc, AppConfig config, boolean mode) throws Exception {
        assertEquals("response", ipc.request("params", "setParams", object().put("imageMode", mode)
                .set("config", JSON.valueToTree(config))).path("type").asText()); events(ipc);
    }
    private List<JsonNode> imported(IpcTestClient ipc, List<String> paths) throws Exception {
        var result = ipc.request("import", "importPaths", object().set("paths", JSON.valueToTree(paths))).path("result");
        assertEquals(paths.size(), result.path("count").intValue()); assertFalse(result.path("failed").booleanValue());
        var events = events(ipc);
        var order = names(events);
        assertEquals("importStateChanged", order.getFirst());
        assertEquals(List.of("importRound", "importFinished", "importStateChanged"), order.subList(order.size() - 3, order.size()));
        assertTrue(order.subList(1, order.size() - 3).stream().allMatch("workspaceChanged"::equals));
        assertTrue(events.getFirst().path("data").path("importing").booleanValue());
        assertFalse(events.getLast().path("data").path("importing").booleanValue());
        assertEquals(JSON.valueToTree(paths), uncheckedFile(events.get(events.size() - 2).path("data").path("accumulatedPaths")));
        return events;
    }
    private static JsonNode uncheckedFile(JsonNode ref) {
        try { return file(ref); } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Test assembly uses exactly the same public app services, with a manually pumped serial delivery queue. */
    private final class Direct implements AutoCloseable {
        final WorkspaceModel model = new WorkspaceModel();
        final GlobalParameterCache global = new GlobalParameterCache();
        final PreviewSession previews;
        final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        final List<PortEvent> events = new ArrayList<>();
        final Uiport port;
        boolean mode;
        Direct() throws Exception {
            previews = PreviewSession.create(tmp.resolve("direct-cache-" + System.nanoTime()));
            var preview = new PreviewService(previews.lifecycle());
            var activity = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
                public List<com.mhtextractor.model.PreviewItem> items() { return model.allItems(); }
                public boolean textMode() { return !mode; }
                public AppConfig effectiveParams(com.mhtextractor.model.PreviewItem i) { return effective(i, mode); }
                public AppConfig effectiveImageParams(com.mhtextractor.model.PreviewItem i) { return effective(i, true); }
            }, preview, (image, index, config) -> {
                String ext = PreviewNamingService.detectExtension(image.data(), "");
                // Independent fixture dimensions, rather than reusing the production wire encoder.
                return new ThumbnailData(PreviewNamingService.imageFileName(image, ext, config.isEnableBatchRenaming(), index + 1, ""),
                        1, 1, null);
            }, callbacks::add);
            var clipboard = new ParamClipboard(model::push, t -> ParamScope.GLOBAL, (t,m,c) -> { });
            var commands = new WorkspaceParamCommandService(model, new WorkspaceParamService(previews.lifecycle()), global, clipboard::pushParamUndo);
            var imports = new WorkspaceImportCoordinator(model, request -> { throw new AssertionError("Unexpected direct confirmation"); }, callbacks::add, 200, 20000);
            port = new InProcessUiport(model, request -> null, activity, commands, imports, global, previews);
            port.events().subscribe(events::add);
        }
        private AppConfig effective(com.mhtextractor.model.PreviewItem i, boolean m) {
            AppConfig c = ParamScope.effectiveParams(i.parentGroup, i.textItemParams, i.imageItemParams, m, global.text(), global.image());
            return c == null ? new AppConfig() : c;
        }
        void waitFor(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            do {
                Runnable callback; while ((callback = callbacks.poll()) != null) callback.run();
                if (done.getAsBoolean()) return;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            fail("Direct activity did not complete");
        }
        List<PortEvent> importPaths(List<String> paths) throws Exception {
            port.commands().importPaths(paths, "IPC", false);
            waitFor(() -> events.stream().anyMatch(e -> e instanceof PortEvent.ImportStateChanged s && !s.importing()));
            var result = List.copyOf(events); events.clear(); return result;
        }
        @Override public void close() { port.commands().cancelWorkspaceActivity(); port.commands().awaitPreviewInFlight(1000); previews.lifecycle().onShutdown(() -> { }); }
    }

    private void compare(PortEvent expected, JsonNode event) throws Exception {
        ObjectNode actual = (ObjectNode) event.path("data").deepCopy(); actual.remove("revision");
        JsonNode wanted = JSON.readTree(JSON.writeValueAsBytes(expected));
        if (expected instanceof PortEvent.PreparseFinished) actual.set("result", file(actual.path("result")));
        if (expected instanceof PortEvent.PreviewLoaded) actual.remove("manifest");
        if (expected instanceof PortEvent.PreviewNotice) actual.remove("namedParams");
        if (expected instanceof PortEvent.PreviewThumbnail thumbnail) {
            for (String key : List.of("coreName", "width", "height")) assertEquals(wanted.path("thumbnail").path(key), actual.path(key));
            assertEquals(thumbnail.generation(), actual.path("generation").longValue());
            assertEquals(thumbnail.path(), actual.path("path").asText()); assertEquals(thumbnail.index(), actual.path("index").intValue());
            format(actual); return;
        }
        assertEquals(wanted, actual, event.toString());
    }

    private void compareImport(Direct direct, PortEvent expected, JsonNode event) throws Exception {
        if (expected instanceof PortEvent.WorkspaceChanged changed) {
            assertEquals(changed.kind(), event.path("data").path("kind").asText());
            var atRound = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(direct.port.queries().workspace()));
            // The round snapshot precedes importStateChanged(false), while this oracle is read after completion.
            ((ObjectNode) atRound.path("details")).put("importing", true);
            assertEquals(atRound,
                    IpcTestClient.expandWorkspace(file(event.path("data").path("snapshot"))));
        } else if (expected instanceof PortEvent.ImportRound round) {
            var data = event.path("data");
            assertEquals(round.generation(), data.path("generation").longValue());
            assertEquals(round.newFiles(), data.path("newFiles").intValue()); assertEquals(round.newGroups(), data.path("newGroups").intValue());
            assertEquals(round.madeListNonEmpty(), data.path("madeListNonEmpty").booleanValue());
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(round.plan())), file(data.path("plan")));
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(direct.port.queries().workspace().groups())), file(data.path("createdGroups")));
        } else if (expected instanceof PortEvent.ImportFinished finished) {
            var actual = (ObjectNode) event.path("data").deepCopy(); actual.remove(List.of("revision", "count", "failed"));
            actual.set("accumulatedPaths", file(actual.path("accumulatedPaths")));
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(expected)), actual);
            assertEquals(finished.accumulatedPaths().size(), event.path("data").path("count").intValue());
            assertFalse(event.path("data").path("failed").booleanValue());
        } else compare(expected, event);
    }
    private static void format(JsonNode image) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(image.path("imagePath").asText()));
        String ext = PreviewNamingService.detectExtension(bytes, "");
        assertEquals(ext, image.path("extension").asText(), "IMAGE_FORMAT_GUARD extension differs from magic");
        assertEquals(ext.equals("jpg") ? "image/jpeg" : "image/" + ext, image.path("mediaType").asText(), "IMAGE_FORMAT_GUARD mediaType differs from magic");
        assertFalse(image.has("pixels")); assertFalse(image.has("pngPath"));
    }

    @Test void eightActivityCommandsAndThreeQueriesMatchPublicPortIncludingFailedPreparse() throws Exception {
        var paths = List.of(html("valid.html", "<h1>Title</h1>text").toString(), html("gone.html", "gone").toString());
        try (var direct = new Direct(); var ipc = client("activity")) {
            handshake(ipc); var expectedImport = direct.importPaths(paths); var actualImport = imported(ipc, paths);
            assertEquals(expectedImport.size(), actualImport.size());
            for (int i = 0; i < expectedImport.size(); i++) compareImport(direct, expectedImport.get(i), actualImport.get(i));
            Files.delete(Path.of(paths.get(1)));
            direct.port.commands().setGlobalParams(false, new AppConfig()); direct.events.clear(); params(ipc, new AppConfig(), false);
            direct.port.commands().startPreparse();
            direct.waitFor(() -> direct.events.stream().filter(e -> e instanceof PortEvent.PreparseFinished).count() == 2);
            ipc.request("start", "startPreparse", context(false));
            var parsed = until(ipc, "preparseFinished");
            while (parsed.stream().filter(e -> e.path("event").asText().equals("preparseFinished")).count() < 2
                    || !parsed.getLast().path("event").asText().equals("preparseProgress")) {
                var e = ipc.receive(); checkRevisions(List.of(e)); parsed.add(e);
            }
            assertEquals(direct.events.size(), parsed.size());
            for (int i = 0; i < parsed.size(); i++) compare(direct.events.get(i), parsed.get(i));
            assertEquals("FAILED", file(parsed.stream().filter(e -> e.path("event").asText().equals("preparseFinished"))
                    .toList().get(1).path("data").path("result")).path("status").asText());
            direct.events.clear(); direct.port.commands().refreshPreparseProgress();
            ipc.request("refresh", "refreshPreparseProgress", object()); compare(direct.events.getFirst(), events(ipc).getFirst());
            direct.events.clear(); direct.port.commands().cancelWorkspaceActivity();
            ipc.request("cancel", "cancelWorkspaceActivity", object()); compare(direct.events.getFirst(), events(ipc).getFirst());
            direct.port.commands().cancelPreviewLoad(); var cp = ipc.request("cp", "cancelPreviewLoad", object()); assertTrue(events(ipc).isEmpty()); revision = cp.path("revision").longValue();
            assertEquals(direct.port.queries().activityGeneration(), ipc.request("ag", "activityGeneration", object()).path("result").path("generation").longValue());
            assertEquals(direct.port.queries().previewGeneration(), ipc.request("pg", "previewGeneration", object()).path("result").path("generation").longValue());
            assertTrue(ipc.request("lp", "previewLoadingPath", object()).path("result").path("path").isNull());
            direct.events.clear(); direct.port.commands().startPreparseFor(paths.subList(0, 1));
            direct.waitFor(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.PreparseFinished));
            ipc.request("for", "startPreparseFor", context(false).set("paths", upload(JSON.valueToTree(paths.subList(0, 1)))));
            var selected = until(ipc, "preparseProgress"); assertEquals(2, selected.size());
            for (int i = 0; i < selected.size(); i++) compare(direct.events.get(i), selected.get(i));
            direct.port.commands().awaitPreviewInFlight(0); assertTrue(ipc.request("await", "awaitPreviewInFlight", object().put("millis", 0)).path("result").isNull());
            var q = ipc.request("page", "workspacePage", object().put("offset", 0).put("limit", 1)).path("result");
            assertEquals(revision, q.path("revision").longValue()); assertEquals(revision, file(q.path("snapshotFile")).path("revision").longValue());
            ipc.finish();
        }
    }

    @Test void applyImportPlanMatchesAllFieldsAndUndoIsPushedBeforeWorkspaceSnapshot() throws Exception {
        var a = html("a.html", "A"); var b = html("b.html", "B");
        var plan = new ImportPlanVm(List.of(new ImportPlanVm.ImportBatchVm(tmp.toString(), "group", true, List.of(a.toString(), b.toString()))), 1,2,3,4,5,true,false);
        try (var direct = new Direct(); var ipc = client("plan")) {
            handshake(ipc); direct.port.commands().applyImportPlan(plan);
            ipc.request("plan", "applyImportPlan", object().set("plan", upload(JSON.valueToTree(plan))));
            var events = events(ipc); assertEquals(List.of("workspaceChanged", "importRound"), names(events));
            var round = events.getLast().path("data"); var expected = (PortEvent.ImportRound) direct.events.getLast();
            assertEquals(JSON.valueToTree(plan), file(round.path("plan")));
            assertEquals(expected.generation(), round.path("generation").longValue());
            assertEquals(expected.newFiles(), round.path("newFiles").intValue()); assertEquals(expected.newGroups(), round.path("newGroups").intValue());
            assertEquals(expected.madeListNonEmpty(), round.path("madeListNonEmpty").booleanValue());
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(direct.port.queries().workspace().groups())), file(round.path("createdGroups")));
            var snapshot = file(events.getFirst().path("data").path("snapshot")); assertEquals(1, snapshot.path("undoDepth").intValue());
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(direct.port.queries().workspace())), IpcTestClient.expandWorkspace(snapshot)); ipc.finish();
        }
    }

    @Test void previewEventsAndScriptManifestKeepRealJpegAndPngAndCacheImageLease() throws Exception {
        String body = "<img src='data:image/png;base64," + PNG + "'><img src='data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(JPEG) + "'>";
        Path source = html("images.html", body);
        try (var direct = new Direct(); var ipc = client("images")) {
            handshake(ipc); direct.importPaths(List.of(source.toString())); imported(ipc, List.of(source.toString()));
            direct.mode = true; direct.port.commands().setGlobalParams(true, new AppConfig()); direct.events.clear(); params(ipc, new AppConfig(), true);
            direct.port.commands().loadPreview(source.toString(), true);
            direct.waitFor(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded));
            ipc.request("load", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var loaded = until(ipc, "previewLoaded");
            ipc.request("await", "awaitPreviewInFlight", object().put("millis", 5000)); loaded.addAll(events(ipc));
            assertEquals(List.of("previewLoadStateChanged", "previewThumbnail", "previewThumbnail", "previewLoadStateChanged", "previewLoaded", "previewNotice"), names(loaded));
            assertEquals(direct.events.size(), loaded.size());
            for (int i = 0; i < loaded.size(); i++) compare(direct.events.get(i), loaded.get(i));
            var manifest = file(loaded.get(4).path("data").path("manifest"));
            assertTrue(Files.readString(Path.of(manifest.path("html").path("path").asText())).contains("data:image/jpeg"));
            for (var image : manifest.path("thumbnails")) format(image);
            var script = ipc.request("script", "previewFiles", object().put("path", source.toString())).path("result");
            var original = file(script.path("manifest")); assertEquals(2, script.path("imageCount").intValue());
            assertTrue(Files.readString(Path.of(original.path("html").path("path").asText())).contains("data:image/jpeg"));
            for (var image : original.path("thumbnails")) { assertTrue(image.has("name")); assertTrue(image.has("index")); format(image); }
            var image = original.path("thumbnails").get(1);
            assertEquals("image/jpeg", image.path("mediaType").asText(), "IMAGE_FORMAT_GUARD JPEG cannot be labeled PNG");
            ipc.request("release", "releaseFiles", object().set("paths", JSON.valueToTree(List.of(image.path("path").asText()))));
            assertTrue(Files.exists(Path.of(image.path("path").asText())), "Cache lifecycle retains original image after bulk lease release");
            ipc.request("cached", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var cached = until(ipc, "previewLoaded");
            ipc.request("awaitCached", "awaitPreviewInFlight", object().put("millis", 5000)); cached.addAll(events(ipc));
            assertEquals(List.of("previewLoaded", "previewNotice"), names(cached)); ipc.finish();
        }
    }

    @Test void asyncImportPumpsIdleEventsAndQuotaAnswersAcrossTwoRounds() throws Exception {
        for (int i = 0; i < 201; i++) { Path dir = Files.createDirectory(tmp.resolve(String.format("dir%03d", i))); Files.writeString(dir.resolve("a.html"), "<html><body>A</body></html>"); }
        try (var ipc = client("rounds")) {
            handshake(ipc);
            var started = ipc.request("async", "importPaths", object().put("async", true).put("sourceDesc", "drag").put("fromDrag", true)
                    .set("pathsFile", upload(JSON.valueToTree(List.of(tmp.toString())))));
            assertTrue(started.path("result").isNull());
            var first = until(ipc, "confirmationRequested");
            assertEquals(1, first.stream().filter(e -> e.path("event").asText().equals("importRound")).count());
            var question = first.getLast().path("data");
            ipc.request("query", "activityGeneration", object()); assertTrue(events(ipc).isEmpty());
            assertTrue(ipc.request("answer", "confirmAnswer", object().put("confirmationId", question.path("confirmationId").asText())
                    .put("actionKey", "import.quotaContinue")).path("result").path("accepted").booleanValue());
            var rest = until(ipc, "importFinished");
            while (!rest.getLast().path("event").asText().equals("importStateChanged")) { var e = ipc.receive(); checkRevisions(List.of(e)); rest.add(e); }
            assertEquals(1, rest.stream().filter(e -> e.path("event").asText().equals("importRound")).count());
            assertFalse(rest.getLast().path("data").path("importing").booleanValue());
            assertEquals(201, rest.stream().filter(e -> e.path("event").asText().equals("importFinished")).findFirst().orElseThrow().path("data").path("count").intValue());
            var snapshot = ipc.request("snapshot", "workspace", object()).path("result"); assertEquals(2, snapshot.path("undoDepth").intValue());
            assertEquals(revision, snapshot.path("revision").longValue()); ipc.finish();
        }
    }

    @Test void regionConfirmationAcceptRejectAndOldGenerationFollowDedicatedCommand() throws Exception {
        Path source = html("region.html", "START END<img src='data:image/png;base64," + PNG + "'>");
        var config = new AppConfig(); config.setEnableRegionExtraction(true); config.setImageRegionStartMarker("START"); config.setImageRegionEndMarker("END");
        try (var direct = new Direct(); var ipc = client("region")) {
            handshake(ipc); direct.importPaths(List.of(source.toString())); imported(ipc, List.of(source.toString())); params(ipc, config, true);
            direct.mode = true; direct.port.commands().setGlobalParams(true, config); direct.events.clear();
            direct.port.commands().loadPreview(source.toString(), true);
            direct.waitFor(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.PreviewRegionConfirmationRequired));
            ipc.request("load", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var required = until(ipc, "previewRegionConfirmationRequired");
            assertEquals(List.of("previewLoadStateChanged", "previewLoadStateChanged", "previewLoaded", "previewRegionConfirmationRequired"), names(required));
            assertEquals(direct.events.size(), required.size());
            for (int i = 0; i < required.size(); i++) compare(direct.events.get(i), required.get(i));
            long generation = required.getLast().path("data").path("generation").longValue();
            direct.events.clear(); direct.port.commands().resolvePreviewRegion(generation - 1, source.toString(), true); assertTrue(direct.events.isEmpty());
            ipc.request("old", "resolvePreviewRegion", object().put("path", source.toString()).put("generation", generation - 1).put("accepted", true)); assertTrue(events(ipc).isEmpty());
            direct.port.commands().resolvePreviewRegion(generation, source.toString(), false);
            ipc.request("reject", "resolvePreviewRegion", object().put("path", source.toString()).put("generation", generation).put("accepted", false));
            var declined = events(ipc); assertEquals(List.of("previewNotice"), names(declined)); assertEquals("preview.region.declined", declined.getFirst().path("data").path("key").asText());
            compare(direct.events.getFirst(), declined.getFirst()); assertEquals(object(), declined.getFirst().path("data").path("namedParams"));
            direct.events.clear(); direct.port.commands().loadPreview(source.toString(), true);
            direct.waitFor(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.PreviewRegionConfirmationRequired));
            ipc.request("load2", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var next = until(ipc, "previewRegionConfirmationRequired"); generation = next.getLast().path("data").path("generation").longValue();
            for (int i = 0; i < next.size(); i++) compare(direct.events.get(i), next.get(i));
            direct.events.clear(); direct.port.commands().resolvePreviewRegion(generation, source.toString(), true);
            direct.waitFor(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded));
            ipc.request("accept", "resolvePreviewRegion", object().put("path", source.toString()).put("generation", generation).put("accepted", true));
            var accepted = until(ipc, "previewLoaded"); assertEquals(1, accepted.stream().filter(e -> e.path("event").asText().equals("previewThumbnail")).count());
            ipc.request("await", "awaitPreviewInFlight", object().put("millis", 5000)); accepted.addAll(events(ipc));
            assertEquals(direct.events.size(), accepted.size());
            for (int i = 0; i < accepted.size(); i++) compare(direct.events.get(i), accepted.get(i)); ipc.finish();
        }
    }

    @Test void largeFileConfirmationAndCancellationCloseLeaveNoWorkersOrDuplicateTerminal() throws Exception {
        Path source = html("large.html", "text");
        try (var channel = FileChannel.open(source, java.nio.file.StandardOpenOption.WRITE)) { channel.position(51L * 1024 * 1024); channel.write(ByteBuffer.wrap(new byte[]{32})); }
        try (var direct = new Direct(); var ipc = client("large")) {
            handshake(ipc); direct.importPaths(List.of(source.toString())); imported(ipc, List.of(source.toString()));
            direct.mode = true; direct.events.clear(); direct.port.commands().loadPreview(source.toString(), false);
            ipc.request("ask", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", false));
            var asked = events(ipc); assertEquals(List.of("previewConfirmationRequired"), names(asked));
            compare(direct.events.getFirst(), asked.getFirst());
            assertEquals(51, asked.getFirst().path("data").path("sizeMiB").longValue());
            var decline = ipc.request("decline", "cancelPreviewLoad", object()); assertTrue(events(ipc).isEmpty()); revision = decline.path("revision").longValue();
            ipc.request("accept", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var started = events(ipc); if (started.isEmpty()) { var e = ipc.receive(); checkRevisions(List.of(e)); started.add(e); }
            assertEquals("previewLoadStateChanged", started.getFirst().path("event").asText());
            assertTrue(started.getFirst().path("data").path("active").booleanValue());
            var ending = ipc.disconnectAndCollect();
            var all = new ArrayList<JsonNode>(started); all.addAll(ending);
            assertEquals(1, all.stream().filter(e -> List.of("previewLoaded", "previewLoadFailed").contains(e.path("event").asText())).count());
            assertEquals(1, all.stream().filter(e -> e.path("event").asText().equals("previewLoadStateChanged") && !e.path("data").path("active").asBoolean()).count());
        }
    }

    @Test void invalidActivityAndPlanParametersAreRejectedAtomically() throws Exception {
        try (var ipc = client("invalid")) {
            handshake(ipc);
            var invalid = List.of(
                    List.of("awaitPreviewInFlight", object().put("millis", -1)),
                    List.of("startPreparse", context(false).set("runtime", object())),
                    List.of("startPreparseFor", context(false).set("paths", JSON.createArrayNode())),
                    List.of("loadPreview", context(true).put("path", "relative.html").put("largeFileConfirmed", true)),
                    List.of("resolvePreviewRegion", object().put("generation", 0).put("path", "relative.html").put("accepted", true)),
                    List.of("applyImportPlan", object().set("plan", upload(object().putArray("batches").addObject().put("dirPath", tmp.toString())))),
                    List.of("previewFiles", object().put("path", "x").put("pngThumbnails", true)));
            for (var pair : invalid) {
                var response = ipc.request("invalid", (String) pair.getFirst(), (JsonNode) pair.getLast());
                assertEquals("INVALID_PARAMS", response.path("error").path("code").asText(), response.toString());
                assertTrue(events(ipc).isEmpty());
            }
            assertEquals(0, ipc.request("same", "workspace", object()).path("result").path("revision").longValue()); ipc.finish();
        }
    }

    @Test void stalledWriterIsBoundedAndNaturalRunnerReturnLeavesNoPumpThreads() throws Exception {
        long started = System.nanoTime();
        try (var ipc = new IpcTestClient(tmp.resolve("stalled"), BackendIpcActivityFixture.class.getName(), "stalled")) {
            handshake(ipc);
            for (int i = 0; i < 256; i++) {
                var request = object().put("v", 1).put("type", "request").put("id", "stall-" + i).put("method", "metrics");
                request.set("params", object()); ipc.send(JSON.writeValueAsString(request));
            }
            ipc.stopReadingAndDisconnect();
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 20);
    }

    @Test void callbackPumpKeepsEveryItemAndOrderedProgressAcrossQueueCapacity() throws Exception {
        var paths = new ArrayList<String>();
        for (int i = 0; i < 300; i++) paths.add(html(String.format("pump%03d.html", i), "<h1>title</h1>text " + i).toString());
        try (var ipc = client("pump")) {
            handshake(ipc); imported(ipc, paths); params(ipc, new AppConfig(), false);
            ipc.request("start", "startPreparse", context(false));
            var delivered = events(ipc); int finished = 0, done = 0;
            while (finished < paths.size() || done < paths.size()) {
                if (delivered.isEmpty()) { var e = ipc.receive(); checkRevisions(List.of(e)); delivered.add(e); }
                for (var e : delivered) {
                    if (e.path("event").asText().equals("preparseFinished")) {
                        assertEquals(paths.get(finished++), file(e.path("data").path("result")).path("path").asText());
                    }
                    if (e.path("event").asText().equals("preparseProgress")) {
                        assertEquals(++done, e.path("data").path("done").intValue());
                        assertEquals(300, e.path("data").path("total").intValue()); assertEquals(finished, done);
                    }
                }
                delivered.clear();
            }
            var metrics = ipc.request("metrics", "metrics", object()).path("result");
            assertTrue(metrics.path("callbackQueuePeak").intValue() > 0); assertTrue(metrics.path("callbackQueuePeak").intValue() <= 256);
            assertEquals(0, metrics.path("callbackQueueSize").intValue());
            var page = ipc.request("page", "workspacePage", object().put("offset", 0).put("limit", 256)).path("result");
            assertEquals(revision, page.path("revision").longValue());
            ipc.finish();
        }
    }

    @Test void stalledOutputAlsoUnblocksReaderWhenPeerKeepsStdinOpen() throws Exception {
        try (var ipc = new IpcTestClient(tmp.resolve("open-input"), BackendIpcActivityFixture.class.getName(), "stalled")) {
            handshake(ipc);
            for (int i = 0; i < 256; i++) {
                var request = object().put("v", 1).put("type", "request").put("id", "open-" + i).put("method", "metrics");
                request.set("params", object()); ipc.send(JSON.writeValueAsString(request));
            }
            ipc.stopReadingAndDisconnect(false);
        }
    }

    @Test void previewFileInfoFailureMatchesPortAndLeavesNoLoadingState() throws Exception {
        Path source = html("missing.html", "text");
        try (var direct = new Direct(); var ipc = client("preview-failure")) {
            handshake(ipc); direct.importPaths(List.of(source.toString())); imported(ipc, List.of(source.toString()));
            Files.delete(source); direct.mode = true; direct.events.clear();
            direct.port.commands().loadPreview(source.toString(), true);
            ipc.request("load", "loadPreview", context(true).put("path", source.toString()).put("largeFileConfirmed", true));
            var failed = events(ipc); assertEquals(List.of("previewLoadFailed"), names(failed));
            compare(direct.events.getFirst(), failed.getFirst());
            assertTrue(failed.getFirst().path("data").path("fileInfo").booleanValue());
            assertTrue(ipc.request("loading", "previewLoadingPath", object()).path("result").path("path").isNull()); ipc.finish();
        }
    }
}
