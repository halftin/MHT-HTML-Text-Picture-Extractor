package com.mhtextractor.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7/8/9 解析层缺陷回归测试：
 * - 深层嵌套 MIME 不再抛 StackOverflowError，超限子树被截断（深度限制与回调无关）
 * - filename="a.png"; size=1234 的 filename 取值不含后续参数尾巴
 * - RFC 2047 编码的中文文件名被正确解码
 * - 细粒度进度回调单调不减
 */
class MimeParserTest {

    @TempDir
    Path tmp;

    // ==================== 辅助 ====================

    /** 写临时 MHT 文件并返回路径 */
    private String writeMht(String content) throws Exception {
        Path file = tmp.resolve("mime-parser-test-" + System.nanoTime() + ".mht");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }

    /** 标准消息头 + multipart/related 包装 */
    private static String wrapRelated(String... bodyParts) {
        StringBuilder sb = new StringBuilder()
                .append("From: <sender@example.com>\r\n")
                .append("To: <receiver@example.com>\r\n")
                .append("Subject: test\r\n")
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
        return "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "\r\n"
                + "<html><body><img src=\"cid:img1\"></body></html>";
    }

    private static String imagePart(String dispositionHeader) {
        return "Content-Type: image/png\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "Content-ID: <img1>\r\n"
                + dispositionHeader + "\r\n"
                + "\r\n"
                + "iVBORw0KGgo=";
    }

    /** 构造 levels 层嵌套 multipart 的 MHT，最内层是 text/html（深度 = levels + 1） */
    private static String deepNestedMht(int levels) {
        StringBuilder body = new StringBuilder(
                "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body>deep</body></html>");
        for (int i = levels - 1; i >= 0; i--) {
            String boundary = "b" + i;
            body = new StringBuilder()
                    .append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n")
                    .append("\r\n")
                    .append("--").append(boundary).append("\r\n")
                    .append(body)
                    .append("\r\n--").append(boundary).append("--");
        }
        return "From: <a@b.c>\r\n"
                + "To: <d@e.f>\r\n"
                + "Subject: deep\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n" + body + "\r\n--outer--\r\n";
    }

    // ==================== P0-7：递归深度保护 ====================

    @Test
    void deepNestingDoesNotThrowErrorAndTruncatesBeyondLimit() throws Exception {
        String path = writeMht(deepNestedMht(300));

        MimeParser.ParsedMht result = assertDoesNotThrow(
                () -> new MimeParser().parse(path, false, null, null, null),
                "畸形深层嵌套 .mht 不得抛出 Error/Exception 穿透 parse()");

        assertNotNull(result);
        assertNull(result.getHtmlContent(), "超过 RECURSION_LIMIT 深度的子树应被截断，不提取其 HTML");
        assertTrue(result.getImageInfoList().isEmpty());
    }

    @Test
    void deepNestingTruncatesRegardlessOfContinueCallback() throws Exception {
        String path = writeMht(deepNestedMht(300));

        // 回调返回 true（用户选"继续"）：修复前会移除深度检查继续下潜，修复后仍须截断
        MimeParser.ParsedMht continued = assertDoesNotThrow(
                () -> new MimeParser().parse(path, false, () -> true, null, null));
        assertNotNull(continued);
        assertNull(continued.getHtmlContent(), "即使回调返回 true，超限子树也必须截断");

        // 回调返回 false（用户选"取消"）：终止解析，不抛 Error
        MimeParser.ParsedMht cancelled = assertDoesNotThrow(
                () -> new MimeParser().parse(path, false, () -> false, null, null));
        assertNotNull(cancelled);
        assertNull(cancelled.getHtmlContent());
    }

    // ==================== P0-8：文件名解析 ====================

    @Test
    void filenameWithTrailingParametersIsParsedCorrectly() throws Exception {
        String path = writeMht(wrapRelated(htmlPart(),
                imagePart("Content-Disposition: attachment; filename=\"a.png\"; size=1234")));
        MimeParser.ParsedMht result = new MimeParser().parse(path, false, null, null, null);

        assertEquals(1, result.getImageInfoList().size());
        assertEquals("a.png", result.getImageInfoList().get(0).getFilename(),
                "filename 值不应包含后续参数（如 \"; size=1234\"）");
    }

