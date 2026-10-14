package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the assembly boundary and observes a real backend request through the runtime. */
class WiringSwitchTest {
    @TempDir Path dir;

    private static String source(Path path) throws Exception {
        return Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//[^\\r\\n]*", "");
    }

    @Test void guiAssemblyCannotConstructAnInProcessBackend() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/com/mhtextractor/gui"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(source(file).contains("InProcessUiport"),
                        "GUI silently constructs or references in-process backend: " + file);
            }
        }
    }

    @Test void guiCannotRestoreBackendOwnedParameterUndo() throws Exception {
        var forbidden = java.util.regex.Pattern.compile("\\b(UndoManager|UndoRecords|GlobalParameterCache|"
                + "WorkspaceParamCommandService|restoreParamSnapshots|undoParamSet|undoClearParams|undoApplyGlobal)\\b");
        try (var files = Files.walk(Path.of("src/main/java/com/mhtextractor/gui"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(forbidden.matcher(source(file)).find(),
                        "GUI retains a backend-owned parameter undo path: " + file);
            }
        }
    }

    @Test void productionAndAcceptanceFactoriesMirrorEachOther() throws Exception {
        String app = Files.readString(Path.of("src/main/java/com/mhtextractor/App.java"));
        String test = Files.readString(Path.of("src/test/java/com/mhtextractor/gui/acceptance/TestMain.java"));
        String factory = "loader.setControllerFactory(_ -> new com.mhtextractor.gui.controller.MainController(loader, runtime.port(), runtime::closeAsync));";
        assertTrue(app.contains(factory));
        assertTrue(test.contains(factory));
        String start = "com.mhtextractor.app.UiportRuntime.start(I18n.currentLocale().toLanguageTag(), confirmer, Platform::runLater,";
        assertTrue(app.contains(start));
        assertTrue(test.contains(start));
    }

    @Test void defaultRuntimeServesARealIpcWorkspaceRequest() throws Exception {
        String oldMode = System.getProperty("mht.uiport");
        String oldTransport = System.getProperty("mht.backend.transport");
        UiportRuntime runtime = null;
        try {
            System.clearProperty("mht.uiport");
            System.setProperty("mht.backend.transport", "jvm");
            var ready = new CompletableFuture<UiportRuntime>();
            UiportRuntime.start("en-US", request -> { throw new AssertionError("Unexpected confirmation"); },
                    Runnable::run, ready::complete, ready::completeExceptionally);
            runtime = ready.get(30, TimeUnit.SECONDS);
            assertInstanceOf(IpcUiport.class, runtime.port().implementation());
            var blank = new AppConfig();blank.setInputFolder("");blank.setOutputFolder("");
            runtime.port().bootstrap(blank);
            assertTrue(runtime.port().snapshot().allUnits().isEmpty(), "Blank form paths must be valid query facts");
            var gate = new CompletableFuture<com.mhtextractor.uiport.PreStartStateVm>();
            runtime.port().execute(port -> port.queries().preStartState(blank, false), gate::complete);
            var blankGate = gate.get(30, TimeUnit.SECONDS);
            assertNotNull(blankGate.denial(), "Q11 must still reject an empty input directory");
            assertEquals(com.mhtextractor.i18n.ErrorCode.INPUT_DIR_INVALID.key(), blankGate.denial().key());
            Path file = dir.resolve("wire.mht");
            Files.writeString(file, "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>wire</p>");
            var observed = new CompletableFuture<Integer>();
            runtime.port().execute(port -> {
                port.commands().applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(
                        List.of(new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm(dir.toString(), null,
                                false, List.of(file.toString()))), 0, 0, 0, 0, 0, false, false));
                return port.queries().workspace().topLevelItems().size();
            }, observed::complete);
            assertEquals(1, observed.get(30, TimeUnit.SECONDS));
            var reimported=new CompletableFuture<String>();
            runtime.port().execute(port->{
                port.commands().removeItems(List.of(file.toString()));
                port.commands().applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(
                        List.of(new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm(dir.toString(),null,
                                false,List.of(file.toString()))),0,0,0,0,0,false,false));
                var config=new AppConfig();config.setStartMarker("visible");
                port.commands().setItemParams(file.toString(),false,config);
                return port.queries().parameterState(false,List.of(com.mhtextractor.uiport.TargetId.ofItem(file.toString())),
                        com.mhtextractor.uiport.TargetResolution.EXPLICIT).states().getFirst().config().getStartMarker();
            },reimported::complete);
            assertEquals("visible",reimported.get(30,TimeUnit.SECONDS),"Real IPC must address the newly imported visible item");
        } finally {
            if (runtime != null) {
                var closed = new CompletableFuture<Void>();
                runtime.closeAsync(() -> closed.complete(null));
                closed.get(30, TimeUnit.SECONDS);
                assertTrue(((IpcUiport) runtime.port().implementation()).stopped().isDone(),
                        "Runtime shutdown callback must follow backend process teardown");
            }
            if (oldMode == null) System.clearProperty("mht.uiport"); else System.setProperty("mht.uiport", oldMode);
            if (oldTransport == null) System.clearProperty("mht.backend.transport"); else System.setProperty("mht.backend.transport", oldTransport);
        }
    }
}
