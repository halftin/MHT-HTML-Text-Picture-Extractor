package com.mhtextractor.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the companion executable without depending on the view or a native-image API. */
public final class BackendExecutable {
    private BackendExecutable() { }

    public static Path resolve() {
        String override = System.getProperty("mht.backend.executable");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        Path folder = Path.of("").toAbsolutePath();
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            folder = ProcessHandle.current().info().command().map(Path::of)
                    .map(Path::getParent).orElse(folder);
        } else {
            try {
                Path source = Path.of(BackendExecutable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(source)) folder = source.getParent();
            } catch (Exception ignored) { /* Exploded JVM classes use the launch working directory. */ }
        }
        return folder.resolve("MhtExtractorBackend.exe");
    }

    public static ProcessBuilder command(String locale) throws IOException {
        Path executable = resolve();
        if (!Files.isRegularFile(executable)) throw new IOException("Backend executable missing: " + executable);
        return new ProcessBuilder(executable.toString(), "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "--serve", "--locale", locale);
    }
}
