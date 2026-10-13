package com.mhtextractor.uiport;

/** An encoded file; bytes is its actual on-disk length. */
public record FileRef(String path, long bytes, String mediaType) {
    public FileRef {
        if (path == null || !java.nio.file.Path.of(path).isAbsolute() || bytes < 0
                || mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("Invalid FileRef");
    }
}
