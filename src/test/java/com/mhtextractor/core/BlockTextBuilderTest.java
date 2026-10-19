package com.mhtextractor.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockTextBuilderTest {
    private String extract(String html) {
        return new ContentExtractor().extractText(html, false, null, null, false);
    }

    @Test void adjacentAndNestedBlocksAndInlineWhitespace() {
        assertEquals("一\n\nA B & C\n\nD\nE\n\nF\n\nG",
                extract("<div><p>一</p><p>A <b> B</b> &amp; C</p><div>D<br>E</div></div><li>F</li><section>G</section>"));
        assertEquals("A B", extract("<p>　A　&nbsp; B　</p>"));
    }

    @Test void allDeclaredBlocksAndDiscardedElements() {
        for (String tag : new String[]{"p","div","li","h1","h2","h3","h4","h5","h6","blockquote","section","article"})
            assertEquals("A\n\nB", extract("<"+tag+">A</"+tag+"><"+tag+">B</"+tag+">"), tag);
        assertEquals("A\n\nB", extract("<table><tr><td>A</td></tr><tr><td>B</td></tr></table>"));
        assertEquals("safe", extract("<head><title>discard</title><meta name=description content=discard></head><body><p>safe</p></body>"));
        for (String tag : new String[]{"script","style","noscript","nav","aside","footer","form","button","iframe","svg"})
            assertEquals("safe", extract("<"+tag+">discard</"+tag+"><p>safe</p>"), tag);
    }

    @Test void brAndPrePreserveTheirDifferentWhitespaceSemantics() {
        assertEquals("A\nB\n\n  x\n    y  \n\nz", extract("<p> A <br> B </p><pre>  x\n    y  </pre><p>z</p>"));
        assertEquals("A\nB", extract("<p>A<br>\n B</p>"));
    }

    @Test void containerSelectionAndConservativeDecorationTable() {
        assertEquals("一\n\nbody", extract("<div>outside</div><div id=content><p>一</p><p>body</p></div><p>outside</p>"));
        for (String notice : new String[]{"网址：www.example.test", "记住本站", "本章节未完", "字体设置", "Copyright 2026",
                "声明", "上一章", "下一章", "返回目录", "翻页"})
            assertEquals("body", extract("<p>"+notice+"</p><p>body</p>"), notice);
        String longNarrative = "他说起了声明和网址，但这是一段必须保留的正文。".repeat(4);
        assertEquals(longNarrative, extract("<p>"+longNarrative+"</p>"));
        assertEquals("body", extract("<div id=content><p>我们马上记住本站网址,www.example.test,若被浏/览/器/转/码,可退出转/码继续阅读,感谢支持.</p><p>body</p></div>"));
        assertEquals("正文里谈到本站网址的故事", extract("<div id=content><p>正文里谈到本站网址的故事</p></div>"));
    }

    @Test void regionRestrictionPrecedesContainerSelection() {
        assertEquals("chosen\n\n一", new ContentExtractor().extractText(
                "<article>outside</article><!--S--><p>chosen</p><p>一</p><!--E-->", false, "<!--S-->", "<!--E-->", false));
    }
}
