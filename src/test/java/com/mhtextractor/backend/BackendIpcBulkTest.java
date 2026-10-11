package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.app.SourceParseService;
import com.mhtextractor.model.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

class BackendIpcBulkTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII=";
    private static final String HTML = "<html><body><p>bulk text sentinel</p><img src=\"data:image/png;base64," + PNG + "\"></body></html>";

    @Test void chineseNestedTempRootPreservesPathsAndCleansSession() throws Exception {
        String original = System.getProperty("java.io.tmpdir");
        Path parent = Files.createTempDirectory(Path.of(original), "p4-4-temp-");
        Path configured = parent.resolve("中文 临时（目录）").resolve("嵌套");
        Path sessionRoot;
        try {
            System.setProperty("java.io.tmpdir", configured.toString());
            try (var files = new IpcFiles(JSON)) {
                sessionRoot = files.root;
                assertEquals(configured.toRealPath(), sessionRoot.getParent());
                JsonNode ref = files.write(".txt", "中文路径往返".getBytes(StandardCharsets.UTF_8), "text/plain");
                Path issued = files.validateIssued(ref.path("path").asText());
                assertEquals("中文路径往返", Files.readString(issued));
                assertTrue(issued.startsWith(sessionRoot));
            }
            assertFalse(Files.exists(sessionRoot), "Session cleanup must use the configured Unicode root");
        } finally {
            System.setProperty("java.io.tmpdir", original);
            Files.delete(configured);
            Files.delete(configured.getParent());
            Files.delete(parent);
        }
    }

    @Test void tempRootRejectsRelativePaths() throws Exception {
        String original = System.getProperty("java.io.tmpdir");
        try {
            for (String bad : List.of("temp-中文", "temp/../mht/中文")) {
                System.setProperty("java.io.tmpdir", bad);
                assertThrows(IOException.class, () -> new IpcFiles(JSON), bad);
            }
        } finally {
            System.setProperty("java.io.tmpdir", original);
        }
    }

    @Test void absoluteTempJunctionUsesRealSessionRoot() throws Exception {
        String original = System.getProperty("java.io.tmpdir");
        Path parent = Files.createTempDirectory(Path.of(original), "p4-4-link-");
        Path target = Files.createDirectories(parent.resolve("target"));
        Path link = parent.resolve("中文重定向");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(),
                target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        try {
            System.setProperty("java.io.tmpdir", link.toString());
            try (var files = new IpcFiles(JSON)) {
                assertEquals(target.toRealPath(), files.root.getParent());
            }
        } finally {
            System.setProperty("java.io.tmpdir", original);
            Files.delete(link); // Remove only the junction, never its target.
            Files.delete(target);
            Files.delete(parent);
        }
    }

    private Path source() throws IOException {
        Path work = Files.createTempDirectory(Path.of("H:/TEST/mht"), "ipc-bulk-test-");
        Path input = work.resolve("input.html");
        Files.writeString(input, HTML);
        return input;
    }

    private String request(String id, String method, JsonNode params) throws Exception {
        var frame = JSON.createObjectNode().put("v", 1).put("type", "request").put("id", id).put("method", method);
        frame.set("params", params);
        return JSON.writeValueAsString(frame) + "\n";
    }

    private List<JsonNode> run(IpcSession session, String input) throws Exception {
        input = BackendIpcContractTest.hello() + input;
        String lastId = JSON.readTree(input.strip().lines().reduce((a, b) -> b).orElseThrow()).path("id").asText();
        var pipe = new java.io.PipedInputStream(65536);
        var client = new java.io.PipedOutputStream(pipe);
        client.write(input.getBytes(StandardCharsets.UTF_8));
        var currentLine = new ByteArrayOutputStream();
        var out = new ByteArrayOutputStream() {
            @Override public synchronized void write(int value) {
                super.write(value);
                currentLine.write(value);
                if (value == '\n') {
                    try {
                        if (JSON.readTree(currentLine.toByteArray()).path("id").asText().equals(lastId)) client.close();
                        currentLine.reset();
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
            }
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                for (int i = offset; i < offset + length; i++) write(bytes[i]);
            }
        };
        try (pipe; client) { new IpcServer(session).run(pipe, out); }
        return out.toString(StandardCharsets.UTF_8).lines().map(line -> {
            try { return JSON.readTree(line); } catch (Exception e) { throw new AssertionError(line, e); }
        }).toList();
    }

    private JsonNode response(List<JsonNode> frames, String id) {
        return frames.stream().filter(frame -> frame.path("id").asText().equals(id)).findFirst().orElseThrow();
    }

    @Test void previewBodyAndThumbnailsTravelOnlyThroughSessionFiles() throws Exception {
        Path source = source();
        Path root;
        try (var session = new IpcSession(new ObjectMapper())) {
            var frames = run(session, request("i", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))))
                    + request("p", "previewFiles", JSON.createObjectNode().put("path", source.toString())));
            assertTrue(frames.stream().noneMatch(frame -> frame.toString().contains("bulk text sentinel")));
            JsonNode result = response(frames, "p").path("result");
            assertEquals(1, result.path("imageCount").asInt());
            Path manifestPath = Path.of(result.path("manifest").path("path").asText());
            root = manifestPath.getParent();
            JsonNode manifest = JSON.readTree(Files.readString(manifestPath));
            Path text = Path.of(manifest.path("text").path("path").asText());
            assertEquals(SourceParseService.parse(source.toString(), new AppConfig(), true).text(), Files.readString(text));
            assertEquals(HTML, Files.readString(Path.of(manifest.path("html").path("path").asText())));
            Path image = Path.of(manifest.path("thumbnails").get(0).path("path").asText());
            assertArrayEquals(java.util.Base64.getDecoder().decode(PNG), Files.readAllBytes(image));
            assertTrue(manifestPath.startsWith(root));
            assertTrue(image.startsWith(root));
            assertEquals(Files.size(text), manifest.path("text").path("bytes").asLong());
            assertTrue(frames.stream().filter(f -> f.path("type").asText().equals("event"))
                    .noneMatch(f -> f.path("data").has("paths") || f.path("data").has("items")));
        }
        assertFalse(Files.exists(root), "Closing the session must remove all issued bulk files and caches");
    }

    @Test void pathAllowlistRejectsOutsideInputTraversalAndOutputBeforeMutation() throws Exception {
        Path source = source();
        String original = System.getProperty("mht.allow.roots");
        System.setProperty("mht.allow.roots", "H:/TEST/mht");
        String outside = Path.of("CLAUDE.md").toAbsolutePath().toString();
        try (var session = new IpcSession(new ObjectMapper())) {
            var frames = run(session, request("outside", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(outside))))
                    + request("traversal", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of("H:/TEST/mht/../../outside.html"))))
                    + request("unknown", "previewFiles", JSON.createObjectNode().put("path", source.toString()))
                    + request("i", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))))
                    + request("out", "extract", JSON.createObjectNode().put("checkedOnly", false).put("outputFolder", "C:/mht-ipc-forbidden"))
                    + request("w", "workspace", JSON.createObjectNode()));
            for (String id : List.of("outside", "traversal", "unknown", "out")) {
                assertEquals("INVALID_PARAMS", response(frames, id).path("error").path("code").asText(), id);
            }
            assertEquals(1, response(frames, "w").path("result").path("topLevelItems").size());
            assertTrue(frames.stream().noneMatch(frame -> frame.path("event").asText().equals("extractionTerminal")));
        } finally {
            if (original == null) System.clearProperty("mht.allow.roots");
            else System.setProperty("mht.allow.roots", original);
        }
    }

    @Test void registryRejectsUnissuedPathsAndAnotherSessionAndValidatesReleaseAtomically() throws Exception {
        try (var files = new IpcFiles(JSON); var other = new IpcFiles(JSON)) {
            Path root = files.root;
            JsonNode ref = files.write(".txt", "owned".getBytes(StandardCharsets.UTF_8), "text/plain");
            Path issued = Path.of(ref.path("path").asText());
            Path unissued = root.resolve("unissued.txt");
            Files.writeString(unissued, "unissued");
            assertThrows(IllegalArgumentException.class, () -> files.validateIssued(unissued.toString()));
            assertThrows(IllegalArgumentException.class, () -> other.validateIssued(issued.toString()));
            assertThrows(IllegalArgumentException.class, () -> files.release(Path.of("CLAUDE.md").toAbsolutePath().toString()));
            assertEquals("owned", Files.readString(files.validateIssued(issued.toString())));
            files.release(issued.toString());
            assertFalse(Files.exists(issued));
            assertThrows(IllegalArgumentException.class, () -> files.validateIssued(issued.toString()));
        }
        Path source = source();
        try (var session = new IpcSession(JSON)) {
            session.events(event -> { });
            session.call("importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))));
            JsonNode preview = session.call("previewFiles", JSON.createObjectNode().put("path", source.toString()));
            String issued = preview.path("manifest").path("path").asText();
            var frames = run(session, request("release", "releaseFiles", JSON.createObjectNode()
                    .set("paths", JSON.valueToTree(List.of(issued, source.toString())))));
            assertEquals("INVALID_PARAMS", response(frames, "release").path("error").path("code").asText());
            assertTrue(Files.exists(Path.of(issued)), "One bad entry must reject the entire deletion list");
        }
    }

    @Test void pagingKeepsPortOrderAndBoundsAndRefreshesAfterSilentMutation() throws Exception {
        try (var session = new IpcSession(JSON)) {
            Path source = source();
            var frames = run(session, request("i", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))))
                    + request("p1", "workspacePage", JSON.createObjectNode().put("offset", 0).put("limit", 1))
                    + request("c", "setChecked", JSON.createObjectNode().put("checked", true).set("paths", JSON.valueToTree(List.of(source.toString()))))
                    + request("p2", "workspacePage", JSON.createObjectNode().put("offset", 0).put("limit", 1))
                    + request("bad", "workspacePage", JSON.createObjectNode().put("offset", -1).put("limit", 1))
                    + request("large", "workspacePage", JSON.createObjectNode().put("offset", 0).put("limit", 257))
                    + request("overflow", "workspacePage", JSON.createObjectNode().put("offset", Integer.MAX_VALUE).put("limit", 256)));
            assertFalse(response(frames, "p1").path("result").path("items").get(0).path("checked").asBoolean());
            JsonNode expected = JSON.readTree(JSON.writeValueAsString(session.port.queries().workspace().topLevelItems().getFirst()));
            assertEquals(expected, response(frames, "p2").path("result").path("items").get(0));
            for (String id : List.of("bad", "large")) assertEquals("INVALID_PARAMS", response(frames, id).path("error").path("code").asText());
            assertTrue(response(frames, "overflow").path("result").path("items").isEmpty());
        }
    }

    @Test void directoryAndIndividualHtmlRejectSidecarJunctionOutsideAllowlist() throws Exception {
        Path source = source();
        String original = System.getProperty("mht.allow.roots");
        System.setProperty("mht.allow.roots", "H:/TEST/mht");
        Path link = source.getParent().resolve("input_files");
        Process junction = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(),
                Path.of(".").toAbsolutePath().normalize().toString()).redirectErrorStream(true).start();
        String output = new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(junction.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, junction.exitValue(), output);
        try {
            assertThrows(IllegalArgumentException.class, () -> IpcFiles.input(source.toString()));
            assertThrows(IllegalArgumentException.class, () -> IpcFiles.input(source.getParent().toString()));
        } finally {
            if (original == null) System.clearProperty("mht.allow.roots");
            else System.setProperty("mht.allow.roots", original);
            Files.delete(link); // Delete only the junction itself; never traverse its target.
        }
    }

    @Test void brokenEventOutputCancelsWorkerAndStillCleansSession() throws Exception {
        Path source = source();
        Path root;
        try (var session = new IpcSession(JSON)) {
            session.events(event -> { });
            session.call("importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))));
            JsonNode preview = session.call("previewFiles", JSON.createObjectNode().put("path", source.toString()));
            root = Path.of(preview.path("manifest").path("path").asText()).getParent();
            var input = BackendIpcContractTest.hello() + request("extract", "extract", JSON.createObjectNode().put("checkedOnly", false)
                    .put("outputFolder", source.getParent().resolve("output").toString()));
            OutputStream broken = new OutputStream() {
                int newlines;
                @Override public void write(int value) throws IOException {
                    if (newlines >= 2) throw new IOException("disconnect after welcome");
                    if (value == '\n') newlines++;
                }
            };
            assertThrows(IOException.class, () -> new IpcServer(session).run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), broken));
        }
        assertFalse(Files.exists(root));
    }
}
