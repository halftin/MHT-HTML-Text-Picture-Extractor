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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * v1 stdio control frames: one UTF-8 JSON object plus LF per frame (see IPC.md).
 * A single reader dispatches synchronously to the port and flushes one response per request.
 * The initial ready event precedes all responses; setChecked is silent at the port and
 * therefore emits no fabricated change events. IO failure ends the session; malformed
 * requests produce error frames and leave the session usable.
 */
@Slf4j
final class IpcServer {
    private final Uiport port;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    IpcServer(Uiport port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    void run(InputStream input, OutputStream output) throws IOException {
        // Borrowed stdio: the caller owns stream lifetime. Never use PrintWriter (swallows IO errors).
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        ObjectNode ready = frame("event").put("event", "ready");
        ready.putObject("data").putArray("methods").add("workspace").add("setChecked");
        write(writer, ready);
        String line;
        while ((line = reader.readLine()) != null) {
            write(writer, dispatch(line));
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
                    result = json.nullNode();
                }
                default -> {
                    return error(id, "UNKNOWN_METHOD", "Unknown method: " + request.path("method").asText());
                }
            }
            ObjectNode response = frame("response").put("id", id);
            response.set("result", result);
            return response;
        } catch (RuntimeException e) {
            log.error("IPC request failed: id={}", id, e);
            return error(id, "INTERNAL_ERROR", "Request failed; see backend log");
        }
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
