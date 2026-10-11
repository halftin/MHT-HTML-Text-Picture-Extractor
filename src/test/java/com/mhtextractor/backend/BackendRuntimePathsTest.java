package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime path policies through the real CLI process; no IpcFiles access. */
class BackendRuntimePathsTest {
    @TempDir Path work;
    private final List<JsonNode> events = new java.util.ArrayList<>();

    private JsonNode request(IpcTestClient client, String id, String method, JsonNode params) throws Exception {
        var frame = JSON.createObjectNode().put("v", 1).put("type", "request").put("id", id).put("method", method);
        frame.set("params", params);
        client.send(JSON.writeValueAsString(frame));
        for (int i = 0; i < 100; i++) {
            JsonNode received = client.receive();
            if (received.path("id").asText().equals(id)) return received;
            assertEquals("event", received.path("type").asText(), received.toString());
            events.add(received);
        }
        throw new AssertionError("No response for " + id);
    }

    private JsonNode paths(Path... paths) {
        return JSON.createObjectNode().set("paths", JSON.valueToTree(
                java.util.Arrays.stream(paths).map(Path::toString).toList()));
    }

    private Path source(Path parent) throws Exception {
        Files.createDirectories(parent);
        return Files.writeString(parent.resolve("sample.html"), "<html><body>runtime path sentinel</body></html>");
    }

    @Test void optionalAllowlistRejectsOutsideAndAcceptsBothConfiguredRoots() throws Exception {
        Path first = Files.createDirectories(work.resolve("allowed-one"));
        Path second = Files.createDirectories(work.resolve("allowed-two"));
        Path outside = source(work.resolve("outside"));
        Path input = source(first);
        Path output = second.resolve("output");
        try (var client = new IpcTestClient(work.resolve("client"), work.resolve("temp"),
                first + File.pathSeparator + second, BackendCli.class.getName(), "--serve", "--locale", "en")) {
            client.ready();
            assertEquals("INVALID_PARAMS", request(client, "outside", "importPaths", paths(outside))
                    .path("error").path("code").asText());
            assertFalse(request(client, "inside", "importPaths", paths(input)).has("error"));
            assertEquals("INVALID_PARAMS", request(client, "relative", "importPaths", paths(Path.of("relative.html")))
                    .path("error").path("code").asText());
            Path forbidden = outside.getParent().resolve("output");
            assertEquals("INVALID_PARAMS", request(client, "bad-output", "extract", JSON.createObjectNode()
                    .put("checkedOnly", false).put("outputFolder", forbidden.toString())).path("error").path("code").asText());
            assertFalse(Files.exists(forbidden));
            assertFalse(request(client, "extract", "extract", JSON.createObjectNode()
                    .put("checkedOnly", false).put("outputFolder", output.toString())).has("error"));
            assertTrue(events.stream().anyMatch(e -> e.path("event").asText().equals("extractionTerminal")
                    && e.path("data").path("end").asText().equals("SUCCEEDED")));
            assertTrue(Files.isDirectory(output));
            client.finish();
        }
    }

    @Test void nonHAbsoluteTempAndInputsWorkWithoutAllowlistAndCleanupRemainsOwned() throws Exception {
        // A drive alias keeps all physical artifacts on H while exercising a non-H spelling.
        String drive = java.util.stream.IntStream.rangeClosed('J', 'Z')
                .mapToObj(c -> (char) c + ":").filter(d -> !Files.exists(Path.of(d + "/")))
                .findFirst().orElseThrow();
        runSubst(drive, work.toString());
        try {
            Path alias = Path.of(drive + "/");
            Path input = source(alias.resolve("input"));
            Path configured = alias.resolve("Backend/temp/not-created-yet");
            Path session;
            try (var client = new IpcTestClient(work.resolve("client"), configured, null,
                    BackendCli.class.getName(), "--serve", "--locale", "en")) {
                client.ready();
                assertFalse(request(client, "import", "importPaths", paths(input)).has("error"));
                assertEquals("INVALID_PARAMS", request(client, "relative-output", "extract", JSON.createObjectNode()
                        .put("checkedOnly", false).put("outputFolder", "relative-output")).path("error").path("code").asText());
                JsonNode preview = request(client, "preview", "previewFiles", JSON.createObjectNode().put("path", input.toString()));
                assertFalse(preview.has("error"), preview.toString());
                Path manifest = Path.of(preview.path("result").path("manifest").path("path").asText());
                session = manifest.getParent();
                assertEquals(configured.toRealPath(), session.getParent());
                Path text = Path.of(JSON.readTree(Files.readString(manifest)).path("text").path("path").asText());
                assertTrue(Files.readString(text).contains("runtime path sentinel"));
                Path unissued = Files.writeString(session.resolve("not-issued.txt"), "unissued");
                assertEquals("INVALID_PARAMS", request(client, "unissued", "releaseFiles", paths(unissued))
                        .path("error").path("code").asText());
                assertFalse(request(client, "release", "releaseFiles", paths(text)).has("error"));
                assertFalse(Files.exists(text));
                assertEquals("INVALID_PARAMS", request(client, "released", "releaseFiles", paths(text))
                        .path("error").path("code").asText());
                assertTrue(Files.exists(input));
                assertEquals("SUCCEEDED", request(client, "extract", "extract", JSON.createObjectNode()
                        .put("checkedOnly", false).put("outputFolder", alias.resolve("output").toString()))
                        .path("result").path("end").asText());
                Process junction = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                        session.resolve("external-link").toString(), input.getParent().toString())
                        .redirectErrorStream(true).start();
                assertTrue(junction.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, junction.exitValue(), new String(junction.getInputStream().readAllBytes()));
                client.finish();
            }
            assertFalse(Files.exists(session));
            assertTrue(Files.exists(input));
        } finally {
            runSubst(drive, "/D");
        }
    }

    private static void runSubst(String drive, String target) throws Exception {
        Process process = new ProcessBuilder("subst.exe", drive, target).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
