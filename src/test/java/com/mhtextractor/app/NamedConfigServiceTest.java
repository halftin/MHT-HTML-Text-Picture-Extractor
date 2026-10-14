package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.TreeSet;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NamedConfigService} 无头单测（P3-3b-8）：四方法包装与底层 {@link ConfigManager}
 * 同参同返（只搬不改），含 {@link IOException} 的异常语义对照。
 *
 * <p>在独立 H 盘工作目录运行原断言，持久化仍为工作目录下 {@code config/}。</p>
 */
class NamedConfigServiceTest {

    private static final String NAME = "p3-3b8-test-" + System.nanoTime();
    private static final boolean CHILD = Boolean.getBoolean("namedconfig.probe");

    private final NamedConfigService service = CHILD ? new NamedConfigService() : null;
    private final ConfigManager underlying = CHILD ? new ConfigManager() : null;

    private void child(String scenario) throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "p5a8-service-");
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=H:/TEST/temp", "-Dnamedconfig.probe=true",
                "-cp", cp, NamedConfigServiceTest.class.getName(), scenario);
        Files.write(root.resolve("command.txt"), command);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("run.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "service child timeout: " + root);
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("run.log")));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    public static void main(String[] arguments) throws Exception {
        var test = new NamedConfigServiceTest();
        try {
            switch (arguments[0]) {
                case "roundtrip" -> test.saveLoadDeleteRoundTripMatchesUnderlying();
                case "missing" -> test.missingNameThrowsSameIOExceptionAsUnderlying();
                case "names" -> test.namesListsSameSetAsUnderlying();
                default -> throw new AssertionError(arguments[0]);
            }
        } finally { test.cleanup(); }
    }

    @AfterEach
    void cleanup() {
        if (!CHILD) return;
        // 两个实例共用同一 config/ 目录；只删自己的名字（没写成功时删除抛 IOException，属预期）
        try {
            service.delete(NAME);
        } catch (IOException ignored) {
        }
    }

    @Test
    void saveLoadDeleteRoundTripMatchesUnderlying() throws Exception {
        if (!CHILD) { child("roundtrip"); return; }
        AppConfig cfg = new AppConfig();
        cfg.setSeparateFiles(true);
        cfg.setHighSpeedMemoryMode(true);
        cfg.setEnableTitleExtraction(true);

        service.save(NAME, cfg);
        assertTrue(service.names().contains(NAME));
        assertTrue(underlying.getConfigNames().contains(NAME));

        AppConfig viaService = service.load(NAME);
        AppConfig viaUnderlying = underlying.loadConfig(NAME);
        // 同一份落盘 JSON：两个入口读回的内容逐字段相等
        assertEquals(viaUnderlying, viaService);
        assertTrue(viaService.isSeparateFiles());
        assertTrue(viaService.isHighSpeedMemoryMode());
        assertTrue(viaService.isEnableTitleExtraction());

        service.delete(NAME);
        assertFalse(service.names().contains(NAME));
        assertFalse(underlying.getConfigNames().contains(NAME));
    }

    @Test
    void missingNameThrowsSameIOExceptionAsUnderlying() throws Exception {
        if (!CHILD) { child("missing"); return; }
        IOException fromUnderlying = assertThrows(IOException.class, () -> underlying.loadConfig(NAME));
        IOException fromService = assertThrows(IOException.class, () -> service.load(NAME));
        assertEquals(fromUnderlying.getMessage(), fromService.getMessage());

        IOException delUnderlying = assertThrows(IOException.class, () -> underlying.deleteConfig(NAME));
        IOException delService = assertThrows(IOException.class, () -> service.delete(NAME));
        assertEquals(delUnderlying.getMessage(), delService.getMessage());
    }

    @Test
    void namesListsSameSetAsUnderlying() throws Exception {
        if (!CHILD) { child("names"); return; }
        // 目录枚举顺序不保证稳定，按集合比对
        assertEquals(new TreeSet<>(underlying.getConfigNames()), new TreeSet<>(service.names()));
    }
}
