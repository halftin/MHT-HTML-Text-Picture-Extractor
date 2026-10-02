package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlSyntaxPalette}（Q8b 抽出的"配色唯一来源"）的单元测试。
 *
 * <p><b>它在保护什么</b>：{@code SourceViewPage} 的 {@code <style>} 现在由本类生成，
 * 而那段 CSS 是 Q8a 的<b>逐字节</b>契约（63 个语料用例）。这里把生成结果与迁移前
 * 逐字写在 {@code writeSourceViewPage} 里的那个字符串<b>并排钉死</b>：任何人重排规则、
 * 改动空格或把 {@code font-style:italic} 拆行，都会先在这里红掉，而不是等字节级装置报
 * "19584 个像素不同"这种事后信号。</p>
 */
class HtmlSyntaxPaletteTest {

    /** 迁移前 {@code SourceViewPage.writeSourceViewPage} 里那三行字面量，逐字抄在测试里当基准。 */
    private static final String BEFORE =
            ".hl-tag{color:#0000ff;}.hl-attr{color:#a31515;}.hl-val{color:#008000;}"
                    + ".hl-cm{color:#808080;font-style:italic;}.hl-dt{color:#808080;}"
                    + ".hl-link{color:#0000ee;text-decoration:underline;}";

    @Test
    void styleBlockIsByteIdenticalToTheInlineLiteralItReplaced() {
        assertEquals(BEFORE, HtmlSyntaxPalette.styleBlock());
    }

    @Test
    void everyKindWithACssClassIsCoveredByAPaletteEntry() {
        for (HtmlSyntaxTokens.Kind kind : HtmlSyntaxTokens.Kind.values()) {
            String cssClass = kind.cssClass();
            if (cssClass == null) {
                assertNull(HtmlSyntaxPalette.of(kind), "TEXT 无 CSS 类，不应有配色");
                continue;
            }
            // VAL_LINK 的类名是 "hl-val hl-link" 两个类（页面上叠加），其余是一对一
            assertTrue(HtmlSyntaxPalette.of(kind) != null,
                    "类别 " + kind + " 有 CSS 类 " + cssClass + " 却没有配色");
        }
    }

    @Test
    void compositeCssClassResolvesToTheLinkRule() {
        // 页面上 .hl-val 先写、.hl-link 后写 ⇒ 后写的赢；应用内查看器必须给出同一个结果
        assertEquals(HtmlSyntaxPalette.LINK,
                HtmlSyntaxPalette.of(HtmlSyntaxTokens.Kind.VAL_LINK));
        assertEquals(HtmlSyntaxPalette.VALUE,
                HtmlSyntaxPalette.of(HtmlSyntaxTokens.Kind.VAL));
        assertTrue(HtmlSyntaxPalette.of(HtmlSyntaxTokens.Kind.VAL_LINK).underline());
        assertTrue(HtmlSyntaxPalette.of(HtmlSyntaxTokens.Kind.COMMENT).italic());
    }

    @Test
    void cssClassNamesMatchTheTokenModel() {
        assertEquals("hl-tag", HtmlSyntaxPalette.TAG.cssClass());
        assertEquals(HtmlSyntaxPalette.ATTR,
                HtmlSyntaxPalette.ofCssClass(HtmlSyntaxTokens.Kind.ATTR.cssClass()));
        assertNull(HtmlSyntaxPalette.ofCssClass("hl-nope"));
    }
}
