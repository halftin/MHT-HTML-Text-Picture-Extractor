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
        putOptional(result, "modifiedTime", vm.modifiedTime());
        putOptional(result, "status", vm.status());
        putOptional(result, "errorMsg", vm.errorMsg());
        if (vm.chapterEnabled() != null) result.put("chapterEnabled", vm.chapterEnabled());
        putOptional(result, "badge", vm.badge());
        putOptional(result, "cachedTitle", vm.cachedTitle());
        putOptional(result, "titleOverride", vm.titleOverride());
        putOptional(result, "extractOutcome", vm.extractOutcome());
        putOptional(result, "extractDetail", vm.extractDetail());
        if (vm.textFile() != null) result.set("textFile", reference(vm.textFile()));
        if (vm.previewManifest() != null) result.set("previewManifest", reference(vm.previewManifest()));
        return result;
    }

    private static void putOptional(ObjectNode value, String key, String text) {
        if (text != null) value.put(key, text);
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
        if (vm.details() != null) {
            var d = vm.details();
            var details = result.putObject("details").put("textConfirmed", d.textConfirmed()).put("imageConfirmed", d.imageConfirmed())
                    .put("activityGeneration", d.activityGeneration()).put("previewGeneration", d.previewGeneration())
                    .put("loadingPath", d.loadingPath()).put("importing", d.importing()).put("importGeneration", d.importGeneration())
                    .put("running", d.running()).put("extractionGeneration", d.extractionGeneration()).put("sessionOutputFolder", d.sessionOutputFolder());
            details.set("lastStats", reference(d.lastStats()));
        }
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
        if (vm.confirmation() != null) entry.putObject("confirmation").put("textConfirmed", vm.confirmation().textConfirmed())
                .put("imageConfirmed", vm.confirmation().imageConfirmed());
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

    com.fasterxml.jackson.databind.JsonNode reference(com.mhtextractor.uiport.FileRef ref) {
        if (ref == null) return json.nullNode();
        try {
            // Copy value files into this session's issued set; a frontend cannot release another owner's files.
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(ref.path()));
            if (ref.mediaType().equals("application/json")) {
                var content = json.readTree(bytes);
                issueNested(content);
                return files.write(".json", json.writeValueAsBytes(content), ref.mediaType());
            }
            return files.write(ref.mediaType().startsWith("text/html") ? ".html" : ".txt", bytes, ref.mediaType());
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private void issueNested(com.fasterxml.jackson.databind.JsonNode node) throws IOException {
        if (node.isObject() && node.has("path") && node.has("bytes") && node.has("mediaType")) {
            var path = java.nio.file.Path.of(node.path("path").asText());
            if (node.path("mediaType").asText().startsWith("image/")) files.borrowImage(path, node.path("mediaType").asText());
            else files.issue(path, node.path("mediaType").asText());
        } else if (node.isContainerNode()) for (var child : node) issueNested(child);
    }
}
