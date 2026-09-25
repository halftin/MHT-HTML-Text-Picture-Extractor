package com.mhtextractor.processor;

import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.util.ExtractOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ==================== 会话 10：提取复用（预览缓存命中） ====================

    private PreviewCacheLifecycle newCacheLifecycle() {
        return new PreviewCacheLifecycle(new PreviewCacheStore(tmp.resolve("cache")));
    }

    /**
     * 模拟会话 9 预览落盘：为 mht 写入一份与预览产物一致的缓存
     * （1 张 webp，sourceId/originalName 均为 "img001"——CID 推断名，与真实预览相同）。
     */
    private PreviewCacheKey writePreviewCache(Path mht, AppConfig params, byte[] webpBytes)
            throws Exception {
        PreviewCacheStore store = new PreviewCacheStore(tmp.resolve("cache"));
        PreviewCacheKey key = PreviewCacheKey.compute(mht,
                params.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime()),
                key.paramFingerprint(), true,
                List.of(new PreviewCacheMeta.ImageEntry(
                        "images/0001.webp", "img001", "img001", "webp", List.of())),
                List.of());
        store.write(key, meta, dir -> Files.write(dir.resolve("0001.webp"), webpBytes));
        return key;
    }

    /** 用等长垃圾覆盖文件并还原 mtime：size/mtime 不变 → 缓存 key 不变；解析必失败 → 可观测 parse 是否被跳过 */
    private void replaceWithGarbageSameSize(Path mht) throws Exception {
        long mtime = Files.getLastModifiedTime(mht).toMillis();
        byte[] garbage = new byte[(int) Files.size(mht)];
        Arrays.fill(garbage, (byte) 0x42);
        Files.write(mht, garbage);
        Files.setLastModifiedTime(mht, FileTime.fromMillis(mtime));
    }

    /** 递归快照（相对路径 + 文件 SHA-256 哈希 + 目录项），比较两棵输出树（文件名 + 字节） */
    private static List<String> snapshotTree(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted().toList()) {
                String rel = root.relativize(p).toString();
                if (Files.isDirectory(p)) {
                    if (!p.equals(root)) {
                        entries.add(rel + "/");
                    }
                } else {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
                    entries.add(rel + "  sha256=" + HexFormat.of().formatHex(digest));
                }
            }
        }
        return entries;
    }

    private static void assertTreeEquals(Path expected, Path actual) throws Exception {
        assertEquals(snapshotTree(expected), snapshotTree(actual),
                "两棵输出树必须完全一致（文件名 + 字节）");
    }

    private static void deleteRecursivelyIfExists(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    @Test
    void cacheHitSkipsParseAndReusesImages() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            webpBytes = is.readAllBytes();
        }
        AppConfig config = preloadSeparateConfig(source);
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        writePreviewCache(a, config, webpBytes);
        // 文件被等长垃圾覆盖（size/mtime 不变）：若实现仍解析 MHT，必然 FAILED/SKIPPED；
        // 只有真正跳过解析走缓存才会 SUCCESS
        replaceWithGarbageSameSize(a);

        ImageBatchProcessor processor = new ImageBatchProcessor(config, lifecycle);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(0, stats.getFailCount(), stats.toString());
        assertEquals(0, stats.getSkipCount(), stats.toString());
        assertEquals(1, stats.getSuccessCount(), stats.toString());
        assertEquals(ExtractOutcome.SUCCESS, stats.getFileResults().get(0).outcome());
        Path saved = out.resolve("a").resolve("img001.webp");
        assertTrue(Files.exists(saved), "缓存命中的输出应使用原始文件名 img001.webp");
        assertTrue(Arrays.equals(webpBytes, Files.readAllBytes(saved)), "输出字节必须与缓存图片一致");
        // 规则 (b)：SUCCESS → 缓存已删除
        assertFalse(lifecycle.hasCachedEntries(), "提取成功后缓存目录应被删除");
    }

    @Test
    void cacheHitSeparateOutputByteIdentical() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out1 = Files.createDirectories(tmp.resolve("out1"));
        Path out2 = Files.createDirectories(tmp.resolve("out2"));

        AppConfig config = preloadSeparateConfig(source);
        config.setEnableBatchRenaming(true);

        // 第一次：无缓存 → 全量提取
        BatchProcessor.ProcessStats stats1 = new ImageBatchProcessor(config).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out1.toString(), null, null);
        // 模拟预览落盘（同一 config → 同一指纹）
        byte[] webpBytes = Files.readAllBytes(out1.resolve("a").resolve("00001.webp"));
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        writePreviewCache(a, config, webpBytes);
        // 第二次：保留缓存 → 应命中复用
        BatchProcessor.ProcessStats stats2 = new ImageBatchProcessor(config, lifecycle).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out2.toString(), null, null);

        assertEquals(1, stats1.getSuccessCount(), stats1.toString());
        assertEquals(1, stats2.getSuccessCount(), stats2.toString());
        assertEquals(stats1.getFileResults().get(0).outcome(), stats2.getFileResults().get(0).outcome());
        assertTreeEquals(out1, out2);
        assertFalse(lifecycle.hasCachedEntries(), "提取成功后缓存目录应被删除");
    }

    @Test
    void cacheHitMergedOutputByteIdentical() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out1 = Files.createDirectories(tmp.resolve("out1"));
        Path out2 = Files.createDirectories(tmp.resolve("out2"));

        AppConfig config = preloadSeparateConfig(source);
        config.setSingleOutputMode(true);
        config.setEnableBatchRenaming(true);

        BatchProcessor.ProcessStats stats1 = new ImageBatchProcessor(config).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out1.toString(), null, null);
        byte[] webpBytes = Files.readAllBytes(out1.resolve("src").resolve("00001.webp"));
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        writePreviewCache(a, config, webpBytes);
        BatchProcessor.ProcessStats stats2 = new ImageBatchProcessor(config, lifecycle).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out2.toString(), null, null);

        assertEquals(1, stats1.getSuccessCount(), stats1.toString());
        assertEquals(1, stats2.getSuccessCount(), stats2.toString());
        assertTreeEquals(out1, out2);
        assertFalse(lifecycle.hasCachedEntries(), "提取成功后缓存目录应被删除");
    }

    @Test
    void cacheHitSeparateTitleParsingByteIdentical() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        // 标题标记：单独模式 + 名称提取时输出目录名依赖 HTML 标题（缓存不含标题，须提取期重算）
        Path a = writeImageMht(source, "a.mht", "TITLE-START漫画A标题TITLE-END");
        Path out1 = Files.createDirectories(tmp.resolve("out1"));
        Path out2 = Files.createDirectories(tmp.resolve("out2"));

        AppConfig config = preloadSeparateConfig(source);
        config.setEnableNameExtraction(true);
        config.setImageNameStartMarker("TITLE-START");
        config.setImageNameEndMarker("TITLE-END");

        BatchProcessor.ProcessStats stats1 = new ImageBatchProcessor(config).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out1.toString(), null, null);
        byte[] webpBytes = Files.readAllBytes(out1.resolve("漫画A标题").resolve("img001.webp"));
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        writePreviewCache(a, config, webpBytes);
        BatchProcessor.ProcessStats stats2 = new ImageBatchProcessor(config, lifecycle).process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out2.toString(), null, null);

        assertEquals(1, stats1.getSuccessCount(), stats1.toString());
        assertEquals(1, stats2.getSuccessCount(), stats2.toString());
        // 标题目录命名（提取期重算，P13）：两次运行一致
        assertTrue(Files.isDirectory(out2.resolve("漫画A标题")), "缓存命中仍须按标题命名输出目录");
        assertTreeEquals(out1, out2);
        assertFalse(lifecycle.hasCachedEntries(), "提取成功后缓存目录应被删除");
    }

    /** C5 固定目录版：跑两次（清缓存全量 / 保留缓存复用），输出落在 target/c5-compare 供外部 diff -r 复核 */
    @Test
    void c5RecursiveComparisonFixedDirs() throws Exception {
        Path c5 = Path.of("target", "c5-compare");
        deleteRecursivelyIfExists(c5);
        Path out1 = Files.createDirectories(c5.resolve("out1"));
        Path out2 = Files.createDirectories(c5.resolve("out2"));

        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path b = writeImageMht(source, "b.mht", null);

        AppConfig config = preloadSeparateConfig(source);
        List<String> paths = List.of(a.toString(), b.toString());

        // 第一次：无缓存 → 全量提取
        BatchProcessor.ProcessStats stats1 = new ImageBatchProcessor(config).process(
                BatchProcessor.buildMhtFileInfoList(paths), out1.toString(), null, null);
        // 模拟两个文件都已预览落盘
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            webpBytes = is.readAllBytes();
        }
        writePreviewCache(a, config, webpBytes);
        writePreviewCache(b, config, webpBytes);
        // 第二次：保留缓存 → 逐文件命中复用
        BatchProcessor.ProcessStats stats2 = new ImageBatchProcessor(config, lifecycle).process(
                BatchProcessor.buildMhtFileInfoList(paths), out2.toString(), null, null);

        assertEquals(2, stats1.getSuccessCount(), stats1.toString());
        assertEquals(2, stats2.getSuccessCount(), stats2.toString());
        assertTreeEquals(out1, out2);
        assertFalse(lifecycle.hasCachedEntries(), "提取成功后缓存目录应被删除");
    }

    @Test
    void paramChangeMissesCacheAndFailureKeepsCache() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        AppConfig configA = preloadSeparateConfig(source);
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            webpBytes = is.readAllBytes();
        }
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        PreviewCacheKey keyA = writePreviewCache(a, configA, webpBytes);
        // 文件被等长垃圾覆盖：若误命中缓存会 SUCCESS；参数变化（指纹不同）必须回全量路径 → 解析失败
        replaceWithGarbageSameSize(a);

        AppConfig configB = preloadSeparateConfig(source);
        configB.setEnableBatchRenaming(true);   // 参与指纹的字段变化 → key 不同 → 不再命中

        ImageBatchProcessor processor = new ImageBatchProcessor(configB, lifecycle);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(0, stats.getSuccessCount(), stats.toString());
        assertEquals(1, stats.getFileResults().size(), stats.toString());
        assertNotEquals(ExtractOutcome.SUCCESS, stats.getFileResults().get(0).outcome(),
                "参数变化后不得命中旧缓存（命中会 SUCCESS，实际必须走全量解析失败路径）");
        // C10：失败后缓存仍存在，可重试
        assertTrue(lifecycle.store().load(keyA).isPresent(), "失败后缓存必须保留");
    }

    @Test
    void cancelKeepsCache() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        AppConfig config = preloadSeparateConfig(source);
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            webpBytes = is.readAllBytes();
        }
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        PreviewCacheKey key = writePreviewCache(a, config, webpBytes);

        ImageBatchProcessor processor = new ImageBatchProcessor(config, lifecycle);
        processor.setCancelSupplier(() -> true);   // 立即取消：循环在首个文件前中断
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(0, stats.getFileResults().size(), stats.toString());
        assertTrue(lifecycle.store().load(key).isPresent(), "取消后缓存必须保留以便重试");
    }

    @Test
    void missWithStaleCacheDeletesOnSuccess() throws Exception {
        Path source = tmp.resolve("src");
        Files.createDirectories(source);
        Path a = writeImageMht(source, "a.mht", null);
        Path out = Files.createDirectories(tmp.resolve("out"));

        AppConfig configA = preloadSeparateConfig(source);
        byte[] webpBytes;
        try (InputStream is = getClass().getResourceAsStream("/sample-preview.webp")) {
            webpBytes = is.readAllBytes();
        }
        PreviewCacheLifecycle lifecycle = newCacheLifecycle();
        PreviewCacheKey keyA = writePreviewCache(a, configA, webpBytes);

        // 参数变化 → 未命中，但文件完整可提取 → SUCCESS → 规则 (b) 仍删旧指纹缓存（按文件路径反查）
        AppConfig configB = preloadSeparateConfig(source);
        configB.setEnableBatchRenaming(true);
        ImageBatchProcessor processor = new ImageBatchProcessor(configB, lifecycle);
        BatchProcessor.ProcessStats stats = processor.process(
                BatchProcessor.buildMhtFileInfoList(List.of(a.toString())), out.toString(), null, null);

        assertEquals(1, stats.getSuccessCount(), stats.toString());
        assertTrue(lifecycle.store().load(keyA).isEmpty(), "未命中但提取成功 → 旧指纹缓存目录也应删除");
    }
}
