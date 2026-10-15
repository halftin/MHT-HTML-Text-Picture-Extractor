package com.mhtextractor.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class IpcOrphanRecoveryTest {
    @TempDir Path work;

    private Path orphan(Path configured, String name, boolean lock) throws Exception {
        Path directory = Files.createDirectories(configured.resolve(name));
        Files.writeString(directory.resolve("sentinel.txt"), "session data");
        if (lock) Files.createFile(directory.resolve(".session.lock"));
        return directory;
    }

    @Test void unlockedOwnerIsReclaimedAndCounted() throws Exception {
        Path directory = orphan(work, "mht-ipc-test-orphan", true);
        assertEquals(1, IpcFiles.recoverOrphans(work));
        assertFalse(Files.exists(directory));
        assertEquals(0, IpcFiles.recoverOrphans(work));
    }

    @Test void heldExclusiveLockPreservesEntireSession() throws Exception {
        Path directory = orphan(work, "mht-ipc-live", true);
        try (var channel = FileChannel.open(directory.resolve(".session.lock"), WRITE);
             var ignored = channel.lock()) {
            assertEquals(0, IpcFiles.recoverOrphans(work));
            assertTrue(Files.isDirectory(directory));
            assertEquals("session data", Files.readString(directory.resolve("sentinel.txt")));
        }
        assertEquals(1, IpcFiles.recoverOrphans(work));
        assertFalse(Files.exists(directory));
    }

    @Test void otherPrefixesAndMatchingFilesAreUntouched() throws Exception {
        Path directory = orphan(work, "other-dir", false);
        Path file = Files.writeString(work.resolve("mht-ipc-notadir"), "ordinary file");
        assertEquals(0, IpcFiles.recoverOrphans(work));
        assertTrue(Files.isDirectory(directory));
        assertEquals("session data", Files.readString(directory.resolve("sentinel.txt")));
        assertEquals("ordinary file", Files.readString(file));
    }

    @Test void legacyDirectoryWithoutLockIsReclaimed() throws Exception {
        Path directory = orphan(work, "mht-ipc-legacy", false);
        assertEquals(1, IpcFiles.recoverOrphans(work));
        assertFalse(Files.exists(directory));
    }

    @Test void onlyImmediateChildrenAndNeverConfiguredRootAreDeleted() throws Exception {
        Path configured = Files.createDirectories(work.resolve("mht-ipc-root"));
        Path outside = orphan(work, "mht-ipc-outside", true);
        Path nested = orphan(configured.resolve("other-dir"), "mht-ipc-nested", false);
        orphan(configured, "mht-ipc-orphan", true);
        assertEquals(1, IpcFiles.recoverOrphans(configured.resolve("child/..")));
        assertTrue(Files.isDirectory(configured));
        assertEquals("session data", Files.readString(outside.resolve("sentinel.txt")));
        assertEquals("session data", Files.readString(nested.resolve("sentinel.txt")));
    }

    @Test void redirectedSessionIsSkippedAndTreeJunctionNeverTraversed() throws Exception {
        Path configured = Files.createDirectories(work.resolve("temp"));
        Path outside = orphan(work, "external", false);
        Path redirected = configured.resolve("mht-ipc-redirected");
        junction(redirected, outside);
        Path directory = orphan(configured, "mht-ipc-orphan", true);
        junction(directory.resolve("external-link"), outside);
        try {
            assertEquals(1, IpcFiles.recoverOrphans(configured));
            assertFalse(Files.exists(directory));
            assertTrue(Files.exists(redirected));
            assertEquals("session data", Files.readString(outside.resolve("sentinel.txt")));
        } finally {
            Files.deleteIfExists(redirected);
        }
    }

    @Test void nonRegularLockIsNotTreatedAsProofOfDeadOwner() throws Exception {
        Path directory = orphan(work, "mht-ipc-unknown", false);
        Files.createDirectory(directory.resolve(".session.lock"));
        assertEquals(0, IpcFiles.recoverOrphans(work));
        assertTrue(Files.exists(directory.resolve("sentinel.txt")));
    }

    @Test void relativeTemporaryRootIsRejected() {
        assertThrows(java.io.IOException.class, () -> IpcFiles.recoverOrphans(Path.of("relative")));
    }

    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
