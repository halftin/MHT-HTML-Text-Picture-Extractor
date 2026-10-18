package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.OutputUnitVm;
import com.mhtextractor.uiport.ParamSchemaVm;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit wire dictionaries; schema records need no native reflection registration. */
final class IpcParamCodec {
    private final ObjectMapper json;

    IpcParamCodec(ObjectMapper json) { this.json = json; }

    AppConfig readConfig(JsonNode value) throws IOException {
        if (!value.isObject()) throw new IllegalArgumentException("Config object required");
        var defaults = config(new AppConfig());
        var fields = value.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            var expected = defaults.get(field.getKey());
            if (expected == null || (expected.isBoolean() ? !field.getValue().isBoolean()
                    : !field.getValue().isTextual() && !field.getValue().isNull())) {
                throw new IllegalArgumentException("Invalid Config field: " + field.getKey());
            }
        }
        return json.treeToValue(value, AppConfig.class);
    }

    @SuppressWarnings("deprecation")
    ObjectNode config(AppConfig c) {
        return json.createObjectNode()
                .put("separateFiles", c.isSeparateFiles())
                .put("enableLimit", c.isEnableContentLimit()).put("startMarker", c.getStartMarker())
                .put("endMarker", c.getEndMarker()).put("useRegex", c.isUseRegex())
                .put("enableCustomTemplate", c.isEnableTemplate()).put("startTemplate", c.getStartTemplate())
                .put("endTemplate", c.getEndTemplate()).put("addChapterNumber", c.isAddChapterNumber())
                .put("autoFixEncoding", c.isAutoFixEncoding()).put("enableTitleExtract", c.isEnableTitleExtraction())
                .put("titleStartMarker", c.getTitleStartMarker()).put("titleEndMarker", c.getTitleEndMarker())
                .put("titleUseRegex", c.isTitleUseRegex()).put("imageNameExtractEnabled", c.isEnableNameExtraction())
                .put("imageNameStartMarker", c.getImageNameStartMarker()).put("imageNameEndMarker", c.getImageNameEndMarker())
                .put("imageRegionExtractEnabled", c.isEnableRegionExtraction())
                .put("imageRegionStartMarker", c.getImageRegionStartMarker()).put("imageRegionEndMarker", c.getImageRegionEndMarker())
                .put("imageRenameEnabled", c.isEnableBatchRenaming()).put("imageZipEnabled", c.isEnableZipPackaging())
                .put("imageCbzEnabled", c.isEnableCbzPackaging()).put("singleOutputMode", c.isSingleOutputMode())
                .put("legacyMemoryMode", c.isHighSpeedMemoryMode()).put("nameLimitStart", c.getNameLimitStart())
                .put("nameLimitEnd", c.getNameLimitEnd()).put("contentLimitStart", c.getContentLimitStart())
                .put("contentLimitEnd", c.getContentLimitEnd());
    }

    ObjectNode runtimeConfig(AppConfig c) {
        return config(c).put("inputFolder", c.getInputFolder()).put("outputFolder", c.getOutputFolder())
                .put("imageModeEnabled", c.isImageModeEnabled()).put("openFolderAfterComplete", c.isOpenFolderAfterComplete());
    }

    ArrayNode units(List<OutputUnitVm> units) {
        var result = json.createArrayNode();
        for (var unit : units) {
            var node = result.addObject().put("outputName", unit.outputName()).put("group", unit.group());
            node.set("params", runtimeConfig(unit.params()));
            var paths = node.putArray("paths");
            unit.paths().forEach(paths::add);
            node.put("outputPath", unit.outputPath()).put("labelKey", unit.labelKey())
                    .put("baseName", unit.baseName()).put("extension", unit.extension())
                    .put("finalFileName", unit.finalFileName()).put("uniqueName", unit.uniqueName());
        }
        return result;
    }

    ObjectNode schema(ParamSchemaVm schema) {
        var result = json.createObjectNode().put("imageMode", schema.imageMode());
        var groups = result.putArray("groups");
        for (var group : schema.groups()) groups.addObject().put("id", group.id())
                .put("labelKey", group.labelKey()).put("noteKey", group.noteKey());
        var fields = result.putArray("fields");
        for (var field : schema.fields()) {
            var node = fields.addObject().put("id", field.id()).put("type", field.type().name())
                    .put("labelKey", field.labelKey()).put("group", field.group())
                    .put("enabledBy", field.enabledBy()).put("nullWhenDisabled", field.nullWhenDisabled())
                    .put("promptText", field.promptText()).put("templateEnter", field.templateEnter());
            scalar(node, "defaultValue", field.defaultValue());
            scalar(node, "disabledValue", field.disabledValue());
        }
        return result;
    }

    ObjectNode values(Map<String, Object> values) {
        var result = json.createObjectNode();
        values.forEach((key, value) -> scalar(result, key, value));
        return result;
    }

    Map<String, Object> readValues(JsonNode values) {
        if (!values.isObject()) throw new IllegalArgumentException("Form values object required");
        var result = new LinkedHashMap<String, Object>();
        values.fields().forEachRemaining(field -> {
            var value = field.getValue();
            if (!value.isNull() && !value.isBoolean() && !value.isTextual())
                throw new IllegalArgumentException("Invalid form value: " + field.getKey());
            result.put(field.getKey(), value.isNull() ? null : value.isBoolean() ? value.booleanValue() : value.textValue());
        });
        return result;
    }

    private void scalar(ObjectNode node, String key, Object value) {
        switch (value) {
            case null -> node.putNull(key);
            case Boolean b -> node.put(key, b);
            case String s -> node.put(key, s);
            default -> throw new IllegalStateException("Unsupported schema value: " + key);
        }
    }
}
