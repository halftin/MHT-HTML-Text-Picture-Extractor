package com.mhtextractor.engine;

import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceParamService} 测试（会话 18 · E2.2）。
 *
 * <p><b>为什么需要它</b>：E2.2 把原先散落在 12 处的"缓存规则 (a)/(c)"调用收敛进本服务。
 * 收敛的前提是<b>守卫语义必须与原先逐点一致</b>：</p>
 * <ul>
 *   <li>{@code imageMode == false} 时规则 (a) <b>不得</b>删除任何缓存
 *       （原代码在每个调用点都有 {@code if (imageMode)} 守卫）；</li>
 *   <li>规则 (c) <b>无</b> imageMode 守卫 —— 条目被移除后其缓存必须删除，与当前模式无关；</li>
 *   <li>空集合不产生副作用（{@code onItemsRemoved} 对空集合本就提前返回 0）。</li>
 * </ul>
 *
 * <p>用 {@code @TempDir} 专属 root，不触碰真实 {@code mht-extractor-preview/}，**零 JavaFX 依赖**
 * （本包由 {@code ArchitectureTest} 强制）。</p>
 */
class WorkspaceParamServiceTest {

    @TempDir
    Path tmpDir;

    private PreviewCacheStore store;
    private WorkspaceParamService service;
    private PreviewItem itemA;
    private PreviewItem itemB;

    @BeforeEach
    void setUp() throws IOException {
        PreviewCacheStore s = new PreviewCacheStore(tmpDir.resolve("preview-root"));
        store = s;
        service = new WorkspaceParamService(new PreviewCacheLifecycle(s));

        Path a = tmpDir.resolve("a.mht");
        Path b = tmpDir.resolve("b.mht");
        Files.writeString(a, "mht-a");
        Files.writeString(b, "mht-b");
        itemA = new PreviewItem(a.toString(), "a.mht", LocalDateTime.now(), tmpDir.toString());
        itemB = new PreviewItem(b.toString(), "b.mht", LocalDateTime.now(), tmpDir.toString());

        writeCache(a);
        writeCache(b);
        assertEquals(2, store.listKeys().size(), "前置：应有 2 个缓存目录");
    }

    private void writeCache(Path mht) throws IOException {
        PreviewCacheKey key = PreviewCacheKey.compute(mht, "fp", PreviewCacheMeta.FORMAT_VERSION);
        PreviewCacheMeta meta = new PreviewCacheMeta(PreviewCacheMeta.FORMAT_VERSION,
                new PreviewCacheMeta.Source(key.absolutePath(), key.size(), key.mtime(),
                        key.extraFingerprint()),
                key.paramFingerprint(), true,
                List.of(new PreviewCacheMeta.ImageEntry("images/0001.png", "cid:x", "x.png",
                        "png", List.of())),
                List.of());
        store.write(key, meta, imagesDir -> Files.writeString(imagesDir.resolve("0001.png"), "fake-png"));
    }

    // ==================== 规则 (a)：参数变化 ====================

    @Test
    void paramsChangedInImageModeDeletesOnlyAffectedItems() throws IOException {
        service.paramsChanged(true, itemA);

        // 只删受影响条目：itemA 的缓存消失，itemB 保留
        assertTrue(store.load(PreviewCacheKey.compute(Path.of(itemA.path), "fp",
                PreviewCacheMeta.FORMAT_VERSION)).isEmpty(), "itemA 缓存应被删除");
        assertFalse(store.load(PreviewCacheKey.compute(Path.of(itemB.path), "fp",
                PreviewCacheMeta.FORMAT_VERSION)).isEmpty(), "itemB 缓存应保留");
    }

    @Test
    void paramsChangedInTextModeDeletesNothing() throws IOException {
        // 原代码每个调用点都有 if (imageMode) 守卫 —— 收敛后必须保持
        service.paramsChanged(false, itemA);
        service.paramsChanged(false, List.of(itemA, itemB));

        assertEquals(2, store.listKeys().size(), "文本模式参数变化不得删除图片预览缓存");
    }

    @Test
    void paramsChangedWithNullItemOrNullIterableIsNoOp() throws IOException {
        service.paramsChanged(false, (PreviewItem) null);
        service.paramsChanged(false, (Iterable<PreviewItem>) null);
        assertEquals(2, store.listKeys().size(), "null 不得产生副作用");
    }

    @Test
    void paramsChangedWithEmptyCollectionIsNoOp() throws IOException {
        service.paramsChanged(true, List.<PreviewItem>of());
        assertEquals(2, store.listKeys().size(), "空集合不得产生副作用");
    }

    @Test
    void paramsChangedAcceptsIterableAndSelectsPaths() throws IOException {
        service.paramsChanged(true, List.of(itemA, itemB));
        assertTrue(store.listKeys().isEmpty(), "批量形态应删除全部受影响条目");
    }

    // ==================== 规则 (c)：条目移除 ====================

    @Test
    void itemsRemovedDeletesRegardlessOfMode() throws IOException {
        // 规则 (c) 没有 imageMode 守卫：移除条目后缓存必须删除
        service.itemsRemoved(List.of(itemA));
        assertEquals(1, store.listKeys().size(), "移除 1 项后应只剩 1 个缓存目录");

        service.itemsRemoved(List.of(itemB));
        assertTrue(store.listKeys().isEmpty(), "再移除 1 项后应清空");
    }

    @Test
    void itemsRemovedWithEmptyOrNullIsNoOp() throws IOException {
        service.itemsRemoved(List.of());
        service.itemsRemoved((Iterable<PreviewItem>) null);
        assertEquals(2, store.listKeys().size(), "空/null 不得产生副作用");
    }

    @Test
    void itemsWithNullPathAreSkippedWithoutThrowing() throws IOException {
        PreviewItem noPath = new PreviewItem(null, "x.mht", LocalDateTime.now(), tmpDir.toString());
        service.itemsRemoved(List.of(noPath, itemA));
        assertEquals(1, store.listKeys().size(), "null path 应被跳过，且仍处理其余条目");
    }
}
