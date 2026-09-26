package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SanitizeUtils 哨兵行为锁定（会话 15B 步骤 A）：
 * sanitize 空输入返回 {@link SanitizeUtils#UNNAMED_SENTINEL}；
 * 真实值 "unnamed" 原样通过——与哨兵同值、下游 equals 比较会误判（现存语义，如实锁定）。
 */
class SanitizeUtilsTest {

    @Test
    void sanitizeNullReturnsUnnamedSentinel() {
        assertEquals(SanitizeUtils.UNNAMED_SENTINEL, SanitizeUtils.sanitize(null));
    }

    @Test
    void sanitizeEmptyReturnsUnnamedSentinel() {
        assertEquals(SanitizeUtils.UNNAMED_SENTINEL, SanitizeUtils.sanitize(""));
    }

    @Test
    void sanitizeLiteralUnnamedPassesThroughUnchanged() {
        assertEquals("unnamed", SanitizeUtils.sanitize("unnamed"));
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
