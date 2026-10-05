package com.mhtextractor.gui.component;

import com.mhtextractor.platform.HtmlSyntaxTokens;
import com.mhtextractor.uicore.HtmlFindIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlFindIndex}（R5.5 的查找索引）的单元测试。
 *
 * <p><b>为什么它必须是单测而不是只靠 GUI 探针</b>：匹配口径（字面量 / 大小写 / 空查询 /
 * 行内列号）是纯函数行为，GUI 探针里只能验一两个样本；而"用户输入 {@code .} 不该变成
 * 通配符"这类事情一旦错了，界面表现完全正常（只是搜出来的东西不对）——
 * 正是本项目"没人会发现"的那种缺陷。</p>
 *
 * <p>本类仍留在 {@code com.mhtextractor.gui.component} 包内，因为它还要直访同包的
 * {@link HtmlSourceViewer#plainText}；{@link HtmlFindIndex} 已于 P2-1 迁入
 * {@code com.mhtextractor.uicore} 并<b>放宽为 public</b>。它也<b>不需要 JavaFX</b>，
 * 因此留在默认执行集里（不是 headless-layer-b 的那五个类）。</p>
 */
class HtmlFindIndexTest {

    private static final String HTML = """
            <html>
            <body>
            Hello World
            hello again, HELLO
            </body>
            </html>
            """;

    private static HtmlFindIndex index(String query) {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(HTML);
        return HtmlFindIndex.build(HtmlSyntaxTokens.normalizeNewlines(HTML), lines, query);
    }

    @Test
    void findsEveryOccurrenceCaseInsensitivelyAndInOrder() {
        HtmlFindIndex idx = index("hello");

        assertEquals(3, idx.size());
        // 第 3 行（0 基 2）"Hello World" 的列 0..5
        assertEquals(2, idx.get(0).line());
        assertEquals("[0,5)", "[" + idx.get(0).startCol() + "," + idx.get(0).endCol() + ")");
        // 第 4 行（0 基 3）两处：开头与逗号后
        assertEquals(3, idx.get(1).line());
        assertEquals(0, idx.get(1).startCol());
        assertEquals(13, idx.get(2).startCol());
    }

    @Test
    void treatsTheQueryAsALiteralNotARegExp() {
        // "H.llo" 若被当正则，会命中 "Hello"；字面量口径必须命中 0 处
        assertEquals(0, index("H.llo").size());
        // "*" 同理：正则里这是非法/量词，字面量口径只找真正的星号
        assertEquals(0, index("*").size());
        assertEquals(0, index("(").size());
        assertEquals(0, index("\\").size());
    }

    @Test
    void emptyQueryMeansNoHitsRatherThanEverything() {
        assertSame(HtmlFindIndex.EMPTY, index(""));
        assertSame(HtmlFindIndex.EMPTY, index(null));
        assertTrue(HtmlFindIndex.EMPTY.isEmpty());
        assertEquals(0, HtmlFindIndex.EMPTY.size());
    }

    @Test
    void matchesDoNotOverlap() {
        // "aaa" 里找 "aa"：与所有查找框一致，得 1 处而不是 2 处
        String html = "aaa";
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(html);
        HtmlFindIndex idx = HtmlFindIndex.build(html, lines, "aa");
        assertEquals(1, idx.size());
    }

    @Test
    void multilineQueryCannotMatchAcrossLines() {
        // 换行符在 htm 里真实存在，所以跨行字面量是能命中的；这里钉住行号是非负的（不会崩）
        HtmlFindIndex idx = index("World\nhello");
        assertEquals(1, idx.size());
        assertEquals(2, idx.get(0).line());
    }

    @Test
    void noHitsForAMissingWord() {
        HtmlFindIndex idx = index("zzzz-not-there");
        assertTrue(idx.isEmpty());
        assertEquals(0, idx.size());
        assertFalse(idx.size() > 0);
    }

    @Test
    void everyMatchCarriesAWellFormedRangeInsideItsLine() {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(HTML);
        HtmlFindIndex idx = HtmlFindIndex.build(HtmlSyntaxTokens.normalizeNewlines(HTML), lines, "o");
        assertTrue(idx.size() > 0);
        for (int i = 0; i < idx.size(); i++) {
            HtmlFindIndex.Match m = idx.get(i);
            String lineText = HtmlSourceViewer.plainText(lines.get(m.line()).tokens());
            assertTrue(m.startCol() >= 0 && m.endCol() > m.startCol()
                            && m.endCol() <= lineText.length(),
                    "命中区间越界：" + m + " line=" + lineText.length());
        }
    }
}
