package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Real transport, public port observation and file-schema oracle, including elapsed summary text. */
class BackendIpcExtractionTest {
    @TempDir Path tmp;
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ObjectNode runtime(Path output, boolean mode) {
        return object().putNull("inputFolder").put("outputFolder", output.toString())
                .put("imageModeEnabled", mode).put("openFolderAfterComplete", false);
    }
    private static Set<String> keys(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
    private static JsonNode read(JsonNode ref) throws Exception {
        assertEquals(Set.of("path", "bytes", "mediaType"), keys(ref));
        Path path = Path.of(ref.path("path").asText());
        assertTrue(path.isAbsolute());
        assertEquals(Files.size(path), ref.path("bytes").longValue());
        assertEquals("application/json", ref.path("mediaType").asText());
        return JSON.readTree(Files.readAllBytes(path));
    }

    private final class Client implements AutoCloseable {
        final Path work;
        final IpcTestClient ipc;
        final List<JsonNode> events = new ArrayList<>();
        long revision;
        Client(String name, Path source) throws Exception {
            work = tmp.resolve(name);
            ipc = new IpcTestClient(work, BackendIpcExtractionFixture.class.getName());
            var ready = ipc.receive();
            System.out.println("EXTRACTION READY: " + ready);
            for (String method : List.of("extract", "startExtraction", "cancelExtraction"))
                assertTrue(ready.path("data").path("methods").toString().contains('"' + method + '"'));
            var identity = BackendIdentity.current();
            var welcome = call("hello", object().put("protocolVersion", identity.protocolVersion())
                    .put("version", identity.version()).put("buildId", identity.buildId()));
            System.out.println("EXTRACTION WELCOME: " + welcome);
            for (String method : List.of("startExtraction", "cancelExtraction"))
                assertTrue(welcome.path("capabilities").toString().contains('"' + method + '"'));
            call("importPaths", object().set("paths", JSON.valueToTree(List.of(source.toString()))));
            events.clear();
        }
        void collect(JsonNode frame) {
            if (!frame.path("type").asText().equals("event")) return;
            assertTrue(frame.path("data").path("revision").isIntegralNumber(), frame.toString());
            assertTrue(frame.path("data").path("revision").longValue() > revision, frame.toString());
            revision = frame.path("data").path("revision").longValue();
            events.add(frame);
        }
        JsonNode call(String method, JsonNode params) throws Exception {
            var response = ipc.request(method, method, params);
            ipc.drainRequestEvents().forEach(this::collect);
            assertEquals("response", response.path("type").asText(), response.toString());
            return response.path("result");
        }
        void start(boolean mode) throws Exception {
            assertTrue(call("startExtraction", object().put("checkedOnly", false)
                    .set("runtime", runtime(tmp.resolve("async-output"), mode))).isNull());
        }
        List<JsonNode> named(String event) {
            return events.stream().filter(e -> e.path("event").asText().equals(event)).toList();
        }
        JsonNode until(String event) throws Exception {
            while (named(event).isEmpty()) collect(ipc.receive(10));
            return named(event).getLast().path("data");
        }
        void settled() throws Exception {
            call("metrics", object()); // Host barrier: all direct observers for preceding events have returned.
        }
        JsonNode direct(long generation, String kind) throws Exception {
            var values = JSON.readTree(Files.readAllBytes(work.resolve("direct-events.json")));
            var matches = new ArrayList<JsonNode>();
            for (var value : values) if (value.path("event").asText().equals(kind)
                    && value.path("data").path("generation").longValue() == generation) matches.add(value.path("data"));
            assertEquals(1, matches.size(), "direct " + kind + " generation=" + generation);
            return matches.getFirst();
        }
        void statsEqual(JsonNode data, String kind) throws Exception {
            var expected = direct(data.path("generation").longValue(), kind);
            var stats = read(data.path("stats"));
            assertEquals(expected.path("stats"), stats, "All stats fields, file order, warnings and summary must survive IPC");
            assertEquals(Set.of("summaryKey", "summaryParams", "elapsedMillis", "totalFiles", "successCount",
                    "failCount", "skipCount", "warnCount", "warnDetails", "files"), keys(stats));
            for (var file : stats.path("files")) {
                assertEquals(Set.of("path", "outcome", "messageKey", "messageParams", "messageDetail", "warnings"), keys(file));
                assertTrue(file.path("warnings").isArray());
                for (var warning : file.path("warnings")) {
                    assertEquals(Set.of("key", "args"), keys(warning));
                    assertTrue(warning.path("args").isObject());
                }
            }
            assertEquals(expected.path("sessionOutputFolder"), data.path("sessionOutputFolder"));
            assertFalse(data.toString().contains("pixels"));
            assertFalse(data.has("files"));
            System.out.println("FULL STATS " + kind + ": " + data + " FILE=" + stats);
        }
        void exactlyOnce(long generation, boolean cancelled) throws Exception {
            settled();
            assertEquals(1, named("extractionTerminal").size(), "terminal exactly once, generation=" + generation);
            assertEquals(cancelled ? 1 : 0, named("extractionResult").size(), "partial exactly once only for cancellation");
            runningPair(generation);
        }
        void runningPair(long generation) {
            var running = named("runningChanged");
            assertEquals(2, running.size());
            assertTrue(running.getFirst().path("data").path("running").asBoolean());
            assertFalse(running.getLast().path("data").path("running").asBoolean());
            for (var e : running) assertEquals(generation, e.path("data").path("generation").longValue());
        }
        @Override public void close() throws Exception { ipc.close(); }
    }

    private Path html() throws Exception {
        return Files.writeString(tmp.resolve("input.html"), "<html><body><h1>Result leg</h1><p>sentinel</p>"
                + "<img src='missing-image.png'></body></html>");
    }
    private Path deep() throws Exception {
        String body = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>deep</body></html>";
        for (int i = 100; i >= 0; i--) body = "Content-Type: multipart/mixed; boundary=b" + i
                + "\r\n\r\n--b" + i + "\r\n" + body + "\r\n--b" + i + "--\r\n";
        return Files.writeString(tmp.resolve("deep.mht"), "MIME-Version: 1.0\r\n" + body);
    }

    @Test void asynchronousSuccessStatsAndProgressMatchInProcessFieldForField() throws Exception {
        try (var c = new Client("success", html())) {
            c.start(false);
            var end = c.until("extractionTerminal");
            assertEquals("SUCCEEDED", end.path("end").asText());
            c.exactlyOnce(1, false);
            c.statsEqual(end, "ExtractionTerminal");
            var observed = JSON.readTree(Files.readAllBytes(c.work.resolve("direct-events.json")));
            var expectedProgress = new ArrayList<JsonNode>();
            for (var e : observed) if (e.path("event").asText().equals("ExtractionProgress")) expectedProgress.add(e.path("data"));
            var actualProgress = c.named("extractionProgress").stream().map(e -> {
                var data = (ObjectNode) e.path("data").deepCopy(); data.remove("revision"); return (JsonNode) data;
            }).toList();
            assertEquals(expectedProgress, actualProgress, "Asynchronous progress retains port ordering");
            assertEquals(end.path("outputFolder"), end.path("sessionOutputFolder"));
            c.ipc.finish();
        }
    }

    @Test void imageWarningsAndSkippedFilesRetainFullStatsSchema() throws Exception {
        try (var c = new Client("image", html())) {
            c.start(true);
            var end = c.until("extractionTerminal");
            c.exactlyOnce(1, false);
            c.statsEqual(end, "ExtractionTerminal");
            var stats = read(end.path("stats"));
            assertTrue(stats.path("warnCount").asInt() > 0);
            assertTrue(stats.path("skipCount").asInt() > 0);
            assertTrue(stats.path("files").get(0).path("warnings").size() > 0);
            c.ipc.finish();
        }
    }

    @Test void runningEventsCarryGenerationAndFollowTheCoordinatorState() throws Exception {
        try (var c = new Client("running", html())) {
            c.start(false);
            c.until("extractionTerminal");
            c.settled();
            c.runningPair(1);
            var expected = new ArrayList<JsonNode>();
            for (var e : JSON.readTree(Files.readAllBytes(c.work.resolve("direct-events.json"))))
                if (e.path("event").asText().equals("RunningChanged")) expected.add(e.path("data"));
            var actual = c.named("runningChanged").stream().map(e -> {
                var data = (ObjectNode) e.path("data").deepCopy(); data.remove("revision"); return (JsonNode) data;
            }).toList();
            assertEquals(expected, actual);
            System.out.println("RUNNING GREEN: " + actual);
            c.ipc.finish();
        }
    }

    @Test void synchronousExtractRetainsLegacyResultsAndBucketedProgress() throws Exception {
        try (var c = new Client("script", html())) {
            var result = c.call("extract", object().put("checkedOnly", false).put("outputFolder", tmp.resolve("script-output").toString()));
            var end = c.until("extractionTerminal");
            c.exactlyOnce(1, false);
            c.statsEqual(end, "ExtractionTerminal");
            assertEquals(Set.of("generation", "end", "outputFolder", "count", "failCount", "skipCount", "warnCount", "resultsFile"), keys(result));
            var legacy = read(result.path("resultsFile"));
            var stats = read(end.path("stats"));
            assertEquals(stats.path("files").size(), legacy.size());
            for (int i = 0; i < legacy.size(); i++) {
                assertEquals(Set.of("path", "outcome", "messageKey", "messageParams", "messageDetail", "warnings"), keys(legacy.get(i)));
                var expected = (ObjectNode) stats.path("files").get(i).deepCopy();
                assertEquals(expected, legacy.get(i));
            }
            assertTrue(c.named("extractionProgress").size() <= 101);
            int previous = -1;
            for (var frame : c.named("extractionProgress")) {
                int bucket = (int) (frame.path("data").path("overall").asDouble() * 100);
                assertTrue(bucket > previous); previous = bucket;
            }
            c.ipc.finish();
        }
    }

    @Test void cancelIsNonblockingTerminalPrecedesResponseAndPartialIsExactlyOnce() throws Exception {
        try (var c = new Client("cancel", deep())) {
            c.start(false); // The response is received while the worker is blocked on a real confirmation.
            c.until("confirmationRequested");
            assertTrue(c.named("extractionTerminal").isEmpty(), "startExtraction must respond before worker completion");
            c.call("cancelExtraction", object());
            assertEquals(1, c.named("extractionTerminal").size(), "Cancellation terminal arrives before cancel response");
            var end = c.named("extractionTerminal").getFirst().path("data");
            assertEquals("CANCELLED", end.path("end").asText());
            assertTrue(end.path("stats").isNull());
            var partial = c.until("extractionResult");
            c.call("cancelExtraction", object());
            c.exactlyOnce(1, true);
            c.statsEqual(partial, "ExtractionResult");
            assertEquals(end.path("sessionOutputFolder"), partial.path("sessionOutputFolder"));
            assertTrue(c.events.indexOf(c.named("extractionTerminal").getFirst()) < c.events.indexOf(c.named("extractionResult").getFirst()));
            System.out.println("CANCEL ORDER: " + c.events);
            c.ipc.finish();
        }
    }

    @Test void completionAndCancellationRaceHasOneTerminalAcrossFortyRuns() throws Exception {
        try (var c = new Client("race", html())) {
            int cancelled = 0;
            for (long generation = 1; generation <= 40; generation++) {
                c.events.clear();
                c.start(false);
                if (generation % 2 == 0) {
                    // Put cancellation beside the worker's final progress/completion boundary.
                    while (c.named("extractionTerminal").isEmpty() && c.named("extractionProgress").stream()
                            .noneMatch(e -> e.path("data").path("overall").asDouble() == 1)) c.collect(c.ipc.receive(10));
                }
                c.call("cancelExtraction", object());
                var end = c.until("extractionTerminal");
                boolean cancel = end.path("end").asText().equals("CANCELLED");
                if (cancel) { cancelled++; c.until("extractionResult"); }
                else assertEquals("SUCCEEDED", end.path("end").asText());
                c.call("cancelExtraction", object());
                c.exactlyOnce(generation, cancel);
                assertEquals(generation, end.path("generation").longValue());
            }
            System.out.println("RACE GREEN: N=40 terminals=40 cancelled=" + cancelled + " succeeded=" + (40 - cancelled));
            c.ipc.finish();
        }
    }

    @Test void invalidRuntimeAndRunningReentryAreRejectedWithoutAnotherGeneration() throws Exception {
        try (var c = new Client("invalid", deep())) {
            for (JsonNode p : List.of(object(), object().put("checkedOnly", false),
                    object().put("checkedOnly", false).set("runtime", runtime(tmp.resolve("bad"), false).putNull("outputFolder")),
                    object().put("checkedOnly", false).set("runtime", runtime(Path.of("relative"), false)))) {
                var response = c.ipc.request("bad", "startExtraction", p);
                assertEquals("INVALID_PARAMS", response.path("error").path("code").asText(), response.toString());
            }
            c.start(false);
            c.until("confirmationRequested");
            var response = c.ipc.request("reentry", "startExtraction", object().put("checkedOnly", false)
                    .set("runtime", runtime(tmp.resolve("other-output"), true)));
            c.ipc.drainRequestEvents().forEach(c::collect);
            assertEquals("INVALID_PARAMS", response.path("error").path("code").asText());
            c.call("cancelExtraction", object());
            c.until("extractionResult");
            c.exactlyOnce(1, true);
            c.ipc.finish();
        }
    }

    @Test void repeatedPortCancellationDeliversTerminalWhileWorkerIsHeldThenOnePartial() throws Exception {
        var model = new com.mhtextractor.app.WorkspaceModel();
        var global = new com.mhtextractor.app.GlobalParameterCache();
        Path source = deep();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        com.mhtextractor.uiport.Confirmer confirmer = request -> new com.mhtextractor.uiport.ConfirmToken() {
            public void answer(String action) { release.countDown(); }
            public void cancel() { release.countDown(); }
            public String await() throws InterruptedException {
                entered.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) throw new InterruptedException("held worker released after cancellation");
                return null;
            }
        };
        var previews = com.mhtextractor.app.PreviewSession.create(tmp.resolve("held-cache"));
        try {
            var plans = new com.mhtextractor.app.ExtractionPlanService(new com.mhtextractor.app.ExtractionPlanService.Context() {
                public List<Object> dataOrder() { return model.topLevel(); }
                public boolean imageMode() { return false; }
                public com.mhtextractor.model.AppConfig textParamsCache() { return global.text(); }
                public com.mhtextractor.model.AppConfig imageParamsCache() { return global.image(); }
                public String inputFolder() { return null; }
                public String outputFolder() { return tmp.resolve("held-output").toString(); }
                public boolean openFolderAfterComplete() { return false; }
            });
            var activities = new com.mhtextractor.app.WorkspaceActivityService(new com.mhtextractor.app.WorkspaceActivityService.Context() {
                public List<com.mhtextractor.model.PreviewItem> items() { return model.allItems(); }
                public boolean textMode() { return true; }
                public com.mhtextractor.model.AppConfig effectiveParams(com.mhtextractor.model.PreviewItem item) { return new com.mhtextractor.model.AppConfig(); }
                public com.mhtextractor.model.AppConfig effectiveImageParams(com.mhtextractor.model.PreviewItem item) { return new com.mhtextractor.model.AppConfig(); }
            }, new com.mhtextractor.app.PreviewService(previews.lifecycle()), (image, index, config) -> null, callbacks::add);
            var cache = new com.mhtextractor.app.WorkspaceParamService(previews.lifecycle());
            var clipboard = new com.mhtextractor.app.ParamClipboard(model::push, target -> com.mhtextractor.model.ParamScope.GLOBAL,
                    (target, mode, config) -> { });
            var params = new com.mhtextractor.app.WorkspaceParamCommandService(model, cache, global, clipboard::pushParamUndo);
            var coordinator = new com.mhtextractor.app.WorkspaceExtractionCoordinator(
                    new com.mhtextractor.app.WorkspaceExtractionService(previews.lifecycle()),
                    com.mhtextractor.app.WorkspaceExtractionCoordinator.productionRequests(plans, model::allItems, confirmer), callbacks::add);
            var port = new com.mhtextractor.app.InProcessUiport(model, confirmer, plans, activities, params,
                    new com.mhtextractor.app.WorkspaceImportCoordinator(model, confirmer, callbacks::add), coordinator, cache);
            var events = new ArrayList<com.mhtextractor.uiport.PortEvent>();
            port.events().subscribe(events::add);
            port.commands().applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(
                    List.of(new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm(tmp.toString(), null, false, List.of(source.toString()))),
                    0, 0, 0, 0, 0, false, false));
            port.commands().startExtraction(false);
            var channel = coordinator.channel();
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                port.commands().cancelExtraction();
                port.commands().cancelExtraction();
                assertFalse(channel.result.isDone(), "Worker is still held when cancellation returns");
                assertEquals(1, events.stream().filter(e -> e instanceof com.mhtextractor.uiport.PortEvent.ExtractionTerminal).count());
                assertEquals(0, events.stream().filter(e -> e instanceof com.mhtextractor.uiport.PortEvent.ExtractionResult).count());
                assertEquals(List.of(new com.mhtextractor.uiport.PortEvent.RunningChanged(true, channel.runId),
                                new com.mhtextractor.uiport.PortEvent.RunningChanged(false, channel.runId)),
                        events.stream().filter(e -> e instanceof com.mhtextractor.uiport.PortEvent.RunningChanged).toList(),
                        "Running is reset while the worker is still held");
                System.out.println("HELD CANCEL: terminal=1 workerResultDone=false partial=0 repeatedRequests=2");
            } finally { release.countDown(); }
            var stats = channel.result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (events.stream().noneMatch(e -> e instanceof com.mhtextractor.uiport.PortEvent.ExtractionResult)) {
                var callback = callbacks.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (callback != null) callback.run();
                assertTrue(System.nanoTime() < deadline);
            }
            Runnable callback;
            while ((callback = callbacks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) callback.run();
            assertEquals(1, events.stream().filter(e -> e instanceof com.mhtextractor.uiport.PortEvent.ExtractionTerminal).count());
            var partials = events.stream().filter(e -> e instanceof com.mhtextractor.uiport.PortEvent.ExtractionResult)
                    .map(e -> (com.mhtextractor.uiport.PortEvent.ExtractionResult) e).toList();
            assertEquals(1, partials.size());
            assertEquals(stats, partials.getFirst().stats());
            assertEquals(channel.runId, partials.getFirst().generation());
            assertEquals(tmp.resolve("held-output"), Path.of(partials.getFirst().sessionOutputFolder()).getParent());
            System.out.println("HELD RELEASE: terminal=1 partial=1 fullStatsEqual=true");
        } finally {
            release.countDown();
            previews.lifecycle().onShutdown(() -> { });
        }
    }
}
