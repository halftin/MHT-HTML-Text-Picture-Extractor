package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.TriState;
import com.mhtextractor.uiport.Uiport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

class BackendIpcContractTest {
    @TempDir
    Path tmp;

    @Test void scriptClientProtocolVersionMatchesJavaIdentity() throws Exception {
        String script = java.nio.file.Files.readString(Path.of("tools/ipc/client.mjs"));
        var match = java.util.regex.Pattern.compile("protocolVersion: (\\d+)").matcher(script);
        assertTrue(match.find(), "Script must declare its expected protocol version");
        assertEquals(BackendIdentity.PROTOCOL_VERSION, Integer.parseInt(match.group(1)));
        assertFalse(match.find(), "Keep a single script protocol version declaration");
    }

    @Test
    void ipcAndInProcessHaveIdenticalSnapshotsAndSilentCommands() throws Exception {
        Uiport direct = BackendIpcFixture.port();
        List<PortEvent> events = new ArrayList<>();
        try (var subscription = direct.events().subscribe(events::add);
             var ipc = new IpcTestClient(tmp.resolve("contract"), BackendIpcFixture.class.getName())) {
            ipc.ready();
            assertSnapshot(direct, ipc, "before");
            var paths = Arrays.asList(BackendIpcFixture.ROOT + "a.mht", BackendIpcFixture.ROOT + "a.mht",
                    BackendIpcFixture.ROOT + "quote\"line\n.mht", "unknown", null);
            check(direct, ipc, "partial", paths, true);
            assertEquals(TriState.SOME, direct.queries().workspace().selection());
            assertSnapshot(direct, ipc, "after-partial");
            check(direct, ipc, "repeat", paths, true);
            assertSnapshot(direct, ipc, "after-repeat");
            var all = BackendIpcFixture.NAMES.stream().map(name -> BackendIpcFixture.ROOT + name).toList();
            check(direct, ipc, "all", all, true);
            assertEquals(TriState.ALL, direct.queries().workspace().selection());
            assertSnapshot(direct, ipc, "after-all");
            check(direct, ipc, "none", all, false);
            assertEquals(TriState.NONE, direct.queries().workspace().selection());
            assertSnapshot(direct, ipc, "after-none");
            check(direct, ipc, "empty", List.of(), true);
            assertSnapshot(direct, ipc, "after-empty");
            assertEquals(3, events.size(), "Only actual checked changes emit events");
            ipc.finish();
        }
    }

    private static void check(Uiport direct, IpcTestClient ipc, String id, List<String> paths,
                              boolean checked) throws Exception {
        direct.commands().setChecked(paths, checked);
        var params = JSON.createObjectNode().put("checked", checked);
        params.set("paths", JSON.valueToTree(paths));
        var response = ipc.request(id, "setChecked", params);
        assertEquals("response", response.path("type").asText());
        assertTrue(response.path("result").isNull());
        assertTrue(response.path("revision").isIntegralNumber());
    }

    private static void assertSnapshot(Uiport direct, IpcTestClient ipc, String id) throws Exception {
        JsonNode response = ipc.request(id, "workspace", JSON.createObjectNode());
        assertEquals("response", response.path("type").asText());
        // Parse independent record serialization to normalize only Jackson's IntNode/LongNode choice.
        // Exact tree equality preserves every field, array order, null, enum, string and numeric value.
        JsonNode expected = JSON.readTree(JSON.writeValueAsString(direct.queries().workspace()));
        assertEquals(expected, IpcTestClient.expandWorkspace(response.get("result")), "IPC snapshot differs from in-process: " + id);
    }

