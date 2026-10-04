package com.mhtextractor.cache;

import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.model.ExtractWarning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PreviewCacheStore 纯逻辑测试（验收 C4/C9/C11）：写读回环、四要素失效、.complete 判定、
 * 孤儿清理、崩溃模拟。全部 IO 落在 @TempDir 专属根目录，绝不触碰真实 mht-extractor-preview/。
 */
class PreviewCacheStoreTest {

    @TempDir
    Path tempDir;

    private static final String FP = "a".repeat(64);

    private Path mhtFile() throws IOException {
        Path file = tempDir.resolve("sample.mht");
        Files.write(file, "fake mht".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private PreviewCacheKey keyFor(Path file) throws IOException {
        return PreviewCacheKey.compute(file, FP, PreviewCacheMeta.FORMAT_VERSION);
    }

    private PreviewCacheMeta metaFor(PreviewCacheKey key, List<PreviewCacheMeta.ImageEntry> images) {
        return new PreviewCacheMeta(key.formatVersion(),
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                        key.extraFingerprint()),
                key.paramFingerprint(), true, images, List.of(new ExtractWarning("w1", Map.of())));
    }

    private PreviewCacheStore.ImagesWriter imagesWriter(byte[]... datas) {
        return imagesDir -> {
            for (int i = 0; i < datas.length; i++) {
                Files.write(imagesDir.resolve(String.format("%04d.png", i + 1)), datas[i]);
            }
        };
    }

    private PreviewCacheStore store() {
        return new PreviewCacheStore(tempDir.resolve("mht-extractor-preview"));
    }

    // ---------- (a) 写入 → 读取回环 ----------

    @Test
    void writeThenLoadRoundTrip() throws IOException {
        PreviewCacheStore store = store();
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        byte[] d1 = "image-one".getBytes(StandardCharsets.UTF_8);
        byte[] d2 = "image-two".getBytes(StandardCharsets.UTF_8);
        List<PreviewCacheMeta.ImageEntry> images = List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:img1", "封面.png", "png", List.of()),
                new PreviewCacheMeta.ImageEntry("images/0002.png", "cid:img2", null, "png",
                        List.of(new ExtractWarning("ad", Map.of()))));

        Path dir = store.write(key, metaFor(key, images), imagesWriter(d1, d2));

        Optional<PreviewCacheMeta> loaded = store.load(key);
        assertTrue(loaded.isPresent());
        PreviewCacheMeta meta = loaded.get();
        assertEquals(PreviewCacheMeta.FORMAT_VERSION, meta.formatVersion());
        assertEquals(FP, meta.paramFingerprint());
        assertEquals(file.toAbsolutePath().normalize().toString(), meta.source().absolutePath());
        assertEquals(2, meta.images().size());
        assertEquals(List.of(new ExtractWarning("w1", Map.of())), meta.warnings());
        assertArrayEquals(d1, Files.readAllBytes(dir.resolve("images/0001.png")));
        assertArrayEquals(d2, Files.readAllBytes(dir.resolve("images/0002.png")));
        assertTrue(Files.exists(dir.resolve(".complete")));
        // .tmp 目录不留残余
        assertFalse(Files.exists(dir.getParent().resolve(key.toDirName() + ".tmp")));

        // DirectoryImageSource 按需读盘
        DirectoryImageSource source = new DirectoryImageSource(meta, dir);
        assertEquals(2, source.size());
        ImageExtractor.ImageData first = source.get(0);
        assertEquals("封面.png", first.getFilename());
        assertEquals("cid:img1", first.getContentId());
        assertArrayEquals(d1, first.getData());
        assertArrayEquals(d2, source.get(1).getData());
        assertEquals(List.of(new ExtractWarning("w1", Map.of())), source.warnings());
    }

    // ---------- (b) 四要素 + 格式版本任一不匹配 → 不命中 ----------

