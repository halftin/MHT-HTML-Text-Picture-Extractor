package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.util.SourceKinds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Session-owned bulk files. Client paths are never used as unrestricted read/delete targets. */
final class IpcFiles implements AutoCloseable {
    static final Path PRODUCTS = Path.of("H:/TEST/mht").toAbsolutePath().normalize();
    static final Path TEMP = Path.of("H:/TEST/temp").toAbsolutePath().normalize();
    private final ObjectMapper json;
    private final Set<Path> issued = new HashSet<>();
    final Path root;

    IpcFiles(ObjectMapper json) throws IOException {
        this.json = json;
        Path configured = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!configured.equals(TEMP) || !configured.toRealPath().equals(TEMP)) {
            throw new IOException("IPC requires -Djava.io.tmpdir=H:/TEST/temp (no redirected root)");
        }
        root = Files.createTempDirectory(configured, "mht-ipc-").toRealPath();
    }

    static Path input(String value) throws IOException {
        Path path = lexical(value, PRODUCTS);
        if (!path.toRealPath().startsWith(PRODUCTS)) throw new IllegalArgumentException("Input escapes allowlist");
        // Preflight directory trees, including junctions and symlinks, before the importer can read them.
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) {
                for (var entries = walk.iterator(); entries.hasNext(); ) {
                    Path entry = entries.next();
                    if (!entry.toRealPath().startsWith(PRODUCTS)) {
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
        Path path = lexical(value, PRODUCTS);
        Path ancestor = path;
        while (!Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(PRODUCTS)) throw new IllegalArgumentException("Output escapes allowlist");
        Files.createDirectories(path);
        if (!path.toRealPath().startsWith(PRODUCTS)) throw new IllegalArgumentException("Output escapes allowlist");
        return path;
    }

    private static Path lexical(String value, Path allowed) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute path required");
        path = path.normalize();
        if (!path.startsWith(allowed) || path.equals(allowed)) throw new IllegalArgumentException("Path outside allowlist");
        return path;
    }

    ObjectNode write(String suffix, byte[] bytes, String mediaType) throws IOException {
        Path file = Files.createTempFile(root, "bulk-", suffix);
        Files.write(file, bytes);
        return issue(file, mediaType);
    }

    ObjectNode issue(Path file, String mediaType) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) throw new IOException("Bulk file escapes session");
        issued.add(real);
        return json.createObjectNode().put("path", real.toString()).put("bytes", Files.size(real))
                .put("mediaType", mediaType);
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
        Files.delete(path);
        issued.remove(path);
    }

    @Override public void close() throws IOException {
        // No FOLLOW_LINKS: a substituted link is deleted, never traversed.
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        issued.clear();
    }
}
