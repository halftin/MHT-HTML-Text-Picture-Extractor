package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.*;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ParamScope;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Public in-process ports versus a real CLI child. No production package-private access. */
class BackendIpcParamsTest {
    @TempDir Path tmp;
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=";
    private static ObjectNode object() { return JSON.createObjectNode(); }

    private static JsonNode file(JsonNode ref) throws Exception {
        assertEquals(Set.of("path", "bytes", "mediaType"), keys(ref));
        Path path = Path.of(ref.path("path").asText());
        assertTrue(path.isAbsolute());
        assertEquals(Files.size(path), ref.path("bytes").longValue());
        assertEquals("application/json", ref.path("mediaType").asText());
        return JSON.readTree(Files.readAllBytes(path));
    }

    private static Set<String> keys(JsonNode node) {
        var result = new HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static ObjectNode runtime(boolean mode, String input, String output, boolean open) {
        return object().put("inputFolder", input).put("outputFolder", output)
                .put("imageModeEnabled", mode).put("openFolderAfterComplete", open);
    }

    /** Independent Jackson oracle retains every Config property, then explicitly restores JsonIgnore fields. */
    private static ObjectNode config(AppConfig c) {
        ObjectNode node = JSON.valueToTree(c);
        return node.put("inputFolder", c.getInputFolder()).put("outputFolder", c.getOutputFolder())
                .put("imageModeEnabled", c.isImageModeEnabled()).put("openFolderAfterComplete", c.isOpenFolderAfterComplete());
    }

    private static ObjectNode richConfig(String marker) {
        ObjectNode node = JSON.valueToTree(new AppConfig());
        for (String key : keys(node)) {
            if (node.path(key).isBoolean()) node.put(key, true);
            else node.put(key, marker + "\n\"\\" + key);
        }
        return node;
    }

    private final class Pair implements AutoCloseable {
        final WorkspaceModel model = new WorkspaceModel();
        final GlobalParameterCache global = new GlobalParameterCache();
        final PreviewSession previews;
        final PreviewService preview;
        final InProcessUiport direct;
        final IpcTestClient ipc;
        final Path bulk;
        final List<String> paths = new ArrayList<>();
        final List<PortEvent.ParamStateChanged> changes = new ArrayList<>();
        ObjectNode context;
        long revision;

        Pair(String name) throws Exception {
            Path root = Files.createDirectories(tmp.resolve(name));
            context = runtime(false, root.toString(), root.resolve("output").toString(), true);
            previews = PreviewSession.create(root.resolve("direct-cache"));
            preview = new PreviewService(previews.lifecycle());
            var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
                public List<Object> dataOrder() { return model.topLevel(); }
                public boolean imageMode() { return context.path("imageModeEnabled").asBoolean(); }
                public AppConfig textParamsCache() { return global.text(); }
                public AppConfig imageParamsCache() { return global.image(); }
                public String inputFolder() { return context.path("inputFolder").isNull() ? null : context.path("inputFolder").asText(); }
                public String outputFolder() { return context.path("outputFolder").isNull() ? null : context.path("outputFolder").asText(); }
                public boolean openFolderAfterComplete() { return context.path("openFolderAfterComplete").asBoolean(); }
            });
            var activity = new WorkspaceActivityService(new WorkspaceActivityService.Context() {
                public List<PreviewItem> items() { return model.allItems(); }
                public boolean textMode() { return !context.path("imageModeEnabled").asBoolean(); }
                public AppConfig effectiveParams(PreviewItem item) { return effective(item, !textMode()); }
                public AppConfig effectiveImageParams(PreviewItem item) { return effective(item, true); }
            }, preview, (image, index, params) -> null, Runnable::run);
            var clipboard = new ParamClipboard(model::push, target -> ParamScope.GLOBAL, (target, mode, params) -> { });
            var cache = new WorkspaceParamService(previews.lifecycle());
            var commands = new WorkspaceParamCommandService(model, cache, global, clipboard::pushParamUndo);
            direct = new InProcessUiport(model, request -> null, plans, activity, commands,
                    new WorkspaceImportCoordinator(model, request -> null, Runnable::run), null, cache);
            direct.events().subscribe(e -> { if (e instanceof PortEvent.ParamStateChanged c) changes.add(c); });
            ipc = new IpcTestClient(root.resolve("child"), BackendCli.class.getName(), "--serve", "--locale", "en");
            var ready = ipc.receive();
            for (String method : List.of("setGlobalParams", "setGroupParams", "setItemParams", "clearParams",
                    "applyGlobalParams", "undo", "runtimeConfig", "outputUnitPlan", "paramSchema")) {
                assertTrue(ready.path("data").path("methods").toString().contains('"' + method + '"'));
            }
            var id = BackendIdentity.current();
            var hello = call("hello", object().put("protocolVersion", id.protocolVersion())
                    .put("version", id.version()).put("buildId", id.buildId()));
            bulk = Path.of(hello.path("bulkRoot").asText());
            for (String letter : List.of("a", "b", "c", "d")) {
                paths.add(Files.writeString(root.resolve(letter + ".html"),
                        "<html><body><p>parameter sentinel</p><img src='data:image/png;base64," + PNG + "'></body></html>").toString());
            }
        }

