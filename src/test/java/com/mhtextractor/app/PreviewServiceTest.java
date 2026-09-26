package com.mhtextractor.app;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PreviewService} 的无头单测（步骤 2）。
 *
 * <p><b>为什么必须有</b>：把预览缓存业务逻辑从 {@code MainController} 搬进 app 层，若只靠
 * GUI 自动化（{@code test.mode}）验证，等于"新资产没有自己的测试" —— 以后任何一次调用点改动
 * 都得靠跑 GUI 才发现。本类直接驱动服务，<b>零 JavaFX</b>（本包由
 * {@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx} 强制）。</p>
 *
 * <p>覆盖搬迁时最容易出错的三点：① 未命中即落盘、再查即命中；② 参数指纹变化即换 key（不串味）；
 * ③ 取消信号在抽图后、写盘前生效（<b>不写缓存</b>）。</p>
 */
class PreviewServiceTest {

    @TempDir
    Path tmp;

    /** 复制夹具到临时目录并固定 mtime（缓存键含 mtime，固定它才能让"再查即命中"成立） */
    private Path fixture(String name) throws Exception {
        Path src = BaselineCases.FIXTURE_DIR.resolve(name);
        assertTrue(Files.exists(src), "夹具缺失：" + src);
        Path dst = tmp.resolve(name);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(dst, FileTime.fromMillis(BaselineCases.FIXED_MTIME_EPOCH_MILLI));
        return dst;
    }

    private PreviewService service() {
        return new PreviewService(new PreviewCacheLifecycle(new PreviewCacheStore(tmp.resolve("cache"))));
    }

    private Path cacheDir(PreviewService svc, Path mht, AppConfig params) throws Exception {
        PreviewCacheKey key = PreviewCacheKey.compute(mht,
                params.imageExtractionFingerprint(), PreviewCacheMeta.FORMAT_VERSION);
        return svc.lifecycle().store().dirFor(key);
    }

    @Test
    void missThenHitWritesCacheAndMetadata() throws Exception {
        Path mht = fixture("multi-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();

        // 第一次：未命中 → 解析 + 抽图 + 原子落盘
        PreviewService.PreviewResult first = svc.ensureCached(mht, params, null);
        assertNotNull(first, "未命中应建立缓存");
        assertFalse(first.meta().images().isEmpty(), "多图夹具应抽出图片");
        assertEquals(PreviewCacheMeta.FORMAT_VERSION, first.meta().formatVersion());
        assertNotNull(first.meta().warnings(), "warnings 不应为 null");

        // 布局三要素：meta.json + images/ + .complete
        Path dir = cacheDir(svc, mht, params);
        assertTrue(Files.exists(dir.resolve(PreviewCacheStore.META_FILE_NAME)), "应写出 meta.json");
        assertTrue(Files.exists(dir.resolve(PreviewCacheStore.IMAGES_DIR_NAME)), "应写出 images/");
        assertTrue(Files.exists(dir.resolve(PreviewCacheStore.COMPLETE_MARKER)), "应写出 .complete");

        // 每张图都真实落盘，且 meta 记录的路径与实盘一致（store.load 据此校验）
        assertEquals(first.meta().images().size(), first.thumbs().size(), "拇指元数据数应与图片数一致");
        for (PreviewCacheMeta.ImageEntry e : first.meta().images()) {
            assertTrue(Files.exists(first.imagesDir().resolve(e.file())),
                    "meta 记录的图片必须真实存在：" + e.file());
            assertFalse(e.detectedExt() == null || e.detectedExt().isBlank(), "扩展名不应为空");
        }
        // thumbs 只给元数据（pixels 留待渲染层解码）
        for (var t : first.thumbs()) {
            assertNull(t.pixels(), "像素应由渲染层填充，服务不产出像素");
            assertFalse(t.coreName() == null || t.coreName().isBlank(), "coreName 不应为空");
        }

        // 第二次：命中（不重新解析）—— meta 应与首次一致
        PreviewService.PreviewResult second = svc.ensureCached(mht, params, null);
        assertNotNull(second);
        assertEquals(first.meta().images().size(), second.meta().images().size());
        assertEquals(first.meta().paramFingerprint(), second.meta().paramFingerprint());
    }

    @Test
    void paramChangeMissesAndRebuildsUnderDifferentKey() throws Exception {
        Path mht = fixture("single-image.mht");
        PreviewService svc = service();

        AppConfig a = new AppConfig();
        PreviewService.PreviewResult ra = svc.ensureCached(mht, a, null);
        assertNotNull(ra);

        // 改一个参与指纹的字段（区域限定起始标记）→ 指纹变化 → 必为不同缓存目录
        AppConfig b = new AppConfig();
        b.setImageRegionStartMarker("REGION-START-XYZ");
        PreviewService.PreviewResult rb = svc.ensureCached(mht, b, null);
        assertNotNull(rb);

        assertFalse(ra.meta().paramFingerprint().equals(rb.meta().paramFingerprint()),
                "参数变化后指纹必须不同（防止缓存串味）");
        assertFalse(ra.imagesDir().equals(rb.imagesDir()), "不同指纹应落到不同缓存目录");
    }

    @Test
    void cancellationAfterExtractionSkipsCacheWrite() throws Exception {
        Path mht = fixture("multi-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();

        // 取消信号恒为 true：模拟"抽图完成时用户已切走/关闭"
        Supplier<Boolean> cancelled = () -> true;
        PreviewService.PreviewResult r = svc.ensureCached(mht, params, cancelled);

        assertNull(r, "取消时应返回 null（放弃本次预览）");
        assertTrue(svc.lifecycle().store().listKeys().isEmpty(),
                "取消后缓存根不应有可用条目，实际：" + svc.lifecycle().store().listKeys());
    }

    @Test
    void hitPathDoesNotRewriteCache() throws Exception {
        Path mht = fixture("single-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();

        PreviewService.PreviewResult first = svc.ensureCached(mht, params, null);
        assertNotNull(first);
        Path complete = cacheDir(svc, mht, params).resolve(PreviewCacheStore.COMPLETE_MARKER);
        FileTime afterFirst = Files.getLastModifiedTime(complete);

        // 命中路径不应重写缓存（.complete 的 mtime 不变）
        Thread.sleep(20);
        PreviewService.PreviewResult second = svc.ensureCached(mht, params, null);
        assertNotNull(second);
        assertEquals(afterFirst, Files.getLastModifiedTime(complete), "命中路径不应重写缓存目录");
    }
}
