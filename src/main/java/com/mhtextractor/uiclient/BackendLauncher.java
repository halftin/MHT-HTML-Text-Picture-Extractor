package com.mhtextractor.uiclient;

import com.mhtextractor.uiclient.BackendIdentity;
import com.mhtextractor.uiclient.BackendLauncher;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Launch a JVM or native --serve command, negotiate identity, then hand the streams to the caller. */
public final class BackendLauncher {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private BackendLauncher() { }

    public static Connection launch(ProcessBuilder command, BackendIdentity expected, Duration timeout)
            throws IOException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Positive handshake timeout required");
        }
        java.util.Objects.requireNonNull(expected, "expected");
        if (command.redirectErrorStream() || command.redirectInput() != ProcessBuilder.Redirect.PIPE
                || command.redirectOutput() != ProcessBuilder.Redirect.PIPE) {
            throw new IllegalArgumentException("Backend needs separate stdout/stderr and piped stdin/stdout");
        }
        // Inherit diagnostics by default: never let an unread stderr pipe deadlock startup.
        if (command.redirectError() == ProcessBuilder.Redirect.PIPE) command.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = command.start();
        Connection connection = new Connection(process);
        ExecutorService handshake = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).factory());
        boolean completed = false;
        try {
            Connection result = handshake.submit(() -> {
                JsonNode ready = read(connection.output);
                if (!validFrame(ready, "event") || !ready.path("event").asText().equals("ready")
                        || !ready.path("data").path("methods").isArray()) {
                    throw new IOException("Expected backend ready event before handshake");
                }
                boolean supportsHello = false;
                for (JsonNode method : ready.path("data").path("methods")) {
                    if (method.asText().equals("hello")) supportsHello = true;
                }
                if (!supportsHello) throw new IOException("Backend does not support required hello handshake");
                var hello = JSON.createObjectNode().put("v", 1).put("type", "request")
                        .put("id", "launcher-hello").put("method", "hello");
                hello.putObject("params").put("protocolVersion", expected.protocolVersion())
                        .put("version", expected.version()).put("buildId", expected.buildId());
                connection.input.write(JSON.writeValueAsString(hello));
                connection.input.write('\n');
                connection.input.flush();
                JsonNode frame = read(connection.output);
                if (!frame.path("id").asText().equals("launcher-hello")) {
                    throw new IOException("Unexpected handshake response id");
                }
                if (validFrame(frame, "error")) throw new IOException("Backend handshake rejected ["
                        + frame.path("error").path("code").asText() + "]: "
                        + frame.path("error").path("message").asText());
                JsonNode welcome = frame.path("result");
                if (!validFrame(frame, "response") || !welcome.path("kind").asText().equals("welcome")
                        || !welcome.path("protocolVersion").isIntegralNumber()
                        || !welcome.path("protocolVersion").canConvertToInt()
                        || !welcome.path("version").isTextual() || !welcome.path("buildId").isTextual()
                        || !welcome.path("capabilities").isArray()
                        || !welcome.path("confirmationRoundTrip").isBoolean()) {
                    throw new IOException("Malformed backend welcome response");
                }
                BackendIdentity actual;
                try {
                    actual = new BackendIdentity(welcome.path("protocolVersion").intValue(),
                            welcome.path("version").textValue(), welcome.path("buildId").textValue());
                } catch (IllegalArgumentException e) { throw new IOException("Invalid backend welcome identity", e); }
                String mismatch = expected.mismatch(actual);
                if (mismatch != null) throw new IOException(mismatch);
                var capabilities = new java.util.ArrayList<String>();
                for (JsonNode capability : welcome.path("capabilities")) {
                    if (!capability.isTextual() || capability.asText().isBlank()) {
                        throw new IOException("Invalid welcome capability");
                    }
                    capabilities.add(capability.textValue());
                }
                connection.identity = actual;
                connection.capabilities = List.copyOf(capabilities);
                connection.confirmationRoundTrip = welcome.path("confirmationRoundTrip").booleanValue();
                connection.sessionId = welcome.path("sessionId").isTextual() ? welcome.path("sessionId").textValue() : null;
                return connection;
            }).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            completed = true;
            return result;
        } catch (TimeoutException e) {
            throw new IOException("Backend handshake timed out after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Backend handshake interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Backend handshake failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            if (!completed) connection.abort();
            handshake.shutdownNow();
        }
    }

    private static boolean validFrame(JsonNode frame, String type) {
        return frame.isObject() && frame.path("v").isIntegralNumber() && frame.path("v").canConvertToInt()
                && frame.path("v").intValue() == 1 && frame.path("type").asText().equals(type);
    }

    private static JsonNode read(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) throw new IOException("Backend exited before completing handshake (stdout EOF)");
        JsonNode frame = JSON.readTree(line);
        if (frame == null || !frame.isObject()) throw new IOException("Backend stdout must contain JSON objects");
        return frame;
    }

    /** Exclusive stream ownership transfers after welcome; callers must consume events as well as responses. */
    public static final class Connection implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final BufferedWriter input;
        private BackendIdentity identity;
        private List<String> capabilities;
        private boolean confirmationRoundTrip;
        private String sessionId;

        private Connection(Process process) {
            this.process = process;
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        public BackendIdentity identity() { return identity; }
        public List<String> capabilities() { return capabilities; }
        public boolean confirmationRoundTrip() { return confirmationRoundTrip; }
        public String sessionId() { return sessionId; }
        public BufferedReader output() { return output; }
        public BufferedWriter input() { return input; }
        public Process process() { return process; }

        private void abort() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { input.close(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
        }

        @Override public void close() throws IOException {
            try {
                input.close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) abort();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abort();
                throw new IOException("Interrupted while stopping backend", e);
            } finally {
                if (process.isAlive()) abort();
                output.close();
            }
        }
    }
}