    @Test
    void sizeMismatchMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter());

        assertTrue(store.load(new PreviewCacheKey(key.absolutePath(), key.size() + 1,
                key.mtime(), key.paramFingerprint(), key.formatVersion())).isEmpty());
    }

    @Test
    void mtimeMismatchMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter());

        assertTrue(store.load(new PreviewCacheKey(key.absolutePath(), key.size(),
                key.mtime() + 1, key.paramFingerprint(), key.formatVersion())).isEmpty());
    }

    @Test
    void fingerprintMismatchMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter());

        assertTrue(store.load(new PreviewCacheKey(key.absolutePath(), key.size(),
                key.mtime(), "b".repeat(64), key.formatVersion())).isEmpty());
    }

    @Test
    void formatVersionMismatchMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter());

        assertTrue(store.load(new PreviewCacheKey(key.absolutePath(), key.size(),
                key.mtime(), key.paramFingerprint(), key.formatVersion() + 1)).isEmpty());
    }

    // ---------- (c) 无 .complete 不命中 ----------

    @Test
    void dirWithoutCompleteMarkerMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter());
        Files.delete(store.dirFor(key).resolve(".complete"));

        assertTrue(store.load(key).isEmpty());
    }

    // ---------- (d) .tmp 目录被 cleanupOrphans 清除 ----------

    @Test
    void cleanupOrphansRemovesTmpDirs() throws IOException {
        PreviewCacheStore store = store();
        Files.createDirectories(store.root());
        Path tmp = store.root().resolve("deadbeefdeadbeef.tmp");
        Files.createDirectories(tmp.resolve("images"));
        Files.write(tmp.resolve("images/0001.png"), new byte[]{1, 2, 3});

        assertEquals(1, store.cleanupOrphans());
        assertFalse(Files.exists(tmp));
        // 根目录本身保留
        assertTrue(Files.isDirectory(store.root()));
    }

    // ---------- (e) 崩溃模拟：有 meta 与图片但无 .complete → 重启后不命中且被清理 ----------

    @Test
    void crashResidueIsCleanedAndNeverHits() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter(new byte[]{9}));
        // 模拟崩溃：写入完成后、.complete 前进程被杀（删除标记即等价状态）
        Files.delete(store.dirFor(key).resolve(".complete"));

        // 重启：新建 store 实例（同一 root）
        PreviewCacheStore restarted = store();
        assertTrue(restarted.load(key).isEmpty());
        assertEquals(1, restarted.cleanupOrphans());
        assertFalse(Files.exists(restarted.dirFor(key)));
        // 二次清理幂等
        assertEquals(0, restarted.cleanupOrphans());
    }

    // ---------- (f) delete(key) 后可重新写入 ----------

    @Test
    void deleteThenRewrite() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter(new byte[]{1}));
        assertTrue(store.delete(key));
        assertFalse(store.delete(key));
        assertTrue(store.load(key).isEmpty());

        byte[] d2 = new byte[]{2, 2, 2};
        Path dir = store.write(key, metaFor(key, List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "a.png", "png", List.of()))),
                imagesWriter(d2));
        assertArrayEquals(d2, Files.readAllBytes(dir.resolve("images/0001.png")));
        assertTrue(store.load(key).isPresent());
    }

    // ---------- 键规范化：同一文件不同路径形式 → 同一 key ----------

    @Test
    void keyNormalizesPathForms() throws IOException {
        Path file = mhtFile();
        PreviewCacheKey direct = PreviewCacheKey.compute(file, FP, PreviewCacheMeta.FORMAT_VERSION);
        Path withDotDot = tempDir.resolve("sub/../sample.mht");
        PreviewCacheKey viaDotDot = PreviewCacheKey.compute(withDotDot, FP, PreviewCacheMeta.FORMAT_VERSION);

        assertEquals(direct, viaDotDot);
        assertEquals(direct.toDirName(), viaDotDot.toDirName());
        assertEquals(64, direct.toDirName().length());
    }

    @Test
    void keyChangesWithFileContent() throws IOException {
        Path file = mhtFile();
        PreviewCacheKey before = keyFor(file);
        Files.write(file, "grow the file".getBytes(StandardCharsets.UTF_8));
        FileTime later = FileTime.fromMillis(before.mtime() + 1000);
        Files.setLastModifiedTime(file, later);
        PreviewCacheKey after = keyFor(file);

        assertTrue(after.size() != before.size());
        assertFalse(before.toDirName().equals(after.toDirName()));
    }

    // ---------- matches 自校验直测（meta 与目录名不符的防线） ----------

    @Test
    void metaMatchesRejectsEachChangedElement() throws IOException {
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        PreviewCacheMeta meta = metaFor(key, List.of());

        assertTrue(meta.matches(key));
        assertFalse(new PreviewCacheMeta(key.formatVersion() + 1, meta.source(), meta.paramFingerprint(),
                meta.imageMode(), meta.images(), meta.warnings()).matches(key));
        assertFalse(metaFor(new PreviewCacheKey(key.absolutePath(), key.size() + 1, key.mtime(),
                key.paramFingerprint(), key.formatVersion()), List.of()).matches(key));
        assertFalse(metaFor(new PreviewCacheKey(key.absolutePath(), key.size(), key.mtime() + 1,
                key.paramFingerprint(), key.formatVersion()), List.of()).matches(key));
        assertFalse(metaFor(new PreviewCacheKey(key.absolutePath(), key.size(), key.mtime(),
                "c".repeat(64), key.formatVersion()), List.of()).matches(key));
        assertFalse(metaFor(new PreviewCacheKey("D:/elsewhere.mht", key.size(), key.mtime(),
                key.paramFingerprint(), key.formatVersion()), List.of()).matches(key));
    }

    @Test
    void missingImageFileMisses() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "a.png", "png", List.of()))),
                imagesWriter(new byte[]{1}));
        Files.delete(store.dirFor(key).resolve("images/0001.png"));

        assertTrue(store.load(key).isEmpty());
    }

    // ---------- §2.5 目录布局精确断言（C1 结构） ----------

    @Test
    void writtenLayoutMatchesSchema() throws IOException {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);
        byte[] d1 = "layout-image".getBytes(StandardCharsets.UTF_8);
        Path dir = store.write(key, metaFor(key, List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "a.png", "png", List.of()))),
                imagesWriter(d1));

        // 目录名 = 64 位小写 hex（SHA-256，文件名与哈希无关）
        String dirName = dir.getFileName().toString();
        assertTrue(dirName.matches("[0-9a-f]{64}"), "缓存目录名应为 64 位小写 hex，实际：" + dirName);
        // 恰好三个条目：meta.json + images/ + .complete，无多余文件
        List<String> entries;
        try (var s = Files.list(dir)) {
            entries = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of(".complete", "images", "meta.json"), entries);
        // .complete 为空常规文件；meta.json 为常规文件；images/ 为目录且含 1 张图
        assertTrue(Files.isRegularFile(dir.resolve(".complete")));
        assertEquals(0, Files.size(dir.resolve(".complete")));
        assertTrue(Files.isRegularFile(dir.resolve("meta.json")));
        assertTrue(Files.isDirectory(dir.resolve("images")));
        assertArrayEquals(d1, Files.readAllBytes(dir.resolve("images/0001.png")));
    }

    // ---------- imageFileFor：预览区「点击查看」定位缓存图片（下标语义 + 越界安全） ----------

    @Test
    void imageFileForResolvesEachCachedImage() throws IOException {
        PreviewCacheStore store = store();
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        byte[] d1 = "first".getBytes(StandardCharsets.UTF_8);
        byte[] d2 = "second".getBytes(StandardCharsets.UTF_8);
        Path dir = store.write(key, metaFor(key, List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "a.png", "png", List.of()),
                new PreviewCacheMeta.ImageEntry("images/0002.png", "cid:y", "b.png", "png", List.of()))),
                imagesWriter(d1, d2));
        PreviewCacheMeta meta = store.load(key).orElseThrow();

        // 与写盘位置一致：key 由 meta 反推，调用方无需了解目录布局
        assertEquals(dir.resolve("images/0001.png"), store.imageFileFor(meta, 0));
        assertEquals(dir.resolve("images/0002.png"), store.imageFileFor(meta, 1));
        assertArrayEquals(d2, Files.readAllBytes(store.imageFileFor(meta, 1)));

        // 越界 / meta 为 null → null（调用方据此放弃，不抛异常）
        assertNull(store.imageFileFor(meta, -1));
        assertNull(store.imageFileFor(meta, 2));
        assertNull(store.imageFileFor(null, 0));
    }

    // ---------- 并发：写入过程中删除（手工删缓存目录场景） ----------

    @Test
    void concurrentDeleteDuringWriteLeavesNoPartialState() throws Exception {
        Path file = mhtFile();
        PreviewCacheStore store = store();
        PreviewCacheKey key = keyFor(file);

        // 写线程 20 次完整写入（提交 = 改名 + .complete 前删除旧目录）；
        // 删线程 20 次删除同一 key。结束后状态必为「完整命中」或「完全不存在」。
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    store.write(key, metaFor(key, List.of()), imagesWriter(new byte[]{(byte) i}));
                } catch (Exception ignored) {
                    // 删线程在提交窗口删除目录 → 写失败是预期竞争结果
                }
            }
        });
        Thread deleter = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    store.delete(key);
                } catch (Exception ignored) {
                }
            }
        });
        writer.start();
        deleter.start();
        writer.join();
        deleter.join();

        Optional<PreviewCacheMeta> loaded = store.load(key);
        if (loaded.isPresent()) {
            assertTrue(loaded.get().matches(key), "若命中，meta 必须与 key 四要素全等");
        }
        // 无 .tmp 残留
        try (var s = Files.list(store.root())) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "竞争结束后不应残留 .tmp 目录");
        }
    }

    // ---------- totalBytes（会话 12：磁盘占用提醒口径） ----------

    @Test
    void totalBytesSumsImagesAndMeta() throws IOException {
        PreviewCacheStore store = store();
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        byte[] d1 = "12345".getBytes(StandardCharsets.UTF_8);
        byte[] d2 = "1234567890".getBytes(StandardCharsets.UTF_8);
        store.write(key, metaFor(key, List.of(
                new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "a.png", "png", List.of()),
                new PreviewCacheMeta.ImageEntry("images/0002.png", "cid:y", "b.png", "png", List.of()))),
                imagesWriter(d1, d2));

        long metaBytes = Files.size(store.dirFor(key).resolve("meta.json"));
        assertEquals(d1.length + d2.length + metaBytes, store.totalBytes(),
                "口径 = 全部图片 + meta.json（.complete 为空文件）");
    }

    @Test
    void totalBytesIncludesTmpResidue() throws IOException {
        PreviewCacheStore store = store();
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter(new byte[]{1, 2, 3, 4}));
        long before = store.totalBytes();

        // 写入中的 .tmp 目录同样真实占用磁盘 → 计入
        Path tmp = store.root().resolve(key.toDirName() + PreviewCacheStore.TMP_SUFFIX);
        Files.createDirectories(tmp);
        Files.write(tmp.resolve("partial.bin"), new byte[]{5, 6, 7});

        assertEquals(before + 3, store.totalBytes(), ".tmp 残留内容应计入磁盘占用");
    }

    @Test
    void totalBytesZeroWhenRootMissing() throws IOException {
        assertEquals(0, new PreviewCacheStore(tempDir.resolve("never-created")).totalBytes());
    }

    @Test
    void totalBytesDecreasesAfterDelete() throws IOException {
        PreviewCacheStore store = store();
        Path file = mhtFile();
        PreviewCacheKey key = keyFor(file);
        store.write(key, metaFor(key, List.of()), imagesWriter(new byte[]{1, 2, 3, 4}));
        assertTrue(store.totalBytes() > 0);

        store.delete(key);

        assertEquals(0, store.totalBytes(), "删除后磁盘占用应归零");
    }

    // ---------- listKeys / deleteAll ----------

    @Test
    void listKeysAndDeleteAll() throws IOException {
        PreviewCacheStore store = store();
        Path f1 = mhtFile();
        Path f2 = tempDir.resolve("other.mht");
        Files.write(f2, "other".getBytes(StandardCharsets.UTF_8));
        PreviewCacheKey k1 = keyFor(f1);
        PreviewCacheKey k2 = PreviewCacheKey.compute(f2, "c".repeat(64), PreviewCacheMeta.FORMAT_VERSION);
        store.write(k1, metaFor(k1, List.of()), imagesWriter());
        store.write(k2, metaFor(k2, List.of()), imagesWriter());

        List<PreviewCacheKey> keys = store.listKeys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains(k1) && keys.contains(k2));

        assertEquals(2, store.deleteAll());
        assertTrue(store.listKeys().isEmpty());
        assertEquals(0, store.cleanupOrphans());
    }
}
