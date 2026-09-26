package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;

import java.util.Locale;

/**
 * 章节序号生成器（0-based；策略按当前 locale 选择，会话 15C §8.1）。
 */
public class ChapterGenerator {

    private static final ChapterNamingStrategy CHINESE =
            new ChapterNamingStrategy.ChineseChapterNamingStrategy();
    private static final ChapterNamingStrategy WESTERN = new WesternChapterNamingStrategy();

    private ChapterGenerator() {
    }

    /**
     * 生成章节名（0-based索引 → "第一章" / "Chapter 1"）。
     * 策略：zh* locale 用中文；其余 locale 用西文。
     */
    public static String generateChapterName(int index) {
        return strategyFor(I18n.currentLocale()).generateChapterName(index);
    }

    private static ChapterNamingStrategy strategyFor(Locale locale) {
        return locale != null && "zh".equals(locale.getLanguage()) ? CHINESE : WESTERN;
    }
}
