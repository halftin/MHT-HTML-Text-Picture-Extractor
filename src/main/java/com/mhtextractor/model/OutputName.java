package com.mhtextractor.model;

/** 名字本体与扩展名；null 扩展名表示目录，null 本体表示尚无单一名字。 */
public record OutputName(String baseName, String extension) {
    public OutputName {
        if (extension != null && !extension.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("Extension must contain lowercase letters or digits");
        }
    }

    public boolean directory() { return extension == null; }

    public String finalFileName() {
        return baseName == null || extension == null ? null : baseName + "." + extension;
    }
}
