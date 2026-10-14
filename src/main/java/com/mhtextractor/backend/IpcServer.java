package com.mhtextractor.backend;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.Uiport;
import com.mhtextractor.uiport.WorkspaceVm;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.ArrayDeque;

/**
 * v1 stdio control frames: one UTF-8 JSON object plus LF per frame (see IPC.md).
 * A serial host dispatches to the port and flushes one response per request. A separate
 * daemon reads stdin so answers and EOF are handled while the host waits for an activity.
 * The initial ready event precedes all responses; actual port changes carry versioned
 * file snapshots. IO failure ends the session; malformed
 * requests produce error frames and leave the session usable.
 * Business requests require a matching hello; ready alone grants no business access.
 */
@Slf4j
final class IpcServer {
    private final Uiport port;
    private final IpcSession session;
    private final BackendIdentity identity;
    private boolean welcomed;
    private WorkspaceVm pageSnapshot;
    private java.util.List<ItemVm> pageItems;
    private final ArrayDeque<String> deferred = new ArrayDeque<>();
    private boolean disconnected;
    private final String sessionId = java.util.UUID.randomUUID().toString();
    private long revision;
    private long pageRevision = -1;
    private ObjectNode pageFile;
    private ObjectNode currentSnapshot;
    private long snapshotRevision = -1;
    private final IpcFiles files;
    private final IpcWorkspaceCodec codec;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    IpcServer(Uiport port) throws IOException {
        this.port = Objects.requireNonNull(port, "port");
        this.session = null;
        this.identity = BackendIdentity.current();
        files = new IpcFiles(json);
        codec = new IpcWorkspaceCodec(json, files);
    }

    IpcServer(IpcSession session) throws IOException {
        this.port = session.port;
        this.session = session;
        this.identity = BackendIdentity.current();
        files = session.files();
        codec = new IpcWorkspaceCodec(json, files);
    }

