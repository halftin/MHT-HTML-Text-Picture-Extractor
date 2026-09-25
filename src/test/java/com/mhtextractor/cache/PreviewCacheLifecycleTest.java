package com.mhtextractor.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 2 §2.7 C1/C2/C3/C7/C8 的纯逻辑部分（预览缓存生命周期四条规则 + 每项目写锁）。
 * 全部用 @TempDir 专属 root，不触碰真实 mht-extractor-preview/，不依赖 JavaFX。
 */
class PreviewCacheLifecycleTest {

    @TempDir
    Path tmpDir;

    private Path root;
    private PreviewCacheStore store;
    private PreviewCacheLifecycle lifecycle;
    private Path mhtA;
    private Path mhtB;
    private PreviewCacheKey keyA;
    private PreviewCacheKey keyB;

    @BeforeEach
    void setUp() throws IOException {
        root = tmpDir.resolve("preview-root");
        store = new PreviewCacheStore(root);
        lifecycle = new PreviewCacheLifecycle(store);
        mhtA = tmpDir.resolve("a.mht");
        mhtB = tmpDir.resolve("b.mht");
        Files.writeString(mhtA, "mht-a");
        Files.writeString(mhtB, "mht-b");
        keyA = PreviewCacheKey.compute(mhtA, "fp-A", PreviewCacheMeta.FORMAT_VERSION);
        keyB = PreviewCacheKey.compute(mhtB, "fp-B", PreviewCacheMeta.FORMAT_VERSION);
    }

    private void writeCache(PreviewCacheKey key, Path mht) throws IOException {
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime()),
                key.paramFingerprint(), true,
                List.of(new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "x.png",
                        "png", List.of())),
                List.of());
        store.write(key, meta, imagesDir ->
                Files.writeString(imagesDir.resolve("0001.png"), "fake-png"));
    }

    @Test
    void paramsChangedDeletesMatchingDirsOnly() throws IOException {
        writeCache(keyA, mhtA);
        writeCache(keyB, mhtB);
        assertEquals(2, store.listKeys().size());

        int deleted = lifecycle.onParamsChanged(mhtA.toString());

        assertEquals(1, deleted);
        assertFalse(store.load(keyA).isPresent());
        assertTrue(store.load(keyB).isPresent());
    }

    @Test
    void paramsChangedAlsoDeletesStaleFingerprintDirsOfSameFile() throws IOException {
        writeCache(keyA, mhtA);
        PreviewCacheKey stale = new PreviewCacheKey(keyA.absolutePath(), keyA.size(), keyA.mtime(),
                "fp-OLD", PreviewCacheMeta.FORMAT_VERSION);
        writeCache(stale, mhtA);
        assertEquals(2, store.listKeys().size());

        lifecycle.onParamsChanged(mhtA.toString());

        // 同文件不同指纹的两个目录（含历史残留）都被删
        assertEquals(0, store.listKeys().size());
    }

    @Test
    void extractionCompletedDeletesOnlyThatItem() throws IOException {
        writeCache(keyA, mhtA);
        writeCache(keyB, mhtB);

        int deleted = lifecycle.onExtractionCompleted(mhtB.toString());

        assertEquals(1, deleted);
        assertTrue(store.load(keyA).isPresent());
        assertFalse(store.load(keyB).isPresent());
    }

    @Test
    void itemRemovedBatchDeletesInOneScan() throws IOException {
        writeCache(keyA, mhtA);
        writeCache(keyB, mhtB);

        int deleted = lifecycle.onItemsRemoved(List.of(mhtA.toString(), mhtB.toString()));

        assertEquals(2, deleted);
        assertTrue(store.listKeys().isEmpty());
    }

    @Test
    void deletionIsNoOpWhenNoCacheExists() throws IOException {
        assertEquals(0, lifecycle.onParamsChanged(mhtA.toString()));
        assertEquals(0, lifecycle.onItemRemoved(mhtA.toString()));
        assertEquals(0, lifecycle.onExtractionCompleted(mhtA.toString()));
    }

    @Test
    void pathNormalizationMatchesDifferentPathForms() throws IOException {
        writeCache(keyA, mhtA);
        // key 内部做 toAbsolutePath().normalize()，删除侧同样归一化后按字符串比对
        Path relativeWithDot = Path.of(mhtA.getFileName().toString());
        // 通过「相对形式 + 显式规范化」构造：直接传非规范形态（含 ./ 段）也应命中
        String messy = mhtA.getParent().resolve(".").resolve("a.mht").toString();

        int deleted = lifecycle.onParamsChanged(messy);

        assertEquals(1, deleted);
        assertFalse(store.load(keyA).isPresent());
    }

    @Test
    void shutdownClearsEntireRootAndRunsCallback() throws IOException {
        writeCache(keyA, mhtA);
        writeCache(keyB, mhtB);
        AtomicBoolean called = new AtomicBoolean(false);

        lifecycle.onShutdown(() -> called.set(true));

        assertTrue(called.get());
        assertTrue(Files.isDirectory(root));
        try (var s = Files.list(root)) {
            assertTrue(s.findAny().isEmpty(), "清空后根目录应为空");
        }
    }

    @Test
    void startupCleanupRemovesCrashResidue() throws IOException {
        writeCache(keyA, mhtA);
        Path tmpDirName = root.resolve(keyA.toDirName() + PreviewCacheStore.TMP_SUFFIX);
        Files.createDirectories(tmpDirName);
        Files.writeString(tmpDirName.resolve("x.bin"), "partial");
        // 模拟崩溃：删掉 .complete
        Files.delete(root.resolve(keyA.toDirName()).resolve(PreviewCacheStore.COMPLETE_MARKER));

        int cleaned = lifecycle.startupCleanup();

        assertTrue(cleaned >= 2, "应清理 .tmp 与无 .complete 的目录");
        try (var s = Files.list(root)) {
            assertTrue(s.findAny().isEmpty());
        }
    }

    @Test
    void hasCachedEntriesTrueAfterWriteFalseAfterClear() throws IOException {
        assertFalse(lifecycle.hasCachedEntries());
        writeCache(keyA, mhtA);
        assertTrue(lifecycle.hasCachedEntries());
        store.deleteAll();
        assertFalse(lifecycle.hasCachedEntries());
    }

    @Test
    void hasCachedEntriesTrueForCorruptCompleteDir() throws IOException {
        writeCache(keyA, mhtA);
        Files.writeString(root.resolve(keyA.toDirName()).resolve(PreviewCacheStore.META_FILE_NAME),
                "{ broken json");
        // meta 损坏但有 .complete：listKeys 会抛，但 hasCachedEntries 按根目录条目判断仍为 true
        assertTrue(lifecycle.hasCachedEntries());
        lifecycle.onShutdown(() -> { });
        assertFalse(lifecycle.hasCachedEntries());
    }

    @Test
    void writeLockIsPerPathIdentity() {
        Object l1 = lifecycle.lockFor(mhtA.toString());
        Object l2 = lifecycle.lockFor(mhtA.toString());
        Object l3 = lifecycle.lockFor(mhtB.toString());
        assertSame(l1, l2, "同一路径应返回同一把锁");
        assertNotSame(l1, l3, "不同路径应返回不同锁");
    }

    @Test
    void writeLockSerializesConcurrentWritersPerPath() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                synchronized (lifecycle.lockFor(mhtA.toString())) {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                    }
                    active.decrementAndGet();
                }
                done.countDown();
            }, "lock-test-" + i);
            t.setDaemon(true);
            t.start();
        }
        done.await();
        assertEquals(1, maxActive.get(), "同一路径的写操作必须串行");
    }
}
