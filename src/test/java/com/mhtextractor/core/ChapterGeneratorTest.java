package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 章节命名策略化锁定（会话 15C §8.1）：中文 locale 规则与既有行为逐字一致
 * （边界 1/10/11/20/100/123/9999/10000）；英文 locale 用 Chapter N；
 * 两策略对 0-based 输入解释一致；签名与语义未变（TextBatchProcessor 依赖）。
 */
class ChapterGeneratorTest {

    private final Locale saved = I18n.currentLocale();

    @AfterEach
    void restoreLocale() {
        I18n.setLocale(saved);
    }

    @Test
    void chineseLocaleMatchesLegacyRules() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("第一章", ChapterGenerator.generateChapterName(0));
        assertEquals("第十章", ChapterGenerator.generateChapterName(9));
        assertEquals("第十一章", ChapterGenerator.generateChapterName(10));
        assertEquals("第二十章", ChapterGenerator.generateChapterName(19));
        assertEquals("第一百章", ChapterGenerator.generateChapterName(99));
        assertEquals("第一百二十三章", ChapterGenerator.generateChapterName(122));
        assertEquals("第九千九百九十九章", ChapterGenerator.generateChapterName(9998));
        assertEquals("第10000章", ChapterGenerator.generateChapterName(9999));
    }

    @Test
    void englishLocaleUsesWesternFormat() {
        I18n.setLocale(Locale.US);
        assertEquals("Chapter 1", ChapterGenerator.generateChapterName(0));
        assertEquals("Chapter 10", ChapterGenerator.generateChapterName(9));
        assertEquals("Chapter 11", ChapterGenerator.generateChapterName(10));
        assertEquals("Chapter 20", ChapterGenerator.generateChapterName(19));
        assertEquals("Chapter 100", ChapterGenerator.generateChapterName(99));
        assertEquals("Chapter 123", ChapterGenerator.generateChapterName(122));
        assertEquals("Chapter 10000", ChapterGenerator.generateChapterName(9999));
    }

    @Test
    void bothStrategiesShareZeroBasedSemantics() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("第六章", ChapterGenerator.generateChapterName(5));
        I18n.setLocale(Locale.US);
        assertEquals("Chapter 6", ChapterGenerator.generateChapterName(5));
    }

    @Test
    void negativeIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> ChapterGenerator.generateChapterName(-1));
    }
}
