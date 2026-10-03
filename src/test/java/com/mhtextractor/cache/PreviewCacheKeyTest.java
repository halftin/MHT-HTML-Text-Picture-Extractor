package com.mhtextractor.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6.5 缓存键的无头测试：<b>HTML 的图片在另一个文件夹里，只改那张图不会改 HTML 的 size/mtime</b>，
 * 若键仍只看四要素就会命中陈旧缓存。本类证明"键必变"，同时用控制组证明"不变时键必须相同"
 * （否则缓存永不命中，是另一种故障）。
 */
class PreviewCacheKeyTest {

    private static final String FP = "fp";

    @TempDir
    Path tmp;

    private Path htmlDir;
    private Path sidecar;
    private Path html;

    private void setup() throws IOException {
        htmlDir = Files.createDirectories(tmp.resolve("page"));
        html = htmlDir.resolve("page.html");
        Files.writeString(html, "<html><body><img src=\"page_files/a.png\"></body></html>",
                StandardCharsets.UTF_8);
        sidecar = Files.createDirectories(htmlDir.resolve("page_files"));
        Files.write(sidecar.resolve("a.png"), new byte[]{1, 2, 3, 4});
    }

    private PreviewCacheKey key() throws IOException {
        return PreviewCacheKey.compute(html, FP, PreviewCacheMeta.FORMAT_VERSION);
    }

    // ==================== R6.5：唯一有效判据 ====================

    @Test
    void changingOnlyASidecarImageChangesTheKeyEvenWhenHtmlSizeAndMtimeAreUnchanged()
            throws IOException {
        setup();
        long sizeBefore = Files.size(html);
        FileTime mtimeBefore = Files.getLastModifiedTime(html);
        PreviewCacheKey before = key();
        String fingerprintBefore = SourceFingerprint.of(html);

        // 只改侧车里的图片字节（HTML 文件一个字节都不动）
        Files.write(sidecar.resolve("a.png"), new byte[]{9, 9, 9, 9, 9});
        Files.setLastModifiedTime(html, mtimeBefore);        // 显式把 mtime 按回原值（模拟"完全没变"）

        assertEquals(sizeBefore, Files.size(html), "前置条件：HTML 的 size 未变");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(html), "前置条件：HTML 的 mtime 未变");
        PreviewCacheKey after = key();

        assertNotEquals(fingerprintBefore, SourceFingerprint.of(html), "侧车摘要必须随图片字节变化");
        assertNotEquals(before.toDirName(), after.toDirName(),
                "侧车图片变了 ⇒ 缓存目录名必须变（否则命中陈旧缓存，用户看到改动前的图）");
        assertFalse(before.equals(after), "键对象本身也必须不等");
    }

    @Test
    void replacingSidecarFileWithSameSizeButDifferentBytesAlsoChangesTheKey() throws IOException {
        setup();
        PreviewCacheKey before = key();
        Files.write(sidecar.resolve("a.png"), new byte[]{4, 3, 2, 1});   // 同样 4 字节，内容不同
        assertNotEquals(before.toDirName(), key().toDirName(),
                "内容摘要（而非 size 摘要）才能抓住同长度替换");
    }

    @Test
    void addingOrRemovingASidecarFileChangesTheKey() throws IOException {
        setup();
        PreviewCacheKey before = key();
        Files.write(sidecar.resolve("b.png"), new byte[]{7, 7});
        PreviewCacheKey added = key();
        assertNotEquals(before.toDirName(), added.toDirName(), "新增侧车文件 ⇒ 键变");
        Files.delete(sidecar.resolve("b.png"));
        assertEquals(before.toDirName(), key().toDirName(), "删回原状 ⇒ 回到原键（可复算）");
    }

    // ==================== 反向断言：不该变时不能变（否则缓存永不命中） ====================

    @Test
    void sameInputComputesTheSameKeyTwice() throws IOException {
        setup();
        assertEquals(key(), key(), "同样的输入两次算出的键必须相同");
        assertEquals(key().toDirName(), key().toDirName());
        assertEquals(SourceFingerprint.of(html), SourceFingerprint.of(html), "摘要必须可复算");
    }

    @Test
    void mhtSourcesHaveNoExtraFingerprintAndKeepTwoElementKeysEqual() throws IOException {
        Path mht = tmp.resolve("a.mht");
        Files.write(mht, new byte[]{1, 2, 3});
        assertEquals("", SourceFingerprint.of(mht), "MHT 无附属文件 ⇒ 空指纹");
        PreviewCacheKey a = PreviewCacheKey.compute(mht, FP, PreviewCacheMeta.FORMAT_VERSION);
        PreviewCacheKey b = PreviewCacheKey.compute(mht, FP, "", PreviewCacheMeta.FORMAT_VERSION);
        assertEquals("", a.extraFingerprint());
        assertEquals(a, b, "显式传空指纹与内部计算必须等价（三处构造点口径一致）");
        assertEquals(a.toDirName(), b.toDirName());
    }

    @Test
    void htmlWithoutSidecarDirectoryAlsoHasEmptyExtraFingerprint() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("flat"));
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<html></html>", StandardCharsets.UTF_8);
        assertEquals("", SourceFingerprint.of(page), "没有侧车目录 ⇒ 空指纹（与 MHT 同键语义）");
        assertEquals("", SourceFingerprint.of(null));
        assertEquals("", SourceFingerprint.of(dir.resolve("x.txt")), "非 HTML 来源 ⇒ 空指纹");
    }

    // ==================== FORMAT_VERSION 必须递增（否则旧缓存被误命中） ====================

    @Test
    void formatVersionHasBeenBumpedToTwo() {
        assertEquals(2, PreviewCacheMeta.FORMAT_VERSION,
                "R6.5：键语义变了（多了附属指纹、meta 多了 sidecarFingerprint 字段），"
                        + "不递增会让 R6 之前写下的旧缓存被新键误命中");
    }

    // ==================== meta 往返：store 必须能由 meta 反推出一致的 key ====================

    @Test
    void storeRebuildsTheSameKeyFromPersistedMeta() throws IOException {
        setup();
        PreviewCacheStore store = new PreviewCacheStore(tmp.resolve("cache-root"));
        PreviewCacheKey key = key();
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                        key.extraFingerprint()),
                key.paramFingerprint(), true,
                List.of(new PreviewCacheMeta.ImageEntry("images/0001.png", "loc:x", "x.png", "png",
                        List.of())),
                List.of());
        store.write(key, meta, imagesDir -> Files.write(imagesDir.resolve("0001.png"),
                new byte[]{5, 5, 5}));

        assertTrue(store.load(key).isPresent(), "往返后应命中");
        assertEquals(1, store.listKeys().size());
        assertEquals(key, store.listKeys().getFirst(),
                "listKeys 由 meta 反推的键必须与原键全等（删除规则 (c) 依赖这一点）");
        assertEquals(store.dirFor(key).resolve("images/0001.png"),
                store.imageFileFor(meta, 0), "imageFileFor 反推的图片路径必须落在同一目录");
    }

    @Test
    void metaWithDifferentSidecarFingerprintDoesNotMatch() throws IOException {
        setup();
        PreviewCacheKey key = key();
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(), "other-digest"),
                key.paramFingerprint(), true, List.of(), List.of());
        assertFalse(meta.matches(key), "附属指纹不同 ⇒ 不得判定命中");
    }
}
