package com.mhtextractor.app;

import com.mhtextractor.baseline.BaselineCases;
import com.mhtextractor.cache.PreviewCacheKey;
import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.cache.PreviewCacheStore;
import com.mhtextractor.cache.SourceFingerprint;
import com.mhtextractor.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // 预览区「点击查看」的定位口径：store.imageFileFor(meta, i) 必须等于 imagesDir/meta.images[i].file
        // —— 缩略图正是从后者解码的；点开若指向别处即为串图（GUI 只按下标调用，不认识目录布局）
        for (int i = 0; i < first.meta().images().size(); i++) {
            assertEquals(first.imagesDir().resolve(first.meta().images().get(i).file()),
                    svc.lifecycle().store().imageFileFor(first.meta(), i),
                    "点击查看定位到的缓存图片必须与缩略图来源一致（下标 " + i + "）");
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

    // ==================== prepare + ensureCachedLocked（P3-3b-2b） ====================

    /** prepare 算键与搬迁前 GUI 口径逐字一致（含 SourceFingerprint 附属指纹）——
     *  落盘目录必须等于按该口径算出的键目录；第二次走同一入口即命中（不重写缓存）。 */
    @Test
    void lockedEntryComputesSameKeyAsGuiAndHits() throws Exception {
        Path mht = fixture("multi-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();
        PreviewService.Prepared prepared = svc.prepare(mht, params);

        // 键算法逐字一致：prepare 的键 = 按搬迁前 PreviewLoader 口径算出的键（六要素全等）
        PreviewCacheKey guiKey = PreviewCacheKey.compute(mht, params.imageExtractionFingerprint(),
                SourceFingerprint.of(mht), PreviewCacheMeta.FORMAT_VERSION);
        assertEquals(guiKey, prepared.cacheKey(), "prepare 的键必须与搬迁前 GUI 口径逐字一致");

        PreviewService.PreviewResult first = svc.ensureCachedLocked(prepared, null);
        assertNotNull(first, "未命中应建立缓存");
        assertFalse(first.meta().images().isEmpty(), "多图夹具应抽出图片");

        // 新入口落盘目录 = 按搬迁前 PreviewLoader 口径算出的键目录
        Path dir = svc.lifecycle().store().dirFor(guiKey);
        assertTrue(Files.exists(dir.resolve(PreviewCacheStore.COMPLETE_MARKER)),
                "新入口必须写到搬迁前口径算出的键目录：" + dir);

        // 命中即返回：.complete 的 mtime 不变（命中路径不重写缓存）
        FileTime afterFirst = Files.getLastModifiedTime(dir.resolve(PreviewCacheStore.COMPLETE_MARKER));
        Thread.sleep(20);
        PreviewService.PreviewResult second = svc.ensureCachedLocked(prepared, null);
        assertNotNull(second);
        assertEquals(first.meta().images().size(), second.meta().images().size());
        assertEquals(afterFirst, Files.getLastModifiedTime(dir.resolve(PreviewCacheStore.COMPLETE_MARKER)),
                "命中路径不应重写缓存目录");
    }

    /** prepare 失败：文件不存在（读不到 size/mtime）→ 抛 IOException，且不产生任何缓存副作用。
     *  搬迁前 GUI 在 FX 线程同步捕获后不进入加载态；服务侧对应的不变量是：
     *  prepare 是纯计算 —— 不建目录、不落盘。 */
    @Test
    void prepareFailureThrowsWithoutCacheSideEffects() throws Exception {
        PreviewService svc = service();
        Path missing = tmp.resolve("no-such-file.mht");

        assertThrows(IOException.class, () -> svc.prepare(missing, new AppConfig()),
                "读不到文件信息必须抛 IOException（GUI 借此在置加载态前失败返回）");
        assertTrue(svc.lifecycle().store().listKeys().isEmpty(),
                "prepare 失败不得产生缓存条目，实际：" + svc.lifecycle().store().listKeys());
    }

    /** 新入口：取消短路（抽图后、写盘前生效，不写缓存）—— 与 2 参入口同一语义。 */
    @Test
    void lockedEntryCancellationSkipsCacheWrite() throws Exception {
        Path mht = fixture("multi-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();
        PreviewService.Prepared prepared = svc.prepare(mht, params);

        PreviewService.PreviewResult r = svc.ensureCachedLocked(prepared, () -> true);
        assertNull(r, "取消时应返回 null（放弃本次预览）");
        assertTrue(svc.lifecycle().store().listKeys().isEmpty(),
                "取消后缓存根不应有可用条目，实际：" + svc.lifecycle().store().listKeys());
    }

    /** 新入口：每项目写锁互不阻塞 —— 测试线程持有 A 的写锁期间，另一个项目 B 走新入口应正常完成。 */
    @Test
    void lockedEntryDoesNotBlockOtherProject() throws Exception {
        Path a = fixture("single-image.mht");
        Path b = fixture("multi-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();
        PreviewService.Prepared preparedB = svc.prepare(b, params);
        Object lockA = svc.lifecycle().lockFor(a.toString());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            synchronized (lockA) {
                Future<PreviewService.PreviewResult> f = exec.submit(
                        () -> svc.ensureCachedLocked(preparedB, null));
                assertNotNull(f.get(5, TimeUnit.SECONDS),
                        "持有 A 的写锁时，项目 B 走新入口不得被阻塞");
            }
        } finally {
            exec.shutdownNow();
        }
    }

    /** 新入口：同项目写锁串行（§5 控制组）—— 测试线程持有该项目写锁期间，同一项目走新入口必须等待；
     *  若把 {@code ensureCachedLocked} 里的 {@code synchronized} 去掉，本用例会跑红。
     *  （先预热缓存，让后续调用只走「命中」快路径，锁定判据只剩锁本身。） */
    @Test
    void lockedEntrySerializesSameProject() throws Exception {
        Path mht = fixture("single-image.mht");
        PreviewService svc = service();
        AppConfig params = new AppConfig();
        PreviewService.Prepared prepared = svc.prepare(mht, params);
        assertNotNull(svc.ensureCachedLocked(prepared, null), "预热：先建立缓存");
        Object lock = svc.lifecycle().lockFor(prepared.cacheKey().absolutePath());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<PreviewService.PreviewResult> f;
            synchronized (lock) {
                f = exec.submit(() -> svc.ensureCachedLocked(prepared, null));
                assertThrows(TimeoutException.class, () -> f.get(500, TimeUnit.MILLISECONDS),
                        "同项目应被写锁串行：测试线程持锁期间新入口必须等待（锁被去掉时本用例跑红）");
            }
            assertNotNull(f.get(5, TimeUnit.SECONDS), "释放锁后应正常完成");
        } finally {
            exec.shutdownNow();
        }
    }
}
