package com.mhtextractor.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4：内容限定正则开关语义反转的回归测试。
 * 修复前 useRegex=true 时 extractByMarker 对标记做 Pattern.quote，与标题提取路径语义相反，
 * 正则永远匹配不上 → 记 WARN 后返回完整 HTML（静默失效）。
 */
class ContentExtractorTest {

    private static final String HTML = "<html><body>"
            + "<p>前言段落</p>"
            + "<h1 id=\"title\">第一章 标题</h1>"
            + "<p>正文内容第一段</p>"
            + "<p>正文内容第二段</p>"
            + "</body></html>";

    @Test
    void regexMarkersBehaveTheSameInTitleAndContentLimiting() {
        ContentExtractor extractor = new ContentExtractor();
        String start = "<h1[^>]*>";
        String end = "</h1>";

        String title = extractor.extractChapterTitle(HTML, start, end, true);
        String text = extractor.extractText(HTML, false, start, end, true);

        assertEquals("第一章 标题", title);
        assertNotNull(text);
        assertTrue(text.contains("第一章 标题"));
        assertFalse(text.contains("正文内容第一段"), "内容限定应只保留正则截取的区域");
    }

    @Test
    void regexModeNoMatchReturnsFullHtmlAndRecordsWarning() {
        ContentExtractor extractor = new ContentExtractor();
        String start = "<div id=\"no-such-tag\">";
        String end = "</div>";

        String text = extractor.extractText(HTML, false, start, end, true);

        assertNotNull(text);
        assertTrue(text.contains("正文内容第一段"), "正则不匹配时应回退到完整 HTML");
        assertFalse(extractor.getLastWarnings().isEmpty());
        assertTrue(extractor.getLastWarnings().get(0).contains("内容限定标记未找到"));
    }

    @Test
    void literalModeTreatsMarkersAsPlainText() {
        ContentExtractor extractor = new ContentExtractor();
        String html = "<html><body>[开始]正文内容[结束]</body></html>";

        String text = extractor.extractText(html, false, "[开始]", "[结束]", false);

        assertEquals("正文内容", text, "字面模式应按普通字符串截取，含正则元字符的标记也能命中");
    }

    @Test
    void chapterTitlePathClearsStaleWarnings() {
        ContentExtractor extractor = new ContentExtractor();
        extractor.extractText(HTML, false, "不存在的开始标记", "不存在的结束标记", false);
        assertFalse(extractor.getLastWarnings().isEmpty());

        extractor.extractChapterTitle(HTML, "<h1[^>]*>", "</h1>", true);

        assertTrue(extractor.getLastWarnings().isEmpty(),
                "标题提取路径应清空上一次提取留下的警告（C-8）");
    }
}