    @Test
    void rfc2047EncodedChineseFilenameIsDecoded() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("中文标题.png".getBytes(StandardCharsets.UTF_8));
        String path = writeMht(wrapRelated(htmlPart(),
                imagePart("Content-Disposition: attachment; filename=\"=?UTF-8?B?" + encoded + "?=\"")));
        MimeParser.ParsedMht result = new MimeParser().parse(path, false, null, null, null);

        assertEquals(1, result.getImageInfoList().size());
        assertEquals("中文标题.png", result.getImageInfoList().get(0).getFilename(),
                "RFC 2047 编码词应被解码为中文文件名");
    }

    // ==================== P0-9：进度单调性 ====================

    @Test
    void byteProgressIsMonotonicWhenHtmlComesAfterMultipartProgress() throws Exception {
        // html 位于第一个子 multipart（5 张图，第 5 个 part 触发间隔进度 0.225）之后：
        // 修复前 HTML 提取上报固定 0.15，进度倒退到 0.225 之前
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            inner.append("--INNER\r\n")
                    .append("Content-Type: image/png\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n")
                    .append("Content-ID: <img").append(i).append(">\r\n")
                    .append("\r\n")
                    .append("iVBORw0KGgo=\r\n");
        }
        inner.append("--INNER--");
        String nestedMultipart = "Content-Type: multipart/mixed; boundary=\"INNER\"\r\n"
                + "\r\n"
                + inner;

        String path = writeMht(wrapRelated(nestedMultipart, htmlPart()));

        List<Double> progress = new ArrayList<>();
        new MimeParser().parse(path, true, null, null, progress::add);

        assertTrue(progress.size() >= 2, "应至少收到循环进度与 HTML 提取进度两次上报：" + progress);
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i) >= progress.get(i - 1),
                    "进度必须单调不减，实际序列：" + progress);
        }
    }

    // ==================== CTE 兜底（2026-10-16 裁决） ====================

    /** 缺 Content-Transfer-Encoding 的 HTML part：按 RFC 2045 默认 7bit，不得因 “utf-8 不是传输编码” 整段失败。 */
    @Test
    void htmlPartWithoutTransferEncodingFallsBackToSevenBit() throws Exception {
        String part = "Content-Type: text/html; charset=utf-8\r\n"
                + "\r\n"
                + "<html><body><p>中文正文 sentinel</p></body></html>";
        String path = writeMht(wrapRelated(part));

        MimeParser.ParsedMht result = assertDoesNotThrow(
                () -> new MimeParser().parse(path, true, null, null, null),
                "CTE 缺失时不得抛 Unknown encoding（修复前把 CTE 回退成 utf-8 会整段失败）");
        assertNotNull(result.getHtmlContent());
        assertTrue(result.getHtmlContent().contains("中文正文 sentinel"),
                "缺 CTE 的 HTML 正文应被正确解码，实际：" + result.getHtmlContent());
    }

    /** 未知/非法 Content-Transfer-Encoding：降级为原样读字节，不得让 part 解析失败。 */
    @Test
    void htmlPartWithUnknownTransferEncodingFallsBackToRawBytes() throws Exception {
        String part = "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: x-bogus\r\n"
                + "\r\n"
                + "<html><body><p>raw fallback sentinel</p></body></html>";
        String path = writeMht(wrapRelated(part));

        MimeParser.ParsedMht result = assertDoesNotThrow(
                () -> new MimeParser().parse(path, true, null, null, null),
                "未知传输编码应降级为原样读字节，而不是抛异常");
        assertNotNull(result.getHtmlContent());
        assertTrue(result.getHtmlContent().contains("raw fallback sentinel"),
                "未知 CTE 时仍应拿到正文，实际：" + result.getHtmlContent());
    }
}
