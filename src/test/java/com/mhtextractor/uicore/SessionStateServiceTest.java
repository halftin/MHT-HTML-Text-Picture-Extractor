package com.mhtextractor.uicore;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public load/save run in a fresh H-drive process, without touching the user's state file.
 */
public class SessionStateServiceTest {

    private void scenario(String name) throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "p5a8-session-" + name + "-");
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=H:/TEST/temp", "-cp", cp,
                SessionStateServiceTest.class.getName(), name);
        Files.write(root.resolve("command.txt"), command);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("run.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "session child timed out: " + root);
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("run.log")));
            assertTrue(Files.readString(root.resolve("run.log")).contains("SESSION_OK"));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    @Test
    void missingFileLoadsNullThenSaveRoundTripsAllFields() throws Exception { scenario("fields"); }

    @Test
    void loadMapsNullFoldersVerbatim() throws Exception { scenario("nulls"); }

    public static void main(String[] args) {
        int code = 0;
        try {
            // 无状态文件 → null（与 GuiStateManager.load 的缺失/失败短路一致）
            assertNull(SessionStateService.load());

            SessionStateService.Session session = args[0].equals("fields")
                    ? new SessionStateService.Session("H:\\TEST\\mht\\in", "H:\\TEST\\mht\\out", true, true, false, "zh_CN")
                    : new SessionStateService.Session(null, "", false, false, true, "en_US");
            SessionStateService.save(session);

            // 落盘产物逐字段对账（GuiState 各字段都被写入且未串位）
            GuiState gs = GuiStateManager.load();
            assertNotNull(gs);
            assertEquals(session.inputFolder(), gs.getInputFolder());
            assertEquals(session.outputFolder(), gs.getOutputFolder());
            assertEquals(session.openFolderAfterComplete(), gs.isOpenFolderAfterComplete());
            assertEquals(session.extractCheckedOnly(), gs.isExtractCheckedOnly());
            assertEquals(session.imageModeEnabled(), gs.isImageModeEnabled());
            assertEquals(session.locale(), gs.getLocale());

            // load 往返：六字段全等（record 结构相等）
            assertEquals(session, SessionStateService.load());
            try (var files = Files.list(Path.of("logs"))) {
                assertEquals(List.of("gui-state.json"), files.map(p -> p.getFileName().toString()).toList());
            }
            System.out.println("SESSION_OK");
        } catch (Throwable error) { error.printStackTrace(); code = 1; }
        System.exit(code);
    }
}
