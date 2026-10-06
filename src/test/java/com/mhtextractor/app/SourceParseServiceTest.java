package com.mhtextractor.app;

import com.mhtextractor.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceParseService} 无头测试（P3-3b-7）：用真实小 MHT 夹具断言三态（UNPARSEABLE / EMPTY / READY）
 * 与 text / title 抽取，含「开启/关闭命名提取」区分断言（控制组锚点）。
 */
class SourceParseServiceTest {

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

    /** HTML part（quoted-printable 下正文不得含 '=' 字符） */
    private static String htmlPart(String bodyHtml) {
        return "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "\r\n"
                + bodyHtml;
    }

    private static String textPart() {
        return "Content-Type: text/plain; charset=utf-8\r\n\r\nplain only";
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static AppConfig textTitleParams() {
        AppConfig params = new AppConfig();
        params.setEnableTitleExtraction(true);
        params.setTitleStartMarker("<h1>");
        params.setTitleEndMarker("</h1>");
        return params;
    }

    private static AppConfig imageNameParams(boolean enabled) {
        AppConfig params = new AppConfig();
        params.setEnableNameExtraction(enabled);
        params.setImageNameStartMarker("<h1>");
        params.setImageNameEndMarker("</h1>");
        return params;
    }

    @Test
    void unparseableWhenMhtHasNoHtmlPart() throws Exception {
        Path mht = write("nohtml-" + System.nanoTime() + ".mht", mht(textPart()));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), new AppConfig(), true);
        assertEquals(SourceParseService.Kind.UNPARSEABLE, r.kind());
        assertNull(r.text());
        assertNull(r.title());
    }

    @Test
    void textModeReadyWithBodyText() throws Exception {
        Path mht = write("ok-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><p>hello world</p></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), new AppConfig(), true);
        assertEquals(SourceParseService.Kind.READY, r.kind());
        assertTrue(r.text() != null && r.text().contains("hello world"), "正文应含夹具文本：" + r.text());
    }

    @Test
    void textModeEmptyWhenBodyHasNoText() throws Exception {
        Path mht = write("empty-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), new AppConfig(), true);
        assertEquals(SourceParseService.Kind.EMPTY, r.kind());
    }

    @Test
    void textModeTitleExtractedWhenEnabled() throws Exception {
        Path mht = write("title-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><h1>My Title</h1><p>hello</p></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), textTitleParams(), true);
        assertEquals("My Title", r.title());
        assertTrue(r.titleAssigned());
    }

    @Test
    void textModeTitleNullWhenExtractionDisabled() throws Exception {
        Path mht = write("notitle-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><h1>My Title</h1><p>hello</p></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), new AppConfig(), true);
        assertNull(r.title());
    }

    @Test
    void imageModeNameExtractionOffYieldsNullTitle() throws Exception {
        // 控制组锚点：临时把 image 分支的 enableNameExtraction 判定去掉（恒抽取标题）时本断言必须跑红
        Path mht = write("img-off-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><h1>My Title</h1><p>hello</p></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), imageNameParams(false), false);
        assertEquals(SourceParseService.Kind.READY, r.kind());
        assertNull(r.text(), "image 模式不产正文");
        assertNull(r.title(), "关闭命名提取时标题必须为 null");
    }

    @Test
    void imageModeNameExtractionOnYieldsTitle() throws Exception {
        Path mht = write("img-on-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><h1>My Title</h1><p>hello</p></body></html>")));
        SourceParseService.ItemParse r = SourceParseService.parse(mht.toString(), imageNameParams(true), false);
        assertEquals(SourceParseService.Kind.READY, r.kind());
        assertEquals("My Title", r.title());
        assertTrue(r.titleAssigned());
    }

    @Test
    void titleAssignedFalseWhenExtractionDisabled() throws Exception {
        // 钉住「titleAssigned=false 时 title 为 null 且 gui 不应写 cachedTitle」（gui 侧按 titleAssigned 守卫；
        // 控制组把 titleAssigned 恒置 true 时本断言必须跑红）
        Path mht = write("noassign-" + System.nanoTime() + ".mht",
                mht(htmlPart("<html><body><h1>My Title</h1><p>hello</p></body></html>")));
        SourceParseService.ItemParse text = SourceParseService.parse(mht.toString(), new AppConfig(), true);
        assertNull(text.title());
        assertFalse(text.titleAssigned(), "text 模式关闭标题提取时不得写 cachedTitle");
        SourceParseService.ItemParse image = SourceParseService.parse(mht.toString(), imageNameParams(false), false);
        assertNull(image.title());
        assertFalse(image.titleAssigned(), "image 模式关闭命名提取时不得写 cachedTitle");
    }

    @Test
    void missingFilePropagatesIOException() {
        // 解析本身的 IOException 不在此处吞并，由 gui 外层 catch (Exception) 映射为「解析失败」（与原来一致）
        assertThrows(IOException.class,
                () -> SourceParseService.parse(tmp.resolve("nope-" + System.nanoTime() + ".mht").toString(),
                        new AppConfig(), true));
    }
}