    void run(InputStream input, OutputStream output) throws IOException {
        welcomed = false;
        // Output is borrowed; the reader closes input on termination. Never swallow transport IO errors.
        IpcOutput writer = new IpcOutput(output);
        boolean outputFailed = false;
        ObjectNode ready = frame("event").put("event", "ready");
        ArrayNode methods = ready.putObject("data").putArray("methods").add("workspace").add("setChecked");
        ready.withObject("data").put("sessionId", sessionId).put("revision", revision);
        if (session != null) {
            IpcSession.METHODS.forEach(methods::add);
            session.silentChanges(() -> revision++);
            session.events(event -> {
                try { emit(writer, event); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        methods.add("hello");
        if (session != null) IpcSession.STRUCTURE_METHODS.forEach(methods::add);
        if (session != null) IpcSession.ACTIVITY_METHODS.forEach(methods::add);
        if (session != null) IpcSession.PARAM_METHODS.forEach(methods::add);
        if (session != null) IpcSession.EXTRACTION_METHODS.forEach(methods::add);
        if (session != null) IpcSession.DETAIL_METHODS.forEach(methods::add);
        if (session != null) IpcSession.NAMED_CONFIG_METHODS.forEach(methods::add);
        AutoCloseable subscription = session == null ? port.events().subscribe(event -> {
            if (event instanceof com.mhtextractor.uiport.PortEvent.WorkspaceChanged changed) {
                var frame = frame("event").put("event", "workspaceChanged");
                frame.putObject("data").put("kind", changed.kind());
                try { emit(writer, frame); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }) : () -> { };
        disconnected = false;
        deferred.clear();
        try (var incoming = new IpcInput(input)) {
            write(writer, ready); // The first write is covered by the same failure cleanup as all later frames.
            if (session != null) session.inputPump(() -> pump(incoming, writer));
            while (!disconnected) {
                writer.check();
                if (session != null) session.drainCallbacks();
                String line;
                if (!deferred.isEmpty() && (session == null || !session.busy())) line = deferred.removeFirst();
                else {
                    var received = session == null ? incoming.take() : incoming.poll(25);
                    if (received == null) continue;
                    if (received.line() == null) {
                        endInput(received);
                        break;
                    }
                    line = received.line();
                }
                if (session != null && session.busy() && !priority(line)) {
                    defer(line, writer);
                } else write(writer, dispatch(line));
            }
        } catch (UncheckedIOException e) {
            outputFailed = true;
            throw e.getCause();
        } catch (IOException e) {
            outputFailed = true;
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IPC host interrupted", e);
        } finally {
            try {
                try { subscription.close(); } catch (Exception e) { throw new IOException(e); }
                if (session != null) {
                    if (outputFailed) session.events(event -> { });
                    session.inputPump(() -> { });
                    try { session.shutdownWorkers(); }
                    finally {
                        session.events(event -> { });
                        session.silentChanges(() -> { });
                    }
                }
                else files.close();
            } finally { writer.close(); }
        }
    }

    private void emit(IpcOutput writer, ObjectNode event) throws IOException {
        var data = (ObjectNode) event.get("data");
        data.put("revision", ++revision);
        if (event.path("event").asText().equals("workspaceChanged")) {
            data.set("snapshot", codec.file(snapshot()));
        }
        write(writer, event);
    }

    private ObjectNode snapshot() throws IOException {
        if (currentSnapshot == null || snapshotRevision != revision) {
            currentSnapshot = codec.workspace(port.queries().workspace(), revision);
            snapshotRevision = revision;
        }
        return currentSnapshot;
    }

    private void endInput(IpcInput.Received received) throws IOException {
        disconnected = true;
        if (received.failure() != null) throw received.failure();
        if (session != null) session.disconnect();
    }

    private void pump(IpcInput incoming, IpcOutput writer) {
        if (disconnected) return;
        try {
            writer.check();
            // EOF is not delayed behind a backlog of ordinary requests while a confirmation is pending.
            if (incoming.end() != null) { endInput(incoming.end()); return; }
            var received = incoming.poll();
            if (received == null) return;
            if (received.line() == null) { endInput(received); return; }
            JsonNode request;
            try { request = json.readTree(received.line()); }
            catch (JsonProcessingException e) { write(writer, dispatch(received.line())); return; }
            if (request != null && priority(received.line())) {
                write(writer, dispatch(received.line()));
            } else {
                defer(received.line(), writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean priority(String line) {
        try {
            JsonNode request = json.readTree(line);
            return request != null && java.util.List.of("confirmAnswer", "cancelWorkspaceActivity", "cancelPreviewLoad",
                    "resolvePreviewRegion", "activityGeneration", "previewGeneration", "previewLoadingPath",
                    "startPreparse", "startPreparseFor", "refreshPreparseProgress", "loadPreview", "awaitPreviewInFlight",
                    "refreshPreparseProgress", "startExtraction", "cancelExtraction").contains(request.path("method").asText());
        } catch (JsonProcessingException e) { return true; }
    }

    private void defer(String line, IpcOutput writer) throws IOException {
        if (deferred.size() < 256) deferred.addLast(line);
        else {
            JsonNode request;
            try { request = json.readTree(line); } catch (JsonProcessingException e) { write(writer, dispatch(line)); return; }
            String id = request != null && request.path("id").isTextual() ? request.path("id").asText() : null;
            write(writer, error(id, "INVALID_REQUEST", "Too many queued requests during activity"));
        }
    }

    private ObjectNode dispatch(String line) {
        JsonNode request;
        try {
            request = json.readTree(line);
        } catch (JsonProcessingException e) {
            return error(null, "BAD_JSON", "Expected one JSON object per line");
        }
        String id = request != null && request.path("id").isTextual()
                && !request.path("id").asText().isEmpty() ? request.path("id").asText() : null;
        if (request == null || !request.isObject() || id == null
                || !request.path("v").isIntegralNumber() || !request.path("v").canConvertToInt()
                || request.path("v").intValue() != 1
                || !request.path("type").asText().equals("request")
                || !request.path("method").isTextual() || !request.path("params").isObject()) {
            return error(id, "INVALID_REQUEST", "Expected v=1, type=request, nonempty string id, method and object params");
        }
        JsonNode params = request.path("params");
        if (request.path("method").asText().equals("hello")) return hello(id, params);
        if (!welcomed) return error(id, "HANDSHAKE_FAILED", "Send matching hello before business requests");
        try {
            JsonNode result;
            switch (request.path("method").asText()) {
                case "workspace" -> {
                    if (params.has("bulk") && !params.path("bulk").isBoolean()) throw new IllegalArgumentException("bulk boolean required");
                    var snapshot = snapshot();
                    if (params.path("bulk").asBoolean()) {
                        var bulk = json.createObjectNode().put("revision", revision);
                        bulk.set("manifest", codec.file(snapshot));
                        result = bulk;
                    } else result = snapshot;
                }
                case "setChecked" -> {
                    if (!params.path("checked").isBoolean()) {
                        return error(id, "INVALID_PARAMS", "setChecked requires paths array and boolean checked");
                    }
                    // Validate the entire array before invoking a mutating command.
                    var paths = new ArrayList<String>();
                    if (params.has("paths") == params.has("pathsFile")) throw new IllegalArgumentException("Exactly one paths source required");
                    var values = params.has("pathsFile") ? files.readUpload(params.path("pathsFile")) : params.path("paths");
                    if (!values.isArray()) throw new IllegalArgumentException("Paths array required");
                    for (JsonNode path : values) {
                        if (!path.isTextual() && !path.isNull()) {
                            return error(id, "INVALID_PARAMS", "paths entries must be strings or null");
                        }
                        paths.add(path.isNull() ? null : path.textValue());
                        if (params.has("pathsFile") && !path.isNull()) IpcFiles.input(path.textValue());
                    }
                    port.commands().setChecked(paths, params.path("checked").booleanValue());
                    result = json.nullNode();
                }
                default -> {
                    String method = request.path("method").asText();
                    if (session == null || (!IpcSession.METHODS.contains(method) && !IpcSession.STRUCTURE_METHODS.contains(method)
                            && !IpcSession.ACTIVITY_METHODS.contains(method) && !IpcSession.PARAM_METHODS.contains(method)
                            && !IpcSession.EXTRACTION_METHODS.contains(method) && !IpcSession.DETAIL_METHODS.contains(method)
                            && !IpcSession.NAMED_CONFIG_METHODS.contains(method))) {
                        return error(id, "UNKNOWN_METHOD", "Unknown method: " + method);
                    }
                    if (method.equals("workspacePage")) result = page(params);
                    else {
                        if ((method.equals("applyDrop") || java.util.List.of("setTitleOverride", "applyParamsToTargets",
                                "parameterState", "preStartState", "presentation").contains(method))
                                && IpcSession.integer(params, "revision") != revision) {
                            throw new IllegalArgumentException("Stale revision");
                        }
                        try { result = session.call(method, params); }
                        catch (IOException error) {
                            if (IpcSession.NAMED_CONFIG_METHODS.contains(method))
                                return error(id, "CONFIG_IO_ERROR", error.getMessage());
                            throw error;
                        }
                        if (method.equals("exportSource")) ((ObjectNode) result).put("sessionId", sessionId);
                        if (method.equals("releaseFiles")) {
                            currentSnapshot = null;
                            pageSnapshot = null;
                        }
                        if (result.isObject() && (method.equals("applyDrop") || method.equals("canUndo")
                                || IpcSession.NAMED_CONFIG_METHODS.contains(method)
                                || java.util.List.of("activityGeneration", "previewGeneration", "previewLoadingPath",
                                        "runtimeConfig", "outputUnitPlan", "paramSchema", "parameterState", "preStartState",
                                        "presentation", "cacheState", "exportSource").contains(method))) {
                            ((ObjectNode) result).put("revision", revision);
                        }
                    }
                }
            }
            ObjectNode response = frame("response").put("id", id);
            response.put("revision", revision);
            response.set("result", result);
            return response;
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IllegalArgumentException | JsonProcessingException | java.nio.file.NoSuchFileException e) {
            return error(id, "INVALID_PARAMS", "Invalid method parameters");
        } catch (Exception e) {
            log.error("IPC request failed: id={}", id, e);
            return error(id, "INTERNAL_ERROR", "Request failed; see backend log");
        }
    }

    private ObjectNode hello(String id, JsonNode params) {
        // A failed repeat handshake revokes the previous agreement as well.
        welcomed = false;
        if (!params.path("protocolVersion").isIntegralNumber()
                || !params.path("protocolVersion").canConvertToInt()
                || params.path("protocolVersion").intValue() < 1
                || !params.path("version").isTextual() || params.path("version").asText().isBlank()
                || !params.path("buildId").isTextual() || params.path("buildId").asText().isBlank()) {
            return error(id, "INVALID_PARAMS", "hello requires positive integer protocolVersion and nonempty version/buildId");
        }
        BackendIdentity expected = new BackendIdentity(params.path("protocolVersion").intValue(),
                params.path("version").asText(), params.path("buildId").asText());
        String mismatch = expected.mismatch(identity);
        if (mismatch != null) return error(id, "HANDSHAKE_FAILED", mismatch);
        ObjectNode response = frame("response").put("id", id);
        ObjectNode welcome = response.putObject("result").put("kind", "welcome")
                .put("protocolVersion", identity.protocolVersion()).put("version", identity.version())
                .put("buildId", identity.buildId());
        ArrayNode capabilities = welcome.putArray("capabilities").add("workspace").add("setChecked");
        if (session != null) capabilities.add("workspacePage").add("bulkFiles").add("confirmationRoundTrip");
        if (session != null) IpcSession.EXTRACTION_METHODS.forEach(capabilities::add);
        if (session != null) IpcSession.DETAIL_METHODS.forEach(capabilities::add);
        if (session != null) IpcSession.NAMED_CONFIG_METHODS.forEach(capabilities::add);
        welcome.put("confirmationRoundTrip", session != null);
        welcome.put("sessionId", sessionId).put("bulkRoot", files.root.toString());
        response.put("revision", revision);
        welcomed = true;
        return response;
    }

    private ObjectNode page(JsonNode params) throws IOException {
        for (String key : java.util.List.of("offset", "limit")) {
            if (!params.path(key).isIntegralNumber() || !params.path(key).canConvertToInt()) {
                throw new IllegalArgumentException("Integer page bounds required");
            }
        }
        int offset = params.path("offset").intValue();
        int limit = params.path("limit").intValue();
        if (offset < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("Page bounds out of range");
        if (params.has("revision") && IpcSession.integer(params, "revision") != revision) {
            throw new IllegalArgumentException("Stale page revision");
        }
        if (pageSnapshot == null || pageRevision != revision) {
            pageSnapshot = port.queries().workspace();
            pageRevision = revision;
            pageFile = codec.file(snapshot());
            var flat = new ArrayList<ItemVm>(pageSnapshot.topLevelItems());
            pageSnapshot.groups().forEach(group -> flat.addAll(group.members()));
            pageItems = java.util.List.copyOf(flat);
        }
        ObjectNode result = json.createObjectNode().put("total", pageItems.size()).put("offset", offset)
                .put("groupCount", pageSnapshot.groups().size());
        result.put("revision", pageRevision).set("snapshotFile", pageFile);
        ArrayNode entries = result.putArray("items");
        int end = (int) Math.min(pageItems.size(), (long) offset + limit);
        for (int i = offset; i < end; i++) entries.add(codec.item(pageItems.get(i)));
        return result;
    }

    private ObjectNode frame(String type) {
        return json.createObjectNode().put("v", 1).put("type", type);
    }

    private ObjectNode error(String id, String code, String message) {
        ObjectNode frame = frame("error").put("id", id);
        frame.putObject("error").put("code", code).put("message", message);
        return frame;
    }

    private void write(IpcOutput writer, ObjectNode frame) throws IOException {
        writer.write(json.writeValueAsString(frame));
    }
}
