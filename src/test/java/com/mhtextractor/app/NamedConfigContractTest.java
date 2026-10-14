package com.mhtextractor.app;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Each case uses a fresh H-drive CWD and the real JVM IPC server without a UI classpath. */
class NamedConfigContractTest {
    private void scenario(String name) throws Exception {
        Path root = Files.createTempDirectory(Path.of("H:/TEST/mht"), "p5a8-named-" + name + "-");
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> !new File(p).getName().matches("(?i)(java" + "fx|atlantafx|ikonli).*"))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=H:/TEST/temp", "-Dfile.encoding=UTF-8",
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", cp, NamedConfigContractProbe.class.getName(), name);
        Files.write(root.resolve("command.txt"), command);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("run.log").toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "contract child timed out: " + root);
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("run.log")));
            assertTrue(Files.readString(root.resolve("run.log")).contains("CONTRACT_OK " + name));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
    @Test void saveListLoadDeleteRoundTrip() throws Exception { scenario("roundtrip"); }
    @Test void overwriteAndFilenameRulesMatch() throws Exception { scenario("names"); }
    @Test void missingAndInvalidNamesMatch() throws Exception { scenario("errors"); }
    @Test void missingDirectoryAfterInitializationMatches() throws Exception { scenario("missing-dir"); }
    @Test void directoryObstructionMatches() throws Exception { scenario("blocked-dir"); }
    @Test void legacyFieldsAndMalformedJsonMatch() throws Exception { scenario("legacy"); }
    @Test void namedCommandsLeaveWorkspaceAndEventsUnchanged() throws Exception { scenario("workspace"); }
    @Test void frontendSerialQueueDispatchesSuccessAndIoFailure() throws Exception { scenario("frontend"); }
}
