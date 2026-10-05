package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缺口单测（测试轮 2026-10-05）：{@link FingerprintEncoder#lengthPrefixed} 是预览缓存键
 * `imageExtractionFingerprint()` / `EffectiveParamsService.signatureOf` 的**字节级契约**，
 * 此前只被间接覆盖（AppConfigTest / EffectiveParamsServiceTest 走整体摘要）。
 *
 * <p>这里把编码口径钉死：null 与空串可区分、冒号不产生歧义、长度按 UTF-16 code unit 计（与旧实现逐字一致）。
 * 口径一改，缓存命中率会**静默**变化，故必须有独立回归。</p>
 */
class FingerprintEncoderTest {

    private static String enc(String value) {
        StringBuilder sb = new StringBuilder();
        FingerprintEncoder.lengthPrefixed(sb, value);
        return sb.toString();
    }

    @Test
    void nullIsDistinctFromEmptyString() {
        assertEquals("-1:", enc(null), "null 编码为 -1:（与空串区分）");
        assertEquals("0:", enc(""), "空串编码为 0:");
    }

    @Test
    void lengthIsUtf16CodeUnitsLikeTheOldInlineExpression() {
        assertEquals("1:a", enc("a"));
        assertEquals("3:abc", enc("abc"));
        // 中文 4 个 UTF-16 code unit（BMP，非代理对）
        assertEquals("4:标题标记", enc("标题标记"));
    }

    @Test
    void colonsInsideTheValueCannotCreateAmbiguity() {
        // 值里本身含冒号：长度前缀仍能唯一定界
        assertEquals("5:a:b:c", enc("a:b:c"));
        assertEquals("3:a:b", enc("a:b"));
    }

    @Test
    void repeatedCallsConcatenateLosslessly() {
        StringBuilder sb = new StringBuilder();
        FingerprintEncoder.lengthPrefixed(sb, "x");
        FingerprintEncoder.lengthPrefixed(sb, null);
        FingerprintEncoder.lengthPrefixed(sb, "yz");
        assertEquals("1:x-1:2:yz", sb.toString());
    }

    @Test
    void longValueKeepsExactLength() {
        String v = "0123456789".repeat(100);
        assertEquals(1000 + ":" + v, enc(v));
    }
}
