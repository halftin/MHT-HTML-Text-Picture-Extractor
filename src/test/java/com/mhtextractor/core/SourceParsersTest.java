package com.mhtextractor.core;

import com.mhtextractor.util.SourceKinds;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6.3 解析器抽象 + R6.6 输出命名口径的无头测试。
 *
 * <p><b>大小写口径是本步被拍板的重点</b>（提示词 §0）：HTML 组大小写不敏感，而 MHT 组的判定
 * <b>保持原样</b>。⚠️ "原样" = <b>本来就大小写不敏感</b>（{@code app.MhtImportPlanner} 改动前即
 * {@code toLowerCase().endsWith(".mht")}，且 {@code MhtImportPlannerTest} 一直在断言
 * {@code a.MHTML} 命中）—— 所以这里对 {@code .MHT} 的断言是
 * <b>"不变"</b>而不是"为假"。详见类 {@link SourceKinds} 的 javadoc 与交付报告。</p>
 */
class SourceParsersTest {

    // ==================== R6.3：按扩展名选实现 ====================

    @Test
    void htmlExtensionsSelectHtmlFolderParser() {
        for (String p : new String[]{"a.html", "a.htm", "a.HTML", "a.HTM", "a.Html",
                "C:\\dir\\页面.HTM", "/tmp/x.html", "a.b.html"}) {
            assertInstanceOf(HtmlFolderParser.class, SourceParsers.forPath(p),
                    "应选 HTML 解析器：" + p);
        }
    }

    @Test
    void mhtExtensionsSelectMimeParser() {
        // ⚠️ .MHT/.MHTML 命中是**既有行为**（不分大小写），本步不得改动它
        for (String p : new String[]{"a.mht", "a.mhtml", "a.MHT", "a.MHTML", "C:\\dir\\A.Mht"}) {
            assertInstanceOf(MimeParser.class, SourceParsers.forPath(p),
                    "应选 MIME 解析器：" + p);
        }
    }

    @Test
    void unsupportedExtensionThrowsInsteadOfSilentlyParsingAsMht() {
        for (String p : new String[]{"a.txt", "a.png", "noext", "a.html.bak"}) {
            assertThrows(NoSuchElementException.class, () -> SourceParsers.forPath(p),
                    "不支持的扩展名必须显式失败（静默降级成 MHT 解析会产出莫名失败）：" + p);
        }
    }

    @Test
    void isSupportedMatchesImportPredicate() {
        assertTrue(SourceParsers.isSupported("a.html"));
        assertTrue(SourceParsers.isSupported("a.MHTML"));
        assertFalse(SourceParsers.isSupported("a.txt"));
        assertFalse(SourceParsers.isSupported(null));
    }

    // ==================== R6.1 / R6.6：扩展名口径 ====================

    @Test
    void importableCoversHtmlAndMhtGroupsOnly() {
        for (String n : new String[]{"a.html", "a.htm", "a.HTML", "a.HTM",
                "a.mht", "a.mhtml", "a.MHT", "a.MHTML"}) {
            assertTrue(SourceKinds.isImportable(n), "应可导入：" + n);
        }
        for (String n : new String[]{"a.txt", "a.png", "a.htmx", "a.mhtx", "noext", ".html", ""}) {
            assertFalse(SourceKinds.isImportable(n), "不应可导入：" + n);
        }
        assertFalse(SourceKinds.isImportable(null));
    }

    @Test
    void stripKnownExtensionHandlesBoundaries() {
        assertEquals("a", SourceKinds.stripKnownExtension("a.html"));
        assertEquals("a", SourceKinds.stripKnownExtension("a.htm"));
        assertEquals("a", SourceKinds.stripKnownExtension("a.mht"));
        assertEquals("a", SourceKinds.stripKnownExtension("a.mhtml"));
        assertEquals("a", SourceKinds.stripKnownExtension("a.HTML"), "扩展名大小写不敏感");
        assertEquals("a.b", SourceKinds.stripKnownExtension("a.b.html"), "多个点：只剥最后一个已知扩展名");
        assertEquals("noext", SourceKinds.stripKnownExtension("noext"), "无扩展名原样");
        assertEquals(".html", SourceKinds.stripKnownExtension(".html"), "点开头：保守，不当作只有扩展名");
        assertEquals("output.v2", SourceKinds.stripKnownExtension("output.v2"),
                "未知识别扩展名不得被剥掉（否则输出命名会因本步而变）");
        assertEquals("a.mht.html.jpg", SourceKinds.stripKnownExtension("a.mht.html.jpg"),
                "最后一段扩展名不是已知来源扩展名时原样返回（只剥已知后缀）");
        assertEquals(null, SourceKinds.stripKnownExtension(null));
    }

    // ==================== R6.8：解析器不产用户文案 ====================

    @Test
    void parserSourcesDoNotUseI18nGet() throws Exception {
        // 这条是 R6.8 的"主动检查"：即便 [arch] 的 52 兜住总量，也要证明**新文件本身**是干净的。
        // 只允许 log.* 的 I18n.msg（日志随界面语言走是既定约定），不允许 I18n.get（用户文案）。
        for (String cls : new String[]{"SourceParser.java", "SourceParsers.java",
                "HtmlFolderParser.java", "MimeParser.java"}) {
            String code = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src", "main", "java", "com", "mhtextractor", "core", cls),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(code.contains("I18n.get("),
                    "core 解析器不得产用户文案（R6.8）：" + cls);
        }
    }

    @Test
    void supportedExtensionsAreTheSameFourEverywhere() {
        // 选择点与导入识别必须用同一份口径：对 4 个扩展名穷举比对，避免两处分叉
        for (String ext : new String[]{"mht", "mhtml", "html", "htm"}) {
            String name = "x." + ext;
            assertEquals(SourceKinds.isImportable(name), SourceParsers.isSupported(name),
                    "导入识别与解析器选择必须同口径：" + name);
        }
        assertDoesNotThrow(() -> SourceParsers.forPath("x.html"));
    }
}
