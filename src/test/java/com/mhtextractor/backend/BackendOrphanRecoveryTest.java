package com.mhtextractor.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.mhtextractor.backend.IpcTestClient.JSON;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** Real production CLI processes, with private temporary roots and raw client transcripts. */
class BackendOrphanRecoveryTest {
    private static final String MAIN = "com.mhtextractor.backend.BackendCli";
    @TempDir Path work;

    private Path session(Path temp) throws Exception {
        try (var entries = Files.list(temp)) {
            var directories = entries.filter(p -> p.getFileName().toString().startsWith("mht-ipc-")
                    && Files.isDirectory(p)).toList();
            assertEquals(1, directories.size(), directories.toString());
            return directories.getFirst();
        }
    }

    @Test void concurrentServersPreserveLiveSessionAndItsFiles() throws Exception {
        Path temp = work.resolve("temp");
        Path firstRoot;
        try (var first = new IpcTestClient(work.resolve("first"), temp, null, MAIN, "--serve", "--locale", "en")) {
            first.ready();
            firstRoot = session(temp);
            Path sentinel = Files.writeString(firstRoot.resolve("live-sentinel.txt"), "live session");
            try (var second = new IpcTestClient(work.resolve("second"), temp, null, MAIN, "--serve", "--locale", "en")) {
                second.ready();
                assertTrue(Files.isDirectory(firstRoot), "Later backend deleted live session directory");
                assertTrue(Files.exists(sentinel), "Later backend deleted live session contents");
                assertEquals("live session", Files.readString(sentinel));
                try (var entries = Files.list(temp)) {
                    assertEquals(2, entries.filter(Files::isDirectory).count());
                }
                assertEquals("response", first.request("still-live", "workspace", JSON.createObjectNode())
                        .path("type").asText());
                second.finish();
            }
            assertTrue(Files.isDirectory(firstRoot));
            first.finish();
        }
        assertFalse(Files.exists(firstRoot));
        System.out.println("ORPHAN CONCURRENCY GREEN: two real --serve; first session and payload survived; both exit=0; normal close removed session");
    }

    @Test void onceReclaimsOrphanButPreservesLockedOwnerWithAllowlistEnabled() throws Exception {
        Path temp = Files.createDirectories(work.resolve("temp"));
        Path orphan = Files.createDirectory(temp.resolve("mht-ipc-orphan"));
        Files.createFile(orphan.resolve(".session.lock"));
        Path legacy = Files.createDirectory(temp.resolve("mht-ipc-legacy"));
        Path live = Files.createDirectory(temp.resolve("mht-ipc-live"));
        Path lock = Files.createFile(live.resolve(".session.lock"));
        Path input = Files.writeString(work.resolve("input.html"), "<html><body>once recovery sentinel</body></html>");
        Path output = work.resolve("output");
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Djava.io.tmpdir=" + temp, "-Dmht.allow.roots=" + work.resolve("unrelated"),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", cp, MAIN,
                "--once", "--input", input.toString(), "--output", output.toString(), "--locale", "en");
        Files.write(work.resolve("once-command.txt"), command);
        try (var channel = FileChannel.open(lock, WRITE); var ignored = channel.lock()) {
            Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true)
                    .redirectOutput(work.resolve("once-output.txt").toFile()).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), Files.readString(work.resolve("once-output.txt")));
            } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
            assertFalse(Files.exists(orphan));
            assertFalse(Files.exists(legacy));
            assertTrue(Files.isDirectory(live));
        }
        assertTrue(Files.isDirectory(temp));
        System.out.println("ORPHAN ONCE GREEN: unlocked + legacy reclaimed; external live owner retained; allowlist independent; exit=0");
    }

    @Test void killedRealServerIsReclaimedOnNextServeStartup() throws Exception {
        Path temp = work.resolve("temp");
        Path crashed;
        try (var first = new IpcTestClient(work.resolve("killed"), temp, null, MAIN, "--serve")) {
            first.ready();
            crashed = session(temp);
            Files.writeString(crashed.resolve("crash-sentinel.txt"), "crashed session");
            // No finish(): the client teardown uses destroyForcibly and verifies process death.
        }
        assertTrue(Files.exists(crashed.resolve(".session.lock")));
        try (var next = new IpcTestClient(work.resolve("next"), temp, work.resolve("unrelated").toString(),
                MAIN, "--serve")) {
            next.ready();
            assertFalse(Files.exists(crashed), "OS-released owner lock must permit crash recovery");
            assertNotEquals(crashed, session(temp));
            next.finish();
        }
        System.out.println("ORPHAN KILL GREEN: real --serve forcibly killed; next --serve reclaimed old root; allowlist independent; next exit=0");
    }
}
