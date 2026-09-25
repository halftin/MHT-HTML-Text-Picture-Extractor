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
 */
@Slf4j
public class PreviewCacheLifecycle {

    private final PreviewCacheStore store;
    /** 每项目写锁注册表（§2.6(6)）：key = 规范化绝对路径 */
    private final ConcurrentHashMap<String, Object> writeLocks = new ConcurrentHashMap<>();

    public PreviewCacheLifecycle(PreviewCacheStore store) {
        this.store = store;
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
