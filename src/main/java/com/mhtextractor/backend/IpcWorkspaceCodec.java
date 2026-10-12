package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.ImportPlanVm;
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
        for (var group : vm.groups()) groups.add(group(group));
        var items = result.putArray("topLevelItems");
        vm.topLevelItems().forEach(i -> items.add(item(i)));
        var order = json.createArrayNode();
        for (TargetId target : vm.topLevelOrder()) order.addObject().put("path", target.path()).put("groupNo", target.groupNo());
        result.set("topLevelOrder", file(order));
        return result.put("canUndo", vm.canUndo()).put("undoDepth", vm.undoDepth())
                .put("selection", vm.selection().name()).put("revision", revision);
    }

    ObjectNode group(com.mhtextractor.uiport.GroupVm vm) {
        var entry = json.createObjectNode().put("no", vm.no()).put("name", vm.name())
                .put("expanded", vm.expanded()).put("defaultNamed", vm.defaultNamed());
        var members = entry.putArray("members");
        vm.members().forEach(m -> members.add(item(m)));
        var folders = entry.putArray("sourceFolders");
        vm.sourceFolders().forEach(folders::add);
        return entry;
    }

    /** Import events also need a full projection in native, where record reflection is unavailable. */
    ObjectNode plan(ImportPlanVm vm) {
        var result = json.createObjectNode().put("ignoredNonMht", vm.ignoredNonMht()).put("hiddenDirs", vm.hiddenDirs())
                .put("emptyDirs", vm.emptyDirs()).put("unreadable", vm.unreadable()).put("depthLimited", vm.depthLimited())
                .put("quotaHit", vm.quotaHit()).put("hasMore", vm.hasMore());
        var batches = result.putArray("batches");
        for (var batch : vm.batches()) {
            var value = batches.addObject().put("dirPath", batch.dirPath()).put("folderName", batch.folderName())
                    .put("grouped", batch.grouped());
            var paths = value.putArray("filePaths");
            batch.filePaths().forEach(paths::add);
        }
        return result;
    }

    ObjectNode file(com.fasterxml.jackson.databind.JsonNode content) throws IOException {
        return files.write(".json", json.writeValueAsBytes(content), "application/json");
    }
}
