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
            assertTrue(events.isEmpty(), "setChecked must preserve silent port semantics");
            ipc.finish();
        }
    }

    private static void check(Uiport direct, IpcTestClient ipc, String id, List<String> paths,
                              boolean checked) throws Exception {
        direct.commands().setChecked(paths, checked);
        var params = JSON.createObjectNode().put("checked", checked);
        params.set("paths", JSON.valueToTree(paths));
        var response = ipc.request(id, "setChecked", params);
        assertEquals(JSON.readTree("{\"v\":1,\"type\":\"response\",\"id\":\"" + id + "\",\"result\":null}"), response);
    }

    private static void assertSnapshot(Uiport direct, IpcTestClient ipc, String id) throws Exception {
        JsonNode response = ipc.request(id, "workspace", JSON.createObjectNode());
        assertEquals("response", response.path("type").asText());
        // Parse independent record serialization to normalize only Jackson's IntNode/LongNode choice.
        // Exact tree equality preserves every field, array order, null, enum, string and numeric value.
        JsonNode expected = JSON.readTree(JSON.writeValueAsString(direct.queries().workspace()));
        assertEquals(expected, response.get("result"), "IPC snapshot differs from in-process: " + id);
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
                    {"groups":[],"topLevelItems":[],"canUndo":false,"undoDepth":0,"selection":"NONE"}
                    """), before);
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
        String input = "{\"v\":1,\"type\":\"request\",\"id\":\"中文\",\"method\":\"workspace\",\"params\":{}}\n"
                + "{\"v\":1,\"type\":\"request\",\"id\":\"2\",\"method\":\"workspace\",\"params\":{}}\n";
        var bytes = new ByteArrayOutputStream();
        new IpcServer(BackendIpcFixture.port()).run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), bytes);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("\r"));
        assertTrue(output.endsWith("\n"));
        String[] lines = output.split("\n");
        assertEquals(3, lines.length);
        assertEquals("ready", JSON.readTree(lines[0]).path("event").asText());
        assertEquals("中文", JSON.readTree(lines[1]).path("id").asText());
        assertEquals("2", JSON.readTree(lines[2]).path("id").asText());
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
}
