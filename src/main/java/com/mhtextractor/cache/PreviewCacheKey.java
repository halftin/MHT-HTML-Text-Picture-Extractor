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
 * 预览缓存的不可变键（需求 2 P1/P9/P10）：四要素（绝对路径、size、mtime、参数指纹）
 * + <b>来源附属指纹</b> + 格式版本。
 * 参数指纹是「算好的不可变字符串」（{@link com.mhtextractor.model.AppConfig#imageExtractionFingerprint()}
 * 的产出），本类不持有、也不依赖 AppConfig（AppConfig 是 @Data 可变对象，不能当键用）。
 *
 * <p><b>R6.5：为什么多一个 {@code extraFingerprint}</b> —— HTML 的图片不在 HTML 文件里，
 * 而在同目录的侧车文件夹（{@code page_files/}）里。只改侧车里的一张图，
 * <b>HTML 文件的 size/mtime 一点都不会变</b> ⇒ 四要素算出的 key 不变 ⇒ 命中陈旧缓存
 * （用户看到的是改动前的那张图，且没有任何提示）。因此对 HTML 来源必须把侧车资源的摘要
 * 折进 key；MHT 来源没有附属文件，该字段为 {@code ""}，键值与 R6 之前<b>逐字节等价</b>。</p>
 *
 * <p>{@link #compute} 内部对 HTML 侧车做摘要（{@link SourceFingerprint}），MHT 直接为空串 ——
 * 这样"三处构造点"（{@code PreviewService} / {@code MainController} / {@code ImageBatchProcessor}）
 * 不必各写一份摘要逻辑，也不会出现两处算法不一致导致缓存永不命中的静默故障。</p>
 */
public record PreviewCacheKey(String absolutePath, long size, long mtime,
                              String paramFingerprint, String extraFingerprint, int formatVersion) {

    public PreviewCacheKey {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(paramFingerprint, "paramFingerprint");
        extraFingerprint = extraFingerprint == null ? "" : extraFingerprint;
    }

    /**
     * 兼容构造（5 参数）：附属指纹取空串。保留它是为了让"改键语义"这件事<b>不扩散</b>成
     * 几十处测试夹具的机械改动 —— 旧调用点的键值与 R6 之前完全一致。
     */
    public PreviewCacheKey(String absolutePath, long size, long mtime,
                           String paramFingerprint, int formatVersion) {
        this(absolutePath, size, mtime, paramFingerprint, "", formatVersion);
    }

    /**
     * 从文件路径计算缓存键（内部做绝对化 + 规范化，保证同一文件无论以何种路径形式传入都得到同一 key）。
     * size/mtime 从文件系统实时读取（P9 四要素）；附属文件摘要见 {@link SourceFingerprint}。
     */
    public static PreviewCacheKey compute(Path file, String paramFingerprint, int formatVersion)
            throws IOException {
        return compute(file, paramFingerprint, SourceFingerprint.of(file), formatVersion);
    }

    /** 同上，但附属指纹由调用方传入（已算好时避免重复遍历侧车目录）。 */
    public static PreviewCacheKey compute(Path file, String paramFingerprint, String extraFingerprint,
                                          int formatVersion) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        long size = Files.size(abs);
        long mtime = Files.getLastModifiedTime(abs).toMillis();
        return new PreviewCacheKey(abs.toString(), size, mtime, paramFingerprint, extraFingerprint,
                formatVersion);
    }

    /** 缓存目录名 =（四要素 + 附属指纹 + 格式版本）拼接串的 SHA-256 全 64 位十六进制 */
    public String toDirName() {
        String raw = absolutePath + "|" + size + "|" + mtime + "|" + paramFingerprint
                + "|" + extraFingerprint + "|v" + formatVersion;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
