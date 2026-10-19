package com.mhtextractor.uiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhtextractor.uiclient.BackendIdentity;
import com.mhtextractor.uiport.ConfirmRequest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BackendSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Question(String id, ConfirmRequest request, Consumer<String> answer) { }
    private static final class View implements BackendSession.View {
        final LinkedBlockingQueue<BackendSession.Status> states = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Question> questions = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<BackendSession.End> ends = new LinkedBlockingQueue<>();
        public void statusChanged(BackendSession.Status status) {
            states.add(status);
            System.out.println("SESSION STATUS: " + status);
        }
        public void confirmationRequested(String id, ConfirmRequest request, Consumer<String> answer) {
            questions.add(new Question(id, request, answer));
            System.out.println("SESSION CONFIRM: id=" + id + " request=" + request);
        }
        public void confirmationFinished(String id, BackendSession.End reason) {
            ends.add(reason);
            System.out.println("SESSION FINISH: id=" + id + " reason=" + reason);
        }
        BackendSession.Status state(BackendSession.State expected) throws Exception {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < limit) {
                var next = states.poll(1, TimeUnit.SECONDS);
                if (next != null && next.state() == expected) return next;
            }
            fail("Missing state " + expected);
            return null;
        }
    }

    private Path root() throws Exception {
        return Files.createTempDirectory(Path.of("H:/TEST/mht"), "p4-17-session-");
    }

    private ProcessBuilder command(Path root, String main, String... args) throws Exception {
        String cp = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !new File(entry).getName().matches("(?i)(java" + "fx|atlantafx|ikonli).*"))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        // CG: shadow only the peer's identity resource, keeping the UI's expected identity intact.
        String declared = System.getProperty("mht.cg.session.buildId");
        if (declared != null) {
            Path overlay = Files.createDirectories(root.resolve("identity"));
            Files.writeString(overlay.resolve("backend-build.properties"), "version=" + BackendIdentity.current().version()
                    + "\nbuildId=" + declared + "\n");
            cp = overlay + File.pathSeparator + cp;
        }
        var cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Dlogback.configurationFile=logback-headless.xml", "-cp", cp, main));
        cmd.addAll(List.of(args));
        Files.write(root.resolve("command.txt"), cmd);
        return new ProcessBuilder(cmd).directory(root.toFile()).redirectError(root.resolve("stderr.txt").toFile());
    }

    @Test void connectsAndMissingBackendDegradesWithReadableReason() throws Exception {
        View view = new View();
        Path work = root();
        try (var session = new BackendSession(Runnable::run, view)) {
            session.start(() -> {
                try { return command(work, "com.mhtextractor.backend.BackendCli", "--serve", "--locale", "en"); }
                catch (Exception e) { throw new java.io.IOException(e); }
            }, Duration.ofSeconds(10));
            view.state(BackendSession.State.CONNECTED);
            JsonNode workspace = session.request("workspace", JSON.createObjectNode()).get(5, TimeUnit.SECONDS);
            assertTrue(workspace.isObject());
            System.out.println("SESSION HANDSHAKE + COMMAND PASS: " + workspace);
        }
        View missing = new View();
        try (var session = new BackendSession(Runnable::run, missing)) {
            session.start(() -> new ProcessBuilder(work.resolve("missing.exe").toString(), "--serve"), Duration.ofSeconds(2));
            assertTrue(missing.state(BackendSession.State.UNAVAILABLE).detail().contains("missing.exe"));
        }
        String previous = System.getProperty("mht.backend.executable");
        try {
            System.setProperty("mht.backend.executable", work.resolve("absent.exe").toString());
            assertEquals(work.resolve("absent.exe"), BackendExecutable.resolve());
            assertThrows(java.io.IOException.class, () -> BackendExecutable.command("en"));
        } finally {
            if (previous == null) System.clearProperty("mht.backend.executable");
            else System.setProperty("mht.backend.executable", previous);
        }
    }

    @Test void handshakeSpoofAndStallDegradeAndCleanUp() throws Exception {
        for (String mode : List.of("spoof", "stall", "protocol", "capabilities")) {
            View view = new View();
            Path work = root();
            try (var session = new BackendSession(Runnable::run, view)) {
                session.start(() -> {
                    try { return command(work, "com.mhtextractor.uiclient.BackendLauncherWireFixture", mode); }
                    catch (Exception e) { throw new java.io.IOException(e); }
                }, Duration.ofSeconds(mode.equals("stall") ? 1 : 5));
                String detail = view.state(BackendSession.State.UNAVAILABLE).detail();
                String expected = switch (mode) {
                    case "stall" -> "timed out";
                    case "protocol" -> "Protocol version mismatch";
                    case "capabilities" -> "lacks required capabilities";
                    default -> "buildId mismatch";
                };
                assertTrue(detail.contains(expected), detail);
            }
        }
    }

    @Test void realConfirmationAnswersTimeoutAndDisconnectReleaseTheView() throws Exception {
        for (String mode : List.of("answer", "timeout", "disconnect")) {
            Path work = root();
            Path source = deepSource(work);
            View view = new View();
            try (var session = new BackendSession(Runnable::run, view)) {
                session.start(() -> {
                    try { return command(work, "com.mhtextractor.backend.BackendIpcConfirmationFixture",
                            mode.equals("timeout") ? "400" : "30000"); }
                    catch (Exception e) { throw new java.io.IOException(e); }
                }, Duration.ofSeconds(10));
                view.state(BackendSession.State.CONNECTED);
                session.request("importPaths", JSON.createObjectNode().set("paths", JSON.valueToTree(List.of(source.toString()))))
                        .get(5, TimeUnit.SECONDS);
                var extract = session.request("extract", JSON.createObjectNode().put("checkedOnly", false)
                        .put("outputFolder", work.resolve("output").toString()));
                Question first = view.questions.poll(5, TimeUnit.SECONDS);
                assertNotNull(first, "Backend must ask instead of silently cancelling");
                assertEquals("alert.recursionDepthHeader", first.request.key());
                assertEquals(java.util.Map.of(), first.request.namedParams());
                if (mode.equals("answer")) {
                    first.answer.accept(System.getProperty("mht.cg.session.answer", "continue"));
                    assertEquals("SUCCEEDED", extract.get(5, TimeUnit.SECONDS).path("end").asText());
                    assertNull(view.questions.poll(), "Continue must suppress the second question");
                    assertEquals(BackendSession.End.ANSWERED, view.ends.poll(5, TimeUnit.SECONDS));
                } else if (mode.equals("timeout")) {
                    assertEquals(BackendSession.End.TIMEOUT, view.ends.poll(5, TimeUnit.SECONDS));
                    Question second = view.questions.poll(5, TimeUnit.SECONDS);
                    assertNotNull(second, "Server timeout must act like cancel");
                    second.answer.accept("continue");
                    assertEquals("SUCCEEDED", extract.get(5, TimeUnit.SECONDS).path("end").asText());
                    view.state(BackendSession.State.CONNECTED);
                } else {
                    session.close();
                    assertEquals(BackendSession.End.DISCONNECTED, view.ends.poll(1, TimeUnit.SECONDS));
                    assertTrue(extract.isCompletedExceptionally());
                }
                System.out.println("SESSION ROUNDTRIP PASS: mode=" + mode);
            }
        }
    }

    private Path deepSource(Path work) throws Exception {
        String leaf = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>truncated</body></html>";
        String body = "Content-Type: multipart/mixed; boundary=leaf\r\n\r\n--leaf\r\n" + leaf
                + "\r\n--leaf\r\n" + leaf + "\r\n--leaf--\r\n";
        for (int i = 98; i >= 0; i--) body = "Content-Type: multipart/mixed; boundary=b" + i
                + "\r\n\r\n--b" + i + "\r\n" + body + "\r\n--b" + i + "--\r\n";
        Path source = work.resolve("deep.mht");
        Files.writeString(source, "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=outer\r\n\r\n--outer\r\n"
                + body + "\r\n--outer--\r\n");
        return source;
    }
}