        AppConfig effective(PreviewItem item, boolean mode) {
            AppConfig c = ParamScope.effectiveParams(item.parentGroup, item.textItemParams, item.imageItemParams,
                    mode, global.text(), global.image());
            return c == null ? new AppConfig() : c;
        }

        JsonNode call(String method, ObjectNode params) throws Exception {
            var frame = ipc.request(method, method, params);
            assertEquals("response", frame.path("type").asText(), frame.toString());
            revision = frame.path("revision").longValue();
            return frame.path("result");
        }

        ObjectNode upload(JsonNode value) throws Exception {
            Path path = Files.createTempFile(bulk, "client-", ".json");
            Files.write(path, JSON.writeValueAsBytes(value));
            return object().put("path", path.toString()).put("bytes", Files.size(path)).put("mediaType", "application/json");
        }

        void seed() throws Exception {
            var plan = new ImportPlanVm(List.of(new ImportPlanVm.ImportBatchVm(tmp.toString(), null, false, paths)),
                    0, 0, 0, 0, 0, false, false);
            direct.commands().applyImportPlan(plan);
            call("applyImportPlan", object().set("plan", upload(JSON.valueToTree(plan))));
            direct.commands().group(paths.subList(0, 2), "params");
            call("group", object().put("namePrefix", "params").set("paths", JSON.valueToTree(paths.subList(0, 2))));
            ipc.drainRequestEvents();
            sameWorkspace();
        }

        long group() { return direct.queries().workspace().groups().getFirst().no(); }

