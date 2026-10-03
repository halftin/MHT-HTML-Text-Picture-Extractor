package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlTextSelection}（E1 的"选择范围 → 文本"纯逻辑模型）的无头单测。
 *
 * <p>它是 E2 显示折行后仍要复用的同一套逻辑字符偏移，所以这里的边界语义
 * （单行子串 / 跨行 LF 连接 / 空行连续 LF / 缩进与行尾空格保留 / 越界 clamp）
 * 必须钉死成行为，而不是"与某个渲染实现一致"。</p>
 */
class HtmlTextSelectionTest {

    /** 三行样例：行 0 带缩进、行 1 空、行 2 带行尾空格。 */
    private static final List<String> SAMPLE = List.of("  <body>", "", "tail   ");

    // ==================== ordered / isEmpty ====================

    @Test
    void orderedNormalizesBothDirections() {
        assertEquals(new HtmlTextSelection.Range(1, 2, 3, 4),
                HtmlTextSelection.ordered(new HtmlTextSelection.Pos(1, 2), new HtmlTextSelection.Pos(3, 4)));
        assertEquals(new HtmlTextSelection.Range(1, 2, 3, 4),
                HtmlTextSelection.ordered(new HtmlTextSelection.Pos(3, 4), new HtmlTextSelection.Pos(1, 2)));
    }

    @Test
    void orderedComparesColumnOnSameLine() {
        assertEquals(new HtmlTextSelection.Range(2, 0, 2, 5),
                HtmlTextSelection.ordered(new HtmlTextSelection.Pos(2, 5), new HtmlTextSelection.Pos(2, 0)));
    }

    @Test
    void isEmptyIsAnchorEqualsCaret() {
        assertTrue(HtmlTextSelection.isEmpty(new HtmlTextSelection.Range(2, 5, 2, 5)));
        assertFalse(HtmlTextSelection.isEmpty(new HtmlTextSelection.Range(2, 5, 2, 6)));
        assertFalse(HtmlTextSelection.isEmpty(new HtmlTextSelection.Range(2, 5, 3, 0)));
        assertFalse(HtmlTextSelection.isEmpty(null));
    }

    // ==================== extract：单行 ====================

    @Test
    void singleLineTakesSubstring() {
        assertEquals("body", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, 3, 0, 7)));
    }

    @Test
    void singleLineWholeLinePreservesTrailingSpaces() {
        // 行尾空格必须逐字符保留（E1 的核心语义之一）
        assertEquals("tail   ", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(2, 0, 2, 7)));
    }

    @Test
    void emptySelectionExtractsNothing() {
        assertEquals("", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(1, 0, 1, 0)));
        assertEquals("", HtmlTextSelection.extract(SAMPLE, null));
    }

    // ==================== extract：跨行 ====================

    @Test
    void crossLinesJoinsWithSingleLfAndKeepsMiddleLineWhole() {
        // 行 0 尾段 + 整行 1（空）+ 行 2 首段
        assertEquals("body>\n\nta", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, 3, 2, 2)));
    }

    @Test
    void emptyMiddleLineProducesConsecutiveLf() {
        String out = HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, 5, 2, 2));
        assertTrue(out.contains("\n\n"), "空行应产生连续 LF，实际=" + out.replace("\n", "\\n"));
    }

    @Test
    void crossLinesPreservesIndentOfFirstTail() {
        // 起点在行首缩进内部：首行尾段仍以缩进字符开头
        assertEquals(" <body>\n\nt", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, 1, 2, 1)));
    }

    // ==================== extract：越界 clamp ====================

    @Test
    void negativeColumnsClampToZero() {
        assertEquals("  <", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, -5, 0, 3)));
        // 两个列都是负 → 空
        assertEquals("", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, -5, 0, -1)));
    }

    @Test
    void columnsBeyondLineLengthClampToLineEnd() {
        assertEquals("tail   ", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(2, 0, 2, 99)));
        // 两个列都越过行尾 → 空
        assertEquals("", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(2, 8, 2, 99)));
        // 跨行时首行起点越界 → 首行尾段为空，只留 LF 连接
        assertEquals("\n", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(0, 99, 1, 0)));
    }

    @Test
    void linesOutOfRangeClampOrEmpty() {
        // 整个范围都在文本之外 → 空串
        assertEquals("", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(9, 0, 12, 3)));
        // 整个范围在文本之上 → 空
        assertEquals("", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(-3, 0, -1, 3)));
        // 部分越界：clamp 到有效行（末行 clamp 后整行取满）
        assertEquals("tail   ", HtmlTextSelection.extract(SAMPLE,
                new HtmlTextSelection.Range(2, 0, 9, 99)));
    }

    @Test
    void extractOfEmptyListIsEmpty() {
        assertEquals("", HtmlTextSelection.extract(List.of(),
                new HtmlTextSelection.Range(0, 0, 1, 1)));
    }
}
