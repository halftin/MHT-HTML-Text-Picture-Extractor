package com.mhtextractor.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 预览缓存的不可变键（需求 2 P1/P9/P10）：四要素（绝对路径、size、mtime、参数指纹）+ 格式版本。
 * 参数指纹是「算好的不可变字符串」（{@link com.mhtextractor.config.AppConfig#imageExtractionFingerprint()}
 * 的产出），本类不持有、也不依赖 AppConfig（AppConfig 是 @Data 可变对象，不能当键用）。
 */
public record PreviewCacheKey(String absolutePath, long size, long mtime,
                              String paramFingerprint, int formatVersion) {

    public PreviewCacheKey {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(paramFingerprint, "paramFingerprint");
    }

    /**
     * 从 MHT 文件路径计算缓存键（内部做绝对化 + 规范化，保证同一文件无论以何种路径形式传入都得到同一 key）。
     * size/mtime 从文件系统实时读取（P9 四要素）。
     */
    public static PreviewCacheKey compute(Path file, String paramFingerprint, int formatVersion)
            throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        long size = Files.size(abs);
        long mtime = Files.getLastModifiedTime(abs).toMillis();
        return new PreviewCacheKey(abs.toString(), size, mtime, paramFingerprint, formatVersion);
    }

    /** 缓存目录名 =（四要素 + 格式版本）拼接串的 SHA-256 全 64 位十六进制（对齐 HtmlExportBuilder.cacheKey 风格） */
    public String toDirName() {
        String raw = absolutePath + "|" + size + "|" + mtime + "|" + paramFingerprint + "|v" + formatVersion;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
