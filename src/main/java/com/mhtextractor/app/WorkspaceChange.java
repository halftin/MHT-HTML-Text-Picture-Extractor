package com.mhtextractor.app;

import com.mhtextractor.model.PreviewItem;

import java.util.List;

/**
 * 一次**成功**的工作区命令所产生的变更（Q4 / P3）—— 「模型发布变更、GUI 作投影」的载荷。
 *
 * <p><b>零 JavaFX</b>：本类型属 {@code app} 层，只依赖 {@code model}（两者均由
 * {@code ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency} 按字节码常量池强制）。</p>
 *
 * <p><b>为什么是"一个 record + 一个 Kind 枚举"，而不是 9 个 sealed 实现</b>
 * （形态取舍，逐条给理由）：</p>
 * <ol>
 *   <li><b>第一阶段 adapter 用全量投影刷新</b>：收到任何变更都只是"调
 *       {@link WorkspaceModel#displayOrder()} 后一次性 {@code setAll}"，因此载荷<b>不需要</b>携带完整数据序
 *       —— 9 个类各自带一份"新数据序"只会是同一份数据的重复表达（也随之产生"以谁为准"的二义性）。</li>
 *   <li><b>调用方真正要问的只有三件事</b>：① 变的是什么语义（{@link Kind}）；② 哪些条目受影响
 *       （{@link #affected()}，供 UI 恢复选中 / 挂接勾选监听 / 失效缓存）；③ 是不是撤销
 *       （{@link Kind#UNDO_APPLIED}）。三者都由本 record 回答。</li>
 *   <li><b>执行约束第 4 条"稳定接口优先用小 record"</b>：造 9 个只有类型没有行为的类，
 *       是"为完备而完备"；一旦某个 Kind 需要额外载荷（例如"被销毁的空组"），
 *       再加<b>可选</b>字段或换形态，比先铺 9 个类再回头删更便宜。</li>
 * </ol>
 *
 * <p><b>{@code affected} 的口径（必须说清，否则调用方会误用）</b>：它是"这次命令<b>直接作用/直接产出</b>的
 * {@link PreviewItem}"，<b>不是</b>"变化后的全部条目"，也<b>不是</b>完整数据序。逐 Kind 的取值：</p>
 * <ul>
 *   <li>{@link Kind#ITEMS_ADDED} —— 真正被加进数据序顶层的条目（按身份去重后实际新增的那些，
 *       与界面勾选监听的挂接面一致）；</li>
 *   <li>{@link Kind#GROUPED} / {@link Kind#UNGROUPED} —— 入组 / 出组的成员（自动建组的新组成员同样是
 *       "入了组"，故 {@code appendGroup} 也归本 Kind）；</li>
 *   <li>{@link Kind#ITEMS_REMOVED} —— 被置 {@code removed=true} 的条目；</li>
 *   <li>{@link Kind#GROUP_REMOVED} —— 被整组移除的组内成员；</li>
 *   <li>{@link Kind#REORDERED} / {@link Kind#MERGED} —— 拖拽中父组可能变化的条目
 *       （{@code WorkspaceModel.DropResult.affected()} 逐字同源）；</li>
 *   <li>{@link Kind#UNDO_APPLIED} —— 该次撤销<b>恢复进数据序</b>的条目（拖拽类为记录里的受影响条目；
 *       撤销导入为<b>空</b>：那一步是把条目移出数据序，没有"恢复进数据序"的条目）；</li>
 *   <li>{@link Kind#CLEARED} —— 空列表（清空后没有"受影响条目"这一说）。</li>
 * </ul>
 *
 * <p><b>列表恒为不可变副本</b>（紧凑构造器里 {@code List.copyOf}）：发布出去的载荷不得被订阅方改写，
 * 否则一个订阅方的写会静默改变后续订阅方看到的事实。</p>
 *
 * @param kind     变更语义（非 null）
 * @param affected 本次命令直接作用/直接产出的条目（不可变；可为空列表，永不为 null）
 */
public record WorkspaceChange(Kind kind, List<PreviewItem> affected) {

    /**
     * 变更语义。
     *
     * <p>只列"模型确实会发布"的九种；每一种都对应一条**成功**命令 —— 失败 / no-op 命令
     * （目标为空、组已不在顶层、原位拖放、不支持的撤销类型）<b>不发布</b>，
     * 因此"收到变更"蕴含"状态真的变了"（见 {@code WorkspaceModelTest} 的
     * {@code failedCommandsPublishNothing} 与 {@code eachCommandPublishesExactlyOnce}）。</p>
     */
    public enum Kind {
        /** 顶层条目新增（导入批次 / 无头添加） */
        ITEMS_ADDED,
        /** 新建组（手工合并为组，或导入自动建组） */
        GROUPED,
        /** 拆分组 */
        UNGROUPED,
        /** 隐藏式移除（F2） */
        ITEMS_REMOVED,
        /** 整组移除（D7） */
        GROUP_REMOVED,
        /** 拖拽重排（顶层位置 / 入组出组） */
        REORDERED,
        /** 拖拽合并两组 */
        MERGED,
        /** 撤销一步（结构类） */
        UNDO_APPLIED,
        /** 清空工作区 */
        CLEARED
    }

    public WorkspaceChange {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不得为 null");
        }
        affected = affected == null ? List.of() : List.copyOf(affected);
    }
}
