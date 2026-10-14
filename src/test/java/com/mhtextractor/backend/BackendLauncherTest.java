package com.mhtextractor.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static org.junit.jupiter.api.Assertions.*;

class BackendLauncherTest {
    @TempDir Path tmp;

    private ProcessBuilder backend(String version, String buildId) throws Exception {
        Path resource = Files.createDirectories(tmp.resolve("identity-override"));
        // Shadow only the child's compile-time resource. No production test switches are needed.
        Files.writeString(resource.resolve("backend-build.properties"), "version=" + version + "\nbuildId=" + buildId + "\n");
        return command(resource + File.pathSeparator + headlessClasspath(), BackendCli.class.getName(), "--serve", "--locale", "en");
    }

    private ProcessBuilder command(String cp, String main, String... arguments) throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", cp, main));
        cmd.addAll(List.of(arguments));
        Files.write(work.resolve("command.txt"), cmd);
        return new ProcessBuilder(cmd).directory(work.toFile()).redirectError(work.resolve("stderr.txt").toFile());
    }

    private static String headlessClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> {
                    String name = new File(entry).getName().toLowerCase(Locale.ROOT);
                    return !name.startsWith("java" + "fx-") && !name.startsWith("atlantafx") && !name.startsWith("ikonli");
                }).map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
    }

    @Test void launcherCompletesHandshake() throws Exception {
        BackendIdentity identity = BackendIdentity.current();
        // CG-1: change only the child's declared buildId; the positive assertion must turn red.
        String declared = System.getProperty("mht.cg.backend.buildId", identity.buildId());
        try (var connection = BackendLauncher.launch(backend(identity.version(), declared), identity, Duration.ofSeconds(30))) {
            assertEquals(identity, connection.identity());
            assertEquals(List.of("workspace", "setChecked", "workspacePage", "bulkFiles", "confirmationRoundTrip",
                    "startExtraction", "cancelExtraction", "setTitleOverride", "applyParamsToTargets", "parameterState",
                    "preStartState", "presentation", "exportSource", "cacheState",
                    "listNamedConfigs", "loadNamedConfig", "saveNamedConfig", "deleteNamedConfig"), connection.capabilities());
            assertTrue(connection.confirmationRoundTrip());
            connection.input().write("{\"v\":1,\"type\":\"request\",\"id\":\"after-hello\",\"method\":\"workspace\",\"params\":{}}\n");
            connection.input().flush();
            assertEquals("response", JSON.readTree(connection.output().readLine()).path("type").asText());
            System.out.println("LAUNCHER HANDSHAKE PASS: " + connection.identity());
        }
    }

    @Test void mismatchedBuildAndVersionAreRejectedWithReasons() throws Exception {
        BackendIdentity identity = BackendIdentity.current();
        for (boolean version : List.of(false, true)) {
            var cmd = backend(version ? "tampered-version" : identity.version(), version ? identity.buildId() : "tampered-build");
            IOException error = assertThrows(IOException.class,
                    () -> BackendLauncher.launch(cmd, identity, Duration.ofSeconds(30)));
            assertTrue(error.getMessage().contains("HANDSHAKE_FAILED"), error.getMessage());
            assertTrue(error.getMessage().contains(version ? "Backend version mismatch" : "Backend buildId mismatch"), error.getMessage());
            assertTrue(error.getMessage().contains("expected") && error.getMessage().contains("backend declares"), error.getMessage());
            System.out.println("LAUNCHER REJECT: " + error.getMessage());
        }
    }

    @Test void launcherIndependentlyChecksWelcomeAndBoundsStartup() throws Exception {
        BackendIdentity identity = BackendIdentity.current();
        var spoof = command(headlessClasspath(), BackendLauncherWireFixture.class.getName(), "spoof");
        IOException mismatch = assertThrows(IOException.class,
                () -> BackendLauncher.launch(spoof, identity, Duration.ofSeconds(30)));
        assertTrue(mismatch.getMessage().contains("Backend buildId mismatch"), mismatch.getMessage());
        var stalled = command(headlessClasspath(), BackendLauncherWireFixture.class.getName(), "stall");
        IOException timeout = assertThrows(IOException.class,
                () -> BackendLauncher.launch(stalled, identity, Duration.ofMillis(300)));
        assertTrue(timeout.getMessage().contains("timed out"), timeout.getMessage());
    }
}
