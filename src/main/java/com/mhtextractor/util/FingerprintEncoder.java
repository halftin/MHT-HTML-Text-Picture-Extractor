package com.mhtextractor.util;

/**
 * 指纹/签名字段的<b>长度前缀编码器</b>（E3.2 收口的全项目唯一实现点）。
 *
 * <p>{@link com.mhtextractor.config.AppConfig#imageExtractionFingerprint()} 与
 * {@link com.mhtextractor.app.EffectiveParamsService#signatureOf} 都要把 String 字段以
 * "长度前缀"方式写入 {@link StringBuilder}（先写长度再写内容；null 与空串可区分、固定字段顺序稳定）。
 * 该编码口径直接决定预览缓存键 —— 两处必须逐字一致，故收敛到本类，禁止就地重写。</p>
 */
public final class FingerprintEncoder {

    private FingerprintEncoder() {
    }

    /**
     * 长度前缀编码：null → {@code -1:}；否则 {@code <长度>:<内容>}。
     *
     * <p>口径与预览缓存键绑定，<b>不得变更</b>（改了会让缓存命中率静默变化）。</p>
     */
    public static void lengthPrefixed(StringBuilder sb, String value) {
        sb.append(value == null ? -1 : value.length()).append(':').append(value == null ? "" : value);
    }
}
