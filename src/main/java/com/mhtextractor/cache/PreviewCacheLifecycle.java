package com.mhtextractor.cache;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * 预览缓存生命周期（需求 2 §2.2 四条删除规则的集中实现，§2.6(1)「不得散落 if 判断」）：
 *
 * <ul>
 *   <li>规则 (a)：参数变化 → 删该项目缓存目录（{@link #onParamsChanged}）；</li>
 *   <li>规则 (b)：提取完成 → 删该项目缓存目录（{@link #onExtractionCompleted}，会话 10 接线）；</li>
 *   <li>规则 (c)：项目移除 → 删该项目缓存目录（{@link #onItemRemoved}）；</li>
 *   <li>规则 (d)：退出 → 清空整个会话缓存（{@link #onShutdown}）。</li>
 * </ul>
 *
 * <p>删除按「源文件绝对路径」反查：缓存目录名是单向哈希（{@link PreviewCacheKey#toDirName()}），
 * 无法从路径推目录名，因此经 {@link PreviewCacheStore#listKeys()}（从各目录 meta.json 重建键）扫描匹配。
 * 一次批量操作只需一次扫描（{@link #onItemsRemoved}）。
 *
 * <p>另提供每项目写锁（§2.6(6)）：同一绝对路径恒返回同一把锁，不同项目互不阻塞；
 * 供预览写盘与规则删除之间的互斥使用。
 *
 * <p>会话 12 新增磁盘占用提醒（§2.6(9)）：每跨越一个台阶（默认 10GiB）经 hook 提醒一次，
 * 只提醒不做配额限制；具体 UI 呈现由调用方经 {@link #setSizeAlertHook(IntConsumer)} 注册。
 */
@Slf4j
public class PreviewCacheLifecycle {

    /** 磁盘占用提醒台阶（会话 12）：默认每 10 GiB 一级，二进制口径 10 × 1024³ = 10737418240 字节 */
    public static final long SIZE_ALERT_STEP_BYTES = 10L * 1024 * 1024 * 1024;

    private final PreviewCacheStore store;
    /** 实际生效的提醒台阶（默认 10GiB；测试经包可见构造器注入小值，绕过无法真实写 10GiB 的限制） */
    private final long sizeAlertStepBytes;
    /** 已提醒到的台阶序号（0 = 尚未提醒）：CAS 推进，保证同台阶跨线程只提醒一次 */
    private final AtomicLong lastReportedStep = new AtomicLong(0);
    /** 最近一次检查观测到的总字节数（供 UI 换算「当前实际值」；checkSizeAlert 写，hook 内同步读） */
    private volatile long lastObservedTotalBytes = 0;
    /** 跨台阶回调：参数 = 已跨越的台阶序号（默认台阶下 1=10GiB、2=20GiB…）；null = 未注册（只推进台阶） */
    private volatile IntConsumer sizeAlertHook = null;
    /** 每项目写锁注册表（§2.6(6)）：key = 规范化绝对路径 */
    private final ConcurrentHashMap<String, Object> writeLocks = new ConcurrentHashMap<>();

    public PreviewCacheLifecycle(PreviewCacheStore store) {
        this(store, SIZE_ALERT_STEP_BYTES);
    }

    /** 包可见：仅供测试注入小台阶（如 1024 字节）；生产一律走默认 10GiB */
    PreviewCacheLifecycle(PreviewCacheStore store, long sizeAlertStepBytes) {
        this.store = store;
        this.sizeAlertStepBytes = sizeAlertStepBytes;
    }

    public PreviewCacheStore store() {
        return store;
    }

    /** 取某项目的写锁（§2.6(6)）：同一路径恒同一把锁 */
    public Object lockFor(String absolutePath) {
        return writeLocks.computeIfAbsent(normalizePath(absolutePath), p -> new Object());
    }

    /** 规则 (a)：该项目提取参数变化 → 删除该项目全部缓存目录（含历史指纹的残留目录） */
    public int onParamsChanged(String mhtPath) {
        return deleteAllForPath(mhtPath);
    }

    /** 规则 (a) 批量形态：一次扫描删除多个项目的缓存（全局参数变更/应用到全局/清除参数用） */
    public int onParamsChangedBatch(Collection<String> mhtPaths) {
        return onItemsRemoved(mhtPaths);
    }

    /** 规则 (b)：该项目提取完成 → 删除该项目缓存目录（本会话实现，调用点由会话 10 接线） */
    public int onExtractionCompleted(String mhtPath) {
        return deleteAllForPath(mhtPath);
    }

    /** 规则 (c)：项目从列表移除 → 删除该项目缓存目录 */
    public int onItemRemoved(String mhtPath) {
        return deleteAllForPath(mhtPath);
    }

    /** 规则 (c) 批量形态：一次扫描删除多个项目的缓存（removeItems/removeGroup 用） */
    public int onItemsRemoved(Collection<String> mhtPaths) {
        Set<String> abs = new HashSet<>();
        for (String p : mhtPaths) {
            abs.add(normalizePath(p));
        }
        if (abs.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        try {
            for (PreviewCacheKey key : store.listKeys()) {
                if (abs.contains(key.absolutePath())) {
                    deleted += deleteQuietly(key);
                }
            }
        } catch (IOException e) {
            log.warn("扫描缓存键失败（存在 meta 损坏目录时可能发生），本次跳过删除：{}", e.getMessage());
        }
        logIfDeleted(deleted);
        return deleted;
    }

    /**
     * 规则 (d)：退出前清空整个会话缓存（只清本应用专属根目录，绝不触碰 java.io.tmpdir 整体）。
     * 清空完成后回调 afterCleared（放行关窗用）；IO 失败不抛（退出路径不允许因缓存问题阻止关闭）。
     */
    public void onShutdown(Runnable afterCleared) {
        try {
            store.deleteAll();
        } catch (IOException e) {
            log.warn("退出清空预览缓存失败：{}", e.getMessage());
        }
        afterCleared.run();
    }

    /** 启动扫孤儿（P7/P11 崩溃恢复）：删除 .tmp 与无 .complete 的目录；失败不抛（启动路径不因此失败） */
    public int startupCleanup() {
        try {
            return store.cleanupOrphans();
        } catch (IOException e) {
            log.warn("启动清理预览缓存孤儿失败：{}", e.getMessage());
            return 0;
        }
    }

    // ==================== 磁盘占用提醒（会话 12） ====================

    /** 注册磁盘占用提醒回调（会话 12）：跨台阶时在写盘线程同步调用；传 null 卸载。只允许注册一个 */
    public void setSizeAlertHook(IntConsumer hook) {
        this.sizeAlertHook = hook;
    }

    /** 最近一次台阶检查观测到的总字节数（供 UI 换算「当前实际 GiB」；无任何检查时为 0） */
    public long lastObservedTotalBytes() {
        return lastObservedTotalBytes;
    }

    /**
     * 写盘成功后的磁盘占用提醒检查（会话 12）：计数口径 = 缓存根当前真实总字节数（覆盖写不重复计数）。
     * currentStep = totalBytes / 台阶 → 仅当 currentStep 大于已提醒台阶时用 CAS 抢占，成功者提醒一次。
     * 同台阶内重复调用不触发（幂等）；一次跨越多个台阶只触发一次（参数 = 当前台阶序号）；
     * 并发同台阶调用恰好一次触发。totalBytes 失败静默降级（log.warn），绝不打断预览流程。
     */
    public void checkSizeAlert() {
        long total;
        try {
            total = store.totalBytes();
        } catch (IOException e) {
            log.warn("统计预览缓存磁盘占用失败，跳过提醒：{}", e.getMessage());
            return;
        }
        long currentStep = total / sizeAlertStepBytes;
        long prev;
        while ((prev = lastReportedStep.get()) < currentStep) {
            if (lastReportedStep.compareAndSet(prev, currentStep)) {
                lastObservedTotalBytes = total;
                fireSizeAlert((int) currentStep, total);
                return;
            }
        }
    }

    private void fireSizeAlert(int step, long totalBytes) {
        long stepGiB = step * sizeAlertStepBytes / (1024L * 1024 * 1024);
        log.warn("预览缓存磁盘占用提醒：已超过 {} GiB（当前 {} GiB，{}）。仅提醒、不限制使用；关闭程序将自动清空缓存。",
                stepGiB, String.format("%.1f", totalBytes / (1024.0 * 1024 * 1024)), store.root());
        IntConsumer hook = sizeAlertHook;
        if (hook != null) {
            hook.accept(step);
        }
    }

    /**
     * 是否有未清理的缓存（规则 d 弹窗判定）：根目录下存在任何条目即视为有（含 meta 损坏目录，
     * 它同样需要 deleteAll 才能清除）。判不了（IO 失败）时保守返回 true——宁可多弹一次窗清空兜底。
     */
    public boolean hasCachedEntries() {
        if (!Files.isDirectory(store.root())) {
            return false;
        }
        try (Stream<Path> stream = Files.list(store.root())) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    private int deleteAllForPath(String mhtPath) {
        return onItemsRemoved(List.of(mhtPath));
    }

    private int deleteQuietly(PreviewCacheKey key) {
        try {
            return store.delete(key) ? 1 : 0;
        } catch (IOException e) {
            log.warn("删除缓存目录失败：{}（{}）", store.dirFor(key), e.getMessage());
            return 0;
        }
    }

    private void logIfDeleted(int deleted) {
        if (deleted > 0) {
            log.info("预览缓存已删除 {} 个目录（{}）", deleted, store.root());
        }
    }

    private static String normalizePath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
}
