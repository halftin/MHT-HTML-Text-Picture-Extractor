package com.mhtextractor.uicore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** GUI-only process lock, held until JVM termination; the lock file is never deleted. */
public final class SingleInstanceLock {
    // A strong reference keeps both the lock and its channel alive for the JVM lifetime.
    private static FileLock heldLock;

    private SingleInstanceLock() {}

    public static synchronized boolean tryAcquire() throws IOException {
        if (heldLock != null) return true;
        Path file = Path.of("logs", "app.lock");
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            heldLock = channel.tryLock();
            if (heldLock != null) return true;
        } catch (OverlappingFileLockException alreadyHeld) {
            return false;
        } finally {
            // Never close a successfully locked channel, including during GUI shutdown.
            if (heldLock == null) channel.close();
        }
        return false;
    }
}
