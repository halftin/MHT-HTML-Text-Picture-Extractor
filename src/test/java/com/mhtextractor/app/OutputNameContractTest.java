package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.OutputName;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.ImportPlanVm;
import com.mhtextractor.uiport.OutputUnitVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputNameContractTest {
    @TempDir Path root;

    private final class Scenario implements ExtractionPlanService.Context {
        final WorkspaceModel model = new WorkspaceModel();
        final AppConfig config = new AppConfig();
        final boolean image;
        final Path output;
        final InProcessUiport port;

        Scenario(String label, boolean image, boolean separate) throws Exception {
            this.image = image;
            output = Files.createDirectory(root.resolve(label));
            config.setSeparateFiles(separate);
            config.setSingleOutputMode(!separate);
            port = new InProcessUiport(model, request -> null, new ExtractionPlanService(this));
            port.commands().setGlobalParams(false, config);
            port.commands().setGlobalParams(true, config);
        }

        String add(String parent, String file) throws Exception {
            Path folder = Files.createDirectories(root.resolve(parent).resolve("same"));
            Path path = Files.copy(Path.of("src/test/resources/fixtures/" +
                    (image ? "single-image.mht" : "simple-text.mht")), folder.resolve(file));
            port.commands().applyImportPlan(new ImportPlanVm(List.of(new ImportPlanVm.ImportBatchVm(
                    folder.toString(), null, false, List.of(path.toString()))), 0, 0, 0, 0, 0, false, false));
            return path.toString();
        }

        void extract(List<OutputUnitVm> units) {
            var service = new WorkspaceExtractionService(PreviewSession.create(root.resolve("cache")).lifecycle());
            var stats = service.run(WorkspaceExtractionService.fromPlan(output.toString(), units,
                    Map.of(), image, () -> false), () -> false, progress -> {}).stats();
            assertEquals(0, stats.getFailCount());
            assertEquals(model.allItems().size(), stats.getSuccessCount());
        }

        public List<Object> dataOrder() { return model.topLevel(); }
        public boolean imageMode() { return image; }
        public AppConfig textParamsCache() { return config; }
        public AppConfig imageParamsCache() { return config; }
        public String inputFolder() { return root.resolve("a/same").toString(); }
        public String outputFolder() { return output.toString(); }
        public boolean openFolderAfterComplete() { return false; }
    }

    @Test void mergedTextNamesMatchWrittenFilesForGroupAndDuplicateSourceBuckets() throws Exception {
        var s = new Scenario("text-merged", false, false);
        s.add("a", "one.mht");
        s.add("b", "two.mht");
        String grouped = s.add("c", "three.mht");
        s.port.commands().group(List.of(grouped), "book");
        s.port.commands().renameGroup(s.model.groups().getFirst().getGroupNo(), "book");
        var units = s.port.queries().outputUnitPlan(false);
        assertEquals(3, units.size());
        assertEquals(List.of("same", "same(2)", "book"), units.stream().map(OutputUnitVm::uniqueName).toList());
        assertTrue(units.getLast().group());
        s.extract(units);
        for (var unit : units) {
            assertNotNull(unit.baseName());
            assertEquals("txt", unit.extension());
            assertEquals(unit.baseName() + ".txt", unit.finalFileName());
            assertEquals(unit.baseName(), unit.uniqueName());
            Path file = Path.of(unit.outputPath());
            assertTrue(Files.isRegularFile(file));
            assertEquals(unit.finalFileName(), file.getFileName().toString());
            assertTrue(Files.size(file) > 0);
        }
    }

    @Test void separateTextUnitsHaveNoSingleNameAndPresentationMatchesWrittenFile() throws Exception {
        var s = new Scenario("text-separate", false, true);
        String path = s.add("a", "chapter.mht");
        var units = s.port.queries().outputUnitPlan(false);
        assertFalse(units.isEmpty());
        var unit = units.getFirst();
        assertNull(unit.baseName());
        assertEquals("txt", unit.extension());
        assertNull(unit.finalFileName());
        assertNull(unit.uniqueName());
        assertEquals("core.ungrouped", unit.labelKey());
        var rows = s.port.queries().presentation(List.of(path), s.port.queries().runtimeConfig());
        assertFalse(rows.isEmpty());
        var row = rows.getFirst();
        assertEquals("chapter", row.baseName());
        assertEquals("txt", row.extension());
        assertEquals(row.displayName(), row.finalFileName());
        assertEquals(row.baseName() + "." + row.extension(), row.finalFileName());
        s.extract(units);
        assertTrue(Files.isRegularFile(Path.of(unit.outputPath()).resolve(row.finalFileName())));
    }

    @Test void imageUnitsDescribeRealDirectoriesInMergedAndSeparateModes() throws Exception {
        for (boolean separate : List.of(false, true)) {
            var s = new Scenario("image-" + separate, true, separate);
            s.add("image-a-" + separate, "one.mht");
            s.add("image-b-" + separate, "two.mht");
            var units = s.port.queries().outputUnitPlan(false);
            assertFalse(units.isEmpty());
            if (!separate) assertEquals(List.of("same", "same(2)"), units.stream().map(OutputUnitVm::uniqueName).toList());
            s.extract(units);
            for (var unit : units) {
                assertNull(unit.extension());
                assertNull(unit.finalFileName());
                assertTrue(Files.isDirectory(Path.of(unit.outputPath())));
                if (separate) {
                    assertNull(unit.baseName());
                    assertNull(unit.uniqueName());
                } else {
                    assertNotNull(unit.baseName());
                    assertFalse(unit.baseName().isBlank());
                    assertEquals(Path.of(unit.outputPath()).getFileName().toString(), unit.baseName());
                    assertEquals(unit.baseName(), unit.uniqueName());
                }
                try (var files = Files.walk(Path.of(unit.outputPath()))) {
                    assertTrue(files.anyMatch(Files::isRegularFile));
                }
            }
        }
    }

    @Test void presentationPreservesDisplayNameAndExposesParsingAndDirectoryFacts() throws Exception {
        var s = new Scenario("presentation", false, false);
        String path = s.add("a", "chapter.mht");
        var runtime = s.port.queries().runtimeConfig();
        var row = s.port.queries().presentation(List.of(path), runtime).getFirst();
        assertEquals("same.txt", row.displayName());
        assertEquals(row.displayName(), row.finalFileName());
        assertEquals(row.baseName() + "." + row.extension(), row.finalFileName());
        runtime.setImageModeEnabled(true);
        for (boolean merged : List.of(false, true)) {
            s.config.setSingleOutputMode(merged);
            s.config.setEnableNameExtraction(true);
            s.port.commands().setGlobalParams(true, s.config);
            var item = s.model.allItems().getFirst();
            item.status = PreviewItem.Status.WAITING;
            row = s.port.queries().presentation(List.of(path), runtime).getFirst();
            assertTrue(row.parsing());
            assertNull(row.displayName());
            assertNull(row.baseName());
            assertNull(row.extension());
            assertNull(row.finalFileName());
            item.status = PreviewItem.Status.READY;
            item.cachedTitle = "title";
            row = s.port.queries().presentation(List.of(path), runtime).getFirst();
            assertFalse(row.parsing());
            assertEquals("title", row.baseName());
            assertEquals(row.displayName(), row.baseName());
            assertNull(row.extension());
            assertNull(row.finalFileName());
        }
    }

    @Test void imageArchivesUseUniqueDirectoryNameWithoutChangingUnitExtension() throws Exception {
        var s = new Scenario("archives", true, false);
        s.config.setEnableZipPackaging(true);
        s.config.setEnableCbzPackaging(true);
        s.add("archive-a", "one.mht");
        s.add("archive-b", "two.mht");
        var units = s.port.queries().outputUnitPlan(false);
        assertEquals(2, units.size());
        s.extract(units);
        for (var unit : units) {
            assertNull(unit.extension());
            assertNull(unit.finalFileName());
            for (String suffix : List.of(".zip", ".cbz")) {
                Path archive = s.output.resolve(unit.uniqueName() + suffix);
                assertTrue(Files.isRegularFile(archive));
                try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
                    assertTrue(zip.size() > 0);
                }
            }
        }
    }

    @Test void outputNameValidatesExtensionWithoutTreatingDirectoriesAsFiles() {
        assertEquals("chapter.txt", new OutputName("chapter", "txt").finalFileName());
        assertNull(new OutputName(null, "txt").finalFileName());
        assertTrue(new OutputName("folder", null).directory());
        assertNull(new OutputName("folder", null).finalFileName());
        for (String invalid : List.of("", "TXT", ".txt", "a/b", "a-b"))
            assertThrows(IllegalArgumentException.class, () -> new OutputName("x", invalid));
    }
}
