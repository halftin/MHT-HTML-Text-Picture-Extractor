package com.mhtextractor.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐文件覆盖项（{@link PerFileOverrides}）的纯单元测试：
 * 标题覆盖判定边界（null / 空串 / 纯空白 / 制表符换行）+ 单参兼容构造。
 */
class PerFileOverridesTest {

    @Test
    void titleOverrideBlankBoundaries() {
        assertFalse(new PerFileOverrides(true, null).hasTitleOverride(), "null 覆盖 = 没有");
        assertFalse(new PerFileOverrides(true, "").hasTitleOverride(), "空串覆盖 = 没有");
        assertFalse(new PerFileOverrides(true, "   ").hasTitleOverride(), "纯空白覆盖 = 没有");
        assertFalse(new PerFileOverrides(true, "\t\n").hasTitleOverride(), "制表符/换行覆盖 = 没有");
        assertTrue(new PerFileOverrides(true, "标题").hasTitleOverride(), "非空覆盖 = 有");
        assertTrue(new PerFileOverrides(true, "  标题  ").hasTitleOverride(), "两侧空白但非空 = 有");
    }

    @Test
    void singleArgConstructorKeepsCompatibilityAndNoTitleOverride() {
        PerFileOverrides ov = new PerFileOverrides(false);
        assertFalse(ov.chapterEnabled());
        assertNull(ov.titleOverride());
        assertFalse(ov.hasTitleOverride());
        assertTrue(PerFileOverrides.DEFAULTS.chapterEnabled());
        assertNull(PerFileOverrides.DEFAULTS.titleOverride());
    }
}
