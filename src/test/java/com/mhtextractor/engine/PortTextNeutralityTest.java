package com.mhtextractor.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.*;
import com.mhtextractor.util.HtmlExportBuilder;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Runs real producers in two fresh JVMs, including their actual user.language setting. */
public class PortTextNeutralityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void portValuesAreIdenticalAcrossEnglishAndChineseProcesses() throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "pf2b-neutral-");
        JsonNode english = run(root, "en");
        JsonNode chinese = run(root, "zh");
        assertEquals(english, chinese, "All selected VM and event fields must be language neutral");
        var fields = new TreeSet<String>(); english.path("question").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("key", "namedParams", "actionKeys", "timeoutMs"), fields);
        assertRemovedConfirmApiDoesNotCompile(root);
    }

    private void assertRemovedConfirmApiDoesNotCompile(Path root) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        Path source = root.resolve("RemovedConfirmApi.java");
        Path classes = Files.createDirectories(root.resolve("compile-check"));
        String valid = "new ConfirmRequest(\"key\", java.util.Map.of(), java.util.List.of(), 0)";
        for (String expression : List.of(valid, valid + ".message()",
                "new ConfirmRequest(\"key\", java.util.Map.of(), java.util.List.of(), 0, \"rendered\")")) {
            Files.writeString(source, "import com.mhtextractor.uiport.ConfirmRequest; class RemovedConfirmApi { Object test() { return "
                    + expression + "; } }");
            var diagnostics = new java.io.ByteArrayOutputStream();
            int result = compiler.run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath",
                    System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
            if (expression.equals(valid)) assertEquals(0, result, diagnostics.toString());
            else assertNotEquals(0, result, "Removed API unexpectedly compiled: " + expression);
        }
    }

    private JsonNode run(Path root, String language) throws Exception {
        Path output = root.resolve(language + ".json");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Duser.language=" + language, "-Duser.country=" + (language.equals("en") ? "US" : "CN"), "-Djava.io.tmpdir=H:/TEST/temp",
                "-Dmht.gui.logs.root=" + root.resolve("logs-" + language),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", System.getProperty("java.class.path"),
                getClass().getName(), root.toString(), output.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve(language + ".log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly(); fail("Locale producer timed out: " + language);
        }
        assertEquals(0, process.exitValue(), () -> read(root.resolve(language + ".log")));
        return JSON.readTree(Files.readString(output));
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception e) { return e.toString(); }
    }

    public static void main(String[] args) throws Exception {
        I18n.setLocale(System.getProperty("user.language").equals("en") ? Locale.US : Locale.SIMPLIFIED_CHINESE);
        Path root = Path.of(args[0]);
        Path bad = root.resolve("bad-charset.mht");
        Files.writeString(bad, "MIME-Version: 1.0\r\nContent-Type: text/html; charset=x-invalid-neutral\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\n<html>content</html>");
        Files.setLastModifiedTime(bad, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
        var item = new PreviewItem(bad.toString(), "bad-charset.mht", LocalDateTime.of(2026, 1, 1, 0, 0), root.toString());
        var other = new PreviewItem(root.resolve("other.mht").toString(), "other.mht", null, root.toString());
        var model = new WorkspaceModel(); model.addItems(List.of(item, other));
        var port = new InProcessUiport(model, ignored -> cancelled());
        model.group(List.of(item));
        model.group(List.of(other), "");
        port.commands().setItemParams(bad.toString(), false, new AppConfig());
        var parsed = new AtomicReference<PortEvent.PreparseFinished>();
        var finished = new CountDownLatch(1);
        try (var subscription = port.events().subscribe(event -> {
            if (event instanceof PortEvent.PreparseFinished p) { parsed.set(p); finished.countDown(); }
        })) {
            port.commands().startPreparseFor(List.of(bad.toString()));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        var workspace = port.queries().workspace();
        for (var group : workspace.groups()) { assertTrue(group.defaultNamed()); assertNull(group.name()); }
        var failedItem = workspace.groups().getFirst().members().getFirst();
        assertEquals("FAILED", failedItem.status()); assertEquals("preview.parseFailed", failedItem.errorKey());
        assertNotNull(failedItem.errorDetail());
        var runtime = new AppConfig(); runtime.setInputFolder(root.toString()); runtime.setOutputFolder(root.resolve("out").toString());
        var preStart = port.queries().preStartState(runtime, false);
        assertEquals(List.of(1L, 2L), preStart.defaultNamedGroups());
        for (Object no : preStart.defaultNamedGroups()) assertInstanceOf(Long.class, no);
        assertNull(port.queries().outputUnitPlan(false).getFirst().outputName());
        assertEquals("core.groupDefault", port.queries().outputUnitPlan(false).getFirst().labelKey());

        // Real scanner reaches a small quota and produces the actual compound confirmation contract.
        Path quota = Files.createDirectories(root.resolve("quota"));
        Files.writeString(quota.resolve("a.mht"), "a");
        Files.writeString(Files.createDirectories(quota.resolve("sub")).resolve("b.mht"), "b");
        var question = new AtomicReference<ConfirmRequest>(); var imported = new CountDownLatch(1);
        var imports = new WorkspaceImportCoordinator(new WorkspaceModel(), request -> {
            question.set(request); return cancelled();
        }, Runnable::run, 1, 1);
        imports.subscribe(event -> { if (event instanceof PortEvent.ImportFinished) imported.countDown(); });
        imports.importPaths(List.of(quota.toString()), "neutral", false);
        assertTrue(imported.await(5, TimeUnit.SECONDS)); assertNotNull(question.get());
        assertEquals("import.quotaReached", question.get().key());
        assertEquals("1", question.get().namedParams().get("accumulatedFiles"));

        // Exercise both the port's parser-exception path and the export builder's own failure path.
        var export = port.queries().exportSource(root.resolve("missing.html").toString());
        assertEquals("failed", export.status()); assertNotNull(export.detailKey()); renderable(export);
        Path blocked = root.resolve("blocked"); Files.writeString(blocked, "not a directory");
        var content = new MimeParser.ParsedMht(); content.setHtmlContent("<html>content</html>"); content.setCidMap(Map.of());
        var builder = HtmlExportBuilder.export(bad.toString(), content, blocked);
        assertFalse(builder.success()); assertEquals("browser.exportFailed", builder.detailKey());
        var builderVm = new ExportSourceVm("failed", null, 0, 0, builder.detailKey(), builder.detailParams());
        renderable(builderVm);
        var values = new LinkedHashMap<String, Object>();
        values.put("groups", workspace.groups()); values.put("preStart", preStart); values.put("parseEvent", parsed.get());
        values.put("question", question.get()); values.put("export", export); values.put("builderExport", builderVm);
        JsonNode snapshot = JSON.valueToTree(values);
        for (Locale locale : List.of(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            I18n.setLocale(locale);
            for (String key : List.of("core.groupDefault", "preview.parseFailed", "browser.exportFailed", "import.quotaReached")) {
                String rendered = I18n.get(key);
                assertNoRenderedValue(snapshot, rendered, key);
            }
        }
        assertFalse(snapshot.toString().matches("(?s).*[\\p{IsHan}].*"));
        assertFalse(snapshot.toString().contains("!preview."));
        Files.writeString(Path.of(args[1]), snapshot.toString());
        System.out.println("PORT_TEXT_NEUTRALITY_OK language=" + System.getProperty("user.language"));
    }

    private static void assertNoRenderedValue(JsonNode value, String rendered, String key) {
        if (value.isTextual()) assertFalse(value.textValue().contains(rendered), key + " leaked rendered text: " + value);
        else value.forEach(child -> assertNoRenderedValue(child, rendered, key));
    }

    private static void renderable(ExportSourceVm vm) {
        Object[] params = vm.detailParams().entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray();
        String rendered = I18n.get(vm.detailKey(), params);
        assertFalse(rendered.contains("!" + vm.detailKey() + "!")); assertFalse(rendered.contains("{reason}"));
    }

    private static ConfirmToken cancelled() {
        return new ConfirmToken() {
            public void answer(String action) {}
            public void cancel() {}
            public String await() { return null; }
        };
    }
}
