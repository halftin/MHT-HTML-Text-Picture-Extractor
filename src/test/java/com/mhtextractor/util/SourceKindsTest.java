package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1：{@link SourceKinds} 纯函数单测 —— 来源扩展名规则唯一入口的锁行为。
 * 覆盖：4 种扩展名、大小写、复合名（a.b.html）、隐藏文件、无扩展名、未知扩展名（output.v2）。
 */
class SourceKindsTest {

    @Test
    void fourKnownExtensionsAreImportableAndStripped() {
        for (String name : List.of("page.html", "page.htm", "page.mht", "page.mhtml")) {
            assertTrue(SourceKinds.isImportable(name), name);
            assertEquals("page", SourceKinds.stripKnownExtension(name), name);
        }
    }

    @Test
    void allFourExtensionsAreCaseInsensitive() {
        for (String name : List.of("PAGE.HTML", "PAGE.HTM", "PAGE.MHT", "Page.MhTml", "a.Html")) {
            assertTrue(SourceKinds.isImportable(name), name);
            assertEquals(name.substring(0, name.lastIndexOf('.')), SourceKinds.stripKnownExtension(name), name);
        }
    }

    @Test
    void compoundNameStripsOnlyTheKnownExtension() {
        assertTrue(SourceKinds.isImportable("a.b.html"));
        assertEquals("a.b", SourceKinds.stripKnownExtension("a.b.html"), "复合名只剥最后一段已知扩展名");
    }

    @Test
    void hiddenDotFilesAreNotSources() {
        for (String name : List.of(".html", ".htm", ".mht")) {
            assertFalse(SourceKinds.isImportable(name), "隐藏文件不算来源：" + name);
            assertEquals(name, SourceKinds.stripKnownExtension(name), "隐藏文件原样返回：" + name);
        }
    }

    @Test
    void noExtensionIsNotASourceAndStaysUnchanged() {
        assertFalse(SourceKinds.isImportable("page"));
        assertEquals("page", SourceKinds.stripKnownExtension("page"));
    }

    @Test
    void unknownExtensionIsNotStripped() {
        assertFalse(SourceKinds.isImportable("output.v2"));
        assertEquals("output.v2", SourceKinds.stripKnownExtension("output.v2"),
                "未知扩展名不剥（三层分离：通用剥离会把 output.v2 变成 output）");
    }

    @Test
    void trailingDotEmptyAndNullAreSafe() {
        assertFalse(SourceKinds.isImportable("page."));
        assertEquals("page.", SourceKinds.stripKnownExtension("page."));
        assertFalse(SourceKinds.isImportable(""));
        assertEquals("", SourceKinds.stripKnownExtension(""));
        assertFalse(SourceKinds.isImportable(null));
        assertNull(SourceKinds.stripKnownExtension(null));
    }

    @Test
    void htmlExtensionsIsTheSingleExposedLiteralSet() {
        assertEquals(List.of(".html", ".htm"), SourceKinds.htmlExtensions());
        assertThrows(UnsupportedOperationException.class,
                () -> SourceKinds.htmlExtensions().add(".xhtml"), "返回集合必须不可变");
    }

    // ==================== 侧车目录后缀（E3.1：三份 SIDECAR_SUFFIX 常量收敛进本类） ====================

    @Test
    void sidecarSuffixIsTheSingleLiteral() {
        assertEquals("_files", SourceKinds.sidecarSuffix());
    }

    @Test
    void sidecarDirNameIsBasePlusSuffix() {
        assertEquals("page_files", SourceKinds.sidecarDirName("page"));
        assertEquals("x_files", SourceKinds.sidecarDirName("x"));
        assertEquals("_files", SourceKinds.sidecarDirName(""));
        assertEquals("a.b_files", SourceKinds.sidecarDirName("a.b"));
    }

    @Test
    void isSidecarDirNameIsCaseInsensitiveAndRequiresABase() {
        assertTrue(SourceKinds.isSidecarDirName("page_files"));
        assertTrue(SourceKinds.isSidecarDirName("PAGE_FILES"));
        assertTrue(SourceKinds.isSidecarDirName("Page_Files"));
        assertTrue(SourceKinds.isSidecarDirName("x_files"), "单字符基名也成立");
        assertFalse(SourceKinds.isSidecarDirName("_files"), "_files 本身没有基名，不算侧车目录名");
        assertFalse(SourceKinds.isSidecarDirName("_FILES"));
        assertFalse(SourceKinds.isSidecarDirName("page_files "), "尾部空格不算（endsWith 判据）");
        assertFalse(SourceKinds.isSidecarDirName("page_filesx"));
        assertFalse(SourceKinds.isSidecarDirName("files"));
        assertFalse(SourceKinds.isSidecarDirName("_file"), "长度等于后缀也不行");
        assertFalse(SourceKinds.isSidecarDirName(""));
        assertFalse(SourceKinds.isSidecarDirName(null));
    }
}
