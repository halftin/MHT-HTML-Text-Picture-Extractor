package com.mhtextractor.app;

import com.mhtextractor.cache.PreviewCacheLifecycle;
import com.mhtextractor.model.PreviewItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作区缓存失效服务（会话 18 · E2.2）—— {@code com.mhtextractor.app} 的**第一个类**。
 *
 * <p><b>解决什么问题</b>：预览缓存的"四条生命周期规则"原先由 {@code MainController} 在
 * <b>12 处</b>手工调用：5 个参数写入点（{@code applyItemParams}/{@code applyConfirmedParams}/
 * {@code applyGroupParams}/{@code applyGlobalParamsTo}/{@code clearParamsForTargets}）、
 * 2 个移除点（{@code removeItems}/{@code removeGroup}）、以及 {@code onUndoClicked} 的
 * <b>5 个分支</b>（撤销导入/PARAM_SET/GROUP 参数/全局参数/CLEAR_PARAMS）。
 * 这种"每个分支自己记得调一次"的形态正是耦合点 #11 与"分支漏调"类 bug 的温床
 * （{@code docs/全量链路清单.md} 耦合点 #11）。</p>
 *
 * <p><b>本类做什么</b>：把"参数已变 / 条目已移除 → 失效其磁盘预览缓存"收敛为两个具名操作，
 * 调用方不再直接触碰 {@link PreviewCacheLifecycle} 的规则方法。语义与原先<b>逐字一致</b>：
 * 只负责磁盘缓存目录的失效，内存态（{@code cachedText/Thumbs/Meta…}）仍由控制器侧的
 * {@code clearPreviewCache} 负责 —— 两者原本就是互补的两件事，本类刻意不合并它们
 * （合并会改变行为，见 R4）。</p>
 *
 * <p><b>为什么放在 {@code app} 包</b>：本类<b>零 JavaFX 依赖</b>，只依赖
 * {@code cache} 与 {@code model} 两个业务包。放在 {@code app} 是为了让它成为目标 ② 里
 * "无头应用层"的第一块砖，并由 {@code ArchitectureTest} 强制其依赖闭包不含 {@code javafx}。</p>
 *
 * <p><b>不变式</b>：{@code imageMode == false} 时<b>不做任何事</b> —— 这是原代码在每个调用点的
 * 共同守卫（文本模式参数与图片预览缓存无关）。本类把该守卫收敛到内部，避免调用方各自判断时漏写。</p>
 */
public final class WorkspaceParamService {

    private final PreviewCacheLifecycle previewCacheLifecycle;

    public WorkspaceParamService(PreviewCacheLifecycle previewCacheLifecycle) {
        this.previewCacheLifecycle = previewCacheLifecycle;
    }

    /**
     * 规则 (a)：参数已变 → 删除受影响条目的磁盘预览缓存目录。
     *
     * <p>与原先各调用点的行为一致：仅在 {@code imageMode} 为真时执行；集合为空时不产生副作用
     * （{@link PreviewCacheLifecycle#onParamsChangedBatch} 最终委托的 {@code onItemsRemoved}
     * 对空集合本就提前返回 0，故跳过调用与调用空集合<b>语义等价</b>）。</p>
     *
     * <p><b>刻意不提供 {@code List<String>} 重载</b>：它与本重载对 {@code null} 实参有歧义
     * （实测编译报"对 paramsChanged 的引用不明确"）。调用方一律传条目集合，语义更清楚，
     * 也避免调用方自己拼路径时漏做 null 过滤。</p>
     *
     * @param imageMode true = 图片模式（文本模式参数变化不影响图片缓存，直接跳过）
     * @param items     受影响条目（null 与空集合均为 no-op）
     */
    public void paramsChanged(boolean imageMode, Iterable<PreviewItem> items) {
        if (!imageMode || items == null) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (PreviewItem it : items) {
            if (it != null && it.path != null) {
                paths.add(it.path);
            }
        }
        if (!paths.isEmpty()) {
            previewCacheLifecycle.onParamsChangedBatch(paths);
        }
    }

    /** 单条目便捷重载（对应原先的 {@code onParamsChanged(path)}） */
    public void paramsChanged(boolean imageMode, PreviewItem item) {
        if (!imageMode || item == null) {
            return;
        }
        previewCacheLifecycle.onParamsChanged(item.path);
    }

    /**
     * 规则 (c)：条目已从列表移除 → 删除其磁盘预览缓存目录。
     *
     * <p>注意：本规则<b>没有</b> {@code imageMode} 守卫 —— 原代码在移除路径上也无条件调用
     * （移除条目后无论当前是什么模式，其图片缓存都不该保留）。此处保持一致。</p>
     *
     * @param items 被移除的条目
     */
    public void itemsRemoved(Iterable<PreviewItem> items) {
        if (items == null) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (PreviewItem it : items) {
            if (it != null && it.path != null) {
                paths.add(it.path);
            }
        }
        if (!paths.isEmpty()) {
            previewCacheLifecycle.onItemsRemoved(paths);
        }
    }

    // ==================== 只读查询（不含规则语义，纯转发） ====================

    /**
     * 是否还有未清理的预览缓存目录（关窗链的"是否需要提示先清空"判据）。
     *
     * <p>这是**只读查询**，不含任何规则语义；放在本服务是为了让控制器不必直接持有
     * {@link PreviewCacheLifecycle} 的查询面。真正的缓存读写（{@code store()}）将在
     * E4 抽 `PreviewService` 时一并收口 —— 那是预览加载路径的职责，不属于参数/移除规则。</p>
     */
    public boolean hasCachedEntries() {
        return previewCacheLifecycle.hasCachedEntries();
    }
}
