package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class ChapterNumbersTest {
    @Test void allChineseNumbersRoundTripAgainstNamingStrategy() {
        Locale previous = I18n.currentLocale();
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            for (int n=1; n<=9999; n++) assertEquals(n, ChapterNumbers.parse(ChapterGenerator.generateChapterName(n-1)).orElseThrow());
        } finally { I18n.setLocale(previous); }
    }

    @Test void arabicEnglishAndMalformedValues() {
        assertEquals(12, ChapterNumbers.parse("第12章 title.mht").orElseThrow());
        assertEquals(7, ChapterNumbers.parse("chapter 7 title.html").orElseThrow());
        assertEquals(10000, ChapterNumbers.parse("Chapter 10000").orElseThrow());
        for (String invalid : List.of("body", "第零章", "Chapter 0", "第十百章", "第一二章"))
            assertTrue(ChapterNumbers.parse(invalid).isEmpty(), invalid);
        assertEquals(2, ChapterNumbers.resolve("第二章 title", "第十章 file.mht"));
    }

    @Test void naturalOrderIsStableAndDoesNotMutateImportOrder() {
        var original = List.of("第十章", "第十一章", "第二十三章", "Chapter 7", "第12章", "第二十三章 B", "z", "a", "m");
        assertEquals(List.of("Chapter 7","第十章","第十一章","第12章","第二十三章","第二十三章 B","z","a","m"),
                ChapterNumbers.stableOrder(original, s -> ChapterNumbers.parse(s).orElse(0)));
        assertEquals("第十章", original.getFirst());
    }
}