    @Test
    void realServeStdoutContainsOnlyFramesAndSurvivesBadRequests() throws Exception {
        try (var ipc = new IpcTestClient(tmp.resolve("serve"), "com.mhtextractor.backend.BackendCli",
                "--serve", "--locale", "en")) {
            ipc.ready();
            String[] bad = {"{broken", "{} {}", "", "[]", "null", "{}",
                    "{\"v\":1,\"type\":\"request\",\"id\":\"x\",\"id\":\"y\"}"};
            String[] codes = {"BAD_JSON", "BAD_JSON", "INVALID_REQUEST", "INVALID_REQUEST",
                    "INVALID_REQUEST", "INVALID_REQUEST", "BAD_JSON"};
            for (int i = 0; i < bad.length; i++) {
                ipc.send(bad[i]);
                JsonNode error = ipc.receive();
                assertError(error, codes[i]);
                assertTrue(error.path("id").isNull());
            }
            assertError(ipc.request("unknown", "notAMethod", JSON.createObjectNode()), "UNKNOWN_METHOD");
            ipc.send("{\"v\":2,\"type\":\"request\",\"id\":\"version\",\"method\":\"workspace\",\"params\":{}}");
            JsonNode version = ipc.receive();
            assertError(version, "INVALID_REQUEST");
            assertEquals("version", version.path("id").asText());
            JsonNode before = ipc.request("before", "workspace", JSON.createObjectNode()).get("result");
            assertEquals(JSON.readTree("""
                    {"groups":[],"topLevelItems":[],"canUndo":false,"undoDepth":0,"selection":"NONE","topLevelOrder":[],
                    "details":{"textConfirmed":false,"imageConfirmed":false,"activityGeneration":0,"previewGeneration":0,
                    "loadingPath":null,"importing":false,"importGeneration":0,"running":false,"extractionGeneration":0,
                    "lastStats":null,"sessionOutputFolder":null}}
                    """), IpcTestClient.expandWorkspace(before));
            var params = JSON.createObjectNode().put("checked", true);
            params.putArray("paths").add("unknown");
            assertEquals("response", ipc.request("command", "setChecked", params).path("type").asText());
            assertEquals(before, ipc.request("after", "workspace", JSON.createObjectNode()).get("result"));
            ipc.finish();
        }
    }

    @Test
    void invalidCommandIsRejectedBeforeAnyMutation() throws Exception {
        try (var ipc = new IpcTestClient(tmp.resolve("invalid"), BackendIpcFixture.class.getName())) {
            ipc.ready();
            JsonNode before = ipc.request("before", "workspace", JSON.createObjectNode()).get("result");
            for (String params : List.of("{}", "{\"paths\":[],\"checked\":\"true\"}",
                    "{\"paths\":null,\"checked\":true}",
                    "{\"paths\":[\"" + BackendIpcFixture.ROOT + "a.mht\",42],\"checked\":true}")) {
                assertError(ipc.request("invalid", "setChecked", JSON.readTree(params)), "INVALID_PARAMS");
                assertEquals(before, ipc.request("after", "workspace", JSON.createObjectNode()).get("result"));
            }
            ipc.finish();
        }
    }

