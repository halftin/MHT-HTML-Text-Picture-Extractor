package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.baseline.BaselineHasher;
import com.mhtextractor.engine.BackendWorkspace;
import com.mhtextractor.i18n.ExtractionTexts;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.Uiport;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The GUI's public port and an independent Node frontend must produce identical bytes. */
class DualFrontendContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final Path ARTIFACTS = Path.of("H:/TEST/mht");

    @Test void scriptSelfTestNeedsNoBackend() throws Exception {
        Path work = Files.createTempDirectory(Files.createDirectories(ARTIFACTS), "dual-self-test-");
        String output = node(work, List.of("--self-test"));
        assertTrue(output.contains("DUAL_CLIENT_SELF_TEST_OK"), output);
    }

    @Test void nodeAndInProcessHaveIdenticalProductsStructureAndSummaryCounts() throws Exception {
        Path work = Files.createTempDirectory(Files.createDirectories(ARTIFACTS), "dual-contract-");
        var paths = List.of("data-uri.mht", "single-image.mht").stream()
                .map(name -> REPO.resolve("src/test/resources/fixtures").resolve(name).toString()).toList();
        var scenario = JSON.createObjectNode();
        scenario.set("paths", JSON.valueToTree(paths));
        scenario.put("groupName", "Dual contract");
        scenario.putObject("textParams").put("separateFiles", false).put("enableCustomTemplate", true)
                .put("startTemplate", "DUAL-BEGIN\n").put("endTemplate", "\nDUAL-END");
        scenario.putObject("imageParams").put("imageRenameEnabled", true);
        Path scenarioFile = work.resolve("scenario.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(scenarioFile.toFile(), scenario);
        var command = new ArrayList<>(List.of("--out", work.resolve("node").toString(),
                "--scenario-file", scenarioFile.toString()));
        String nativeBackend = System.getProperty("mht.dual.backend");
        if (nativeBackend != null) command.addAll(List.of("--backend", nativeBackend));
        else {
            String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .filter(entry -> !Path.of(entry).getFileName().toString().matches("(?i)(javafx-|atlantafx|ikonli).*"))
                    .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            command.addAll(List.of("--classpath", cp));
        }
        String output = node(work, command);
        assertTrue(output.contains("DUAL_CLIENT_OK"), output);
        JsonNode report = JSON.readTree(work.resolve("node/tree.json").toFile());
        assertTrue(report.path("sessionCleaned").asBoolean());
        assertTrue(report.path("releasedFiles").asInt() > 0);
        Locale original = I18n.currentLocale();
        I18n.setLocale(Locale.US);
        var comparison = JSON.createObjectNode();
        try (var direct = new Direct()) {
            Uiport port = direct.workspace.port();
            port.commands().importPaths(paths, "IPC", false);
            direct.until(() -> direct.events.stream().anyMatch(e -> e instanceof PortEvent.ImportStateChanged state && !state.importing()));
            for (var group : port.queries().workspace().groups()) port.commands().ungroup(group.no());
            port.commands().setChecked(paths, false);
            port.commands().group(paths, scenario.path("groupName").asText());
            port.commands().setChecked(paths, true);
            assertEquals(structure(JSON.readTree(JSON.writeValueAsBytes(port.queries().workspace()))), structure(report.path("workspace")),
                    "Grouped workspace structure and checked items must match");
            for (boolean imageMode : List.of(false, true)) {
                String mode = imageMode ? "image" : "text";
                AppConfig config = JSON.treeToValue(scenario.path(mode + "Params"), AppConfig.class);
                var form = port.queries().paramValues(config, imageMode);
                AppConfig collected = port.queries().collectParams(form, imageMode);
                assertEquals(form, port.queries().paramValues(collected, imageMode));
                port.commands().setGlobalParams(imageMode, collected);
            }
            for (boolean imageMode : List.of(false, true)) {
                String mode = imageMode ? "image" : "text";
                direct.runtime.setInputFolder(Path.of(paths.getFirst()).getParent().toString());
                direct.runtime.setOutputFolder(work.resolve("in-process/" + mode).toString());
                direct.runtime.setImageModeEnabled(imageMode);
                int start = direct.events.size();
                port.commands().startPreparse();
                direct.until(() -> direct.events.subList(start, direct.events.size()).stream().anyMatch(e -> e instanceof PortEvent.PreparseFinished));
                // Image preview has its own mode, including when the extraction page is text.
                direct.runtime.setImageModeEnabled(true);
                int previewStart = direct.events.size();
                port.commands().loadPreview(paths.getFirst(), true);
                direct.until(() -> direct.events.subList(previewStart, direct.events.size()).stream().anyMatch(e -> e instanceof PortEvent.PreviewLoaded));
                port.commands().awaitPreviewInFlight(10000);
                direct.runtime.setImageModeEnabled(imageMode);
                int extractionStart = direct.events.size();
                port.commands().startExtraction(true);
                direct.until(() -> direct.events.subList(extractionStart, direct.events.size()).stream().anyMatch(e -> e instanceof PortEvent.ExtractionTerminal));
                var terminal = direct.events.subList(extractionStart, direct.events.size()).stream()
                        .filter(PortEvent.ExtractionTerminal.class::isInstance).map(PortEvent.ExtractionTerminal.class::cast).findFirst().orElseThrow();
                assertEquals(PortEvent.ExtractionEnd.SUCCEEDED, terminal.end());
                assertNotNull(terminal.stats());
                var expectedTree = tree(Path.of(terminal.sessionOutputFolder()));
                var nodeRun = report.path("runs").path(mode);
                var actualTree = new TreeMap<String, String>();
                nodeRun.path("tree").forEach(entry -> assertNull(actualTree.put(entry.path("path").asText(), entry.path("sha256").asText()), "Duplicate product path"));
                assertFalse(expectedTree.isEmpty());
                assertEquals(expectedTree, actualTree, "PRODUCT_TREE_MISMATCH " + mode);
                assertEquals(expectedTree, tree(Path.of(nodeRun.path("outputFolder").asText())), "Independently hash Node products with BaselineHasher");
                ExtractionStats nodeStats = JSON.treeToValue(nodeRun.path("stats"), ExtractionStats.class);
                assertEquals(counts(terminal.stats()), counts(nodeStats), "Structured success/failure/skip/warning counts");
                assertEquals(summaryCounts(terminal.stats()), summaryCounts(nodeStats), "summaryLine count parameters");
                assertEquals(counts(terminal.stats()), summaryCounts(terminal.stats()));
                assertEquals(counts(nodeStats), summaryCounts(nodeStats));
                var row = comparison.putObject(mode);
                row.set("inProcess", JSON.valueToTree(expectedTree));
                row.set("node", JSON.valueToTree(actualTree));
                row.set("counts", JSON.valueToTree(counts(nodeStats)));
                row.put("inProcessSummaryLine", ExtractionTexts.summaryLine(terminal.stats().summaryKey(), terminal.stats().summaryParams()));
                row.put("nodeSummaryLine", ExtractionTexts.summaryLine(nodeStats.summaryKey(), nodeStats.summaryParams()));
                System.out.println("DUAL_SHA_MATCH " + mode + " " + row);
            }
        } finally { I18n.setLocale(original); }
        JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("sha-comparison.json").toFile(), comparison);
        System.out.println("DUAL_CONTRACT_OK evidence=" + work);
    }

    private static Map<String, String> tree(Path root) throws Exception {
        var tree = new TreeMap<String, String>();
        for (var entry : BaselineHasher.hashTree(root)) tree.put(entry.path(), entry.sha256());
        return tree;
    }

    private static List<Integer> counts(ExtractionStats stats) {
        return List.of(stats.successCount(), stats.failCount(), stats.skipCount(), stats.warnCount());
    }

    private static List<Integer> summaryCounts(ExtractionStats stats) {
        assertEquals("extract.summary", stats.summaryKey());
        return List.of("success", "failed", "skipped", "warned").stream()
                .map(key -> Integer.parseInt(stats.summaryParams().get(key))).toList();
    }

    private static ObjectNode structure(JsonNode workspace) {
        var out = JSON.createObjectNode();
        out.set("selection", workspace.path("selection"));
        out.set("topLevelOrder", workspace.path("topLevelOrder"));
        out.set("topLevelItems", items(workspace.path("topLevelItems")));
        var groups = out.putArray("groups");
        for (var group : workspace.path("groups")) {
            var value = groups.addObject();
            for (String key : List.of("no", "name", "expanded", "defaultNamed", "sourceFolders")) value.set(key, group.path(key));
            value.set("members", items(group.path("members")));
        }
        return out;
    }

    private static JsonNode items(JsonNode items) {
        var out = JSON.createArrayNode();
        for (var item : items) {
            var value = out.addObject();
            for (String key : List.of("id", "name", "checked", "removed", "unitIndex", "sourceFolder")) value.set(key, item.path(key));
        }
        return out;
    }

    private static String node(Path work, List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("node", REPO.resolve("tools/ipc/dual-client.mjs").toString()));
        command.addAll(arguments);
        Path log = work.resolve("node.log");
        JSON.writeValue(work.resolve("node-command.json").toFile(), command);
        var builder = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(200, TimeUnit.SECONDS), "Node workflow timeout: " + log);
            String output = Files.readString(log);
            System.out.print(output);
            assertEquals(0, child.exitValue(), output);
            return output;
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static final class Direct implements AutoCloseable {
        final Thread host = Thread.currentThread();
        final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final List<PortEvent> events = new ArrayList<>();
        final AppConfig runtime = new AppConfig();
        final BackendWorkspace workspace = new BackendWorkspace(request -> {
            throw new AssertionError("Unexpected confirmation: " + request);
        }, () -> runtime, task -> {
            if (Thread.currentThread() == host) task.run();
            else callbacks.add(task);
        });
        final AutoCloseable subscription = workspace.port().events().subscribe(events::add);

        void until(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!done.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "In-process event timeout");
                Runnable task = callbacks.poll(25, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
            }
            Runnable task;
            while ((task = callbacks.poll()) != null) task.run();
        }

        @Override public void close() throws Exception {
            workspace.close();
            subscription.close();
        }
    }
}
