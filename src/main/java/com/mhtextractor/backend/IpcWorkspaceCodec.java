package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.TargetId;
import com.mhtextractor.uiport.WorkspaceVm;

import java.io.IOException;

/** Explicit v1 projection shared by legacy, paged and event snapshots. */
final class IpcWorkspaceCodec {
    private final ObjectMapper json;
    private final IpcFiles files;

    IpcWorkspaceCodec(ObjectMapper json, IpcFiles files) { this.json = json; this.files = files; }

    ObjectNode item(ItemVm vm) {
        var result = json.createObjectNode().put("id", vm.id()).put("name", vm.name())
                .put("checked", vm.checked()).put("removed", vm.removed())
                .put("unitIndex", vm.unitIndex()).put("update", vm.update().name());
        result.put("sourceFolder", vm.sourceFolder());
        return result;
    }

    ObjectNode workspace(WorkspaceVm vm, long revision) throws IOException {
        var result = json.createObjectNode();
        var groups = result.putArray("groups");
        for (var group : vm.groups()) {
            var entry = groups.addObject().put("no", group.no()).put("name", group.name())
                    .put("expanded", group.expanded()).put("defaultNamed", group.defaultNamed());
            var members = entry.putArray("members");
            group.members().forEach(m -> members.add(item(m)));
            var folders = entry.putArray("sourceFolders");
            group.sourceFolders().forEach(folders::add);
        }
        var items = result.putArray("topLevelItems");
        vm.topLevelItems().forEach(i -> items.add(item(i)));
        var order = json.createArrayNode();
        for (TargetId target : vm.topLevelOrder()) order.addObject().put("path", target.path()).put("groupNo", target.groupNo());
        result.set("topLevelOrder", file(order));
        return result.put("canUndo", vm.canUndo()).put("undoDepth", vm.undoDepth())
                .put("selection", vm.selection().name()).put("revision", revision);
    }

    ObjectNode file(com.fasterxml.jackson.databind.JsonNode content) throws IOException {
        return files.write(".json", json.writeValueAsBytes(content), "application/json");
    }
}
