package com.mhtextractor.uiclient.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;

/** Session leases, never a general file reader. Revocation applies even to retained cache images. */
public final class IpcFilesClient {
    public record FileRef(String path, long bytes, String mediaType) {
        public FileRef {
            if (path == null || !Path.of(path).isAbsolute() || bytes < 0 || mediaType == null || mediaType.isBlank())
                throw new IllegalArgumentException("Invalid FileRef");
        }
    }
    private final ObjectMapper json;
    private final Set<String> issued = new HashSet<>();
    private final Set<String> revoked = new HashSet<>();
    private final BiFunction<String, JsonNode, CompletableFuture<JsonNode>> releaseRequest;
    private Path root;
    private final ThreadLocal<java.util.ArrayList<FileRef>> consumed = new ThreadLocal<>();

    public IpcFilesClient(ObjectMapper json, BiFunction<String, JsonNode, CompletableFuture<JsonNode>> releaseRequest) {
        this.json = json;
        this.releaseRequest = releaseRequest;
    }

    public synchronized void root(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public void beginConsumption() { consumed.set(new java.util.ArrayList<>()); }
    public void endConsumption() {
        var refs = consumed.get(); consumed.remove();
        if (refs != null && !refs.isEmpty()) release(refs.stream().distinct().toList());
    }
    public void releaseAfterUse(FileRef ref) {
        var refs = consumed.get();
        if (refs == null) release(List.of(ref)); else refs.add(ref);
    }

    public synchronized FileRef accept(JsonNode value) {
        if (!value.path("path").isTextual() || !value.path("bytes").isIntegralNumber()
                || !value.path("bytes").canConvertToLong() || !value.path("mediaType").isTextual())
            throw new IllegalArgumentException("Malformed FileRef: " + value);
        var ref = new FileRef(value.path("path").textValue(), value.path("bytes").longValue(), value.path("mediaType").textValue());
        if (root == null || !Path.of(ref.path()).normalize().startsWith(root)) throw new IllegalArgumentException("FileRef outside backend session");
        issued.add(ref.path());
        revoked.remove(ref.path()); // A new server issuance may renew a retained cache image.
        return ref;
    }

    private void ensureReadable(FileRef ref) throws IOException {
        synchronized (this) {
            if (!issued.contains(ref.path()) || revoked.contains(ref.path())) throw new IllegalStateException("FileRef released or not issued: " + ref.path());
        }
        Path path = Path.of(ref.path());
        if (!path.toRealPath().equals(path) || !path.startsWith(root) || !Files.isRegularFile(path))
            throw new IOException("FileRef escapes backend session");
    }

    public byte[] read(FileRef ref) throws IOException {
        ensureReadable(ref);
        byte[] bytes = Files.readAllBytes(Path.of(ref.path()));
        if (bytes.length != ref.bytes()) throw new IOException("FileRef length mismatch: " + ref.path());
        return bytes;
    }
    public void validate(FileRef ref) throws IOException {
        ensureReadable(ref);
        if (Files.size(Path.of(ref.path())) != ref.bytes()) throw new IOException("FileRef length mismatch");
    }

    public JsonNode consumeJson(JsonNode value) throws IOException {
        var ref = accept(value);
        try { return json.readTree(read(ref)); }
        finally { releaseAfterUse(ref); }
    }

    public String consumeText(JsonNode value) throws IOException {
        var ref = accept(value);
        try { return new String(read(ref), java.nio.charset.StandardCharsets.UTF_8); }
        finally { releaseAfterUse(ref); }
    }

    public CompletableFuture<JsonNode> release(List<FileRef> refs) {
        // Revoke locally before enqueueing: the host can defer this RPC while an activity is busy.
        synchronized (this) {
            for (var ref : refs) { issued.remove(ref.path()); revoked.add(ref.path()); }
        }
        return releaseRequest.apply("releaseFiles", json.createObjectNode().set("paths", json.valueToTree(refs.stream().map(FileRef::path).toList())));
    }

    public synchronized void invalidate() { revoked.addAll(issued); issued.clear(); }
}
