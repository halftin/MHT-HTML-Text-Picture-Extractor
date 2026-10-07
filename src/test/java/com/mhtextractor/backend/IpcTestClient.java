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

    IpcTestClient(Path work, String main, String... arguments) throws Exception {
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
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", cp, main));
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
        String line = reads.submit(output::readLine).get(30, TimeUnit.SECONDS);
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
        assertEquals(id, response.path("id").asText(), "responses must remain ordered");
        return response;
    }

    void ready() throws Exception {
        assertEquals(JSON.readTree("""
                {"v":1,"type":"event","event":"ready","data":{"methods":["workspace","setChecked"]}}
                """), receive());
        assertTrue(process.isAlive(), "serve must stay alive before stdin EOF");
    }

    void finish() throws Exception {
        input.close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "EOF must terminate server");
        // Drain to EOF as well: catches trailing logs, unexpected events and extra response frames.
        assertNull(reads.submit(output::readLine).get(5, TimeUnit.SECONDS), "unexpected trailing stdout");
        assertEquals(0, process.exitValue(), Files.readString(stderr));
        finished = true;
    }

    @Override
    public void close() throws Exception {
        try {
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            reads.shutdownNow();
            output.close();
            input.close();
        }
    }
}
