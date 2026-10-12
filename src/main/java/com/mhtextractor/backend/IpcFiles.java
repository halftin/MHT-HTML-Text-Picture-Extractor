package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.util.SourceKinds;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Session-owned bulk files. Client paths are never used as unrestricted read/delete targets. */
final class IpcFiles implements AutoCloseable {
    private final ObjectMapper json;
    private final Set<Path> issued = new HashSet<>();
    private final Set<Path> borrowed = new HashSet<>();
    final Path root;

    IpcFiles(ObjectMapper json) throws IOException {
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        Path configured = Path.of(System.getProperty("java.io.tmpdir")).normalize();
        if (!configured.isAbsolute()) throw new IOException("Absolute temporary path required");
        Files.createDirectories(configured);
        root = Files.createTempDirectory(configured, "mht-ipc-").toRealPath();
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
        // Default walkFileTree uses NOFOLLOW_LINKS. Windows junctions can still appear
        // as directories, so reject redirected directories before visiting their children.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.toRealPath().equals(dir.toAbsolutePath().normalize())) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        issued.clear();
    }
}
