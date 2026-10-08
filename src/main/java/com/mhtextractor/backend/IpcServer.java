package com.mhtextractor.backend;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.uiport.GroupVm;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.Uiport;
import com.mhtextractor.uiport.WorkspaceVm;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * v1 stdio control frames: one UTF-8 JSON object plus LF per frame (see IPC.md).
 * A single reader dispatches synchronously to the port and flushes one response per request.
 * The initial ready event precedes all responses; setChecked is silent at the port and
 * therefore emits no fabricated change events. IO failure ends the session; malformed
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
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    IpcServer(Uiport port) throws IOException {
        this.port = Objects.requireNonNull(port, "port");
        this.session = null;
        this.identity = BackendIdentity.current();
    }

    IpcServer(IpcSession session) throws IOException {
        this.port = session.port;
        this.session = session;
        this.identity = BackendIdentity.current();
    }

    void run(InputStream input, OutputStream output) throws IOException {
        welcomed = false;
        // Borrowed stdio: the caller owns stream lifetime. Never use PrintWriter (swallows IO errors).
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        ObjectNode ready = frame("event").put("event", "ready");
        ArrayNode methods = ready.putObject("data").putArray("methods").add("workspace").add("setChecked");
        if (session != null) {
            IpcSession.METHODS.forEach(methods::add);
            session.events(event -> {
                try { write(writer, event); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        methods.add("hello");
        write(writer, ready);
        String line;
        while ((line = reader.readLine()) != null) {
            try { write(writer, dispatch(line)); }
            catch (UncheckedIOException e) { throw e.getCause(); }
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
                case "workspace" -> result = workspace(port.queries().workspace());
                case "setChecked" -> {
                    if (!params.path("paths").isArray() || !params.path("checked").isBoolean()) {
                        return error(id, "INVALID_PARAMS", "setChecked requires paths array and boolean checked");
                    }
                    // Validate the entire array before invoking a mutating command.
                    var paths = new ArrayList<String>();
                    for (JsonNode path : params.path("paths")) {
                        if (!path.isTextual() && !path.isNull()) {
                            return error(id, "INVALID_PARAMS", "paths entries must be strings or null");
                        }
                        paths.add(path.isNull() ? null : path.textValue());
                    }
                    port.commands().setChecked(paths, params.path("checked").booleanValue());
                    pageSnapshot = null;
                    result = json.nullNode();
                }
                default -> {
                    String method = request.path("method").asText();
                    if (session == null || !IpcSession.METHODS.contains(method)) {
                        return error(id, "UNKNOWN_METHOD", "Unknown method: " + method);
                    }
                    if (method.equals("workspacePage")) result = page(params);
                    else {
                        result = session.call(method, params);
                        pageSnapshot = null;
                    }
                }
            }
            ObjectNode response = frame("response").put("id", id);
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
        if (session != null) capabilities.add("workspacePage").add("bulkFiles");
        welcome.put("confirmationRoundTrip", false);
        welcomed = true;
        return response;
    }

    private ObjectNode page(JsonNode params) {
        for (String key : java.util.List.of("offset", "limit")) {
            if (!params.path(key).isIntegralNumber() || !params.path(key).canConvertToInt()) {
                throw new IllegalArgumentException("Integer page bounds required");
            }
        }
        int offset = params.path("offset").intValue();
        int limit = params.path("limit").intValue();
        if (offset < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("Page bounds out of range");
        if (pageSnapshot == null) {
            pageSnapshot = port.queries().workspace();
            var flat = new ArrayList<ItemVm>(pageSnapshot.topLevelItems());
            pageSnapshot.groups().forEach(group -> flat.addAll(group.members()));
            pageItems = java.util.List.copyOf(flat);
        }
        ObjectNode result = json.createObjectNode().put("total", pageItems.size()).put("offset", offset)
                .put("groupCount", pageSnapshot.groups().size());
        ArrayNode entries = result.putArray("items");
        int end = (int) Math.min(pageItems.size(), (long) offset + limit);
        for (int i = offset; i < end; i++) entries.add(item(pageItems.get(i)));
        return result;
    }

    // Explicit wire mapping avoids reflective DTO registration for a future native backend.
    // Tests compare this mapping against Jackson's independent serialization of the port records.
    private ObjectNode workspace(WorkspaceVm vm) {
        ObjectNode result = json.createObjectNode();
        ArrayNode groups = result.putArray("groups");
        for (GroupVm group : vm.groups()) {
            ObjectNode entry = groups.addObject().put("no", group.no()).put("name", group.name())
                    .put("expanded", group.expanded()).put("defaultNamed", group.defaultNamed());
            ArrayNode members = entry.putArray("members");
            group.members().forEach(item -> members.add(item(item)));
        }
        ArrayNode items = result.putArray("topLevelItems");
        vm.topLevelItems().forEach(item -> items.add(item(item)));
        return result.put("canUndo", vm.canUndo()).put("undoDepth", vm.undoDepth())
                .put("selection", vm.selection().name());
    }

    private ObjectNode item(ItemVm vm) {
        return json.createObjectNode().put("id", vm.id()).put("name", vm.name())
                .put("checked", vm.checked()).put("removed", vm.removed())
                .put("unitIndex", vm.unitIndex()).put("update", vm.update().name());
    }

    private ObjectNode frame(String type) {
        return json.createObjectNode().put("v", 1).put("type", type);
    }

    private ObjectNode error(String id, String code, String message) {
        ObjectNode frame = frame("error").put("id", id);
        frame.putObject("error").put("code", code).put("message", message);
        return frame;
    }

    private void write(BufferedWriter writer, ObjectNode frame) throws IOException {
        writer.write(json.writeValueAsString(frame));
        writer.write('\n');
        writer.flush();
    }
}
