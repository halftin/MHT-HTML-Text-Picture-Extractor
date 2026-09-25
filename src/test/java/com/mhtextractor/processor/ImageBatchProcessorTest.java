package com.mhtextractor.processor;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.util.ExtractOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageBatchProcessor 编排层回归（会话 5，P0-1/2/3）：
 * 预加载索引错位、归档失败删源、全部写入失败仍记 SUCCESS。
 */
class ImageBatchProcessorTest {

    @TempDir
    Path tmp;

    /** 含一张 webp 图片的合法 MHT；extraHtml 插入 <body> 开头（用于标题标记） */
    private Path writeImageMht(Path dir, String fileName, String extraHtml) throws Exception {
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            assertNotNull(is, "sample-preview.webp 资源缺失");
            webpBytes = is.readAllBytes();
        }
        String boundary = "----=_ibp_test_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"utf-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
        sb.append("<html><body>").append(extraHtml == null ? "" : extraHtml)
                .append("<img src=\"cid:img001\"></body></html>\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: image/webp\r\n");
        sb.append("Content-ID: <img001>\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getEncoder().encodeToString(webpBytes)).append("\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        Path f = dir.resolve(fileName);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** 合法 MIME 但无 HTML 部件 → MimeParser 返回 htmlContent==null → 无法解析（SKIPPED） */
    private Path writeNoHtmlMht(Path dir, String fileName) throws Exception {
        String boundary = "----=_ibp_plain_boundary";
        StringBuilder sb = new StringBuilder();
        sb.append("From: <test@example.com>\r\n");
        sb.append("Subject: ").append(fileName).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=\"utf-8\"\r\n\r\n");
        sb.append("no html here\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        Path f = dir.resolve(fileName);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static Map<String, FileResult> resultsByName(BatchProcessor.ProcessStats stats) {
        Map<String, FileResult> m = new HashMap<>();
        for (FileResult r : stats.getFileResults()) {
            m.put(Path.of(r.path()).getFileName().toString(), r);
        }
        return m;
    }

    private static AppConfig preloadSeparateConfig(Path source) {
        AppConfig config = new AppConfig();
        config.setHighSpeedMemoryMode(true);
        config.setInputFolder(source.toString());
        return config;
    }

    // ==================== P0-1：预加载模式索引错位 ====================

    @Test
    void preloadUnparseableFileDoesNotShiftImageAttribution() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path plain = writeNoHtmlMht(source, "plain.mht");
        Path b = writeImageMht(source, "b.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        ImageBatchProcessor processor = new ImageBatchProcessor(preloadSeparateConfig(source));
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString(), plain.toString(), b.toString())),
                out.toString(), null, null);

        assertEquals(0, stats.getFailCount(), stats.toString());
        assertEquals(1, stats.getSkipCount(), stats.toString());
        assertEquals(3, stats.getFileResults().size(), "每文件必须恰好一条结果（不得双记录）");
        Map<String, FileResult> results = resultsByName(stats);
        assertEquals(ExtractOutcome.SUCCESS, results.get("a.mht").outcome());
        assertEquals(ExtractOutcome.SKIPPED, results.get("plain.mht").outcome());
        assertEquals(ExtractOutcome.SUCCESS, results.get("b.mht").outcome());

        // 归属正确：错位时 b 的图片会被写进 plain 的目录，且 b 越界失败
        assertEquals(1, Files.list(out.resolve("a")).count(), "a 目录应只有 a 的 1 张图");
        assertEquals(1, Files.list(out.resolve("b")).count(), "b 目录应只有 b 的 1 张图");
        assertFalse(Files.exists(out.resolve("plain")), "不可解析文件不应产生输出目录");
    }

    @Test
    void preloadParseExceptionDoesNotShiftImageAttribution() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        // 目录冒充 .mht → 解析必然抛异常（FileInputStream 打不开目录）
        Path badDir = Files.createDirectories(source.resolve("bad.mht"));
        Path b = writeImageMht(source, "b.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        ImageBatchProcessor processor = new ImageBatchProcessor(preloadSeparateConfig(source));
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString(), badDir.toString(), b.toString())),
                out.toString(), null, null);

        assertEquals(1, stats.getFailCount(), stats.toString());
        assertEquals(3, stats.getFileResults().size(), "每文件必须恰好一条结果（不得双记录）");
        Map<String, FileResult> results = resultsByName(stats);
        assertEquals(ExtractOutcome.SUCCESS, results.get("a.mht").outcome());
        assertEquals(ExtractOutcome.FAILED, results.get("bad.mht").outcome());
        assertTrue(results.get("bad.mht").message().contains("加载 MHT 文件失败"),
                "失败原因应保留解析异常信息：" + results.get("bad.mht").message());
        assertEquals(ExtractOutcome.SUCCESS, results.get("b.mht").outcome());

        assertEquals(1, Files.list(out.resolve("a")).count());
        assertEquals(1, Files.list(out.resolve("b")).count());
    }

    @Test
    void preloadMergedNameDoesNotComeFromWrongFile() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path plain = writeNoHtmlMht(source, "plain.mht");
        Path a = writeImageMht(source, "a.mht", "TITLE-START漫画A标题TITLE-END");
        Path out = Files.createDirectories(tmp.resolve("out"));

        AppConfig config = preloadSeparateConfig(source);
        config.setSingleOutputMode(true);
        config.setEnableNameExtraction(true);
        config.setImageNameStartMarker("TITLE-START");
        config.setImageNameEndMarker("TITLE-END");

        ImageBatchProcessor processor = new ImageBatchProcessor(config);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(plain.toString(), a.toString())),
                out.toString(), null, null);

        assertEquals(0, stats.getFailCount(), stats.toString());
        Map<String, FileResult> results = resultsByName(stats);
        assertEquals(ExtractOutcome.SKIPPED, results.get("plain.mht").outcome());
        assertEquals(ExtractOutcome.SUCCESS, results.get("a.mht").outcome());

        // 首个文件不可解析 → 合并目录名回退默认（输入目录名），不得取第二个文件的标题
        Path merged = out.resolve("src");
        assertTrue(Files.isDirectory(merged), "合并目录应使用默认名 src，实际内容："
                + Files.list(out).map(p -> p.getFileName().toString()).toList());
        assertFalse(Files.exists(out.resolve("漫画A标题")), "不得用第二个文件的标题命名合并目录");
        assertEquals(1, Files.list(merged).count());
    }

    // ==================== P0-2：归档失败仍删源目录 ====================

    @Test
    void archiveFailureKeepsSourceDirectoryAndMarksFailed() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));
        // 在 zip 目标路径预置目录 → FileOutputStream 打开失败 → Archiver 返回 false
        Files.createDirectories(out.resolve("a.zip"));

        AppConfig config = new AppConfig();
        config.setEnableZipPackaging(true);
        config.setInputFolder(source.toString());

        ImageBatchProcessor processor = new ImageBatchProcessor(config);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(1, stats.getFailCount(), stats.toString());
        assertTrue(stats.getFailDetails().stream().anyMatch(d -> d.contains("归档")), stats.toString());
        FileResult r = stats.getFileResults().get(0);
        assertEquals(ExtractOutcome.FAILED, r.outcome());
        assertTrue(r.message().contains("归档"), r.message());

        // 源目录未被删除，图片仍在（修复前此处唯一副本被删）
        Path fileOutDir = out.resolve("a");
        assertTrue(Files.isDirectory(fileOutDir), "归档失败不得删除源目录");
        assertEquals(1, Files.list(fileOutDir).count());
    }

    @Test
    void mergedArchiveFailureKeepsMergedDirectory() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.createDirectories(out.resolve("src.zip"));

        AppConfig config = new AppConfig();
        config.setSingleOutputMode(true);
        config.setEnableZipPackaging(true);
        config.setInputFolder(source.toString());

        ImageBatchProcessor processor = new ImageBatchProcessor(config);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(1, stats.getSuccessCount(), "文件级写入成功不受归档失败影响：" + stats);
        assertEquals(1, stats.getFailCount(), stats.toString());
        assertTrue(stats.getFailDetails().stream().anyMatch(d -> d.contains("归档")), stats.toString());
        Path mergedDir = out.resolve("src");
        assertTrue(Files.isDirectory(mergedDir), "归档失败不得删除合并目录");
        assertEquals(1, Files.list(mergedDir).count());
    }

    // ==================== P0-3：全部写入失败仍标记 SUCCESS ====================

    @Test
    void allWritesFailedRecordsFailureNotSuccess() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));
        // 输出目录路径被普通文件占位 → 目录创建失败 → 全部写入失败
        Files.write(out.resolve("a"), new byte[]{1, 2, 3});

        ImageBatchProcessor processor = new ImageBatchProcessor(preloadSeparateConfig(source));
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(0, stats.getSuccessCount(), stats.toString());
        assertEquals(1, stats.getFailCount(), stats.toString());
        assertTrue(stats.getFailDetails().stream().anyMatch(d -> d.contains("写入失败")), stats.toString());
        FileResult r = stats.getFileResults().get(0);
        assertEquals(ExtractOutcome.FAILED, r.outcome());
        assertTrue(r.message().contains("写入失败"), r.message());
    }

    @Test
    void renameCollisionWritesNothingRecordsFailureNotSuccess() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path fileOutDir = Files.createDirectories(out.resolve("a"));
        // 重命名模式首图名为 00001.ext（saveImagesConcurrent 从 1 起）；ext 由魔数检测。
        // 预置全部候选扩展名文件 → 重命名撞名（会话 4 起视为失败）→ saved == 0
        for (String ext : new String[]{"jpg", "webp", "png", "gif", "bmp"}) {
            Files.write(fileOutDir.resolve("00001." + ext), new byte[]{1});
        }

        AppConfig config = preloadSeparateConfig(source);
        config.setEnableBatchRenaming(true);

        ImageBatchProcessor processor = new ImageBatchProcessor(config);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(0, stats.getSuccessCount(), stats.toString());
        assertEquals(1, stats.getFailCount(), stats.toString());
        FileResult r = stats.getFileResults().get(0);
        assertEquals(ExtractOutcome.FAILED, r.outcome());
        assertTrue(r.message().contains("写入失败"), r.message());
        // 5 个占位文件仍在（未被覆盖/追加），且没有新文件写入
        assertEquals(5, Files.list(fileOutDir).count());
    }
}
