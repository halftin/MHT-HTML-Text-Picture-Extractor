package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.app.InProcessUiport;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.TargetId;
import com.mhtextractor.uiport.Uiport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Real CLI contract; production is observed only through public ports and protocol frames. */
class BackendIpcStructureTest {
    @TempDir Path tmp;
    private long lastRevision;
    private final List<JsonNode> expectedEvents = new ArrayList<>();
    private List<String> expectedAffected = List.of();

    private IpcTestClient client(String name) throws Exception {
        return new IpcTestClient(tmp.resolve(name), BackendCli.class.getName(), "--serve", "--locale", "en");
    }

    private static ObjectNode object() { return JSON.createObjectNode(); }

    private static JsonNode file(JsonNode ref) throws Exception {
        assertTrue(ref.path("path").isTextual());
        assertTrue(ref.path("bytes").isIntegralNumber());
        assertEquals("application/json", ref.path("mediaType").asText());
        Path path = Path.of(ref.path("path").textValue());
        assertTrue(path.isAbsolute());
        assertEquals(Files.size(path), ref.path("bytes").longValue());
        return JSON.readTree(Files.readAllBytes(path));
    }

    private static ObjectNode upload(Path root, JsonNode content) throws Exception {
        Path path = Files.createTempFile(root, "client-", ".json");
        Files.write(path, JSON.writeValueAsBytes(content));
        return object().put("path", path.toString()).put("bytes", Files.size(path)).put("mediaType", "application/json");
    }

    private static JsonNode handshake(IpcTestClient ipc) throws Exception {
        var ready = ipc.receive();
        assertEquals("ready", ready.path("event").asText());
        assertEquals(0, ready.path("data").path("revision").longValue());
        var id = BackendIdentity.current();
        var welcome = ipc.request("h", "hello", object().put("protocolVersion", id.protocolVersion())
                .put("version", id.version()).put("buildId", id.buildId())).path("result");
        assertEquals(ready.path("data").path("sessionId"), welcome.path("sessionId"));
        var methods = ready.path("data").path("methods");
        var legacy = new ArrayList<String>();
        for (int i = 0; i < 13; i++) legacy.add(methods.get(i).textValue());
        assertEquals(List.of("workspace", "setChecked", "importPaths", "group", "setParams", "extract", "workspacePage",
                "previewFiles", "fileInfo", "releaseFiles", "metrics", "confirmAnswer", "hello"), legacy);
        for (var name : List.of("ungroup", "renameGroup", "toggleGroupExpanded", "clearItems", "applyDrop", "removeItems", "removeGroup", "canUndo")) {
            assertTrue(methods.toString().contains('"' + name + '"'), name);
        }
        return welcome;
    }

