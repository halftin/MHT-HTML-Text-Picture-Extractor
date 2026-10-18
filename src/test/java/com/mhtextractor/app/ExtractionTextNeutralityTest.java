package com.mhtextractor.app;

import com.mhtextractor.i18n.Texts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.*;
import com.mhtextractor.processor.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real processor -> result service -> ItemVm in two separate default-locale JVMs. */
public class ExtractionTextNeutralityTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test void statsAndItemsAreIdenticalAcrossProcesses() throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "pf2b2-neutral-");
        JsonNode english = run(root, "en"), chinese = run(root, "zh");
        assertEquals(english, chinese, "Full extraction stats and ItemVm must be language neutral");
        System.out.println("EXTRACTION_NEUTRAL_STRUCTURES_EQUAL " + root + " (en.json / zh.json)");
    }

    private JsonNode run(Path root, String language) throws Exception {
        Path log = root.resolve(language + ".log"), output = root.resolve(language + ".json");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Duser.language=" + language, "-Duser.country=" + (language.equals("en") ? "US" : "CN"),
                "-Djava.io.tmpdir=H:/TEST/temp", "-Dmht.gui.logs.root=" + root.resolve("logs-" + language),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", System.getProperty("java.class.path"),
                getClass().getName(), root.toString(), output.toString());
        Files.writeString(root.resolve(language + "-command.json"), JSON.writeValueAsString(command));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Locale producer timeout"); }
        assertEquals(0, process.exitValue(), () -> { try { return Files.readString(log); } catch (Exception e) { return e.toString(); } });
        return JSON.readTree(Files.readString(output));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        I18n.setLocale(Locale.getDefault());
        var values = JSON.createArrayNode();
        for (String mode : List.of("text", "text-null-end", "image", "write")) {
            Path inputs = Files.createDirectories(root.resolve(mode));
            var paths = new ArrayList<String>();
            for (String fixture : List.of("simple-text.mht", "garbage.mht", "region.mht", "bad-charset.mht")) {
                Path file = inputs.resolve(fixture);
                try (var source = ExtractionTextNeutralityTest.class.getResourceAsStream("/fixtures/" + fixture)) {
                    Files.copy(Objects.requireNonNull(source), file, StandardCopyOption.REPLACE_EXISTING);
                }
                paths.add(file.toString());
            }
            Path empty = inputs.resolve("empty.html"); Files.writeString(empty, "<html><body></body></html>"); paths.add(empty.toString());
            var params = new AppConfig();
            params.setEnableTitleExtraction(true);
            params.setTitleStartMarker("<missing>"); params.setTitleEndMarker("</missing>");
            if (mode.equals("text-null-end")) { params.setTitleEndMarker(null); params.setTitleUseRegex(true); }
            Path output = root.resolve(mode + "-out");
            BatchProcessor.ProcessStats raw;
            if (mode.equals("image")) {
                raw = new ImageBatchProcessor(params).process(BatchProcessor.buildMhtFileInfoList(paths), output.toString(), "images", null);
            } else {
                Files.createDirectories(output);
                if (mode.equals("write")) Files.createDirectories(output.resolve("simple-text.txt"));
                raw = new TextBatchProcessor(params).processSeparate(BatchProcessor.buildMhtFileInfoList(paths), output.toString(), null, Map.of());
            }
            raw.setStartTime(1000); raw.setEndTime(2235);
            var stats = WorkspaceExtractionService.statsFrom(raw);
            assertEquals("extract.summary", stats.summaryKey());
            assertEquals("1235", stats.summaryParams().get("elapsed"));
            stats.summaryParams().forEach((key, value) -> assertTrue(value.matches("-?\\d+"), key + "=" + value));
            for (var file : stats.files()) {
                if (file.messageKey() != null) assertKey(file.messageKey());
                for (var warning : file.warnings()) assertKey(warning.key());
            }
            for (var detail : stats.warnDetails()) {
                assertKey(detail.key()); if (detail.warning() != null) assertKey(detail.warning().key());
            }
            if (mode.equals("text")) assertTrue(stats.warnDetails().stream()
                    .anyMatch(d -> d.key().equals("extract.fileWarnedNoTitle")));
            assertTrue(stats.files().stream().anyMatch(f -> f.messageKey() == null
                    && f.messageDetail() != null && f.messageDetail().contains("x-nonexistent-charset")));
            if (mode.equals("text-null-end")) {
                var first = stats.warnDetails().getFirst();
                assertFalse(first.params().containsKey("end"));
                assertTrue(Texts.extractionSummary(stats).contains(I18n.get("extract.fileWarnedNoTitle",
                        "file", first.params().get("file"), "start", "<missing>", "end", null)));
                assertEquals("null", stats.files().getFirst().warnings().getFirst().args().get("end"));
            }
            if (mode.equals("image")) assertTrue(stats.files().stream()
                    .anyMatch(f -> "extract.reasonNoExtracted".equals(f.messageKey())));
            if (mode.equals("write")) assertTrue(stats.files().stream()
                    .anyMatch(f -> "extract.reasonWriteFailed".equals(f.messageKey())));
            var items = paths.stream().map(path -> new PreviewItem(path, Path.of(path).getFileName().toString(),
                    LocalDateTime.of(2026, 1, 1, 0, 0), inputs.toString())).toList();
            ExtractResultService.apply(stats, items);
            var model = new WorkspaceModel(); model.addItems(items);
            var port = new InProcessUiport(model, request -> { throw new AssertionError("Unexpected confirmation"); });
            var entry = values.addObject(); entry.set("stats", JSON.valueToTree(stats));
            entry.set("items", JSON.valueToTree(port.queries().workspace().topLevelItems()));
        }
        Files.writeString(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values));
        System.out.println("EXTRACTION_NEUTRALITY_OK locale=" + Locale.getDefault());
    }

    private static void assertKey(String key) {
        assertTrue(key.matches("[a-zA-Z][a-zA-Z0-9_.]+"), "Rendered value in key: " + key);
        assertTrue(I18n.bundle().containsKey(key), "Unknown key: " + key);
    }

    @Test void fixedSnapshotRendersExactlyLikeLegacyInBothLanguages() throws Exception {
        Locale original = I18n.currentLocale(), originalDefault = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            var raw = new BatchProcessor.ProcessStats();
            raw.setTotalFiles(5); raw.setSuccessCount(2); raw.setFailCount(1); raw.setSkipCount(2); raw.setWarnCount(3);
            raw.setStartTime(1000); raw.setEndTime(2235);
            var warning = new ExtractWarning("extract.noTitleMarkers", Map.of("start", "<h1>", "end", "</h1>"));
            raw.addWarnDetail(new ExtractionStats.WarningDetail("extract.fileWarned", Map.of("file", "a.mht"), warning));
            raw.addWarnDetail(new ExtractionStats.WarningDetail("extract.fileWarnedNoTitle", Map.of("file", "b.mht", "start", "<h1>", "end", "</h1>"), null));
            var stats = WorkspaceExtractionService.statsFrom(raw);
            for (var locale : List.of(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                I18n.setLocale(locale);
                try (var input = getClass().getResourceAsStream("/extraction-text/" + locale + ".json")) {
                    JsonNode expected = JSON.readTree(Objects.requireNonNull(input));
                    assertEquals(expected.path("summary").asText(), Texts.extractionSummary(stats));
                    assertEquals(expected.path("detail").asText(), Texts.extractDetail("extract.reasonEmpty", null, null, List.of(warning)));
                    assertEquals(expected.path("diagnostic").asText(), Texts.extractDetail(null, null, "raw {diagnostic}\nline", List.of(warning)));
                    assertNull(Texts.extractDetail(null, null, "", List.of()));
                    assertEquals("raw {diagnostic}", Texts.extractDetail(null, null, "raw {diagnostic}", List.of()));
                }
            }
            // String.format historically follows the JVM default, independently of the bundle locale.
            Locale.setDefault(Locale.GERMANY); I18n.setLocale(Locale.US);
            assertTrue(Texts.extractionSummary(stats).contains("Elapsed: 1,24 s"));
        } finally { Locale.setDefault(originalDefault); I18n.setLocale(original); }
    }
}
