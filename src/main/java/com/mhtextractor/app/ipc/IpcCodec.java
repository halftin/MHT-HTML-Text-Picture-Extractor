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

/** Explicit protocol v2 decoding; no record reflection registration is needed by a native frontend. */
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
    private static String optional(JsonNode n, String key) {
        return n.hasNonNull(key) ? text(n,key) : null;
    }
    /** Keep value-object references leased until the caller releases them or the session closes. */
    public FileRef reference(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        var ref = files.accept(n);
        try { files.validate(ref); }
        catch (IOException e) { throw new IllegalArgumentException("Invalid retained FileRef",e); }
        return new FileRef(ref.path(),ref.bytes(),ref.mediaType());
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
                UpdateKind.valueOf(text(n,"update")),nullable(n,"sourceFolder"),
                optional(n,"modifiedTime"),optional(n,"status"),optional(n,"errorMsg"),
                n.hasNonNull("chapterEnabled") ? bool(n,"chapterEnabled") : null,
                optional(n,"badge"),optional(n,"cachedTitle"),optional(n,"titleOverride"),
                optional(n,"extractOutcome"),optional(n,"extractDetail"),
                reference(n.path("textFile")),reference(n.path("previewManifest")));
    }
    public GroupVm group(JsonNode n) {
        return new GroupVm(number(n,"no"),text(n,"name"),bool(n,"expanded"),bool(n,"defaultNamed"),
                list(n.path("members"),this::item),list(n.path("sourceFolders"),v -> v.isNull() ? null : string(v)),
                n.hasNonNull("confirmation") ? new GroupVm.Confirmation(bool(n.path("confirmation"),"textConfirmed"),
                        bool(n.path("confirmation"),"imageConfirmed")) : null);
    }
    public static TargetId target(JsonNode n) {
        return new TargetId(nullable(n,"path"),n.path("groupNo").isNull() ? null : number(n,"groupNo"));
    }
    public WorkspaceVm workspace(JsonNode n) throws IOException {
        return new WorkspaceVm(list(n.path("groups"),this::group),list(n.path("topLevelItems"),this::item),
                bool(n,"canUndo"),integer(n,"undoDepth"),TriState.valueOf(text(n,"selection")),
                list(files.consumeJson(n.path("topLevelOrder")),IpcCodec::target),details(n.path("details")));
    }
    private WorkspaceVm.Details details(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        return new WorkspaceVm.Details(bool(n,"textConfirmed"),bool(n,"imageConfirmed"),number(n,"activityGeneration"),
                number(n,"previewGeneration"),nullable(n,"loadingPath"),bool(n,"importing"),number(n,"importGeneration"),
                bool(n,"running"),number(n,"extractionGeneration"),reference(n.path("lastStats")),nullable(n,"sessionOutputFolder"));
    }
    private AppConfig parameters(JsonNode n) throws IOException {
        return n.isNull() ? null : json.treeToValue(n,AppConfig.class);
    }
    public ParameterStateVm parameterState(JsonNode n) throws IOException {
        var states = new ArrayList<ParameterStateVm.TargetState>();
        for (var s : n.path("states")) states.add(new ParameterStateVm.TargetState(target(s.path("target")),
                bool(s,"independent"),bool(s,"effective"),text(s,"source"),parameters(s.path("config"))));
        return new ParameterStateVm(bool(n,"textConfirmed"),parameters(n.path("textGlobal")),bool(n,"imageConfirmed"),
                parameters(n.path("imageGlobal")),list(n.path("targets"),IpcCodec::target),integer(n,"count"),states,
                bool(n,"uniform"),bool(n,"hasEffective"));
    }
    public PreStartStateVm preStart(JsonNode n) {
        var d = n.path("denial");
        return new PreStartStateVm(d.isNull() ? null : new PreStartStateVm.Denial(text(d,"key"),text(d,"level"),map(d.path("namedParams"))),
                bool(n,"textConfirmed"),bool(n,"imageConfirmed"),strings(n.path("defaultNamedGroups")),bool(n,"subsetMergedName"));
    }
    public synchronized List<PresentationVm> presentation(JsonNode n) throws IOException {
        if (!n.isArray()) throw new IllegalArgumentException("Presentation array required");
        var result = new ArrayList<PresentationVm>();
        for (var p : n) {
            var images = new ArrayList<PortEvent.ImageFile>();
            for (var i : p.path("images")) images.add(image(i));
            result.add(new PresentationVm(text(p,"path"),nullable(p,"displayName"),bool(p,"parsing"),bool(p,"imageMode"),
                    text(p,"status"),nullable(p,"head"),nullable(p,"tail"),reference(p.path("textFile")),images,
                    nullable(p,"baseName"),nullable(p,"extension"),nullable(p,"finalFileName")));
        }
        // Presentation images are retained value references, not transient preview-event payloads.
        imageLeases.clear();
        return List.copyOf(result);
    }
    public ExportSourceVm exportSource(JsonNode n) {
        return new ExportSourceVm(text(n,"status"),reference(n.path("htmlFile")),integer(n,"imageCount"),
                integer(n,"warningCount"),nullable(n,"detail"));
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
                strings(u.path("paths")),nullable(u,"outputPath"),nullable(u,"labelKey"),
                nullable(u,"baseName"),nullable(u,"extension"),nullable(u,"finalFileName"),nullable(u,"uniqueName")));
        return List.copyOf(result);
    }
    private Object scalar(JsonNode n) {
        if (n.isNull()) return null;
        if (n.isBoolean()) return n.booleanValue();
        return string(n);
    }
    public Map<String, Object> values(JsonNode n) {
        if (!n.isObject()) throw new IllegalArgumentException("Form values object required");
        var values = new LinkedHashMap<String, Object>();
        n.fields().forEachRemaining(field -> values.put(field.getKey(), scalar(field.getValue())));
        return values;
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
            if (n.hasNonNull("dimensions") && (integer(n.path("dimensions"),"width") != integer(n,"width")
                    || integer(n.path("dimensions"),"height") != integer(n,"height"))) throw new IOException("Image dimensions mismatch");
            return n.has("displayName")
                    ? new PortEvent.ImageFile(text(n,"imagePath"),ref.bytes(),ref.mediaType(),text(n,"extension"),
                        text(n,"coreName"),integer(n,"width"),integer(n,"height"),integer(n,"index"),nullable(n,"displayName"))
                    : new PortEvent.ImageFile(text(n,"imagePath"),ref.bytes(),ref.mediaType(),text(n,"extension"),
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
            case "cacheSizeAlert" -> new PortEvent.CacheSizeAlert(integer(n,"step"), number(n,"actualBytes"),
                    number(n,"stepBytes"), text(n,"root"), text(n,"key"), map(n.path("namedParams")),
                    text(n,"level"), number(n,"revision"));
            case "backendLog" -> new PortEvent.BackendLog(text(n,"level"), text(n,"key"),
                    map(n.path("namedParams")), number(n,"timestamp"), number(n,"revision"));
            case "confirmationRequested", "confirmationExpired" -> null;
            case "workspaceChanged" -> new PortEvent.WorkspaceChanged(text(n,"kind"),workspace(files.consumeJson(n.path("snapshot"))),
                    optional(n,"undoKind"),n.hasNonNull("affected") ? list(files.consumeJson(n.path("affected")),IpcCodec::target) : List.of(),
                    n.hasNonNull("suggestedSelection") ? target(n.path("suggestedSelection")) : null);
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
