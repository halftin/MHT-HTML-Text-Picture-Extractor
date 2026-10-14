package com.mhtextractor.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.backend.BackendIdentity;
import com.mhtextractor.backend.BackendLauncher;
import com.mhtextractor.uiport.ConfirmRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the companion process and its pipes; view callbacks never perform IO or wait for a worker. */
public final class BackendSession implements AutoCloseable {
    public enum State { CONNECTING, CONNECTED, CONFIRMING, UNAVAILABLE, DISCONNECTED, CLOSED }
    public enum End { ANSWERED, REPLACED, TIMEOUT, ACTIVITY_FINISHED, DISCONNECTED }
    public record Status(State state, String detail) { }
    public interface View {
        void statusChanged(Status status);
        void confirmationRequested(String id, ConfirmRequest request, Consumer<String> answer);
        void confirmationFinished(String id, End reason);
        default void confirmationAnswerReceived(String id, boolean accepted) { }
        default void confirmationFailed(String id, Throwable error) { }
    }

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Consumer<Runnable> dispatch;
    private final View view;
    private final ScheduledExecutorService writes = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("backend-session-write").daemon(true).factory());
    private final Map<String, CompletableFuture<JsonNode>> requests = new LinkedHashMap<>();
    private BackendLauncher.Connection connection;
    private Pending pending;
    private boolean started;
    private boolean closed;
    private long sequence;
    private long revision = -1;
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final List<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();

    /** Listeners only enqueue work: reading files or waiting here would stop response delivery. */
    public AutoCloseable subscribeEvents(Consumer<JsonNode> listener) {
        eventListeners.add(java.util.Objects.requireNonNull(listener));
        return () -> eventListeners.remove(listener);
    }

    public CompletableFuture<Void> connected() { return connected.copy(); }
    /** Completes after the companion process and its streams have finished teardown. */
    public CompletableFuture<Void> stopped() { return stopped.copy(); }
    public synchronized long revision() { return revision; }
    public synchronized String sessionId() { return connection == null ? null : connection.sessionId(); }

    public static final class RemoteError extends IOException {
        private final String code;
        public RemoteError(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public BackendSession(Consumer<Runnable> dispatch, View view) {
        this.dispatch = java.util.Objects.requireNonNull(dispatch);
        this.view = java.util.Objects.requireNonNull(view);
    }

    public void start(String locale) {
        start(() -> BackendExecutable.command(locale), Duration.ofSeconds(10));
    }

    @FunctionalInterface public interface Command { ProcessBuilder create() throws IOException; }

    /** Also supports JVM --serve hosts; identity always comes from this client's filtered build resource. */
    public synchronized void start(Command command, Duration timeout) {
        if (started || closed) throw new IllegalStateException("Backend session can only start once");
        started = true;
        status(State.CONNECTING, "");
        Thread.ofPlatform().name("backend-session-read").daemon(true).start(() -> read(command, timeout));
    }

    private void read(Command command, Duration timeout) {
        BackendLauncher.Connection launched = null;
        boolean welcomed = false;
        try {
            launched = BackendLauncher.launch(command.create(), BackendIdentity.current(), timeout);
            List<String> required = List.of("workspace", "setChecked", "workspacePage", "bulkFiles", "confirmationRoundTrip");
            if (!launched.confirmationRoundTrip() || !launched.capabilities().containsAll(required)) {
                throw new IOException("Backend welcome lacks required capabilities: " + required
                        + "; declared " + launched.capabilities() + ", confirmationRoundTrip=" + launched.confirmationRoundTrip());
            }
            synchronized (this) {
                if (closed) return;
                connection = launched;
                welcomed = true;
                status(State.CONNECTED, launched.identity().version() + " / " + launched.identity().buildId());
                connected.complete(null);
            }
            String line;
            while ((line = launched.output().readLine()) != null) receive(json.readTree(line));
            throw new IOException("Backend exited or disconnected (stdout EOF)");
        } catch (Exception error) {
            synchronized (this) {
                if (!closed) {
                    closed = true;
                    finishPending(End.DISCONNECTED);
                    failRequests(error);
                    connected.completeExceptionally(error);
                    status(welcomed ? State.DISCONNECTED : State.UNAVAILABLE, error.getMessage());
                }
            }
        } finally {
            // Closing pipes / waiting for process exit is confined to this daemon, never the view thread.
            if (launched != null) {
                try { launched.close(); } catch (IOException ignored) { }
            }
            writes.shutdownNow();
            stopped.complete(null);
        }
    }

    private synchronized void receive(JsonNode frame) throws IOException {
        if (closed) return;
        if (frame == null || !frame.isObject() || !frame.path("v").isIntegralNumber()
                || frame.path("v").intValue() != 1) throw new IOException("Malformed backend IPC frame");
        String type = frame.path("type").asText();
        if (type.equals("response") || type.equals("error")) {
            if (type.equals("response")) checkRevision(frame.path("revision"), false);
            CompletableFuture<JsonNode> result = requests.remove(frame.path("id").asText());
            if (result != null) {
                if (type.equals("error")) result.completeExceptionally(new RemoteError(
                        frame.path("error").path("code").asText(), frame.path("error").path("message").asText()));
                else result.complete(frame.path("result"));
            }
            return;
        }
        if (!type.equals("event")) throw new IOException("Unknown backend frame type: " + type);
        String event = frame.path("event").asText();
        JsonNode data = frame.path("data");
        checkRevision(data.path("revision"), true);
        for (var listener : eventListeners) listener.accept(frame.deepCopy());
        if (event.equals("confirmationRequested")) {
            ConfirmRequest request = confirmation(data);
            if (pending != null) answer(pending.id, null, End.REPLACED);
            Pending next = new Pending(data.path("confirmationId").textValue());
            pending = next;
            status(State.CONFIRMING, next.id);
            dispatch.accept(() -> view.confirmationRequested(next.id, request,
                    action -> answer(next.id, action, End.ANSWERED)));
            // The server's deadline starts before receipt. Give its expiry event a short delivery margin.
            next.timer = writes.schedule(() -> answer(next.id, null, End.TIMEOUT),
                    Math.min(request.timeoutMs(), 30_000) + 250, TimeUnit.MILLISECONDS);
        } else if (event.equals("confirmationExpired")) {
            if (pending != null && pending.id.equals(data.path("confirmationId").asText())) {
                finishPending(End.TIMEOUT);
                status(State.CONNECTED, "");
            }
        } else if (event.equals("extractionTerminal") || event.equals("importFinished")) {
            if (pending != null) {
                finishPending(End.ACTIVITY_FINISHED);
                status(State.CONNECTED, "");
            }
        }
    }

    private void checkRevision(JsonNode value, boolean event) throws IOException {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
                || (event ? value.longValue() <= revision : value.longValue() < revision)) {
            throw new IOException("Invalid or regressing backend revision: " + value + " after " + revision);
        }
        revision = value.longValue();
    }

    private ConfirmRequest confirmation(JsonNode data) throws IOException {
        if (!data.path("confirmationId").isTextual() || data.path("confirmationId").asText().isBlank()
                || !data.path("key").isTextual() || data.path("key").asText().isBlank()
                || !data.path("namedParams").isObject() || !data.path("actionKeys").isArray()
                || !data.has("defaultAction") || !data.path("defaultAction").isNull()
                || !data.path("timeoutMs").isIntegralNumber() || !data.path("timeoutMs").canConvertToLong()
                || data.path("timeoutMs").longValue() <= 0) throw new IOException("Malformed backend confirmation");
        Map<String, String> params = new LinkedHashMap<>();
        var fields = data.path("namedParams").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isTextual()) throw new IOException("Confirmation parameters must be strings");
            params.put(field.getKey(), field.getValue().textValue());
        }
        List<String> actions = new ArrayList<>();
        for (JsonNode action : data.path("actionKeys")) {
            if (!action.isTextual() || action.asText().isBlank()) throw new IOException("Invalid confirmation action");
            actions.add(action.textValue());
        }
        return new ConfirmRequest(data.path("key").textValue(), params, actions, data.path("timeoutMs").longValue());
    }

    private synchronized void answer(String id, String action, End reason) {
        if (closed || pending == null || !pending.id.equals(id)) return;
        finishPending(reason);
        status(State.CONNECTED, "");
        ObjectNode params = json.createObjectNode().put("confirmationId", id);
        if (action == null) params.putNull("actionKey"); else params.put("actionKey", action);
        request("confirmAnswer", params).whenComplete((result, error) -> {
            if (error != null) {
                dispatch.accept(() -> view.confirmationFailed(id, error));
            } else if (!result.path("accepted").isBoolean()) {
                dispatch.accept(() -> view.confirmationFailed(id, new IOException("Malformed confirmAnswer response")));
            } else {
                boolean accepted = result.path("accepted").asBoolean();
                dispatch.accept(() -> view.confirmationAnswerReceived(id, accepted));
                if (!accepted && reason == End.ANSWERED) dispatch.accept(() -> view.confirmationFinished(id, End.TIMEOUT));
            }
        });
    }

    /** Explicit IPC commands for companion clients; existing GUI business commands retain their in-process port. */
    public synchronized CompletableFuture<JsonNode> request(String method, JsonNode params) {
        var result = new CompletableFuture<JsonNode>();
        if (closed || connection == null) {
            result.completeExceptionally(new IOException("Backend is not connected"));
            return result;
        }
        String id = "gui-" + ++sequence;
        requests.put(id, result);
        ObjectNode frame = json.createObjectNode().put("v", 1).put("type", "request").put("id", id).put("method", method);
        frame.set("params", params.deepCopy());
        writes.execute(() -> {
            try {
                connection.input().write(json.writeValueAsString(frame));
                connection.input().write('\n');
                connection.input().flush();
            } catch (IOException error) {
                closeWithError(error);
            }
        });
        return result;
    }

    private void closeWithError(IOException error) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            finishPending(End.DISCONNECTED);
            failRequests(error);
            connected.completeExceptionally(error);
            status(State.DISCONNECTED, error.getMessage());
        }
        stopProcess();
    }

    private void finishPending(End reason) {
        if (pending == null) return;
        Pending old = pending;
        pending = null;
        if (old.timer != null) old.timer.cancel(false);
        dispatch.accept(() -> view.confirmationFinished(old.id, reason));
    }

    private void status(State state, String detail) {
        dispatch.accept(() -> view.statusChanged(new Status(state, detail == null ? "" : detail)));
    }

    private void failRequests(Exception error) {
        requests.values().forEach(future -> future.completeExceptionally(error));
        requests.clear();
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            finishPending(End.DISCONNECTED);
            failRequests(new IOException("Backend session closed"));
            connected.completeExceptionally(new IOException("Backend session closed"));
            status(State.CLOSED, "");
            if (!started) stopped.complete(null);
        }
        stopProcess();
    }

    private void stopProcess() {
        Thread.ofPlatform().name("backend-session-stop").daemon(true).start(() -> {
            BackendLauncher.Connection current;
            synchronized (this) { current = connection; }
            if (current != null) {
                try { current.close(); } catch (IOException ignored) { }
            }
        });
    }

    private static final class Pending {
        final String id;
        ScheduledFuture<?> timer;
        Pending(String id) { this.id = id; }
    }
}
