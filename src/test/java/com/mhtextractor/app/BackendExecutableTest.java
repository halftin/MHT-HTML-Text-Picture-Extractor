package com.mhtextractor.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4-21 打包布局：后端在 {@code Backend/} 子文件夹，前端 exe + DLL 留在根目录。
 * 三个用例互为控制组（嵌套优先 / 仅嵌套 / 旧扁平兼容）。
 */
class BackendExecutableTest {

    @TempDir Path tmp;

    private Path nested() throws Exception {
        Path p = tmp.resolve(BackendExecutable.BACKEND_DIR).resolve(BackendExecutable.BACKEND_EXE);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "backend");
        return p;
    }

    @Test
    void nestedBackendFolderWinsOverFlatLayout() throws Exception {
        Path nested = nested();
        Files.writeString(tmp.resolve(BackendExecutable.BACKEND_EXE), "old-flat"); // 旧布局控制组
        assertEquals(nested, BackendExecutable.resolveIn(tmp),
                "Backend/ 布局必须优先于根目录旧布局");
    }

    @Test
    void nestedOnlyLayoutResolves() throws Exception {
        assertEquals(nested(), BackendExecutable.resolveIn(tmp));
    }

    @Test
    void flatLayoutStillResolvesForOldBuilds() throws Exception {
        Path flat = tmp.resolve(BackendExecutable.BACKEND_EXE);
        Files.writeString(flat, "old-flat");
        assertEquals(flat, BackendExecutable.resolveIn(tmp), "旧版扁平布局必须仍可定位（兼容）");
    }

    @Test void classDirectoryUsesItsParentAndCreatesTempBeforeLaunch() throws Exception {
        checkCommand(false, false);
    }

    @Test void jarUsesItsParentRegardlessOfWorkingDirectory() throws Exception {
        checkCommand(true, false);
    }

    @Test void missingCodeSourceFallsBackToWorkingDirectory() throws Exception {
        checkCommand(false, true);
    }

    @Test void nativeRootUsesCurrentExecutableDirectory() {
        String nativeCode = System.getProperty("org.graalvm.nativeimage.imagecode");
        String override = System.getProperty("mht.backend.executable");
        try {
            System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
            System.clearProperty("mht.backend.executable");
            Path root = Path.of(ProcessHandle.current().info().command().orElseThrow()).getParent();
            assertEquals(root.resolve("MhtExtractorBackend.exe"), BackendExecutable.resolve());
        } finally {
            restore("org.graalvm.nativeimage.imagecode", nativeCode);
            restore("mht.backend.executable", override);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    private void checkCommand(boolean jar, boolean fallback) throws Exception {
        Path dist = Files.createDirectories(tmp.resolve("distribution"));
        Path cwd = Files.createDirectories(tmp.resolve("unrelated-cwd"));
        Path expected = fallback ? cwd : dist;
        Path exe = expected.resolve("Backend/MhtExtractorBackend.exe");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "command construction only");
        String resource = "com/mhtextractor/app/BackendExecutable.class";
        byte[] bytes;
        try (var stream = BackendExecutable.class.getClassLoader().getResourceAsStream(resource)) {
            bytes = stream.readAllBytes();
        }
        Path source;
        if (jar) {
            source = dist.resolve("frontend.jar");
            try (var out = new JarOutputStream(Files.newOutputStream(source))) {
                out.putNextEntry(new JarEntry(resource));
                out.write(bytes);
                out.closeEntry();
            }
        } else {
            source = Files.createDirectories(dist.resolve("classes"));
            Path target = source.resolve(resource);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        Path testClasses = Path.of(CommandProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> argv = List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--limit-modules", "java.se", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", source + File.pathSeparator + testClasses, CommandProbe.class.getName(),
                Boolean.toString(fallback), expected.toString(), exe.toString());
        Path log = tmp.resolve("probe.log");
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "layout probe timeout");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.isDirectory(expected.resolve("Backend/temp")));
            assertFalse(Files.exists((fallback ? dist : cwd).resolve("Backend/temp")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    /** Only invokes public production API, including with an unavailable CodeSource. */
    public static final class CommandProbe {
        public static void main(String[] arguments) throws Throwable {
            ProcessBuilder command;
            if (Boolean.parseBoolean(arguments[0])) {
                byte[] bytes;
                try (var stream = BackendExecutable.class.getResourceAsStream("BackendExecutable.class")) {
                    bytes = stream.readAllBytes();
                }
                Class<?> isolated = new ClassLoader(null) {
                    Class<?> define(byte[] content) {
                        return defineClass(BackendExecutable.class.getName(), content, 0, content.length);
                    }
                }.define(bytes);
                command = (ProcessBuilder) MethodHandles.publicLookup().findStatic(isolated, "command",
                        MethodType.methodType(ProcessBuilder.class, String.class)).invokeExact("en");
            } else {
                command = BackendExecutable.command("en");
            }
            Path root = Path.of(arguments[1]);
            Path temp = root.resolve("Backend/temp");
            if (!root.toFile().equals(command.directory()) || !Files.isDirectory(temp) || !Files.isDirectory(root.resolve("logs"))
                    || !command.command().equals(List.of(arguments[2], "-Djava.io.tmpdir=" + temp,
                    "-Dmht.logs.root=" + root.resolve("logs"), "--serve", "--locale", "en"))) throw new AssertionError(command.command());
            System.out.println("LAYOUT OK cwd=" + command.directory() + " temp=" + temp);
        }
    }
}
