package com.mhtextractor.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceViewService} 无头测试（P3-3b-4）：用真实小 MHT 夹具断言三种结果分类
 * 与 Success 的 htmlFile 真实存在（缓存命中/内容正确性由 {@code HtmlExportBuilderTest} 兜底）。
 */
class SourceViewServiceTest {

    @TempDir
    Path tmp;

    /** 标准消息头 + multipart/related 包装（与 {@code MimeParserTest.wrapRelated} 同形的最小版） */
    private static String mht(String... bodyParts) {
        StringBuilder sb = new StringBuilder()
                .append("From: <sender@example.com>\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("Content-Type: multipart/related; boundary=\"XYZ\"\r\n")
                .append("\r\n");
        for (String p : bodyParts) {
            sb.append("--XYZ\r\n").append(p).append("\r\n");
        }
        sb.append("--XYZ--\r\n");
        return sb.toString();
    }

    private static String htmlPart() {
        // Content-Transfer-Encoding 必须给（MimeParser.extractHtmlFromPart 用 MimeUtility.decode 按 CTE 解码，
        // 缺省回落 "utf-8" 会被 jakarta mail 拒掉 —— 与 MimeParserTest.htmlPart 同形）
        return "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "\r\n"
                + "<html><body>hello</body></html>";
    }

    private static String textPart() {
        return "Content-Type: text/plain; charset=utf-8\r\n\r\nplain only";
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void successForMhtWithHtmlPart() throws Exception {
        Path mht = write("ok-" + System.nanoTime() + ".mht", mht(htmlPart()));
        SourceViewService.Export e = SourceViewService.export(mht.toString());
        assertInstanceOf(SourceViewService.Export.Success.class, e);
        SourceViewService.Export.Success s = (SourceViewService.Export.Success) e;
        assertTrue(Files.exists(s.htmlFile()), "导出的 html 文件必须真实存在：" + s.htmlFile());
    }

    @Test
    void unparseableForMhtWithoutHtmlPart() throws Exception {
        Path mht = write("nohtml-" + System.nanoTime() + ".mht", mht(textPart()));
        // 控制组锚点：临时把 Unparseable 分支改成 Failed("x") 时本断言必须跑红（能区分三态）
        assertInstanceOf(SourceViewService.Export.Unparseable.class, SourceViewService.export(mht.toString()));
    }
}
