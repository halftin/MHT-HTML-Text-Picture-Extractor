package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only real-process client: separate stderr, bounded reads, EOF and unconditional cleanup. */
final class IpcTestClient implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Process process;
    private final BufferedWriter input;
    private final BufferedReader output;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final Path stderr;
    private final Path transcript;
    private boolean finished;
    private final List<JsonNode> requestEvents = new ArrayList<>();

    IpcTestClient(Path work, String main, String... arguments) throws Exception {
        this(work, Path.of(System.getProperty("java.io.tmpdir")), null, main, arguments);
    }

    IpcTestClient(Path work, Path temp, String allowedRoots, String main, String... arguments) throws Exception {
        Files.createDirectories(work);
        stderr = work.resolve("stderr.txt");
        transcript = work.resolve("frames.txt");
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> {
                    String name = new File(entry).getName().toLowerCase(Locale.ROOT);
                    return !name.startsWith("java" + "fx-") && !name.startsWith("atlantafx")
                            && !name.startsWith("ikonli");
                }).map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--limit-modules", "java.se", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Djava.io.tmpdir=" + temp,
                "-Dlogback.configurationFile=logback-headless.xml"));
        if (allowedRoots != null) cmd.add("-Dmht.allow.roots=" + allowedRoots);
        cmd.addAll(List.of("-cp", cp, main));
        cmd.addAll(List.of(arguments));
        Files.write(work.resolve("command.txt"), cmd, StandardCharsets.UTF_8);
        process = new ProcessBuilder(cmd).directory(work.toFile()).redirectError(stderr.toFile()).start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    void send(String line) throws Exception {
        Files.writeString(transcript, "> " + line + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        input.write(line);
        input.write('\n');
        input.flush();
    }

    JsonNode receive() throws Exception {
        return receive(30);
    }

    JsonNode receive(long timeoutSeconds) throws Exception {
        String line = reads.submit(output::readLine).get(timeoutSeconds, TimeUnit.SECONDS);
        assertNotNull(line, "Unexpected EOF; stderr=" + Files.readString(stderr));
        Files.writeString(transcript, "< " + line + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        JsonNode frame;
        try {
            frame = JSON.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("stdout is not a JSON protocol frame: " + line, e);
        }
        assertNotNull(frame, "blank stdout line");
        assertTrue(frame.isObject(), "stdout must contain only JSON objects: " + line);
        assertEquals(1, frame.path("v").asInt(), "protocol version: " + line);
        assertTrue(List.of("event", "response", "error").contains(frame.path("type").asText()), line);
        return frame;
    }

    JsonNode request(String id, String method, JsonNode params) throws Exception {
        var frame = JSON.createObjectNode().put("v", 1).put("type", "request")
                .put("id", id).put("method", method);
        frame.set("params", params);
        send(JSON.writeValueAsString(frame));
        JsonNode response = receive();
        while (response.path("type").asText().equals("event")) {
            requestEvents.add(response);
            response = receive();
        }
        assertEquals(id, response.path("id").asText(), "responses must remain ordered");
        return response;
    }

    List<JsonNode> drainRequestEvents() {
        var result = List.copyOf(requestEvents);
        requestEvents.clear();
        return result;
    }

    static JsonNode expandWorkspace(JsonNode wire) throws Exception {
        var expanded = (com.fasterxml.jackson.databind.node.ObjectNode) wire.deepCopy();
        expanded.remove("revision");
        expanded.set("topLevelOrder", JSON.readTree(Files.readAllBytes(Path.of(wire.path("topLevelOrder").path("path").asText()))));
        return expanded;
    }

    void ready() throws Exception {
        readyWithoutHandshake();
        var identity = BackendIdentity.current();
        var welcome = request("hello", "hello", JSON.createObjectNode()
                .put("protocolVersion", identity.protocolVersion()).put("version", identity.version())
                .put("buildId", identity.buildId()));
        assertEquals("response", welcome.path("type").asText());
        assertEquals("welcome", welcome.path("result").path("kind").asText());
    }

    void readyWithoutHandshake() throws Exception {
        JsonNode frame = receive();
        assertEquals("event", frame.path("type").asText());
        assertEquals("ready", frame.path("event").asText());
        assertEquals("workspace", frame.path("data").path("methods").get(0).asText());
        assertEquals("setChecked", frame.path("data").path("methods").get(1).asText());
        assertTrue(process.isAlive(), "serve must stay alive before stdin EOF");
    }

    void finish() throws Exception {
        input.close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "EOF must terminate server");
        // Drain to EOF as well: catches trailing logs, unexpected events and extra response frames.
        assertNull(reads.submit(output::readLine).get(5, TimeUnit.SECONDS), "unexpected trailing stdout");
        assertEquals(0, process.exitValue(), Files.readString(stderr));
        assertFalse(Files.readString(stderr).contains("Exception in thread"), "Uncaught backend worker exception");
        finished = true;
    }

    List<JsonNode> disconnectAndCollect() throws Exception {
        long started = System.nanoTime();
        long pid = process.pid();
        var descendants = process.descendants().toList();
        input.close();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS), "CG-2: disconnected backend must exit within 3s; pid=" + pid);
        long processExitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var frames = new ArrayList<JsonNode>();
        String line;
        while ((line = reads.submit(output::readLine).get(1, TimeUnit.SECONDS)) != null) {
            Files.writeString(transcript, "< " + line + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            frames.add(JSON.readTree(line));
        }
        assertEquals(0, process.exitValue(), Files.readString(stderr));
        assertFalse(process.isAlive());
        assertTrue(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true), "Residual backend process");
        assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive), "Residual descendants");
        String diagnostics = Files.readString(stderr);
        assertFalse(diagnostics.contains("Exception in thread"), diagnostics);
        assertTrue(diagnostics.contains("IPC THREADS CLEAN: nonDaemon=0 inputReaders=0"), diagnostics);
        System.out.println("CG-2 GREEN: pid=" + pid + " exit=" + process.exitValue() + " processExitMs=" + processExitMs + " cleanupMs="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " residualProcesses=0; " + diagnostics.strip());
        finished = true;
        return frames;
    }

    @Override
    public void close() throws Exception {
        try {
            if (!finished) {
                long pid = process.pid();
                var descendants = process.descendants().toList();
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Failed test teardown left backend alive");
                assertFalse(process.isAlive());
                assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive));
                System.out.println("IPC TEST TEARDOWN: pid=" + pid + " forcedExit=" + process.exitValue() + " residualProcesses=0");
            }
        } finally {
            reads.shutdownNow();
            output.close();
            input.close();
            assertTrue(reads.awaitTermination(5, TimeUnit.SECONDS), "Residual client read thread");
        }
    }
}
