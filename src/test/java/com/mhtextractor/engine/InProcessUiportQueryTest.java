package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.OutputUnitVm;
import com.mhtextractor.uiport.WorkspaceQueries;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InProcessUiportQueryTest {
    private static final class State implements ExtractionPlanService.Context {
        final List<Object> order = new ArrayList<>();
        final GlobalParameterCache cache = new GlobalParameterCache();
        boolean image, open;
        String input = "in", output = "out";
        public List<Object> dataOrder() { return order; }
        public boolean imageMode() { return image; }
        public AppConfig textParamsCache() { return cache.text(); }
        public AppConfig imageParamsCache() { return cache.image(); }
        public String inputFolder() { return input; }
        public String outputFolder() { return output; }
        public boolean openFolderAfterComplete() { return open; }
    }

    private static WorkspaceQueries queries(ExtractionPlanService plans) {
        return new InProcessUiport(new WorkspaceModel(), request -> {
            throw new UnsupportedOperationException();
        }, plans).queries();
    }

    @Test
    void runtimeQueryUsesLiveModeCacheAndAllRuntimeFields() {
        State state = new State();
        WorkspaceQueries queries = queries(new ExtractionPlanService(state));
        List<Object> expected = new ArrayList<>(), actual = new ArrayList<>();
        for (boolean image : List.of(false, true)) for (boolean confirmed : List.of(false, true)) {
            state.image = image;
            state.open = confirmed;
            state.input += "i"; state.output += "o";
            AppConfig cache = confirmed ? new AppConfig() : null;
            state.cache.set(image, cache);
            AppConfig result = queries.runtimeConfig();
            expected.add(List.of(image, confirmed, state.input, state.output, true, true));
            actual.add(List.of(result.isImageModeEnabled(), result.isOpenFolderAfterComplete(),
                    result.getInputFolder(), result.getOutputFolder(), !confirmed || result == cache,
                    result.isAutoFixEncoding()));
        }
        assertEquals(expected, actual);
    }

    private static PreviewItem item(String name, boolean checked) {
        var item = new PreviewItem("H:/TEST/mht/input/" + name, name, LocalDateTime.now(), "H:/TEST/mht/input");
        item.selected = checked;
        return item;
    }

    private static List<Object> observable(OutputUnitPlanner.OutputUnit u) {
        return Arrays.asList(u.getOutputName(), u.isGroup(), u.getParams(), u.getPaths(), u.getOutputPath(),
                u.fileCount(), u.getOutputName() == null ? "core.ungrouped" : null,
                u.getName().baseName(), u.getName().extension(), u.getName().finalFileName(), u.getUniqueName());
    }

    private static List<Object> observable(OutputUnitVm u) {
        return Arrays.asList(u.outputName(), u.group(), u.params(), u.paths(), u.outputPath(), u.fileCount(), u.labelKey(),
                u.baseName(), u.extension(), u.finalFileName(), u.uniqueName());
    }

    @Test
    void removedMemberWithReimportedPathDoesNotMarkRenamedGroupAsDefault() {
        State state = new State();
        var removed = item("reimport.mht", true); var retained = item("retained.mht", true);
        var defaultGroup = new GroupItem<PreviewItem>(1, "default1");
        defaultGroup.setDefaultNamed(true);
        defaultGroup.getMembers().addAll(List.of(removed, retained));
        removed.removed = true;
        var replacement = item("reimport.mht", true);
        var namedGroup = new GroupItem<PreviewItem>(2, "renamed");
        namedGroup.getMembers().add(replacement);
        state.order.addAll(List.of(defaultGroup, namedGroup));
        var units = new ExtractionPlanService(state).outputUnitPlan(false);
        assertNull(units.getFirst().outputName());
        assertEquals("core.groupDefault", units.getFirst().labelKey());
        assertEquals("renamed", units.getLast().outputName());
        assertNull(units.getLast().labelKey());
    }

    @Test
    void planQueryAndExecutionBridgePreserveEveryObservableField() {
        State state = new State();
        var a = item("a.mht", true); var b = item("b.mht", false); var removed = item("removed.mht", true);
        removed.removed = true;
        var group = new GroupItem<PreviewItem>(1, "A:B");
        group.getMembers().addAll(List.of(item("g1.mht", true), item("g2.mht", false), removed));
        state.order.addAll(List.of(a, group, b, removed));
        state.cache.set(false, new AppConfig()); state.cache.set(true, new AppConfig());
        var plans = new ExtractionPlanService(state);
        var queries = queries(plans);
        List<Object> expected = new ArrayList<>(), actual = new ArrayList<>();
        for (boolean image : List.of(false, true)) for (boolean separate : List.of(false, true))
            for (boolean checked : List.of(false, true)) for (String session : List.of("session-one", "session-two")) {
                state.image = image; state.open = checked;
                state.cache.text().setSeparateFiles(separate);
                state.cache.image().setSingleOutputMode(!separate);
                plans.setSessionOutputFolder(session);
                var legacy = ExtractionPlanService.plan(state.order, image, state.cache.text(), state.cache.image(),
                        session, state.input, state.output, state.open, checked);
                var result = queries.outputUnitPlan(checked);
                var request = WorkspaceExtractionService.fromPlan(session, result, Map.of(), image, () -> false);
                expected.add(legacy.stream().map(InProcessUiportQueryTest::observable).toList());
                actual.add(result.stream().map(InProcessUiportQueryTest::observable).toList());
                expected.add(legacy.stream().map(InProcessUiportQueryTest::observable).toList());
                actual.add(request.units().stream().map(InProcessUiportQueryTest::observable).toList());
            }
        a.selected = false; b.selected = true;
        expected.add(List.of("H:/TEST/mht/input/g1.mht", b.path));
        actual.add(queries.outputUnitPlan(true).stream().flatMap(u -> u.paths().stream()).toList());
        assertEquals(expected, actual);
    }
}
