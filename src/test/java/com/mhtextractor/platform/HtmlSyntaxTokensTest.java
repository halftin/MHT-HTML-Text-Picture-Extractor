package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlSyntaxTokens}（Q8a 从 {@code SourceViewPage} 抽出的高亮内核）与
 * {@link SourceViewPage} 渲染器的单元测试。
 *
 * <p><b>与"逐字节判据"的分工</b>：{@code SourceViewPage} 的输出<b>逐字节不变</b>由 {@code .claude-work}
 * 的字节级装置在 53 个语料用例上验证（那是验收方的装置）。本测试类面对的是<b>语义</b>：
 * token 的类别与原文是否对得上、行结构（跨行 token 切行）是否正确、行号栏宽度是否随总行数变、
 * 转义与 URL 尾随标点的纯函数行为是否与迁移前一致 —— 这些是"逐字节相等"证明不了的东西
 * （例如"两个类名被拆成两个 span"仍可能凑出别的字节）。</p>
 *
 * <p>⚠️ 本类<b>刻意不复制</b>字节级比对装置：同一判据的第二份实现会成为下一个分叉点（R8 要消灭的正是这个）。</p>
 */
class HtmlSyntaxTokensTest {

    // ==================== token 模型 ====================

    @Test
    void tokenizesTagNameAndBareAttribute() {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize("<input disabled>");

        assertEquals(1, lines.size());
        List<HtmlSyntaxTokens.Token> tokens = lines.get(0).tokens();
        assertEquals(List.of(
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "<input"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, " "),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, "disabled"),
                // ⚠️ 零长切片：迁移前照样写一对空 span，token 模型必须如实表达（丢掉就不再逐字节相同）
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, ""),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, ">")), tokens);
    }

    @Test
    void attributeValueSplitsIntoAttrEqualsWhitespaceAndValue() {
        List<HtmlSyntaxTokens.Token> tokens =
                HtmlSyntaxTokens.tokenize("<a title = 'v'>x</a>").get(0).tokens();

        assertEquals(List.of(
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "<a"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, " "),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, "title"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, " "),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, "="),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.ATTR, " "),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.VAL, "'v'"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, ">"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TEXT, "x"),
                // ⚠️ 结束标签的 ">" 是单独一段："<" 后的名字扫描止于 ">"，再走空白/斜杠/尖括号连续段
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "</a"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, ">")), tokens);
    }

    @Test
    void urlAttributeValueUsesSingleValLinkKind() {
        List<HtmlSyntaxTokens.Token> tokens =
                HtmlSyntaxTokens.tokenize("<a href=\"https://example.com/x\">q</a>").get(0).tokens();

        assertTrue(tokens.contains(
                        new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.VAL_LINK, "\"https://example.com/x\"")),
                "URL 属性值应是 VAL_LINK（一个 class 属性里两个类名），实际：" + tokens);
        assertEquals("hl-val hl-link", HtmlSyntaxTokens.Kind.VAL_LINK.cssClass());
        assertEquals("hl-val", HtmlSyntaxTokens.Kind.VAL.cssClass());
        assertNull(HtmlSyntaxTokens.Kind.TEXT.cssClass(), "正文文本不包 span");
    }

    @Test
    void commentAndDoctypeKindsDoNotSwallowFollowingMarkup() {
        List<HtmlSyntaxTokens.Token> tokens =
                HtmlSyntaxTokens.tokenize("<!-- c --><!DOCTYPE html><p>t</p>").get(0).tokens();

        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.COMMENT, "<!-- c -->"), tokens.get(0));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.DOCTYPE, "<!DOCTYPE html>"), tokens.get(1));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "<p"), tokens.get(2));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, ">"), tokens.get(3));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TEXT, "t"), tokens.get(4));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "</p"), tokens.get(5));
        assertEquals(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, ">"), tokens.get(6));
    }

    @Test
    void doctypeDetectionIsCaseInsensitive() {
        for (String decl : List.of("<!doctype html>", "<!DoCtYpE html>", "<!DOCTYPE html>")) {
            List<HtmlSyntaxTokens.Token> tokens = HtmlSyntaxTokens.tokenize(decl).get(0).tokens();
            assertEquals(1, tokens.size(), "整个声明应是一段 DOCTYPE，实际：" + tokens);
            assertEquals(HtmlSyntaxTokens.Kind.DOCTYPE, tokens.get(0).kind(), decl);
        }
    }

    @Test
    void unclosedCommentTagAndDoctypeRunToEndOfInput() {
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.COMMENT, "<!-- x <p>y")),
                HtmlSyntaxTokens.tokenize("<!-- x <p>y").get(0).tokens());
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.DOCTYPE, "<!doctype html")),
                HtmlSyntaxTokens.tokenize("<!doctype html").get(0).tokens());
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TAG, "<a href=\">")),
                HtmlSyntaxTokens.tokenize("<a href=\">").get(0).tokens());
    }

    // ==================== 行结构 ====================

    @Test
    void everyTokenIsConfinedToOneLineAndLineCountFollowsNewlines() {
        for (String html : List.of("", "\n", "\n\n", "a\nb", "<a\nhref=\"x\">y</a>", "<!-- a\nb -->x",
                "<div class=\"q\n\">", "a\r\nb", "a\rb", "<p>a</p>\n<p>b</p>", "trailing\n")) {
            List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(html);
            String normalized = HtmlSyntaxTokens.normalizeNewlines(html);
            assertEquals(1 + HtmlSyntaxTokens.countNewlines(normalized), lines.size(),
                    "行数必须是「归一化后换行数 + 1」，输入：" + normalized);
            for (HtmlSyntaxTokens.Line line : lines) {
                for (HtmlSyntaxTokens.Token t : line.tokens()) {
                    assertFalse(t.text().contains("\n"), "token 不得含换行，实际：" + t + "（输入：" + normalized + "）");
                }
            }
        }
    }

    @Test
    void linesRebuildTheExactUnescapedSource() {
        // token 文本 + 「行边界即换行」必须还原归一化后的原文（转义由渲染器负责，token 持未转义原文）
        for (String html : List.of("<a href=\"x?a=1&b=2\">q &amp; r</a>", "<!-- a\nb -->", "<>", "a\r\nb",
                "a\n\nb", "trailing\n", "", "\r\r")) {
            assertEquals(HtmlSyntaxTokens.normalizeNewlines(html), rebuild(html), "token 拼接应还原原文：" + html);
        }
    }

    @Test
    void multiLineTokenIsSplitAtLineBoundaries() {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize("<!-- one\ntwo -->");

        assertEquals(2, lines.size());
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.COMMENT, "<!-- one")),
                lines.get(0).tokens());
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.COMMENT, "two -->")),
                lines.get(1).tokens());
    }

    @Test
    void emptyLineKeepsTheCarriedOverEmptySegment() {
        // "a\n\nb" → 3 行。⚠️ 中间那行**不是**空 token 列表：迁移前换行后的余段（空串）留在行缓冲里，
        // 下一次 flush 才提交 —— 与"空的 hl-attr span"同源，都是零长切片必须如实保留的证据。
        // 不断言具体成分（那属于内部细节）：只断言"行内容 + 行边界 = 原文"这条不变量。
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize("a\n\nb");

        assertEquals(3, lines.size());
        assertEquals("a\n\nb", rebuild("a\n\nb"), "行内容 + 行边界应还原原文（含空行）");
        assertEquals(List.of(new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TEXT, "b")), lines.get(2).tokens());
    }

    @Test
    void textUrlBecomesLinkTokenAndTrailingPunctuationStaysText() {
        List<HtmlSyntaxTokens.Token> tokens =
                HtmlSyntaxTokens.tokenize("go to https://example.com/x. now").get(0).tokens();

        assertEquals(List.of(
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TEXT, "go to "),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.LINK, "https://example.com/x"),
                new HtmlSyntaxTokens.Token(HtmlSyntaxTokens.Kind.TEXT, ". now")), tokens);
    }

    // ==================== 纯函数（必须与迁移前逐字等价） ====================

    @Test
    void escapeHtmlReplacesAmpersandFirst() {
        // ⚠️ & 必须先替换。控制组（本次实测）：把顺序换成先 "<" 再 "&" 后，字节级装置
        // 在 53 个语料里有 42 个用例变成 "&amp;lt;"（`&lt;a` 而非 `&lt;a`）—— 这就是这条规则的证明。
        assertEquals("&lt;a", HtmlSyntaxTokens.escapeHtml("<a"), "先换 < 再换 & 会得到 &amp;lt;a");
        // 输入里本来就有裸 "&" 时，"&" 规则照常生效（不幂等是迁移前的既有行为，原样保留）
        assertEquals("&amp;lt;", HtmlSyntaxTokens.escapeHtml("&lt;"));
        assertEquals("&amp;amp;", HtmlSyntaxTokens.escapeHtml("&amp;"));
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot;", HtmlSyntaxTokens.escapeHtml("a & <b> \"c\""));
        assertEquals("", HtmlSyntaxTokens.escapeHtml(""));
    }

    @Test
    void trimUrlStripsTrailingSentencePunctuation() {
        assertEquals("https://e.com/x", HtmlSyntaxTokens.trimUrl("https://e.com/x."));
        assertEquals("https://e.com/x", HtmlSyntaxTokens.trimUrl("https://e.com/x),;:!?]}"));
        assertEquals("https://e.com/x", HtmlSyntaxTokens.trimUrl("https://e.com/x"));
        assertEquals("", HtmlSyntaxTokens.trimUrl("..."));
        // ⚠️ 尾随 ")" 会被剥掉 —— 即便它是配对的括号（迁移前就是这个行为，原样保留）
        assertEquals("https://e.com/a(b", HtmlSyntaxTokens.trimUrl("https://e.com/a(b)"));
        assertEquals("https://e.com/a(b", HtmlSyntaxTokens.trimUrl("https://e.com/a(b),"));
    }

    @Test
    void isUrlValueMatchesWholeStringOnly() {
        assertTrue(HtmlSyntaxTokens.isUrlValue("\"https://e.com/x\""));
        assertTrue(HtmlSyntaxTokens.isUrlValue("'http://e.com'"));
        assertTrue(HtmlSyntaxTokens.isUrlValue("https://e.com/x"));
        assertTrue(HtmlSyntaxTokens.isUrlValue("\"https://e.com/x.\""), "trim 后再整串匹配");
        assertFalse(HtmlSyntaxTokens.isUrlValue("\"see https://e.com\""), "前后有词就不是整串 URL");
        assertFalse(HtmlSyntaxTokens.isUrlValue("\"\""), "空值不是 URL");
        assertFalse(HtmlSyntaxTokens.isUrlValue("\"ftp://e.com\""), "只认 http(s)");
        assertFalse(HtmlSyntaxTokens.isUrlValue("\"https://e.com/a b\""), "URL 内空白即不匹配");
    }

    @Test
    void isNameCharCoversLettersDigitsDashColonUnderscore() {
        assertTrue(HtmlSyntaxTokens.isNameChar('a'));
        assertTrue(HtmlSyntaxTokens.isNameChar('Z'));
        assertTrue(HtmlSyntaxTokens.isNameChar('7'));
        assertTrue(HtmlSyntaxTokens.isNameChar('-'));
        assertTrue(HtmlSyntaxTokens.isNameChar(':'));
        assertTrue(HtmlSyntaxTokens.isNameChar('_'));
        assertFalse(HtmlSyntaxTokens.isNameChar(' '));
        assertFalse(HtmlSyntaxTokens.isNameChar('='));
        assertFalse(HtmlSyntaxTokens.isNameChar('/'));
        assertFalse(HtmlSyntaxTokens.isNameChar('>'));
    }

    // ==================== 渲染器（行模板 / 行号栏宽度） ====================

    @Test
    void rendererEmitsExactRowTemplateWithGutterWidthFromTotalLines() {
        assertEquals("<div class=\"row\"><span class=\"ln\" style=\"width:2em\">1</span>"
                        + "<span class=\"lc\"><span class=\"hl-tag\">&lt;a</span>"
                        + "<span class=\"hl-tag\">&gt;</span></span></div>",
                SourceViewPage.buildSourceViewBody("<a>"));
    }

    @Test
    void gutterWidthGrowsWithTotalLineDigits() {
        // gutterDigits = String.valueOf(totalLines).length()，宽度 = gutterDigits + 1 —— 边界在 10/100 行
        assertEquals("2em", gutterWidth(SourceViewPage.buildSourceViewBody(repeatLines(9))));
        assertEquals("3em", gutterWidth(SourceViewPage.buildSourceViewBody(repeatLines(99))));
        assertEquals("4em", gutterWidth(SourceViewPage.buildSourceViewBody(repeatLines(100))));
        assertEquals("2em", gutterWidth(SourceViewPage.buildSourceViewBody("")), "空串 = 1 行 = 2em");
    }

    @Test
    void rendererClosesAndReopensSpanAtLineBoundary() {
        // 跨行标签：第 1 行先闭合，第 2 行起始重开（顺序不能变）
        String body = SourceViewPage.buildSourceViewBody("<!-- a\nb -->");

        assertEquals(2, body.split("<div class=\"row\">", -1).length - 1);
        assertTrue(body.contains("<span class=\"lc\"><span class=\"hl-cm\">&lt;!-- a</span></span></div>"),
                "第 1 行应在行尾闭合，实际：" + body);
        assertTrue(body.contains("<span class=\"lc\"><span class=\"hl-cm\">b --&gt;</span></span></div>"),
                "第 2 行应重开 span，实际：" + body);
    }

    @Test
    void rendererKeepsEmptySpanForZeroLengthToken() {
        assertEquals("<div class=\"row\"><span class=\"ln\" style=\"width:2em\">1</span>"
                        + "<span class=\"lc\"><span class=\"hl-tag\">&lt;</span>"
                        + "<span class=\"hl-tag\">&gt;</span></span></div>",
                SourceViewPage.buildSourceViewBody("<>"));
    }

    private static String repeatLines(int totalLines) {
        return "x\n".repeat(totalLines - 1) + "x";
    }

    /** 行内容按顺序拼接，行与行之间补回换行 —— 应还原归一化后的原文 */
    private static String rebuild(String html) {
        List<HtmlSyntaxTokens.Line> lines = HtmlSyntaxTokens.tokenize(html);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            for (HtmlSyntaxTokens.Token t : lines.get(i).tokens()) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String gutterWidth(String body) {
        String head = "<div class=\"row\"><span class=\"ln\" style=\"width:";
        int from = body.indexOf(head);
        assertTrue(from >= 0, "首行缺少行号栏模板，实际：" + body);
        int start = from + head.length();
        int em = body.indexOf("em\"", start);
        return body.substring(start, em) + "em";
    }
}
