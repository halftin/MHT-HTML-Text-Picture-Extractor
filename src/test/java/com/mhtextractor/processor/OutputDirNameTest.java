package com.mhtextractor.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A1：{@link BatchProcessor#getOutputDirName} 口径收敛 —— 与
 * {@code SourceKinds.stripKnownExtension} 等价。
 * 修复前本方法用 {@code replaceAll("\.(?i)(mht|mhtml)$")}，HTML 来源的
 * {@code .html}/{@code .htm} 会残留在输出名里（如文本单独输出写成 page.html.txt）。
 */
class OutputDirNameTest {

    /** getOutputDirName 是 protected：经最小子类暴露给测试（不改生产可见性） */
    private static final class Exposed extends BatchProcessor {
        String nameOf(String fileName) {
            return getOutputDirName(fileName);
        }
    }

    private final Exposed exposed = new Exposed();

    @Test
    void knownExtensionsAreStripped() {
        assertEquals("page", exposed.nameOf("page.html"), "A1：修复前为 page.html");
        assertEquals("page", exposed.nameOf("page.htm"), "A1：修复前为 page.htm");
        assertEquals("page", exposed.nameOf("page.mht"), "MHT 既有行为不变");
        assertEquals("page", exposed.nameOf("page.mhtml"), "MHT 既有行为不变");
    }

    @Test
    void caseInsensitiveAndCompoundNames() {
        assertEquals("PAGE", exposed.nameOf("PAGE.HTML"));
        assertEquals("a.b", exposed.nameOf("a.b.html"));
    }

    @Test
    void unknownExtensionHiddenAndNullAreUntouched() {
        assertEquals("output.v2", exposed.nameOf("output.v2"), "未知扩展名不剥（三层分离）");
        assertEquals(".html", exposed.nameOf(".html"), "隐藏文件原样返回");
        assertNull(exposed.nameOf(null));
    }
}