    private void same(Uiport direct, IpcTestClient ipc, String method, ObjectNode params, Runnable command) throws Exception {
        expectedEvents.clear();
        command.run();
        var response = ipc.request(method, method, params);
        assertEquals("response", response.path("type").asText(), response.toString());
        var events = ipc.drainRequestEvents();
        assertEquals(expectedEvents.size(), events.size(), method);
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            assertEquals("workspaceChanged", event.path("event").asText());
            long revision = event.path("data").path("revision").longValue();
            assertTrue(revision > lastRevision, event.toString());
            assertEquals(lastRevision + 1, revision, "Each structural event owns the next revision");
            lastRevision = revision;
            assertEquals(expectedEvents.get(i).path("kind"), event.path("data").path("kind"));
            var snapshot = file(event.path("data").path("snapshot"));
            assertEquals(revision, snapshot.path("revision").longValue());
            assertEquals(JSON.readTree(JSON.writeValueAsString(expectedEvents.get(i).path("snapshot"))), IpcTestClient.expandWorkspace(snapshot));
        }
        assertEquals(lastRevision, response.path("revision").longValue(), "No-op must not advance revision");
        var query = ipc.request("q", "workspace", object()).path("result");
        assertEquals(lastRevision, query.path("revision").longValue());
        assertEquals(JSON.readTree(JSON.writeValueAsString(direct.queries().workspace())), IpcTestClient.expandWorkspace(query));
        if (method.equals("applyDrop")) {
            assertEquals(!events.isEmpty(), response.path("result").path("changed").booleanValue());
            assertEquals(JSON.valueToTree(expectedAffected), file(response.path("result").path("affectedPaths")));
        } else assertTrue(response.path("result").isNull());
    }

    @Test void everyStructuralCommandMatchesPortFieldsAndEventOrder() throws Exception {
        Path first = Files.createDirectories(tmp.resolve("one/shared"));
        Path second = Files.createDirectories(tmp.resolve("two/shared"));
        Path a = Files.writeString(first.resolve("a.html"), "<html><body>A</body></html>");
        Files.writeString(first.resolve("b.html"), "<html><body>B</body></html>");
        Files.writeString(second.resolve("c.html"), "<html><body>C</body></html>");
        Path loose = Files.writeString(tmp.resolve("loose.html"), "<html><body>L</body></html>");
        var paths = List.of(first.getParent().toString(), loose.toString(), second.getParent().toString());
        Uiport direct = new InProcessUiport(new WorkspaceModel(), request -> null);
        var done = new CountDownLatch(1);
        try (var initial = direct.events().subscribe(e -> { if (e instanceof PortEvent.ImportFinished) done.countDown(); });
             var ipc = client("structure")) {
            JsonNode hello = handshake(ipc);
            Path bulkRoot = Path.of(hello.path("bulkRoot").asText());
            direct.commands().importPaths(paths, "IPC", false);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            var imported = ipc.request("i", "importPaths", object().set("paths", JSON.valueToTree(paths)));
            assertEquals("response", imported.path("type").asText());
            lastRevision = imported.path("revision").longValue();
            ipc.drainRequestEvents();
            try (var sub = direct.events().subscribe(e -> {
                if (e instanceof PortEvent.WorkspaceChanged changed) {
                    var expected = object().put("kind", changed.kind());
                    expected.set("snapshot", JSON.valueToTree(direct.queries().workspace()));
                    expectedEvents.add(expected);
                }
            })) {
                var snapshot = direct.queries().workspace();
                long group = snapshot.groups().get(0).no();
                long otherGroup = snapshot.groups().get(1).no();
                assertEquals(List.of(TargetId.ofGroup(group), TargetId.ofItem(loose.toString()), TargetId.ofGroup(otherGroup)), snapshot.topLevelOrder());
                same(direct, ipc, "setChecked", object().put("checked", true).set("pathsFile", upload(bulkRoot, JSON.valueToTree(List.of(a.toString())))),
                        () -> direct.commands().setChecked(List.of(a.toString()), true));
                same(direct, ipc, "setChecked", object().put("checked", true).set("paths", JSON.valueToTree(List.of(a.toString()))),
                        () -> direct.commands().setChecked(List.of(a.toString()), true));
                same(direct, ipc, "renameGroup", object().put("groupNo", group).put("newName", "renamed"), () -> direct.commands().renameGroup(group, "renamed"));
                same(direct, ipc, "toggleGroupExpanded", object().put("groupNo", group), () -> direct.commands().toggleGroupExpanded(group));
                same(direct, ipc, "ungroup", object().put("groupNo", group), () -> direct.commands().ungroup(group));
                var drag = object().put("zone", "INTO").put("revision", lastRevision);
                drag.set("dragged", JSON.valueToTree(TargetId.ofItem(a.toString())));
                drag.set("target", JSON.valueToTree(TargetId.ofGroup(otherGroup)));
                same(direct, ipc, "applyDrop", drag, () -> expectedAffected = direct.commands().applyDrop(TargetId.ofItem(a.toString()), TargetId.ofGroup(otherGroup), "INTO").affectedPaths());
                var noop = object().put("zone", "TOP").put("revision", lastRevision);
                noop.set("dragged", JSON.valueToTree(TargetId.ofItem(a.toString())));
                noop.set("target", JSON.valueToTree(TargetId.ofItem(a.toString())));
                same(direct, ipc, "applyDrop", noop, () -> expectedAffected = direct.commands().applyDrop(TargetId.ofItem(a.toString()), TargetId.ofItem(a.toString()), "TOP").affectedPaths());
                var manual = List.of(first.resolve("b.html").toString(), loose.toString());
                same(direct, ipc, "group", object().put("namePrefix", "manual").set("pathsFile", upload(bulkRoot, JSON.valueToTree(manual))),
                        () -> direct.commands().group(manual, "manual"));
                long manualGroup = direct.queries().workspace().groups().stream().filter(g -> g.no() != otherGroup).findFirst().orElseThrow().no();
                var move = object().put("zone", "BOTTOM").put("revision", lastRevision);
                move.set("dragged", JSON.valueToTree(TargetId.ofGroup(manualGroup)));
                move.putNull("target");
                same(direct, ipc, "applyDrop", move, () -> expectedAffected = direct.commands().applyDrop(TargetId.ofGroup(manualGroup), null, "BOTTOM").affectedPaths());
                var merge = object().put("zone", "INTO").put("revision", lastRevision);
                merge.set("dragged", JSON.valueToTree(TargetId.ofGroup(manualGroup)));
                merge.set("target", JSON.valueToTree(TargetId.ofGroup(otherGroup)));
                same(direct, ipc, "applyDrop", merge, () -> expectedAffected = direct.commands().applyDrop(TargetId.ofGroup(manualGroup), TargetId.ofGroup(otherGroup), "INTO").affectedPaths());
                same(direct, ipc, "removeItems", object().set("paths", upload(bulkRoot, JSON.valueToTree(List.of(loose.toString())))),
                        () -> direct.commands().removeItems(List.of(loose.toString())));
                same(direct, ipc, "removeGroup", object().put("groupNo", otherGroup), () -> direct.commands().removeGroup(otherGroup));
                same(direct, ipc, "group", object().put("namePrefix", "manual").set("pathsFile", upload(bulkRoot, JSON.valueToTree(List.of(a.toString())))),
                        () -> direct.commands().group(List.of(a.toString()), "manual"));
                var undo = ipc.request("u", "canUndo", object()).path("result");
                assertEquals(direct.queries().canUndo(), undo.path("canUndo").booleanValue());
                assertEquals(direct.queries().workspace().undoDepth(), undo.path("undoDepth").intValue());
                same(direct, ipc, "clearItems", object(), () -> direct.commands().clearItems());
                assertTrue(direct.queries().canUndo(), "clearItems preserves undo stack");
            }
            ipc.finish();
        }
    }

    @Test void pagesAndBulkBindOneRevisionAndRejectStalePages() throws Exception {
        var paths = new ArrayList<String>();
        for (int i = 0; i < 3; i++) paths.add(Files.writeString(tmp.resolve(i + ".html"), "<html><body>x</body></html>").toString());
        try (var ipc = client("pages")) {
            handshake(ipc);
            ipc.request("i", "importPaths", object().set("paths", JSON.valueToTree(paths)));
            ipc.drainRequestEvents();
            var first = ipc.request("p", "workspacePage", object().put("offset", 0).put("limit", 1)).path("result");
            long revision = first.path("revision").longValue();
            var complete = file(first.path("snapshotFile"));
            assertEquals(revision, complete.path("revision").longValue());
            for (int i = 0; i < 3; i++) {
                var page = ipc.request("p", "workspacePage", object().put("offset", i).put("limit", 1).put("revision", revision)).path("result");
                assertEquals(revision, page.path("revision").longValue());
                assertEquals(first.path("snapshotFile"), page.path("snapshotFile"));
                assertTrue(page.path("items").get(0).has("sourceFolder"), "Source field must be present, even if null");
                assertEquals(complete.path("topLevelItems").get(i), page.path("items").get(0));
            }
            var bulk = ipc.request("b", "workspace", object().put("bulk", true)).path("result");
            assertEquals(complete, file(bulk.path("manifest")));
            ipc.request("c", "setChecked", object().put("checked", true).set("paths", JSON.valueToTree(paths)));
            ipc.drainRequestEvents();
            assertError(ipc.request("stale", "workspacePage", object().put("offset", 1).put("limit", 1).put("revision", revision)));
            var fresh = ipc.request("fresh", "workspacePage", object().put("offset", 0).put("limit", 1)).path("result");
            assertTrue(fresh.path("revision").longValue() > revision);
            assertTrue(fresh.path("items").get(0).path("checked").booleanValue());
            ipc.finish();
        }
    }

    @Test void reconnectUsesNewSessionAndCannotReuseFilesOrRevision() throws Exception {
        String session;
        JsonNode oldFile;
        try (var ipc = client("old")) {
            session = handshake(ipc).path("sessionId").asText();
            oldFile = ipc.request("b", "workspace", object().put("bulk", true)).path("result").path("manifest");
            ipc.finish();
        }
        try (var ipc = client("new")) {
            var hello = handshake(ipc);
            assertNotEquals(session, hello.path("sessionId").asText());
            assertError(ipc.request("oldFile", "setChecked", object().put("checked", true).set("pathsFile", oldFile)));
            assertError(ipc.request("oldRevision", "workspacePage", object().put("offset", 0).put("limit", 1).put("revision", 1)));
            assertEquals(0, ipc.request("w", "workspace", object()).path("result").path("revision").longValue());
            ipc.finish();
        }
    }

    @Test void invalidFieldsAndFileReferencesDoNotMutate() throws Exception {
        Path source = Files.writeString(tmp.resolve("input.html"), "<html><body>x</body></html>");
        try (var ipc = new IpcTestClient(tmp.resolve("errors"), Path.of(System.getProperty("java.io.tmpdir")),
                tmp.toString(), BackendCli.class.getName(), "--serve")) {
            var hello = handshake(ipc);
            Path root = Path.of(hello.path("bulkRoot").asText());
            ipc.request("i", "importPaths", object().set("paths", JSON.valueToTree(List.of(source.toString()))));
            ipc.drainRequestEvents();
            var before = ipc.request("q", "workspace", object()).path("result");
            var valid = upload(root, JSON.valueToTree(List.of(source.toString())));
            var both = object().put("checked", true);
            both.set("pathsFile", valid);
            both.set("paths", JSON.valueToTree(List.of(source.toString())));
            assertError(ipc.request("both", "setChecked", both));
            var wrongSize = valid.deepCopy().put("bytes", 0);
            assertError(ipc.request("size", "setChecked", object().put("checked", true).set("pathsFile", wrongSize)));
            var relative = valid.deepCopy().put("path", "relative.json");
            assertError(ipc.request("relativeFile", "removeItems", object().set("paths", relative)));
            assertError(ipc.request("escape", "removeItems", object().set("paths", object().put("path", source.toString()).put("bytes", Files.size(source)).put("mediaType", "application/json"))));
            assertError(ipc.request("relativePath", "removeItems", object().set("paths", upload(root, JSON.valueToTree(List.of("relative.html"))))));
            assertError(ipc.request("unauthorized", "removeItems", object().set("paths", upload(root, JSON.valueToTree(List.of("C:/forbidden.html"))))));
            for (JsonNode invalid : List.of(JSON.nullNode(), JSON.getNodeFactory().numberNode(1), JSON.getNodeFactory().booleanNode(false))) {
                assertError(ipc.request("name", "renameGroup", object().put("groupNo", 1).set("newName", invalid)));
            }
            assertError(ipc.request("overflow", "removeGroup", object().set("groupNo", JSON.readTree("9223372036854775808"))));
            assertError(ipc.request("bulkType", "workspace", object().put("bulk", 1)));
            var invalidDrop = object().put("zone", "INTO").put("revision", before.path("revision").longValue());
            invalidDrop.set("dragged", JSON.valueToTree(TargetId.ofItem(source.toString())));
            invalidDrop.putNull("target");
            assertError(ipc.request("invalidZone", "applyDrop", invalidDrop));
            invalidDrop.put("zone", "BOTTOM").put("revision", before.path("revision").longValue() - 1);
            assertError(ipc.request("staleDrop", "applyDrop", invalidDrop));
            ipc.request("empty", "removeItems", object().set("paths", upload(root, JSON.createArrayNode())));
            ipc.request("unknown", "group", object().put("namePrefix", "unknown").set("paths", JSON.valueToTree(List.of("unknown"))));
            assertEquals(before, ipc.request("after", "workspace", object()).path("result"));
            assertTrue(ipc.drainRequestEvents().isEmpty());
            ipc.finish();
        }
    }

    @Test void dropAndRemovalInvalidateRealPreviewCacheFiles() throws Exception {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=";
        String html = "<html><body><p>cache contract</p><img src=\"data:image/png;base64," + png + "\"></body></html>";
        var paths = new ArrayList<String>();
        for (int i = 0; i < 3; i++) paths.add(Files.writeString(tmp.resolve(i + ".html"), html).toString());
        try (var ipc = client("cache")) {
            Path root = Path.of(handshake(ipc).path("bulkRoot").asText());
            ipc.request("i", "importPaths", object().set("paths", JSON.valueToTree(paths)));
            ipc.request("g", "group", object().put("namePrefix", "cache").set("paths", JSON.valueToTree(paths.subList(1, 3))));
            ipc.request("params", "setParams", object().put("imageMode", false)
                    .set("config", JSON.valueToTree(new com.mhtextractor.model.AppConfig())));
            var workspace = ipc.request("q", "workspace", object()).path("result");
            long group = workspace.path("groups").get(0).path("no").longValue();
            Path image = previewImage(ipc, paths.get(0));
            var drop = object().put("zone", "INTO").put("revision", workspace.path("revision").longValue());
            drop.set("dragged", JSON.valueToTree(TargetId.ofItem(paths.get(0))));
            drop.set("target", JSON.valueToTree(TargetId.ofGroup(group)));
            var dropped = ipc.request("d", "applyDrop", drop);
            assertEquals("response", dropped.path("type").asText());
            assertFalse(Files.exists(image), "Parent change invalidates disk image before response");
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long callbackRevision;
            do {
                Thread.sleep(25);
                callbackRevision = ipc.request("callback", "workspace", object()).path("result").path("revision").longValue();
            } while (callbackRevision <= dropped.path("revision").longValue() && System.nanoTime() < timeout);
            assertTrue(callbackRevision > dropped.path("revision").longValue(), "Preparse callback applies without a synchronous activity request");
            image = previewImage(ipc, paths.get(0));
            ipc.request("r", "removeItems", object().set("paths", upload(root, JSON.valueToTree(List.of(paths.get(0))))));
            assertFalse(Files.exists(image), "Item removal applies rule c");
            assertError(ipc.request("lost", "fileInfo", object().put("path", image.toString())));
            Path memberImage = previewImage(ipc, paths.get(1));
            ipc.request("rg", "removeGroup", object().put("groupNo", group));
            assertFalse(Files.exists(memberImage), "Group removal applies rule c to members");
            ipc.drainRequestEvents();
            ipc.finish();
        }
    }

    private static Path previewImage(IpcTestClient ipc, String path) throws Exception {
        var response = ipc.request("preview", "previewFiles", object().put("path", path));
        assertEquals("response", response.path("type").asText(), response.toString());
        JsonNode manifest = file(response.path("result").path("manifest"));
        Path image = Path.of(manifest.path("thumbnails").get(0).path("path").asText());
        assertTrue(Files.isRegularFile(image));
        return image;
    }

    private static void assertError(JsonNode frame) {
        assertEquals("error", frame.path("type").asText(), frame.toString());
        assertEquals("INVALID_PARAMS", frame.path("error").path("code").asText(), frame.toString());
    }
}
