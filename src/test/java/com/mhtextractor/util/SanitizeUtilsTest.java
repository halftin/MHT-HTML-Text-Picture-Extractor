package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SanitizeUtils 空串语义锁定（会话 15B 步骤 B）：
 * null/空/纯空白输入返回空串（内部"无名字"表示）；真实名字 "unnamed" 原样保留——
 * 不再与回退名 {@link SanitizeUtils#UNNAMED_FALLBACK_NAME} 碰撞。
 */
class SanitizeUtilsTest {

    @Test
    void sanitizeNullReturnsEmpty() {
        assertEquals("", SanitizeUtils.sanitize(null));
    }

    @Test
    void sanitizeEmptyReturnsEmpty() {
        assertEquals("", SanitizeUtils.sanitize(""));
    }

    @Test
    void sanitizeBlankReturnsEmpty() {
        assertEquals("", SanitizeUtils.sanitize("   "));
    }

    @Test
    void sanitizeLiteralUnnamedIsKept() {
        // 步骤 B 修复点：真实标题 "unnamed" 不再被当作"无名字"丢弃
        assertEquals("unnamed", SanitizeUtils.sanitize("unnamed"));
    }

    @Test
    void fallbackNameIsOnlyForDisplay() {
        // 展示回退名仅用于产物命名兜底（如 unnamed.jpg），内部"无名字"判定用空串
        assertEquals("unnamed", SanitizeUtils.UNNAMED_FALLBACK_NAME);
        assertEquals("", SanitizeUtils.sanitize(""));
    }

    @Test
    void sanitizeReplacesIllegalFilenameChars() {
        assertEquals("a_b_c", SanitizeUtils.sanitize("a:b/c"));
    }

    @Test
    void sanitizeKeepsNormalName() {
        assertEquals("正常标题", SanitizeUtils.sanitize("正常标题"));
    }
}
