package com.mhtextractor.engine;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.cache.PreviewCacheStore;

import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * 预览会话缓存生命周期门面（P3-3b-16）—— <b>持有</b> {@link PreviewCacheLifecycle}
 * （内部经 {@link PreviewCacheFactory} 构造，与搬迁前 {@code MainController} 字段初始化
 * <b>同一时机、同一参数</b>），把 gui 需要的操作 1:1 委托，使 gui 只命名
 * {@code app.PreviewSession}、不再直连 {@code cache.PreviewCacheLifecycle} 类型。
 *
 * <p>语义一律不变：创建时机、启动扫孤儿（{@link #startupCleanup()}）、10 GiB 台阶提醒 hook
 * （{@link #setSizeAlertHook(IntConsumer)}，签名与底层 Lombok setter 同型以保 gui 调用点逐字不变）、
 * 根目录（{@link #store()}）、已观测字节数（{@link #lastObservedTotalBytes()}）
 * 全部原样转发底层生命周期；缓存<b>删除规则</b>仍只经 {@code WorkspaceParamService} 收口，
 * 本门面不新增任何删除入口。</p>
 *
 * <p><b>零 JavaFX / 零 I18n 依赖</b>：本类只依赖 {@code cache} 业务包，
 * 由 {@code ArchitectureTest} 强制其依赖闭包不含 {@code javafx}。</p>
 */
public final class PreviewSession {

    private final PreviewCacheLifecycle lifecycle;

    private PreviewSession(PreviewCacheLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 生产会话：{@code <java.io.tmpdir>/mht-extractor-preview}（与搬迁前的创建同源同参）。 */
    public static PreviewSession create() {
        return new PreviewSession(PreviewCacheFactory.create());
    }

    /** 指定根目录构造（测试可用，不触碰真实用户缓存目录）。 */
    public static PreviewSession create(Path root) {
        return new PreviewSession(PreviewCacheFactory.create(root));
    }

    /**
     * 底层生命周期（仅供 app 服务构造使用；gui 侧仅以方法调用形式出现，不出现类型名）。
     */
    public PreviewCacheLifecycle lifecycle() {
        return lifecycle;
    }

    /** 启动扫孤儿（P7/P11 崩溃恢复）：1:1 委托 {@link PreviewCacheLifecycle#startupCleanup()}。 */
    public int startupCleanup() {
        return lifecycle.startupCleanup();
    }

    /**
     * 磁盘占用跨台阶提醒 hook：1:1 委托
     * {@link PreviewCacheLifecycle#setSizeAlertHook(IntConsumer)}（底层 Lombok setter 的
     * 确切签名；传 null 卸载）。
     */
    public void setSizeAlertHook(IntConsumer hook) {
        lifecycle.setSizeAlertHook(hook);
    }

    /** 存储层：1:1 委托 {@link PreviewCacheLifecycle#store()}。 */
    public PreviewCacheStore store() {
        return lifecycle.store();
    }

    /** 最近一次台阶检查观测到的总字节数：1:1 委托 {@link PreviewCacheLifecycle#lastObservedTotalBytes()}。 */
    public long lastObservedTotalBytes() {
        return lifecycle.lastObservedTotalBytes();
    }

    /** 磁盘占用提醒台阶字节数（默认 10 GiB）：返回 {@link PreviewCacheLifecycle#SIZE_ALERT_STEP_BYTES}。 */
    public long sizeAlertStepBytes() {
        return PreviewCacheLifecycle.SIZE_ALERT_STEP_BYTES;
    }
}
