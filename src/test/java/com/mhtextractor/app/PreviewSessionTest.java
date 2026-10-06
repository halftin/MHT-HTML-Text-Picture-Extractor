package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P3-3b-16：{@link PreviewSession} 门面的 1:1 委托对照 —— 创建（{@code create()}）与
 * {@link PreviewCacheLifecycle} 的非空/一致、台阶字节数、启动扫孤儿、已观测字节数与
 * 台阶 hook 的原样转发。零 JavaFX，可在 headless 上下文执行。
 */
class PreviewSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void createBuildsSessionOverFactoryLifecycle() {
        PreviewSession session = PreviewSession.create();
        assertNotNull(session, "create() 必须返回非空会话");
        assertNotNull(session.lifecycle(), "底层生命周期必须非空");
        assertNotNull(session.store(), "存储层必须非空");
        assertSame(session.lifecycle().store(), session.store(),
                "store() 必须与 lifecycle().store() 同一实例");
    }

    @Test
    void createWithRootBuildsOverGivenRoot() {
        Path root = tempDir.resolve("preview");
        PreviewSession session = PreviewSession.create(root);
        assertEquals(root, session.store().root(), "指定根目录必须原样落到存储层");
    }

    @Test
    void sizeAlertStepBytesMatchesLifecycleConstant() {
        assertEquals(PreviewCacheLifecycle.SIZE_ALERT_STEP_BYTES,
                PreviewSession.create().sizeAlertStepBytes(),
                "台阶字节数必须与 PreviewCacheLifecycle.SIZE_ALERT_STEP_BYTES 一致");
    }

    @Test
    void delegatesMirrorLifecycleVerbatim() {
        PreviewSession session = PreviewSession.create(tempDir.resolve("preview"));
        assertEquals(0, session.lastObservedTotalBytes(), "未做任何检查前观测字节数必须为 0");
        assertEquals(0, session.startupCleanup(), "空根目录扫孤儿必须为 0（且不抛）");
        AtomicInteger hooked = new AtomicInteger(-1);
        session.setSizeAlertHook(hooked::set);
        session.setSizeAlertHook(null);
        assertEquals(-1, hooked.get(), "hook 只在跨台阶时触发；此处仅验证注册/卸载不抛");
    }
}
