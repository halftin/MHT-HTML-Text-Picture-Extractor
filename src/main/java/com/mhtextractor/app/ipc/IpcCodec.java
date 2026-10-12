package com.mhtextractor.app.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.model.*;
import com.mhtextractor.uiport.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Explicit v1 decoding; no record reflection registration is needed by a native frontend. */
public final class IpcCodec {
    private final ObjectMapper json;
    private final IpcFilesClient files;
    private final Map<String,IpcFilesClient.FileRef> imageLeases = new LinkedHashMap<>();
    public IpcCodec(ObjectMapper json, IpcFilesClient files) { this.json = json; this.files = files; }

    public static long number(JsonNode n, String key) {
        var v = n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong()) throw new IllegalArgumentException("Integer required: " + key);
        return v.longValue();
    }
    public static int integer(JsonNode n, String key) { return Math.toIntExact(number(n, key)); }
    public static boolean bool(JsonNode n, String key) {
        if (!n.path(key).isBoolean()) throw new IllegalArgumentException("Boolean required: " + key);
        return n.path(key).booleanValue();
    }
    public static String text(JsonNode n, String key) {
        if (!n.path(key).isTextual()) throw new IllegalArgumentException("String required: " + key);
        return n.path(key).textValue();
    }
    public static String nullable(JsonNode n, String key) {
        if (!n.has(key)) throw new IllegalArgumentException("Missing field: " + key);
        return n.path(key).isNull() ? null : text(n, key);
    }
    public static <T> List<T> list(JsonNode n, Function<JsonNode,T> mapper) {
        if (!n.isArray()) throw new IllegalArgumentException("Array required");
        var values = new ArrayList<T>();
        for (var value : n) values.add(mapper.apply(value));
        return java.util.Collections.unmodifiableList(values);
    }
    private static String string(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("String required");
        return value.textValue();
    }
    public static List<String> strings(JsonNode n) { return list(n, IpcCodec::string); }
    private Map<String,String> map(JsonNode n) {
        if (!n.isObject()) throw new IllegalArgumentException("String dictionary required");
        var values = new LinkedHashMap<String,String>();
        n.fields().forEachRemaining(f -> values.put(f.getKey(), string(f.getValue())));
        return values;
    }

    public ItemVm item(JsonNode n) {
        if (!n.has("unitIndex")) throw new IllegalArgumentException("Missing unitIndex");
        return new ItemVm(text(n,"id"),text(n,"name"),bool(n,"checked"),bool(n,"removed"),
                n.path("unitIndex").isNull() ? null : integer(n,"unitIndex"),
                UpdateKind.valueOf(text(n,"update")),nullable(n,"sourceFolder"));
    }
    public GroupVm group(JsonNode n) {
        return new GroupVm(number(n,"no"),text(n,"name"),bool(n,"expanded"),bool(n,"defaultNamed"),
                list(n.path("members"),this::item),list(n.path("sourceFolders"),v -> v.isNull() ? null : string(v)));
    }
    public static TargetId target(JsonNode n) {
        return new TargetId(nullable(n,"path"),n.path("groupNo").isNull() ? null : number(n,"groupNo"));
    }
    public WorkspaceVm workspace(JsonNode n) throws IOException {
        return new WorkspaceVm(list(n.path("groups"),this::group),list(n.path("topLevelItems"),this::item),
                bool(n,"canUndo"),integer(n,"undoDepth"),TriState.valueOf(text(n,"selection")),
                list(files.consumeJson(n.path("topLevelOrder")),IpcCodec::target));
    }
    public ImportPlanVm plan(JsonNode n) {
        return new ImportPlanVm(list(n.path("batches"), b -> new ImportPlanVm.ImportBatchVm(text(b,"dirPath"),
                nullable(b,"folderName"),bool(b,"grouped"),strings(b.path("filePaths")))),
                integer(n,"ignoredNonMht"),integer(n,"hiddenDirs"),integer(n,"emptyDirs"),integer(n,"unreadable"),
                integer(n,"depthLimited"),bool(n,"quotaHit"),bool(n,"hasMore"));
    }
    public ObjectNode target(TargetId t) {
        return t == null ? json.createObjectNode().putNull("path").putNull("groupNo")
                : json.createObjectNode().put("path",t.path()).put("groupNo",t.groupNo());
    }
    public ObjectNode plan(ImportPlanVm p) {
        var n = json.createObjectNode().put("ignoredNonMht",p.ignoredNonMht()).put("hiddenDirs",p.hiddenDirs())
                .put("emptyDirs",p.emptyDirs()).put("unreadable",p.unreadable()).put("depthLimited",p.depthLimited())
                .put("quotaHit",p.quotaHit()).put("hasMore",p.hasMore());
        var batches = n.putArray("batches");
        for (var b : p.batches()) batches.addObject().put("dirPath",b.dirPath()).put("folderName",b.folderName())
                .put("grouped",b.grouped()).set("filePaths",json.valueToTree(b.filePaths()));
        return n;
    }
    public AppConfig config(JsonNode n) throws IOException {
        if (!n.isObject()) throw new IllegalArgumentException("Config required");
        var params = (ObjectNode)n.deepCopy();
        params.remove(List.of("inputFolder","outputFolder","imageModeEnabled","openFolderAfterComplete"));
        AppConfig c = json.treeToValue(params,AppConfig.class);
        c.setInputFolder(nullable(n,"inputFolder")); c.setOutputFolder(nullable(n,"outputFolder"));
        c.setImageModeEnabled(bool(n,"imageModeEnabled")); c.setOpenFolderAfterComplete(bool(n,"openFolderAfterComplete"));
        return c;
    }
    public List<OutputUnitVm> units(JsonNode n) throws IOException {
        var result = new ArrayList<OutputUnitVm>();
        if (!n.isArray()) throw new IllegalArgumentException("Output units required");
        for (var u : n) result.add(new OutputUnitVm(nullable(u,"outputName"),bool(u,"group"),config(u.path("params")),
                strings(u.path("paths")),nullable(u,"outputPath"),nullable(u,"labelKey")));
        return List.copyOf(result);
    }
    private Object scalar(JsonNode n) {
        if (n.isNull()) return null;
        if (n.isBoolean()) return n.booleanValue();
        return string(n);
    }
    public ParamSchemaVm schema(JsonNode n) {
        return new ParamSchemaVm(bool(n,"imageMode"),list(n.path("groups"),g -> new ParamSchemaVm.Group(text(g,"id"),
                text(g,"labelKey"),nullable(g,"noteKey"))),list(n.path("fields"),f -> new ParamSchemaVm.Field(text(f,"id"),
                ParamSchemaVm.Type.valueOf(text(f,"type")),text(f,"labelKey"),text(f,"group"),scalar(f.path("defaultValue")),
                nullable(f,"enabledBy"),scalar(f.path("disabledValue")),bool(f,"nullWhenDisabled"),nullable(f,"promptText"),bool(f,"templateEnter"))));
    }
    private ExtractionStats stats(JsonNode ref) throws IOException {
        if (ref.isNull()) return null;
        JsonNode n = files.consumeJson(ref);
        return new ExtractionStats(nullable(n,"summaryLine"),integer(n,"failCount"),integer(n,"skipCount"),integer(n,"warnCount"),
                list(n.path("files"),f -> new ExtractionStats.FileOutcome(text(f,"path"),ExtractOutcome.valueOf(text(f,"outcome")),
                        nullable(f,"message"),list(f.path("warnings"),w -> new ExtractWarning(text(w,"key"),map(w.path("args")))))));
    }
    private PortEvent.ImageFile image(JsonNode n) throws IOException {
        // previewThumbnail.path is the source ID; imagePath is the signed cache file.
        var description = (ObjectNode)n.deepCopy();
        description.put("path",text(n,"imagePath"));
        var ref = files.accept(description);
        imageLeases.put(ref.path(), ref);
            // Ownership/existence are checked without converting the original into pixels or retaining its bytes.
            files.validate(ref);
            return new PortEvent.ImageFile(text(n,"imagePath"),ref.bytes(),ref.mediaType(),text(n,"extension"),
                    text(n,"coreName"),integer(n,"width"),integer(n,"height"),integer(n,"index"));
    }
    private void releaseImages() {
        imageLeases.values().forEach(files::releaseAfterUse);
        imageLeases.clear();
    }
    public synchronized PortEvent.PreviewPayload preview(JsonNode ref) throws IOException {
        if (ref.isNull()) { releaseImages(); return null; }
        JsonNode manifest = files.consumeJson(ref);
        String text = files.consumeText(manifest.path("text"));
        String html = files.consumeText(manifest.path("html"));
        var images = new ArrayList<PortEvent.ImageFile>();
        for (var value : manifest.path("thumbnails")) images.add(image(value));
        releaseImages();
        return new PortEvent.PreviewPayload(text,html,images);
    }

    /** Null is reserved for the two known confirmation control events. Unknown kinds always fail. */
    public synchronized PortEvent event(String name, JsonNode n) throws IOException {
        return switch (name) {
            case "confirmationRequested", "confirmationExpired" -> null;
            case "workspaceChanged" -> new PortEvent.WorkspaceChanged(text(n,"kind"),workspace(files.consumeJson(n.path("snapshot"))));
            case "workspaceActivityChanged" -> new PortEvent.WorkspaceActivityChanged(number(n,"generation"));
            case "preparseStarted" -> new PortEvent.PreparseStarted(number(n,"generation"));
            case "preparseProgress" -> new PortEvent.PreparseProgress(number(n,"generation"),integer(n,"done"),integer(n,"total"));
            case "preparseFinished" -> {
                JsonNode r = files.consumeJson(n.path("result"));
                yield new PortEvent.PreparseFinished(number(n,"generation"),new PreparseResult(text(r,"path"),
                        PreviewItem.Status.valueOf(text(r,"status")),nullable(r,"errorKey"),nullable(r,"errorDetail"),
                        nullable(r,"text"),bool(r,"textAssigned"),nullable(r,"title"),bool(r,"titleAssigned")));
            }
            case "paramsChanged" -> new PortEvent.ParamStateChanged(PortEvent.ParamChangeKind.valueOf(text(n,"kind")),
                    bool(n,"imageMode"),strings(files.consumeJson(n.path("affectedPaths"))));
            case "importStateChanged" -> new PortEvent.ImportStateChanged(number(n,"generation"),bool(n,"importing"));
            case "importRound" -> new PortEvent.ImportRound(number(n,"generation"),plan(files.consumeJson(n.path("plan"))),
                    integer(n,"newFiles"),integer(n,"newGroups"),bool(n,"madeListNonEmpty"),List.of(),
                    list(files.consumeJson(n.path("createdGroups")),this::group));
            case "importFinished" -> new PortEvent.ImportFinished(number(n,"generation"),strings(files.consumeJson(n.path("accumulatedPaths"))));
            case "previewLoadStateChanged" -> new PortEvent.PreviewLoadStateChanged(number(n,"generation"),text(n,"path"),bool(n,"active"));
            case "previewConfirmationRequired" -> new PortEvent.PreviewConfirmationRequired(text(n,"path"),text(n,"name"),number(n,"sizeMiB"));
            case "previewLoadFailed" -> new PortEvent.PreviewLoadFailed(number(n,"generation"),text(n,"path"),bool(n,"fileInfo"),nullable(n,"detail"));
            case "previewThumbnail" -> {
                var image = image(n);
                yield new PortEvent.PreviewThumbnail(number(n,"generation"),text(n,"path"),integer(n,"index"),
                        new ThumbnailData(image.coreName(),image.width(),image.height(),null),image);
            }
            case "previewLoaded" -> new PortEvent.PreviewLoaded(number(n,"generation"),text(n,"path"),preview(n.path("manifest")));
            case "previewRegionConfirmationRequired" -> new PortEvent.PreviewRegionConfirmationRequired(number(n,"generation"),text(n,"path"));
            case "previewNotice" -> new PortEvent.PreviewNotice(number(n,"generation"),text(n,"path"),text(n,"key"),map(n.path("namedParams")));
            case "runningChanged" -> new PortEvent.RunningChanged(bool(n,"running"),number(n,"generation"));
            case "extractionProgress" -> {
                if (!n.path("overall").isNumber() || !Double.isFinite(n.path("overall").doubleValue())) throw new IOException("Invalid progress");
                yield new PortEvent.ExtractionProgress(number(n,"generation"),n.path("overall").doubleValue());
            }
            case "extractionTerminal" -> new PortEvent.ExtractionTerminal(number(n,"generation"),PortEvent.ExtractionEnd.valueOf(text(n,"end")),
                    stats(n.path("stats")),nullable(n,"sessionOutputFolder"));
            case "extractionResult" -> new PortEvent.ExtractionResult(number(n,"generation"),stats(n.path("stats")),nullable(n,"sessionOutputFolder"));
            case "notice" -> new PortEvent.Notice(text(n,"key"),map(n.path("namedParams")),NoticeLevel.valueOf(text(n,"level")));
            default -> throw new IOException("Unknown backend event kind: " + name);
        };
    }
}
