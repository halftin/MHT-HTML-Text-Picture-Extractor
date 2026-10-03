package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2 §3.1 的<b>Chromium 对照语料</b>（无头侧）：LF / CRLF / CR / 连续空行 / 文件末尾带换行与
 * 不带换行 / 制表符 / 行首与行尾空格 / 超长行 / 跨行注释。
 *
 * <p>断言钉死"换行归一化 + 行模型 + 复制"的逐字符保真 —— 归一化换行类型（CRLF/CR→LF）
 * 可接受，但<b>不得合并空行、不得丢字符</b>。同一份语料在
 * {@code tools/probes/HtmlViewerProbe} 里走一遍真实查看器（GUI 侧证据）。</p>
 */
class HtmlSourceCorpusTest {

    /** 语料主体（行号见各断言）：末尾<b>不带</b>换行，行 8 保留 3 个行尾空格。 */
    static final String BODY =
            "<html>\r\n"                            // 0: CRLF → `<html>`
                    + "  <body>\r"                  // 1: 裸 CR + 2 个行首空格
                    + "\n"                          // 2: 空行
                    + "\n"                          // 3: 空行（连续）
                    + "\n"                          // （行 2/3 是两个连续空行）
                    + "    <p title=\"a\tb\">\n"    // 4: 4 个行首空格 + 属性值内制表符
                    + "<!-- cross\n"                // 5: 跨行注释（首段）
                    + "line comment -->\n"          // 6: 跨行注释（尾段）
                    + "<!-- " + "LONG-".repeat(40) + " -->\n"   // 7: 209 字符超长行
                    + "<div>trailing   ";           // 8: 3 个行尾空格，文件末尾无换行

    /** 归一化 + 分词 → 逐行纯文本（与查看器的 plainLines 同口径）。 */
    private static List<String> plainLines(String raw) {
        List<String> out = new ArrayList<>();
        for (HtmlSyntaxTokens.Line l : HtmlSyntaxTokens.tokenize(HtmlSyntaxTokens.normalizeNewlines(raw))) {
            StringBuilder sb = new StringBuilder();
            for (HtmlSyntaxTokens.Token t : l.tokens()) {
                sb.append(t.text());
            }
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    void trailingNewlineYieldsOneMoreEmptyLine() {
        String normalized = HtmlSyntaxTokens.normalizeNewlines(BODY + "\n");
        List<String> lines = plainLines(BODY + "\n");
        assertEquals(10, lines.size());
        assertEquals(9, HtmlSyntaxTokens.countNewlines(normalized));
        assertEquals(1 + HtmlSyntaxTokens.countNewlines(normalized), lines.size(),
                "lineCount == 1 + 归一化后换行数");
        assertEquals("", lines.get(9), "末尾换行 → 末尾空行保留");
    }

    @Test
    void noTrailingNewlineKeepsExactlyTheText() {
        String normalized = HtmlSyntaxTokens.normalizeNewlines(BODY);
        List<String> lines = plainLines(BODY);
        assertEquals(9, lines.size());
        assertEquals(8, HtmlSyntaxTokens.countNewlines(normalized));
        assertEquals(1 + HtmlSyntaxTokens.countNewlines(normalized), lines.size());
        assertFalse(normalized.contains("\r"), "CRLF/CR 已归一为 LF，不再有 \\r");
    }

    @Test
    void everyLineMatchesCharByChar() {
        assertEquals(List.of(
                "<html>",
                "  <body>",
                "",
                "",
                "    <p title=\"a\tb\">",
                "<!-- cross",
                "line comment -->",
                "<!-- " + "LONG-".repeat(40) + " -->",
                "<div>trailing   ",
                ""), plainLines(BODY + "\n"),
                "逐行 lineText 与语料逐字符一致（含 Tab、行尾空格、空行、末尾空行）");
    }

    @Test
    void emptyLinesTabsAndSpacesSurviveExtract() {
        List<String> lines = plainLines(BODY + "\n");
        // 整篇复制：逐字符 = 逐行 LF 连接（末尾空行 → 结尾带 LF）
        String whole = HtmlTextSelection.extract(lines, new HtmlTextSelection.Range(0, 0, 9, 0));
        assertEquals(String.join("\n", lines), whole);
        assertTrue(whole.endsWith("\n"), "末尾空行保留 → 整篇复制以 LF 结尾");
        assertTrue(whole.contains("\n\n\n"), "连续空行 → 连续 LF，不得合并");
        // 制表符与行尾空格在子串里逐字符保留
        assertEquals("    <p title=\"a\tb\">",
                HtmlTextSelection.extract(lines, new HtmlTextSelection.Range(4, 0, 4, 19)));
        assertEquals("<div>trailing   ",
                HtmlTextSelection.extract(lines, new HtmlTextSelection.Range(8, 0, 8, 16)));
    }

    @Test
    void noTrailingNewlineWholeDocDoesNotEndWithLf() {
        List<String> lines = plainLines(BODY);
        String whole = HtmlTextSelection.extract(lines, new HtmlTextSelection.Range(0, 0, 8, 16));
        assertEquals(String.join("\n", lines), whole);
        assertFalse(whole.endsWith("\n"), "文件末尾无换行 → 整篇复制不以 LF 结尾");
    }
}