        void sameWorkspace() throws Exception {
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(direct.queries().workspace())),
                    IpcTestClient.expandWorkspace(call("workspace", object())));
        }

        void queryBothModes() throws Exception {
            long before = revision;
            for (boolean mode : List.of(false, true)) {
                context.put("imageModeEnabled", mode);
                var configResult = call("runtimeConfig", object().set("runtime", context));
                assertEquals(config(direct.queries().runtimeConfig()), configResult.path("config"));
                assertEquals(33, configResult.path("config").size());
                assertEquals(before, configResult.path("revision").longValue());
                for (boolean checked : List.of(false, true)) {
                    var units = direct.queries().outputUnitPlan(checked);
                    var expected = JSON.createArrayNode();
                    for (var unit : units) {
                        ObjectNode node = JSON.valueToTree(unit);
                        node.set("params", config(unit.params()));
                        expected.add(node);
                    }
                    var result = call("outputUnitPlan", object().put("checkedOnly", checked).set("runtime", context));
                    assertEquals(expected, file(result.path("units")));
                    assertEquals(before, result.path("revision").longValue());
                }
            }
            assertTrue(ipc.drainRequestEvents().isEmpty(), "Queries must not mutate revision or emit events");
        }

        void command(String method, ObjectNode params, Runnable action, int records) throws Exception {
            changes.clear();
            int before = direct.queries().workspace().undoDepth();
            action.run();
            assertTrue(call(method, params).isNull());
            var events = ipc.drainRequestEvents();
            assertEquals(changes.size(), events.size());
            for (int i = 0; i < changes.size(); i++) {
                var expected = changes.get(i);
                var event = events.get(i);
                assertEquals("paramsChanged", event.path("event").asText());
                var data = event.path("data");
                assertEquals(expected.kind().name(), data.path("kind").asText());
                assertEquals(expected.affectedPaths().size(), data.path("affectedCount").intValue());
                assertEquals(expected.imageMode(), data.path("imageMode").booleanValue());
                assertEquals(JSON.valueToTree(expected.affectedPaths()), file(data.path("affectedPaths")));
                assertEquals(revision, data.path("revision").longValue());
            }
            assertEquals(before + records, direct.queries().workspace().undoDepth(), "One user action = one record");
            sameWorkspace();
            queryBothModes();
        }

        void set(String method, boolean mode, ObjectNode value, int records) throws Exception {
            AppConfig c = JSON.treeToValue(value, AppConfig.class);
            ObjectNode params = object().put("imageMode", mode).set("params", value);
            Runnable action = switch (method) {
                case "setGlobalParams" -> () -> direct.commands().setGlobalParams(mode, c);
                case "setGroupParams" -> {
                    params.put("groupNo", group());
                    yield () -> direct.commands().setGroupParams(group(), mode, c);
                }
                case "setItemParams" -> {
                    params.put("path", paths.get(2));
                    yield () -> direct.commands().setItemParams(paths.get(2), mode, c);
                }
                default -> throw new AssertionError(method);
            };
            command(method, params, action, records);
        }

        List<JsonNode> load(ObjectNode request) throws Exception {
            call("loadPreview", request);
            var events = new ArrayList<>(ipc.drainRequestEvents());
            while (events.stream().noneMatch(e -> e.path("event").asText().equals("previewLoaded"))) {
                assertTrue(events.size() < 20, "Bounded fixture event stream");
                var event = ipc.receive();
                assertEquals("event", event.path("type").asText());
                events.add(event);
            }
            call("awaitPreviewInFlight", object().put("millis", 10000));
            events.addAll(ipc.drainRequestEvents());
            return events;
        }

        @Override public void close() throws Exception {
            try { ipc.finish(); } finally { ipc.close(); new WorkspaceParamService(previews.lifecycle()).clearAllCaches(); }
        }
    }

    @Test void schemaHasEveryFieldForBothModes() throws Exception {
        try (var pair = new Pair("schema")) {
            for (boolean mode : List.of(false, true)) {
                var result = pair.call("paramSchema", object().put("imageMode", mode));
                assertEquals(JSON.valueToTree(pair.direct.queries().paramSchema(mode)), result.path("schema"));
                assertEquals(0, result.path("revision").longValue());
                for (var field : result.path("schema").path("fields")) {
                    assertEquals(Set.of("id", "type", "labelKey", "group", "defaultValue", "enabledBy", "disabledValue",
                            "nullWhenDisabled", "promptText", "templateEnter"), keys(field));
                }
            }
        }
    }

    @Test void fiveCommandsShareThreeLayerStateAndExactlyOneRecord() throws Exception {
        try (var pair = new Pair("layers")) {
            pair.seed();
            for (boolean mode : List.of(false, true)) {
                pair.set("setGlobalParams", mode, richConfig("global"), 1);
                pair.set("setGroupParams", mode, richConfig("group"), 1);
                pair.set("setItemParams", mode, richConfig("item"), 1);
                assertTrue(pair.effective(pair.model.allItems().get(0), mode).getStartMarker().startsWith("group"));
                assertTrue(pair.effective(pair.model.allItems().get(2), mode).getStartMarker().startsWith("item"));
                assertTrue(pair.effective(pair.model.allItems().get(3), mode).getStartMarker().startsWith("global"));
                var target = TargetId.ofGroup(pair.group());
                pair.command("applyGlobalParams", object().put("imageMode", mode).set("target", JSON.valueToTree(target)),
                        () -> pair.direct.commands().applyGlobalParams(target, mode), 1);
                for (var item : pair.model.allItems()) assertTrue(pair.effective(item, mode).getStartMarker().startsWith("group"));
                pair.set("setGroupParams", mode, richConfig("again-group"), 1);
                pair.set("setItemParams", mode, richConfig("again-item"), 1);
                var checked = List.of(pair.paths.get(0), pair.paths.get(2));
                pair.direct.commands().setChecked(checked, true);
                pair.call("setChecked", object().put("checked", true).set("paths", JSON.valueToTree(checked)));
                pair.ipc.drainRequestEvents();
                var fallback = List.of(TargetId.ofItem(pair.paths.get(3)));
                pair.command("clearParams", object().put("imageMode", mode).set("fallbackTargets", pair.upload(JSON.valueToTree(fallback))),
                        () -> pair.direct.commands().clearParams(fallback, mode), 1);
                for (var item : pair.model.allItems()) assertTrue(pair.effective(item, mode).getStartMarker().startsWith("group"));
            }
        }
    }

    @Test void runtimeContextIsRequestLocalAndQueriesDoNotCreateDirectories() throws Exception {
        try (var pair = new Pair("context")) {
            pair.seed();
            pair.queryBothModes();
            String output = pair.context.path("outputFolder").asText();
            assertFalse(Files.exists(Path.of(output)));
            pair.context = runtime(true, null, null, false);
            pair.queryBothModes();
            pair.context = runtime(false, tmp.toString(), output, true);
            pair.queryBothModes();
            assertFalse(Files.exists(Path.of(output)));
        }
    }

    @Test void unsupportedParameterUndoReportsErrorAndPreservesRecordAndState() throws Exception {
        for (String method : List.of("setGlobalParams", "clearParams", "applyGlobalParams")) {
            try (var pair = new Pair("undo-" + method)) {
                pair.seed();
                pair.set("setGlobalParams", false, richConfig("before"), 1);
                pair.set("setItemParams", false, richConfig("item"), 1);
                var target = TargetId.ofItem(pair.paths.get(2));
                if (method.equals("clearParams")) {
                    var targets = List.of(target);
                    pair.command(method, object().put("imageMode", false).set("fallbackTargets", pair.upload(JSON.valueToTree(targets))),
                            () -> pair.direct.commands().clearParams(targets, false), 1);
                } else if (method.equals("applyGlobalParams")) {
                    pair.command(method, object().put("imageMode", false).set("target", JSON.valueToTree(target)),
                            () -> pair.direct.commands().applyGlobalParams(target, false), 1);
                }
                var before = pair.direct.queries().workspace();
                for (int attempt = 0; attempt < 2; attempt++) {
                    pair.direct.commands().undo();
                    var error = pair.ipc.request("undo", "undo", object());
                    assertEquals("INTERNAL_ERROR", error.path("error").path("code").asText());
                    assertTrue(pair.ipc.drainRequestEvents().isEmpty());
                    assertEquals(before, pair.direct.queries().workspace());
                    pair.sameWorkspace();
                    pair.queryBothModes();
                }
            }
        }
    }

    @Test void structuralAndEmptyUndoMatchPortSnapshotAndSelection() throws Exception {
        try (var pair = new Pair("structure-undo")) {
            pair.seed();
            pair.direct.commands().setChecked(pair.paths, true);
            pair.call("setChecked", object().put("checked", true).set("paths", JSON.valueToTree(pair.paths)));
            pair.ipc.drainRequestEvents();
            for (int i = 0; i < 3; i++) {
                int before = pair.direct.queries().workspace().undoDepth();
                pair.direct.commands().undo();
                assertTrue(pair.call("undo", object()).isNull());
                var events = pair.ipc.drainRequestEvents();
                assertEquals(before == 0 ? 0 : 1, events.size());
                if (!events.isEmpty()) assertEquals("UNDO_APPLIED", events.getFirst().path("data").path("kind").asText());
                assertEquals(Math.max(0, before - 1), pair.direct.queries().workspace().undoDepth());
                pair.sameWorkspace();
            }
        }
    }

    @Test void malformedParamsAreAtomicAndUnknownTargetsAreNoOps() throws Exception {
        try (var pair = new Pair("invalid")) {
            pair.seed();
            var before = pair.call("workspace", object());
            for (var config : List.of(object().put("imageModeEnabled", true), object().put("autoFixEncoding", "true"),
                    object().putNull("autoFixEncoding"), object().put("startMarker", 7), object().put("unknown", true))) {
                var error = pair.ipc.request("invalid", "setGlobalParams", object().put("imageMode", false).set("params", config));
                assertEquals("INVALID_PARAMS", error.path("error").path("code").asText());
            }
            var malformed = JSON.createArrayNode().add(JSON.valueToTree(TargetId.ofItem(pair.paths.get(2)))).add(object().put("path", "missing-groupNo"));
            var error = pair.ipc.request("invalid-targets", "clearParams", object().put("imageMode", false).set("fallbackTargets", pair.upload(malformed)));
            assertEquals("INVALID_PARAMS", error.path("error").path("code").asText());
            for (String key : List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete")) {
                var incomplete = pair.context.deepCopy();
                incomplete.remove(key);
                error = pair.ipc.request("invalid-runtime", "runtimeConfig", object().set("runtime", incomplete));
                assertEquals("INVALID_PARAMS", error.path("error").path("code").asText());
            }
            assertEquals(before, pair.call("workspace", object()));
            assertTrue(pair.ipc.drainRequestEvents().isEmpty());
            pair.command("setGroupParams", object().put("imageMode", false).put("groupNo", 999).set("params", object()),
                    () -> pair.direct.commands().setGroupParams(999, false, new AppConfig()), 0);
            pair.command("setItemParams", object().put("imageMode", false).put("path", "unknown-id").set("params", object()),
                    () -> pair.direct.commands().setItemParams("unknown-id", false, new AppConfig()), 0);
        }
    }

    @Test void allFiveImageCommandsDeleteActualCacheFilesAndTextCommandsPreserveThem() throws Exception {
        try (var pair = new Pair("cache")) {
            pair.seed();
            for (String method : List.of("setGlobalParams", "setGroupParams", "setItemParams", "clearParams", "applyGlobalParams")) {
                if (method.equals("clearParams")) pair.set("setItemParams", true, object(), 1);
                var directFiles = new ArrayList<Path>();
                var remoteFiles = new ArrayList<Path>();
                for (var item : pair.model.allItems()) {
                    var cached = pair.preview.ensureCachedLocked(pair.preview.prepare(Path.of(item.path), pair.effective(item, true)), () -> false);
                    directFiles.add(pair.previews.store().imageFileFor(cached.meta(), 0));
                    var manifest = file(pair.call("previewFiles", object().put("path", item.path)).path("manifest"));
                    remoteFiles.add(Path.of(manifest.path("thumbnails").get(0).path("imagePath").asText()));
                }
                pair.set("setGlobalParams", false, object(), 1);
                for (var path : directFiles) assertTrue(Files.exists(path));
                for (var path : remoteFiles) assertTrue(Files.exists(path));
                if (method.equals("clearParams")) {
                    var targets = List.of(TargetId.ofItem(pair.paths.get(2)));
                    pair.command(method, object().put("imageMode", true).set("fallbackTargets", pair.upload(JSON.valueToTree(targets))),
                            () -> pair.direct.commands().clearParams(targets, true), 1);
                } else if (method.equals("applyGlobalParams")) {
                    var target = TargetId.ofGroup(pair.group());
                    pair.command(method, object().put("imageMode", true).set("target", JSON.valueToTree(target)),
                            () -> pair.direct.commands().applyGlobalParams(target, true), 1);
                } else pair.set(method, true, object(), 1);
                Set<String> affected = new HashSet<>(pair.changes.getFirst().affectedPaths());
                for (int i = 0; i < pair.paths.size(); i++) {
                    assertEquals(!affected.contains(pair.paths.get(i)), Files.exists(directFiles.get(i)), method + " direct");
                    assertEquals(!affected.contains(pair.paths.get(i)), Files.exists(remoteFiles.get(i)), method + " IPC");
                }
            }
        }
    }

    @Test void legacySetParamsStillSelectsScriptModeAndNewCallsDoNotChangeIt() throws Exception {
        try (var pair = new Pair("legacy")) {
            pair.seed();
            pair.command("setParams", object().put("imageMode", false).set("config", object().put("separateFiles", true)),
                    () -> { var c = new AppConfig(); c.setSeparateFiles(true); pair.direct.commands().setGlobalParams(false, c); }, 1);
            pair.set("setGlobalParams", true, object(), 1);
            pair.context.put("imageModeEnabled", true);
            pair.call("runtimeConfig", object().set("runtime", pair.context));
            Path output = tmp.resolve("legacy-extract");
            var result = pair.call("extract", object().put("checkedOnly", false).put("outputFolder", output.toString()));
            assertEquals("SUCCEEDED", result.path("end").asText());
            assertEquals(0, result.path("failCount").intValue());
            try (var files = Files.walk(output)) {
                var products = files.filter(Files::isRegularFile).toList();
                assertFalse(products.isEmpty());
                assertTrue(products.stream().allMatch(p -> p.toString().endsWith(".txt")), products.toString());
            }
        }
    }

    @Test void loadedPreviewCanBeRebuiltAfterSameParametersInvalidateItsFiles() throws Exception {
        try (var pair = new Pair("reload")) {
            pair.seed();
            pair.context.put("imageModeEnabled", true);
            ObjectNode request = object().put("path", pair.paths.get(2)).put("largeFileConfirmed", true).set("runtime", pair.context);
            var firstEvents = pair.load(request);
            var first = firstEvents.stream().filter(e -> e.path("event").asText().equals("previewLoaded")).findFirst().orElseThrow();
            var manifest = file(first.path("data").path("manifest"));
            Path cached = Path.of(manifest.path("thumbnails").get(0).path("imagePath").asText());
            assertTrue(Files.exists(cached));
            pair.set("setItemParams", true, object(), 1);
            assertFalse(Files.exists(cached), "The original cache file really must be deleted");
            var events = pair.load(request);
            assertTrue(events.stream().noneMatch(e -> e.path("event").asText().equals("previewLoadFailed")));
            var loaded = events.stream().filter(e -> e.path("event").asText().equals("previewLoaded")).findFirst().orElseThrow();
            manifest = file(loaded.path("data").path("manifest"));
            assertTrue(Files.exists(Path.of(manifest.path("thumbnails").get(0).path("imagePath").asText())));
            assertTrue(events.stream().anyMatch(e -> e.path("event").asText().equals("previewThumbnail")),
                    "Rebuild must deliver a real thumbnail, not reuse stale in-memory state");
        }
    }
}
