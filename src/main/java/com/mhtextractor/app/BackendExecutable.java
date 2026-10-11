package com.mhtextractor.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the companion executable without depending on the view or a native-image API.
 *
 * <p><b>P4-21 打包布局</b>：发行目录把**后端独立**放在根目录下的 {@code Backend/} 子文件夹
 * （前端 exe + JavaFX/VC++ DLL 仍在根目录，dynamic 配方要求它们与前端 exe 同级）。
 * 解析顺序：{@code Backend/<exe>} → 根目录旧扁平布局（兼容旧发行包/开发期 jar），
 * 仍可用 {@code -Dmht.backend.executable=<path>} 覆盖。</p>
 */
public final class BackendExecutable {
    /** 随包后端可执行文件名。 */
    static final String BACKEND_EXE = "MhtExtractorBackend.exe";
    /** 持有后端的发行子目录（前端 exe 与 DLL 仍在根目录）。 */
    static final String BACKEND_DIR = "Backend";

    private BackendExecutable() { }

    public static Path resolve() {
        String override = System.getProperty("mht.backend.executable");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        return resolveIn(dataRoot());
    }

    /**
     * 在一个发行目录内解析：优先 {@code Backend/<exe>}（P4-21 当前布局），
     * 否则回退根目录旧布局（旧发行包 / jar 同目录 / 工作目录）。
     * 包级可见：让布局规则可以**脱离 native 镜像**做单元测试。
     */
    static Path resolveIn(Path folder) {
        Path nested = folder.resolve(BACKEND_DIR).resolve(BACKEND_EXE);
        return Files.isRegularFile(nested) ? nested : folder.resolve(BACKEND_EXE);
    }

    static Path dataRoot() {
        Path folder = Path.of("").toAbsolutePath();
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return ProcessHandle.current().info().command().map(Path::of)
                    .map(Path::getParent).orElse(folder);
        }
        try {
            Path source = Path.of(BackendExecutable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        } catch (Exception ignored) { /* Unavailable code source: use the launch working directory. */ }
        return folder;
    }

    static Path tempRoot() {
        return dataRoot().resolve(BACKEND_DIR).resolve("temp");
    }

    public static ProcessBuilder command(String locale) throws IOException {
        Path executable = resolve();
        if (!Files.isRegularFile(executable)) throw new IOException("Backend executable missing: " + executable);
        Path temp = tempRoot();
        Files.createDirectories(temp);
        return new ProcessBuilder(executable.toString(), "-Djava.io.tmpdir=" + temp,
                "--serve", "--locale", locale).directory(dataRoot().toFile());
    }
}
