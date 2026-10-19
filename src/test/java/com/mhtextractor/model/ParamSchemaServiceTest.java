package com.mhtextractor.model;

import com.mhtextractor.engine.InProcessUiport;
import com.mhtextractor.engine.WorkspaceModel;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.ParamSchemaVm;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 改前 FXML / 表单快照作独立 oracle；不从待测 schema 推导期望规则。 */
class ParamSchemaServiceTest {
    private static List<String[]> before() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/param-schema-before.tsv")).stream()
                .filter(s -> !s.startsWith("#")).map(s -> s.split("\t")).toList();
    }

    private static ParamSchemaVm schema(boolean image) {
        return new InProcessUiport(new WorkspaceModel(), request -> {
            throw new UnsupportedOperationException();
        }).queries().paramSchema(image);
    }

    @Test
    void schemaMatchesEveryLegacyFieldInDisplayAndLoadOrder() throws Exception {
        List<String> expected = new ArrayList<>();
        for (String[] row : before()) expected.add(String.join("\t", row));
        List<String> actual = new ArrayList<>();
        int offset = 0;
        for (boolean image : List.of(false, true)) {
            var schema = schema(image);
            var loadOrder = new ArrayList<>(ParamSchemaService.values(new AppConfig(), schema).keySet());
            for (var f : schema.fields()) {
                String groupKey = schema.groups().stream().filter(g -> g.id().equals(f.group()))
                        .findFirst().orElseThrow().labelKey();
                Object disabled = f.disabledValue();
                String disabledLiteral = disabled == null ? "-" : disabled instanceof String s
                        ? "\"" + s.replace("\\", "\\\\") + "\"" : disabled.toString();
                actual.add(String.join("\t", f.id(), f.type().name(), f.labelKey(), f.group(), groupKey,
                        "" + (offset + loadOrder.indexOf(f.id())),
                        f.enabledBy() == null ? "-" : f.enabledBy(), disabledLiteral));
            }
            offset += loadOrder.size();
        }
        assertEquals(expected, actual);
    }

    @Test
    void defaultsPromptsGroupsAndCollectionRulesMatchLegacy() throws Exception {
        List<Object> expected = new ArrayList<>();
        List<Object> actual = new ArrayList<>();
        for (boolean image : List.of(false, true)) {
            var schema = schema(image);
            expected.add(image);
            actual.add(schema.imageMode());
            for (var row : before().stream().filter(r -> r[3].startsWith(image ? "image" : "text")).toList()) {
                boolean template = row[0].equals("startTemplate") || row[0].equals("endTemplate");
                Object defaultValue = row[1].equals("BOOLEAN") ? row[0].equals("autoFixEncoding")
                        : template ? resetValue(row) : "";
                expected.add(java.util.Arrays.asList(row[0], defaultValue, row[0].startsWith("imageRegion"),
                        template, template ? "\n ========== " + (row[0].equals("startTemplate") ? "START" : "END") + " ==========\n " : null));
            }
            for (var f : schema.fields()) actual.add(java.util.Arrays.asList(f.id(), f.defaultValue(),
                    f.nullWhenDisabled(), f.templateEnter(), f.promptText()));
            expected.add(before().stream().filter(r -> r[3].startsWith(image ? "image" : "text"))
                    .map(r -> r[3] + ":" + r[4] + ":" + (r[3].equals("textTemplatePane") ? "param.text.templateVars" : "null"))
                    .distinct().toList());
            actual.add(schema.groups().stream().map(g -> g.id() + ":" + g.labelKey() + ":" + g.noteKey()).toList());
        }
        assertEquals(expected, actual);
    }

    private static Object resetValue(String[] row) {
        if (row[7].equals("false")) return false;
        return row[7].substring(1, row[7].length() - 1).replace("\\\\", "\\");
    }

    @Test
    void allThirtyTwoEnableCombinationsPreserveLegacyResets() throws Exception {
        List<String> switches = List.of("enableContentLimit", "enableTemplate", "enableTitleExtraction",
                "enableNameExtraction", "enableRegionExtraction");
        List<Map<String, Object>> expected = new ArrayList<>(), actual = new ArrayList<>();
        for (int mask = 0; mask < 32; mask++) {
            Map<String, Object> input = new LinkedHashMap<>();
            for (var row : before()) input.put(row[0], row[1].equals("BOOLEAN") ? true : "[" + row[0]);
            for (int i = 0; i < switches.size(); i++) input.put(switches.get(i), (mask & (1 << i)) != 0);
            Map<String, Object> wanted = new LinkedHashMap<>(input), found = new LinkedHashMap<>(input);
            for (var row : before()) if (!row[6].equals("-") && !Boolean.TRUE.equals(input.get(row[6])))
                wanted.put(row[0], resetValue(row));
            for (String controller : switches) for (boolean image : List.of(false, true))
                found.putAll(com.mhtextractor.uicore.ParamSchemaRules.disabledValues(schema(image), input, controller));
            expected.add(wanted);
            actual.add(found);
        }
        assertEquals(expected, actual);
    }

    @Test
    void everyConfigMappingRoundTripsWithoutCrossWiringOrExtraValidation() throws Exception {
        List<Object> expected = new ArrayList<>(), actual = new ArrayList<>();
        var rows = before().stream().sorted(Comparator.comparingInt(r -> Integer.parseInt(r[5]))).toList();
        // One-hot booleans + distinct, deliberately incomplete regex strings detect swapped bindings.
        for (int hot = -1; hot < rows.size(); hot++) {
            Map<String, Object> values = new LinkedHashMap<>();
            AppConfig input = new AppConfig(), collected = new AppConfig();
            for (int i = 0; i < rows.size(); i++) {
                String[] row = rows.get(i);
                boolean bool = row[1].equals("BOOLEAN");
                Object value = bool ? i == hot : "[" + row[0];
                values.put(row[0], value);
                legacySet(input, row[0], value);
            }
            for (var row : rows) {
                Object value = values.get(row[0]);
                if (row[0].startsWith("imageRegion") && !Boolean.TRUE.equals(values.get("enableRegionExtraction"))) value = null;
                legacySet(collected, row[0], value);
            }
            Map<String, Object> loaded = new LinkedHashMap<>();
            loaded.putAll(ParamSchemaService.values(input, schema(false)));
            loaded.putAll(ParamSchemaService.values(input, schema(true)));
            expected.add(values); actual.add(loaded);
            expected.add(collected); actual.add(ParamSchemaService.collect(values));
        }
        assertEquals(expected, actual);
    }
    // Explicit legacy setters: no reflective test-access surface.
    private static void legacySet(AppConfig c, String id, Object value) {
        switch (id) {
            case "addChapterNumber" -> c.setAddChapterNumber((Boolean) value);
            case "enableTitleExtraction" -> c.setEnableTitleExtraction((Boolean) value);
            case "titleStartMarker" -> c.setTitleStartMarker((String) value);
            case "titleEndMarker" -> c.setTitleEndMarker((String) value);
            case "titleUseRegex" -> c.setTitleUseRegex((Boolean) value);
            case "enableContentLimit" -> c.setEnableContentLimit((Boolean) value);
            case "startMarker" -> c.setStartMarker((String) value);
            case "endMarker" -> c.setEndMarker((String) value);
            case "useRegex" -> c.setUseRegex((Boolean) value);
            case "enableTemplate" -> c.setEnableTemplate((Boolean) value);
            case "startTemplate" -> c.setStartTemplate((String) value);
            case "endTemplate" -> c.setEndTemplate((String) value);
            case "separateFiles" -> c.setSeparateFiles((Boolean) value);
            case "autoFixEncoding" -> c.setAutoFixEncoding((Boolean) value);
            case "enableNameExtraction" -> c.setEnableNameExtraction((Boolean) value);
            case "imageNameStartMarker" -> c.setImageNameStartMarker((String) value);
            case "imageNameEndMarker" -> c.setImageNameEndMarker((String) value);
            case "enableRegionExtraction" -> c.setEnableRegionExtraction((Boolean) value);
            case "imageRegionStartMarker" -> c.setImageRegionStartMarker((String) value);
            case "imageRegionEndMarker" -> c.setImageRegionEndMarker((String) value);
            case "enableBatchRenaming" -> c.setEnableBatchRenaming((Boolean) value);
            case "enableZipPackaging" -> c.setEnableZipPackaging((Boolean) value);
            case "enableCbzPackaging" -> c.setEnableCbzPackaging((Boolean) value);
            case "singleOutputMode" -> c.setSingleOutputMode((Boolean) value);
            case "highSpeedMemoryMode" -> c.setHighSpeedMemoryMode((Boolean) value);
            default -> throw new IllegalArgumentException(id);
        }
    }
}
