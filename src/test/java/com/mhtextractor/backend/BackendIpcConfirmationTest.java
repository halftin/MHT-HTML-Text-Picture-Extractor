package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.mhtextractor.app.ExtractionPlanService;
import com.mhtextractor.app.WorkspaceExtractionCoordinator;
import com.mhtextractor.app.WorkspaceExtractionService;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.ConfirmRequest;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.Confirmer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

class BackendIpcConfirmationTest {
    private Path source() throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "p4-14-confirm-");
        String leaf = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>truncated</body></html>";
        String body = "Content-Type: multipart/mixed; boundary=leaf\r\n\r\n--leaf\r\n" + leaf
                + "\r\n--leaf\r\n" + leaf + "\r\n--leaf--\r\n";
        for (int i = 98; i >= 0; i--) {
            body = "Content-Type: multipart/mixed; boundary=b" + i + "\r\n\r\n--b" + i + "\r\n"
                    + body + "\r\n--b" + i + "--\r\n";
        }
        // At depth 100, two children exceed the limit: continue suppresses the second question.
        Path source = root.resolve("deep.mht");
        Files.writeString(source, "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n"
                + "--outer\r\n" + body + "\r\n--outer--\r\n");
        return source;
    }

    private IpcTestClient start(Path source, long timeoutMs) throws Exception {
        var client = new IpcTestClient(source.getParent().resolve("client"), BackendIpcConfirmationFixture.class.getName(),
                String.valueOf(timeoutMs));
        try {
            client.ready();
            send(client, "import", "importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))));
            untilResponse(client, "import");
            send(client, "extract", "extract", JSON.createObjectNode().put("checkedOnly", false)
                    .put("outputFolder", source.getParent().resolve("output").toString()));
            return client;
        } catch (Throwable e) { client.close(); throw e; }
    }

    private void send(IpcTestClient client, String id, String method, JsonNode params) throws Exception {
        var frame = JSON.createObjectNode().put("v", 1).put("type", "request").put("id", id).put("method", method);
        frame.set("params", params);
        client.send(JSON.writeValueAsString(frame));
    }

    private JsonNode untilResponse(IpcTestClient client, String id) throws Exception {
        while (true) {
            JsonNode frame = client.receive(5);
            assertFalse(frame.path("type").asText().equals("error"), frame.toString());
            if (frame.path("id").asText().equals(id)) return frame;
        }
    }

    private JsonNode confirmation(IpcTestClient client) throws Exception {
        while (true) {
            JsonNode frame = client.receive(5);
            if (frame.path("event").asText().equals("confirmationRequested")) {
                assertEquals("alert.recursionDepthHeader", frame.path("data").path("key").asText());
                assertTrue(frame.path("data").path("namedParams").isObject());
                assertEquals(JSON.readTree("[\"continue\"]"), frame.path("data").path("actionKeys"));
                assertTrue(frame.path("data").path("defaultAction").isNull());
                assertFalse(frame.path("data").has("message"));
                System.out.println("CONFIRM FRAME: " + frame);
                return frame;
            }
            assertEquals("event", frame.path("type").asText(), "Extraction terminated without asking: " + frame);
        }
    }

    @Test void confirmAndCancelMatchInProcessIncludingQuestionCountAndResults() throws Exception {
        for (boolean accepted : List.of(true, false)) {
            Path source = source();
            AtomicInteger directQuestions = new AtomicInteger();
            var direct = direct(source, request -> {
                directQuestions.incrementAndGet();
                return decided(accepted ? "continue" : null);
            });
            try (var client = start(source, 10_000)) {
                int questions = 0;
                int terminals = 0;
                int answers = 0;
                int invalidAnswers = 0;
                JsonNode response;
                var ids = new ArrayList<String>();
                while (true) {
                    JsonNode frame = client.receive(5);
                    if (frame.path("id").asText().startsWith("bad-")) {
                        assertEquals("INVALID_PARAMS", frame.path("error").path("code").asText());
                        invalidAnswers++;
                        continue;
                    }
                    assertFalse(frame.path("type").asText().equals("error"), frame.toString());
                    assertNotEquals("queued-query", frame.path("id").asText(), "Ordinary commands must wait for activity completion");
                    if (frame.path("event").asText().equals("confirmationRequested")) {
                        String id = frame.path("data").path("confirmationId").asText();
                        assertFalse(ids.contains(id));
                        ids.add(id);
                        questions++;
                        if (questions == 1) {
                            send(client, "queued-query", "workspace", JSON.createObjectNode());
                            send(client, "bad-action", "confirmAnswer", JSON.createObjectNode()
                                    .put("confirmationId", id).put("actionKey", "not-offered"));
                            send(client, "bad-shape", "confirmAnswer", JSON.createObjectNode().put("confirmationId", id));
                        }
                        System.out.println("ROUNDTRIP " + (accepted ? "CONFIRM" : "CANCEL") + ": " + frame);
                        var params = JSON.createObjectNode().put("confirmationId", id);
                        if (accepted) params.put("actionKey", "continue"); else params.putNull("actionKey");
                        send(client, "answer-" + questions, "confirmAnswer", params);
                    } else if (frame.path("id").asText().startsWith("answer-")) {
                        assertTrue(frame.path("result").path("accepted").asBoolean());
                        answers++;
                    } else if (frame.path("event").asText().equals("extractionTerminal")) {
                        terminals++;
                        assertEquals("SUCCEEDED", frame.path("data").path("end").asText());
                    } else if (frame.path("id").asText().equals("extract")) { response = frame; break; }
                }
                assertEquals(accepted ? 1 : 2, questions); // Distinguishes a delivered yes from silent cancellation.
                assertEquals(directQuestions.get(), questions);
                assertEquals(questions, answers);
                assertEquals(2, invalidAnswers);
                assertEquals(1, terminals);
                assertEquals("SUCCEEDED", response.path("result").path("end").asText());
                assertEquals(direct.stats().getSkipCount(), response.path("result").path("skipCount").asInt());
                JsonNode results = JSON.readTree(Files.readString(Path.of(response.path("result").path("resultsFile").path("path").asText())));
                var expected = JSON.createArrayNode();
                for (var file : direct.stats().getFileResults()) expected.addObject().put("path", file.path())
                        .put("outcome", file.outcome().name()).put("message", file.message());
                assertEquals(expected, results);
                assertEquals("response", untilResponse(client, "queued-query").path("type").asText());
                // Duplicate/late decisions cannot be delivered again.
                assertFalse(client.request("late", "confirmAnswer", JSON.createObjectNode().put("confirmationId", ids.getFirst())
                        .put("actionKey", "continue")).path("result").path("accepted").asBoolean());
                System.out.println("ROUNDTRIP " + (accepted ? "CONFIRM" : "CANCEL") + " GREEN: questions=" + questions
                        + " inProcessQuestions=" + directQuestions.get() + " terminalCount=" + terminals + " result=" + response);
                client.finish();
            }
        }
    }

    private WorkspaceExtractionService.ExtractionResult direct(Path source, Confirmer confirmer) {
        WorkspaceModel model = new WorkspaceModel();
        model.addItems(List.of(new PreviewItem(source.toString(), "deep.mht", LocalDateTime.now(), source.getParent().toString())));
        AppConfig config = new AppConfig();
        var plans = new ExtractionPlanService(new ExtractionPlanService.Context() {
            public List<Object> dataOrder() { return model.topLevel(); }
            public boolean imageMode() { return false; }
            public AppConfig textParamsCache() { return config; }
            public AppConfig imageParamsCache() { return config; }
            public String inputFolder() { return null; }
            public String outputFolder() { return source.getParent().resolve("direct-output").toString(); }
            public boolean openFolderAfterComplete() { return false; }
        });
        var request = WorkspaceExtractionCoordinator.productionRequests(plans, model::allItems, confirmer).apply(false);
        return new WorkspaceExtractionService((com.mhtextractor.cache.PreviewCacheLifecycle) null)
                .run(request, () -> false, progress -> { });
    }

    private ConfirmToken decided(String action) {
        return new ConfirmToken() {
            public void answer(String ignored) { throw new UnsupportedOperationException(); }
            public void cancel() { }
            public String await() { return action; }
        };
    }

    @Test void unansweredConfirmTimesOutAsCancelAndReachesOneTerminal() throws Exception {
        Path source = source();
        try (var client = start(source, 400)) {
            JsonNode first = confirmation(client);
            assertEquals(400, first.path("data").path("timeoutMs").asLong());
            long started = System.nanoTime();
            int questions = 1;
            int terminals = 0;
            JsonNode result;
            while (true) {
                JsonNode frame = client.receive(3); // RED if timeout delivery is removed, then teardown kills the child.
                if (frame.path("event").asText().equals("confirmationRequested")) questions++;
                if (frame.path("event").asText().equals("extractionTerminal")) terminals++;
                if (frame.path("id").asText().equals("extract")) { result = frame; break; }
            }
            assertEquals(2, questions, "Timeout must behave like cancel, not continue");
            assertEquals(1, terminals);
            assertEquals("SUCCEEDED", result.path("result").path("end").asText());
            assertEquals(1, result.path("result").path("skipCount").asInt());
            assertFalse(client.request("expired", "confirmAnswer", JSON.createObjectNode()
                    .put("confirmationId", first.path("data").path("confirmationId").asText()).put("actionKey", "continue"))
                    .path("result").path("accepted").asBoolean());
            assertEquals("response", client.request("still-live", "workspace", JSON.createObjectNode()).path("type").asText());
            System.out.println("CG-1 GREEN: deliberately unanswered; questions=" + questions + " elapsedMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " terminalCount=" + terminals + " result=" + result);
            client.finish();
        }
    }

    @Test void disconnectDuringPendingConfirmationCancelsAndNaturallyExitsWithoutResiduals() throws Exception {
        Path source = source();
        try (var client = start(source, 30_000)) {
            confirmation(client); // Close a real client pipe only after confirmation is in flight.
            var frames = client.disconnectAndCollect();
            var terminals = frames.stream().filter(f -> f.path("event").asText().equals("extractionTerminal")).toList();
            assertEquals(1, terminals.size());
            assertEquals("CANCELLED", terminals.getFirst().path("data").path("end").asText());
            assertEquals("CANCELLED", frames.stream().filter(f -> f.path("id").asText().equals("extract"))
                    .findFirst().orElseThrow().path("result").path("end").asText());
            System.out.println("CG-2 TERMINAL: " + terminals.getFirst());
        }
    }

    @Test void tokenValidatesActionsAndOnlyOneAnswerWinsAndWireOmitsRenderedMessage() throws Exception {
        var events = new ArrayList<JsonNode>();
        var confirmations = new IpcConfirmations(JSON, Runnable::run, events::add);
        var token = confirmations.confirm(new ConfirmRequest("question.key", Map.of("files", "17"),
                List.of("continue", "stop"), 0, "rendered text must never cross the pipe"));
        JsonNode data = events.getFirst();
        assertFalse(data.has("message"));
        assertEquals("17", data.path("namedParams").path("files").asText());
        assertEquals(30_000, data.path("timeoutMs").asLong());
        String id = data.path("confirmationId").asText();
        assertThrows(IllegalArgumentException.class, () -> confirmations.answer(id, "invalid"));
        assertTrue(confirmations.answer(id, "continue"));
        token.cancel();
        assertFalse(confirmations.answer(id, "stop"));
        assertEquals("continue", token.await());
        long started = System.nanoTime();
        var timed = confirmations.confirm(new ConfirmRequest("request.deadline", Map.of(), List.of("yes"), 30));
        assertEquals(30, events.getLast().path("timeoutMs").asLong());
        assertNull(timed.await());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 20 && elapsed < 2000, "Request deadline elapsedMs=" + elapsed);
        var pending = confirmations.confirm(new ConfirmRequest("other.key", Map.of(), List.of("yes"), 0));
        confirmations.disconnect();
        assertNull(pending.await());
        assertNull(confirmations.confirm(new ConfirmRequest("after.close", Map.of(), List.of("yes"), 0)).await());
        var brokenExpiry = new IpcConfirmations(JSON, Runnable::run, data2 -> { }, data2 -> {
            throw new IllegalStateException("broken expiry notification");
        });
        var released = brokenExpiry.confirm(new ConfirmRequest("expiry.failure", Map.of(), List.of("yes"), 1));
        assertThrows(IllegalStateException.class, released::await);
        var cancellation = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofPlatform().daemon(true).start(() -> {
            try { cancellation.complete(released.await()); }
            catch (Throwable error) { cancellation.completeExceptionally(error); }
        });
        assertNull(cancellation.get(2, TimeUnit.SECONDS), "Expiry must complete cancellation even if notification delivery fails");
    }
}
