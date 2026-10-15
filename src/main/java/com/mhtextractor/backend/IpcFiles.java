package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.util.SourceKinds;

import java.io.IOException;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Session-owned bulk files. Client paths are never used as unrestricted read/delete targets. */
final class IpcFiles implements AutoCloseable {
    private final ObjectMapper json;
    private final Set<Path> issued = new HashSet<>();
    private final Set<Path> borrowed = new HashSet<>();
    private static final String PREFIX = "mht-ipc-";
    private static final String SESSION_LOCK = ".session.lock";
    private static final long RECOVERY_BUDGET_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
    private final FileChannel lockChannel;
    private final FileLock ownerLock;
    final Path root;

    IpcFiles(ObjectMapper json) throws IOException {
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var owned = withStartupLock(configuredTempRoot(), configured -> {
            recoverLocked(configured);
            Path directory = Files.createTempDirectory(configured, PREFIX).toRealPath();
            FileChannel channel = FileChannel.open(directory.resolve(SESSION_LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
            try { return new OwnedRoot(directory, channel, channel.lock()); }
            catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        });
        root = owned.path();
        lockChannel = owned.channel();
        ownerLock = owned.lock();
    }

    private record OwnedRoot(Path path, FileChannel channel, FileLock lock) { }

    private static Path configuredTempRoot() throws IOException {
        Path configured = Path.of(System.getProperty("java.io.tmpdir")).normalize();
        if (!configured.isAbsolute()) throw new IOException("Absolute temporary path required");
        return configured;
    }

    static int recoverOrphans() throws IOException {
        return recoverOrphans(configuredTempRoot());
    }

    static int recoverOrphans(Path configured) throws IOException {
        return withStartupLock(configured, IpcFiles::recoverLocked);
    }

    @FunctionalInterface
    private interface RootAction<T> { T run(Path configured) throws IOException; }

    // The JVM monitor avoids OverlappingFileLockException between local callers; the OS
    // lock serializes processes. Never unlink this gate: waiters must share the same inode.
    // Publication (mkdir + owner lock) and scans use the SAME gate, including --once.
    private static synchronized <T> T withStartupLock(Path configured, RootAction<T> action) throws IOException {
        configured = configured.normalize();
        if (!configured.isAbsolute()) throw new IOException("Absolute temporary path required");
        Files.createDirectories(configured);
        Path real = configured.toRealPath();
        try (var channel = FileChannel.open(real.resolve(".mht-ipc-startup.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            if (!configured.toRealPath().equals(real)) throw new IOException("Temporary root redirected");
            return action.run(real);
        }
    }

    private static boolean sessionDirectory(Path directory, Path configured) throws IOException {
        Path path = directory.toAbsolutePath().normalize();
        return path.getParent().equals(configured) && path.getFileName().toString().startsWith(PREFIX)
                && Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath().equals(path);
    }

    private static int recoverLocked(Path configured) throws IOException {
        int recovered = 0;
        long deadline = System.nanoTime() + RECOVERY_BUDGET_NANOS;
        try (var entries = Files.newDirectoryStream(configured, PREFIX + "*")) {
            for (Path directory : entries) {
                if (System.nanoTime() >= deadline) break;
                try {
                    if (!sessionDirectory(directory, configured)) continue;
                    Path lock = directory.resolve(SESSION_LOCK);
                    // CREATE also claims legacy roots lacking a lock. The publication gate
                    // proves these cannot be a new session between mkdir and owner lock.
                    if (Files.exists(lock, NOFOLLOW_LINKS)
                            && (!Files.isRegularFile(lock, NOFOLLOW_LINKS) || !lock.toRealPath().equals(lock))) continue;
                    try (var channel = FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS)) {
                        try (var acquired = channel.tryLock()) {
                            if (acquired == null) continue;
                            if (!sessionDirectory(directory, configured)) continue;
                            deleteTree(directory, true, deadline);
                        }
                    }
                    // Windows requires closing the lock handle before deleting it. The
                    // startup gate still excludes all publishers and other reclaimers.
                    if (!sessionDirectory(directory, configured)) continue;
                    Files.delete(lock);
                    Files.delete(directory);
                    recovered++;
                } catch (OverlappingFileLockException ignored) {
                    // A live owner in this JVM is just as live as one in another process.
                } catch (IOException ignored) {
                    // Inaccessible/redirected/concurrently removed entries are not proof
                    // of a dead owner. Leave them for a later startup, never broaden scope.
                }
            }
        }
        return recovered;
    }

    static Path input(String value) throws IOException {
        Path path = absolute(value);
        List<Path> roots = allowedRoots();
        if (roots.isEmpty()) return path;
        Path allowed = allowedRoot(path, roots);
        if (!path.toRealPath().startsWith(allowed)) throw new IllegalArgumentException("Input escapes allowlist");
        // Preflight directory trees, including junctions and symlinks, before the importer can read them.
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) {
                for (var entries = walk.iterator(); entries.hasNext(); ) {
                    Path entry = entries.next();
                    if (!entry.toRealPath().startsWith(allowed)) {
                        throw new IllegalArgumentException("Input tree escapes allowlist");
                    }
                }
            }
        } else if (SourceKinds.isHtml(path.getFileName().toString())) {
            Path sidecar = path.getParent().resolve(SourceKinds.sidecarDirName(
                    SourceKinds.stripKnownExtension(path.getFileName().toString())));
            if (Files.exists(sidecar, java.nio.file.LinkOption.NOFOLLOW_LINKS)) input(sidecar.toString());
        }
        return path;
    }

    static Path output(String value) throws IOException {
        Path path = checkOutput(value);
        Files.createDirectories(path);
        List<Path> roots = allowedRoots();
        if (!roots.isEmpty() && !path.toRealPath().startsWith(allowedRoot(path, roots))) throw new IllegalArgumentException("Output escapes allowlist");
        return path;
    }

    static Path checkOutput(String value) throws IOException {
        Path path = absolute(value);
        List<Path> roots = allowedRoots();
        if (roots.isEmpty()) return path;
        Path allowed = allowedRoot(path, roots);
        Path ancestor = path;
        while (!Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(allowed)) throw new IllegalArgumentException("Output escapes allowlist");
        return path;
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute path required");
        return path.normalize();
    }

    private static List<Path> allowedRoots() {
        String configured = System.getProperty("mht.allow.roots");
        if (configured == null) return List.of();
        return java.util.Arrays.stream(configured.split(Pattern.quote(File.pathSeparator), -1))
                .map(IpcFiles::absolute).toList();
    }

    private static Path allowedRoot(Path path, List<Path> roots) {
        return roots.stream().filter(root -> path.startsWith(root) && !path.equals(root))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Path outside allowlist"));
    }

    private static Path lexical(String value, Path allowed) {
        Path path = absolute(value);
        if (!path.startsWith(allowed) || path.equals(allowed)) throw new IllegalArgumentException("Path outside allowlist");
        return path;
    }

    ObjectNode write(String suffix, byte[] bytes, String mediaType) throws IOException {
        Path file = Files.createTempFile(root, "bulk-", suffix);
        Files.write(file, bytes);
        return issue(file, mediaType);
    }

    JsonNode readUpload(JsonNode ref) throws IOException {
        if (!ref.isObject() || !ref.path("path").isTextual()
                || !ref.path("bytes").isIntegralNumber() || !ref.path("bytes").canConvertToLong()
                || ref.path("bytes").longValue() < 0 || !ref.path("mediaType").asText().equals("application/json")) {
            throw new IllegalArgumentException("JSON FileRef required");
        }
        Path path = lexical(ref.path("path").asText(), root);
        // Validate every ancestor as well: a redirected directory must not reach outside the session.
        if (!path.toRealPath().equals(path) || !Files.isRegularFile(path)
                || Files.size(path) != ref.path("bytes").longValue()) {
            throw new IllegalArgumentException("Invalid upload file");
        }
        return json.readTree(Files.readAllBytes(path));
    }

    ObjectNode issue(Path file, String mediaType) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) throw new IOException("Bulk file escapes session");
        issued.add(real);
        return json.createObjectNode().put("path", real.toString()).put("bytes", Files.size(real))
                .put("mediaType", mediaType);
    }

    ObjectNode borrowImage(Path file, String mediaType) throws IOException {
        ObjectNode ref = issue(file, mediaType);
        borrowed.add(file.toRealPath());
        return ref;
    }

    Path validateIssued(String value) throws IOException {
        Path path = lexical(value, root);
        if (!issued.contains(path) || !path.toRealPath().equals(path)) {
            throw new IllegalArgumentException("Path was not issued by this session");
        }
        return path;
    }

    void release(String value) throws IOException {
        Path path = validateIssued(value);
        // Cache images have the preview lifecycle's lease, rather than a bulk-output lease.
        if (!borrowed.remove(path)) Files.delete(path);
        issued.remove(path);
    }

    @Override public void close() throws IOException {
        close(() -> { });
    }

    void close(Runnable beforeDelete) throws IOException {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        Thread cleaner = Thread.ofPlatform().name("ipc-file-cleanup").daemon(true).start(() -> {
            try {
                try { beforeDelete.run(); } finally {
                    withStartupLock(root.getParent(), configured -> {
                        try { deleteTree(root, true); }
                        finally {
                            try { ownerLock.release(); } finally { lockChannel.close(); }
                        }
                        if (sessionDirectory(root, configured)) {
                            Files.deleteIfExists(root.resolve(SESSION_LOCK));
                            Files.deleteIfExists(root);
                        }
                        return null;
                    });
                }
                done.complete(null);
            }
            catch (Throwable failure) { done.completeExceptionally(failure); }
            finally {
                // Also release ownership if acquiring the startup gate itself failed.
                try { lockChannel.close(); } catch (IOException ignored) { }
            }
        });
        try { done.get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Session cleanup interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            cleaner.interrupt();
            throw new IOException("Session cleanup failed or exceeded 2 seconds", failure);
        } finally { issued.clear(); borrowed.clear(); }
    }

    private static void deleteTree(Path root, boolean preserveLock) throws IOException {
        deleteTree(root, preserveLock, Long.MAX_VALUE);
    }

    private static void deleteTree(Path root, boolean preserveLock, long deadline) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        var failures = new java.util.ArrayList<IOException>();
        // Default walkFileTree uses NOFOLLOW_LINKS. Windows junctions can still appear
        // as directories, so reject redirected directories before visiting their children.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkDeadline(deadline);
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                if (!dir.toRealPath().equals(dir.toAbsolutePath().normalize())) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkDeadline(deadline);
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                if (preserveLock && file.equals(root.resolve(SESSION_LOCK))) return FileVisitResult.CONTINUE;
                try { Files.deleteIfExists(file); } catch (IOException failure) { failures.add(failure); }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                failures.add(failure);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                checkDeadline(deadline);
                if (failure != null) failures.add(failure);
                if (preserveLock && dir.equals(root)) return FileVisitResult.CONTINUE;
                try { Files.deleteIfExists(dir); } catch (IOException error) { failures.add(error); }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!failures.isEmpty()) {
            IOException error = new IOException("Could not remove " + failures.size() + " session entries");
            failures.forEach(error::addSuppressed);
            throw error;
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new IOException("Session recovery budget exceeded");
    }
}
