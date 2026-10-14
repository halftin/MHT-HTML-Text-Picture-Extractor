package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.app.ipc.IpcCodec;
import com.mhtextractor.app.ipc.IpcFilesClient;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.uiport.PortEvent;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class BackendEventsTest {
    static final long STEP = 10L * 1024 * 1024 * 1024;
    @TempDir Path work;
    final ObjectMapper json = new ObjectMapper();

    static void sparse(Path file, long size) throws Exception {
        Files.deleteIfExists(file);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(size - 1); channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertEquals(size, Files.size(file));
    }

    @Test void exactTenGiBBoundaryAboveAndFallThenRecross() throws Exception {
        var root = Files.createDirectory(work.resolve("cache"));
        var lifecycle = new PreviewCacheLifecycle(new PreviewCacheStore(root));
        var steps = new ArrayList<Integer>();
        lifecycle.setSizeAlertHook(steps::add);
        Path pad = root.resolve("pad");
        sparse(pad, STEP - 1); lifecycle.checkSizeAlert(); assertTrue(steps.isEmpty());
        sparse(pad, STEP); lifecycle.checkSizeAlert(); assertEquals(List.of(1), steps);
        sparse(pad, STEP + 1); lifecycle.checkSizeAlert(); lifecycle.checkSizeAlert();
        assertEquals(List.of(1), steps); assertEquals(STEP + 1, lifecycle.lastObservedTotalBytes());
        sparse(pad, STEP - 1); lifecycle.checkSizeAlert(); assertEquals(List.of(1), steps);
        sparse(pad, STEP); lifecycle.checkSizeAlert(); assertEquals(List.of(1, 1), steps);
        lifecycle.onShutdown(() -> { }); assertFalse(Files.exists(pad));
        sparse(pad, STEP); lifecycle.checkSizeAlert(); assertEquals(List.of(1, 1, 1), steps);
    }

    @Test void infoAndDebugAreFilteredAndWarnErrorKeepKeyArguments() throws Exception {
        var events = new ArrayList<PortEvent.BackendLog>();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("p5a7.levels");
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (var forwarder = new BackendLogForwarder(events::add)) {
            logger.info(I18n.msg("log.previewcachelifecycle.04", "info-sentinel"));
            logger.debug(I18n.msg("log.previewcachelifecycle.04", "debug-sentinel"));
            logger.warn(I18n.msg("log.previewcachelifecycle.04", "warn-sentinel"));
            logger.error(I18n.msg("log.previewcachelifecycle.04", "error-sentinel"));
            assertEquals(List.of("WARN", "ERROR"), events.stream().map(PortEvent.BackendLog::level).toList());
            assertEquals("log.previewcachelifecycle.04", events.getFirst().key());
            assertEquals("warn-sentinel", events.getFirst().namedParams().get("0"));
            assertEquals("error-sentinel", events.getLast().namedParams().get("0"));
        } finally { logger.setLevel(previousLevel); }
        logger.warn(I18n.msg("log.previewcachelifecycle.04", "after-close"));
        assertEquals(2, events.size());
    }

    @Test void nestedWarningKeepsItsKeyInsteadOfBackendRenderedText() {
        var events = new ArrayList<PortEvent.BackendLog>();
        var logger = LoggerFactory.getLogger("p5a7.nested");
        try (var forwarder = new BackendLogForwarder(events::add)) {
            var warning = new com.mhtextractor.model.ExtractWarning("extract.adFiltered", java.util.Map.of("name", "image.png"));
            logger.warn(I18n.msg("log.region.warning", "input.mht", I18n.render(warning)));
            var params = events.getFirst().namedParams();
            assertEquals("input.mht", params.get("0")); assertEquals("", params.get("1"));
            assertEquals("extract.adFiltered", params.get("$key.1"));
            assertEquals("image.png", params.get("$arg.1.name"));
            assertFalse(params.containsValue(I18n.render(warning)));
            logger.warn("unstructured-sentinel");
            assertEquals("log.backend.unstructured", events.getLast().key());
            assertEquals("p5a7.nested", events.getLast().namedParams().get("0"));
            assertFalse(events.getLast().namedParams().containsValue("unstructured-sentinel"));
        }
    }

    @Test void productionHookEmitsCodecReadyMeasurementAndSessionRemovesAllIssuedFiles() throws Exception {
        Path source = Files.writeString(work.resolve("input.html"), "<html><body>test</body></html>");
        Path root;
        try (var session = new IpcSession(json)) {
            root = session.files().root;
            var frames = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
            session.events(frames::add);
            session.call("importPaths", json.createObjectNode().set("paths", json.valueToTree(List.of(source.toString()))));
            Path cache = Path.of(session.call("cacheState", json.createObjectNode()).path("root").asText());
            Files.createDirectories(cache); sparse(cache.resolve("pad"), STEP);
            session.call("previewFiles", json.createObjectNode().put("path", source.toString()));
            var alerts = frames.stream().filter(f -> f.path("event").asText().equals("cacheSizeAlert")).toList();
            assertEquals(1, alerts.size());
            var data = alerts.getFirst().withObject("data").put("revision", 17);
            var codec = new IpcCodec(json, new IpcFilesClient(json, (m,p) -> CompletableFuture.completedFuture(json.nullNode())));
            var alert = (PortEvent.CacheSizeAlert) codec.event("cacheSizeAlert", data);
            assertEquals(1, alert.step()); assertTrue(alert.actualBytes() >= STEP);
            assertEquals(STEP, alert.stepBytes()); assertEquals(cache.toString(), alert.root());
            assertEquals("cache.sizeAlert", alert.key()); assertEquals("WARN", alert.level()); assertEquals(17, alert.revision());
            var export = session.call("exportSource", json.createObjectNode().put("path", source.toString()));
            assertEquals("success", export.path("status").asText());
            assertTrue(Path.of(export.path("htmlFile").path("path").asText()).startsWith(root));
            assertTrue(Files.exists(root));
            Files.writeString(root.resolve("unissued.tmp"), "orphan");
            session.files().write(".txt", new byte[]{1}, "text/plain");
            System.out.println("P5A7_CLEANUP_BEFORE root=" + root + " exists=" + Files.exists(root));
        }
        System.out.println("P5A7_CLEANUP_AFTER root=" + root + " exists=" + Files.exists(root));
        assertFalse(Files.exists(root), "Session must remove cache, exports, issued and orphan files");
        assertTrue(Files.exists(source));
    }

    @Test void backendLogCodecRequiresRevisionAndDoesNotDependOnLocale() throws Exception {
        var codec = new IpcCodec(json, new IpcFilesClient(json, (m,p) -> CompletableFuture.completedFuture(json.nullNode())));
        var data = json.createObjectNode().put("key", "log.previewcachelifecycle.04")
                .put("level", "WARN").put("timestamp", 123).put("revision", 4);
        data.putObject("namedParams").put("0", "sentinel");
        var log = (PortEvent.BackendLog) codec.event("backendLog", data);
        assertEquals("sentinel", log.namedParams().get("0")); assertEquals(4, log.revision());
        data.remove("revision"); assertThrows(IllegalArgumentException.class, () -> codec.event("backendLog", data));
    }

    @Test void actualProcessDeliversOnlyWarnErrorAndCacheEventWithIncreasingRevision() throws Exception {
        Path source = Files.writeString(work.resolve("wire.html"), "<html><body>wire</body></html>");
        Path root;
        try (var ipc = new IpcTestClient(work.resolve("wire"), BackendEventsWireFixture.class.getName())) {
            ipc.ready();
            var warn = ipc.receive(); var error = ipc.receive();
            assertEquals("backendLog", warn.path("event").asText());
            assertEquals("WARN", warn.path("data").path("level").asText());
            assertEquals("WARN_SENTINEL", warn.path("data").path("namedParams").path("0").asText());
            assertEquals("ERROR", error.path("data").path("level").asText());
            assertTrue(error.path("data").path("revision").asLong() > warn.path("data").path("revision").asLong());
            ipc.request("import", "importPaths", json.createObjectNode().set("paths", json.valueToTree(List.of(source.toString()))));
            var preview = ipc.request("preview", "previewFiles", json.createObjectNode().put("path", source.toString()));
            var events = ipc.drainRequestEvents();
            var alerts = events.stream().filter(f -> f.path("event").asText().equals("cacheSizeAlert")).toList();
            assertEquals(1, alerts.size());
            assertTrue(alerts.getFirst().path("data").path("revision").asLong() > error.path("data").path("revision").asLong());
            assertTrue(alerts.getFirst().path("data").path("actualBytes").asLong() >= STEP);
            assertFalse(events.stream().anyMatch(f -> f.path("data").path("level").asText().equals("INFO")));
            root = Path.of(preview.path("result").path("manifest").path("path").asText()).getParent();
            ipc.finish();
        }
        assertFalse(Files.exists(root));
    }

    @Test void cleanupTimeoutIsBoundedAndFailureDoesNotEscapeSessionClose() throws Exception {
        var files = new IpcFiles(json);
        files.write(".txt", new byte[]{1}, "text/plain");
        var entered = new java.util.concurrent.CountDownLatch(1);
        long start = System.nanoTime();
        assertThrows(java.io.IOException.class, () -> files.close(() -> {
            entered.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
        }));
        assertEquals(0, entered.getCount());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3500);
        // An actual invalid owner root makes tree deletion fail; closing still returns.
        var session = new IpcSession(json);
        Path root = session.files().root;
        Files.delete(root); Files.writeString(root, "replaced-root");
        Files.setAttribute(root, "dos:readonly", true);
        assertDoesNotThrow(session::close);
        assertTrue(Files.exists(root), "Failure must leave observable evidence, while close still returns");
        Files.setAttribute(root, "dos:readonly", false);
        Files.deleteIfExists(root);
    }
}
