package com.mhtextractor.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Real production CLI subprocess, with persistent commands/stdout/stderr and no UI runtime. */
final class BackendProcess {
    private BackendProcess() { }

    record Result(int code, String stdout, String stderr) { }

    static Path evidence(String name) throws Exception {
        String runtime = System.getProperty("mht.backend.executable") == null ? "jvm" : "native";
        Path root = Path.of(System.getProperty("mht.backend.evidence",
                "H:/TEST/mht/p4-5-" + runtime + "-baseline")).toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, name + "-");
    }

    static List<String> command() {
        List<String> cmd = new ArrayList<>();
        String exe = System.getProperty("mht.backend.executable");
        if (exe != null && !exe.isBlank()) {
            cmd.add(Path.of(exe).toAbsolutePath().toString());
        } else {
            cmd.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            String agentDir = System.getProperty("mht.backend.agentDir");
            if (agentDir != null) {
                cmd.add("-agentpath:" + Path.of(System.getProperty("java.home"), "bin", "native-image-agent.dll")
                        + "=config-merge-dir=" + agentDir);
            }
            cmd.add("--limit-modules");
            cmd.add(agentDir == null ? "java.se" : "java.se,jdk.unsupported");
        }
        cmd.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        if (exe == null || exe.isBlank()) {
            cmd.add("-cp");
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            cmd.add(Arrays.stream(cp.split(File.pathSeparator))
                    .filter(p -> {
                        String s = p.toLowerCase(Locale.ROOT);
                        return !s.contains("java" + "fx") && !s.contains("atlantafx") && !s.contains("ikonli");
                    })
                    .map(p -> Path.of(p).toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator)));
            cmd.add("com.mhtextractor.backend.BackendCli");
        }
        return cmd;
    }

    static Result run(Path work, List<String> options) throws Exception {
        Files.createDirectories(work);
        if (System.getProperty("mht.backend.agentDir") != null) {
            Files.createDirectories(Path.of(System.getProperty("mht.backend.agentDir")));
        }
        List<String> cmd = command();
        cmd.addAll(options);
        Files.writeString(work.resolve("command.json"), new ObjectMapper().writeValueAsString(cmd));
        Path stdout = work.resolve("stdout.txt");
        Path stderr = work.resolve("stderr.txt");
        Process process = new ProcessBuilder(cmd).directory(work.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("CLI timeout: " + work);
        }
        int code = process.exitValue();
        Files.writeString(work.resolve("exit-code.txt"), Integer.toString(code));
        return new Result(code, Files.readString(stdout), Files.readString(stderr));
    }
}
