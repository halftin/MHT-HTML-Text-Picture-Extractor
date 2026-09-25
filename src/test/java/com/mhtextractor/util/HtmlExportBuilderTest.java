package com.mhtextractor.util;

import com.mhtextractor.core.MimeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器查看 HTML 导出纯逻辑测试（T1–T10，见《浏览器查看HTML功能实施方案.md》第 5.1 节）。
 * HtmlExportBuilder 不依赖 JavaFX 运行时，直接构造 ParsedMht 即可测试。
 */
class HtmlExportBuilderTest {

    @TempDir
    Path tmp;

    /** 本次测试生成的缓存目录（@AfterEach 统一清理） */
    private final List<Path> generatedDirs = new ArrayList<>();

    /** PNG 魔数字节（89504E47...），用于命中检测 */
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private static MimeParser.ParsedMht parsed(String html, Map<String, byte[]> cidMap) {
        MimeParser.ParsedMht p = new MimeParser.ParsedMht();
        p.setHtmlContent(html);
        p.setCidMap(cidMap);
        return p;
    }

    /** 用不存在的 MHT 路径导出（缓存键只看路径+大小+修改时间，无需真实文件） */
    private HtmlExportBuilder.ExportResult exportTo(String html, Map<String, byte[]> cidMap) {
        return exportTo(tmp.resolve("mht-" + System.nanoTime() + ".mht").toString(), html, cidMap);
    }

    private HtmlExportBuilder.ExportResult exportTo(String mhtPath, String html, Map<String, byte[]> cidMap) {
        HtmlExportBuilder.ExportResult r = HtmlExportBuilder.export(mhtPath, parsed(html, cidMap));
        if (r.success() && r.htmlFile() != null) {
            generatedDirs.add(r.htmlFile().getParent());
        }
        return r;
    }