    @Test
    void wireUsesUtf8LfAndPipelineOrder() throws Exception {
        String input = hello() + "{\"v\":1,\"type\":\"request\",\"id\":\"中文\",\"method\":\"workspace\",\"params\":{}}\n"
                + "{\"v\":1,\"type\":\"request\",\"id\":\"2\",\"method\":\"workspace\",\"params\":{}}\n";
        var bytes = new ByteArrayOutputStream();
        new IpcServer(BackendIpcFixture.port()).run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), bytes);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("\r"));
        assertTrue(output.endsWith("\n"));
        String[] lines = output.split("\n");
        assertEquals(4, lines.length);
        assertEquals("ready", JSON.readTree(lines[0]).path("event").asText());
        assertEquals("welcome", JSON.readTree(lines[1]).path("result").path("kind").asText());
        assertEquals("中文", JSON.readTree(lines[2]).path("id").asText());
        assertEquals("2", JSON.readTree(lines[3]).path("id").asText());
        assertTrue(output.contains("中文"));
    }

    @Test
    void brokenOutputPropagatesInsteadOfSilentlyContinuing() {
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("disconnected"); }
        };
        assertThrows(IOException.class, () -> new IpcServer(BackendIpcFixture.port())
                .run(new ByteArrayInputStream(new byte[0]), broken));
    }

    private static void assertError(JsonNode frame, String code) {
        assertEquals("error", frame.path("type").asText());
        assertEquals(code, frame.path("error").path("code").asText());
        assertFalse(frame.path("error").path("message").asText().isEmpty());
        assertFalse(frame.has("result"));
    }

    static String hello() throws Exception {
        BackendIdentity identity = BackendIdentity.current();
        var frame = JSON.createObjectNode().put("v", 1).put("type", "request").put("id", "hello").put("method", "hello");
        frame.putObject("params").put("protocolVersion", identity.protocolVersion())
                .put("version", identity.version()).put("buildId", identity.buildId());
        return JSON.writeValueAsString(frame) + "\n";
    }

    @Test void handshakeIsRequiredAndMismatchRevokesAgreement() throws Exception {
        BackendIdentity identity = BackendIdentity.current();
        try (var ipc = new IpcTestClient(tmp.resolve("handshake"), BackendCli.class.getName(), "--serve")) {
            ipc.readyWithoutHandshake();
            assertError(ipc.request("before-hello", "workspace", JSON.createObjectNode()), "HANDSHAKE_FAILED");
            var params = JSON.createObjectNode().put("protocolVersion", identity.protocolVersion())
                    .put("version", identity.version()).put("buildId", identity.buildId());
            JsonNode welcome = ipc.request("match", "hello", params);
            System.out.println("HANDSHAKE WIRE: " + welcome);
            assertEquals("response", welcome.path("type").asText());
            JsonNode result = welcome.path("result");
            assertEquals("welcome", result.path("kind").asText());
            assertEquals(identity.protocolVersion(), result.path("protocolVersion").intValue());
            assertEquals(2, result.path("protocolVersion").intValue());
            assertEquals(identity.version(), result.path("version").asText());
            assertEquals(identity.buildId(), result.path("buildId").asText());
            assertEquals(JSON.readTree("[\"workspace\",\"setChecked\",\"workspacePage\",\"bulkFiles\",\"confirmationRoundTrip\",\"startExtraction\",\"cancelExtraction\",\"setTitleOverride\",\"applyParamsToTargets\",\"parameterState\",\"preStartState\",\"presentation\",\"exportSource\",\"cacheState\",\"listNamedConfigs\",\"loadNamedConfig\",\"saveNamedConfig\",\"deleteNamedConfig\"]"), result.path("capabilities"));
            assertTrue(result.path("confirmationRoundTrip").booleanValue());
            for (String field : List.of("protocolVersion", "version", "buildId")) {
                var bad = params.deepCopy();
                if (field.equals("protocolVersion")) bad.put(field, identity.protocolVersion() + 1); else bad.put(field, "tampered");
                JsonNode rejected = ipc.request("mismatch", "hello", bad);
                assertError(rejected, "HANDSHAKE_FAILED");
                assertTrue(rejected.path("error").path("message").asText().contains("mismatch"));
                assertError(ipc.request("blocked", "workspace", JSON.createObjectNode()), "HANDSHAKE_FAILED");
                assertEquals("response", ipc.request("recover", "hello", params).path("type").asText());
            }
            assertError(ipc.request("bad-hello", "hello", JSON.createObjectNode()), "INVALID_PARAMS");
            assertError(ipc.request("still-blocked", "workspace", JSON.createObjectNode()), "HANDSHAKE_FAILED");
            assertEquals("response", ipc.request("again", "hello", params).path("type").asText());
            assertEquals("response", ipc.request("after", "workspace", JSON.createObjectNode()).path("type").asText());
            ipc.finish();
        }
    }
}