    @AfterEach
    void cleanup() {
        for (Path dir : generatedDirs) {
            FileUtils.deleteDirectory(dir.toFile());
        }
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ==================== T1：charset 声明替换 ====================

    @Test
    void t1CharsetDeclarationReplaced() throws Exception {
        String html = "<!DOCTYPE html><html><head>"
                + "<meta charset=\"gb2312\">"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">"
                + "<title>测试</title></head><body>内容</body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, Map.of());
        assertTrue(r.success());
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertFalse(out.contains("gb2312"), "原编码声明必须被清除");
        assertTrue(out.contains("<meta charset=\"utf-8\">"));
        assertEquals(1, countOccurrences(out, "<meta charset=\"utf-8\">"), "最终只保留一个 utf-8 声明");
    }

    // ==================== T2：中文不乱码 + 无 BOM ====================

    @Test
    void t2ChineseNotGarbledAndNoBom() throws Exception {
        String html = "<html><head><meta charset=\"gbk\"></head><body>第一章 测试</body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, Map.of());
        byte[] bytes = Files.readAllBytes(r.htmlFile());
        assertNotEquals((byte) 0xEF, bytes[0], "不得写入 UTF-8 BOM");
        String out = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(out.contains("第一章 测试"), "中文正文按 UTF-8 写盘读回不乱码");
    }

    // ==================== T3：cid 重写 + 图片落盘 ====================

    @Test
    void t3CidRewrittenAndImageWritten() throws Exception {
        Map<String, byte[]> cidMap = new LinkedHashMap<>();
        cidMap.put("image001.png@01D9", PNG);
        String html = "<html><body><img src=\"cid:image001.png@01D9\"></body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, cidMap);
        assertTrue(r.success());
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertTrue(out.contains("img/0001.png"));
        Path img = r.htmlFile().getParent().resolve("img/0001.png");
        assertTrue(Files.exists(img));
        assertArrayEquals(PNG, Files.readAllBytes(img), "落盘字节与 cidMap 输入一致");
        assertEquals(1, r.imageCount());
    }

    // ==================== T4：cid 变体归一化（大小写/尖括号/百分号编码） ====================

    @Test
    void t4CidVariantNormalized() throws Exception {
        Map<String, byte[]> cidMap = new LinkedHashMap<>();
        cidMap.put("<image001.PNG@01d9>", PNG);
        String html = "<html><body><img src=\"CID:%3CImage001.PNG%4001d9%3E\"></body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, cidMap);
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertTrue(out.contains("img/0001.png"), "归一化后应命中同一图片");
        assertTrue(Files.exists(r.htmlFile().getParent().resolve("img/0001.png")));
    }

    // ==================== T5：未命中 cid 原样保留 + warnings ====================

    @Test
    void t5UnresolvedCidKeptWithWarning() throws Exception {
        String html = "<html><body><img src=\"cid:unknown.png\"></body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, Map.of());
        assertTrue(r.success());
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertTrue(out.contains("cid:unknown.png"), "未命中的引用必须原样保留");
        assertFalse(r.warnings().isEmpty(), "未命中须计入 warnings");
    }

    // ==================== T6：同一 cid 多次引用去重编号 ====================

    @Test
    void t6SameCidDeduplicated() throws Exception {
        Map<String, byte[]> cidMap = new LinkedHashMap<>();
        cidMap.put("pic@01", PNG);
        String html = "<html><body>"
                + "<img src=\"cid:pic@01\"><img src=\"cid:pic@01\"><img src=\"cid:pic@01\">"
                + "</body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, cidMap);
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertEquals(3, countOccurrences(out, "img/0001.png"), "3 处引用同一相对路径");
        assertEquals(1, r.imageCount(), "同一 cid 只写一个图片文件");
        try (var s = Files.list(r.htmlFile().getParent().resolve("img"))) {
            assertEquals(1, s.count());
        }
    }

    // ==================== T7：无 head 时 meta 置于文档最前 ====================

    @Test
    void t7NoHeadMetaPrepended() throws Exception {
        String html = "<html><body>x</body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, Map.of());
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        String head200 = out.substring(0, Math.min(200, out.length()));
        assertTrue(head200.contains("<meta charset=\"utf-8\">"), "meta 必须位于前 200 字符内");
        assertTrue(out.startsWith("<meta charset=\"utf-8\">"));
    }

    // ==================== T8：魔数无法识别时回退 png ====================

    @Test
    void t8UnknownMagicFallsBackToPng() throws Exception {
        Map<String, byte[]> cidMap = new LinkedHashMap<>();
        cidMap.put("vector", "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8));
        String html = "<html><body><img src=\"cid:vector\"></body></html>";
        HtmlExportBuilder.ExportResult r = exportTo(html, cidMap);
        assertTrue(r.success(), "魔数无法识别不得抛异常");
        String out = Files.readString(r.htmlFile(), StandardCharsets.UTF_8);
        assertTrue(out.contains("img/0001.png"), "扩展名回退 png");
    }

    // ==================== T9：缓存复用（同一路径 + 相同 mtime/size 二次调用不重写） ====================

    @Test
    void t9CacheReusedOnSecondCall() throws Exception {
        Path mht = tmp.resolve("cache-test.mht");
        Files.writeString(mht, "dummy mht", StandardCharsets.UTF_8);
        MimeParser.ParsedMht p = parsed("<html><body>缓存</body></html>", Map.of());
        HtmlExportBuilder.ExportResult r1 = HtmlExportBuilder.export(mht.toString(), p);
        assertTrue(r1.success());
        Path index = r1.htmlFile();
        generatedDirs.add(index.getParent());
        long mtime1 = Files.getLastModifiedTime(index).toMillis();

        HtmlExportBuilder.ExportResult r2 = HtmlExportBuilder.export(mht.toString(), p);
        assertTrue(r2.success());
        assertEquals(index, r2.htmlFile(), "缓存命中返回同一路径");
        assertEquals(mtime1, Files.getLastModifiedTime(index).toMillis(), "第二次不得重写页面文件");
    }

    // ==================== T10：htmlContent 为 null 的失败路径 ====================

    @Test
    void t10NullHtmlContentFails() {
        HtmlExportBuilder.ExportResult r = HtmlExportBuilder.export(
                tmp.resolve("broken.mht").toString(), parsed(null, Map.of()));
        assertFalse(r.success());
        assertNotNull(r.error());
        assertFalse(r.error().isEmpty());
    }
}
