package com.mhtextractor.uiport;

import java.util.List;
import java.util.Objects;

/**
 * 工作区视图模型快照（一次性不可变 record，自持；不引用任何业务类型）。
 *
 * <p><b>P3-3a 收敛（诚实化）</b>：P3-2 切片中原有的 {@code running} / {@code importing} /
 * {@code lastStats} 已删除 —— 后端当前不能回答它们（提取/导入编排与统计仍在 GUI）。
 * 待能力下沉后加回（P3-3b+）。</p>
 *
 * @param groups        分组列表（含成员，按后端数据序）
 * @param topLevelItems 顶层条目列表（按后端数据序）
 * @param canUndo       是否有可撤销命令
 * @param undoDepth     撤销深度
 * @param selection     勾选三态（{@link TriState}，从条目勾选状态导出）
 */
public record WorkspaceVm(List<GroupVm> groups, List<ItemVm> topLevelItems,
                          boolean canUndo, int undoDepth, TriState selection, List<TargetId> topLevelOrder) {

    public WorkspaceVm(List<GroupVm> groups, List<ItemVm> items, boolean canUndo, int undoDepth, TriState selection) {
        this(groups, items, canUndo, undoDepth, selection,
                java.util.stream.Stream.concat(groups.stream().map(g -> TargetId.ofGroup(g.no())),
                        items.stream().map(i -> TargetId.ofItem(i.id()))).toList());
    }

    public WorkspaceVm {
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        topLevelItems = List.copyOf(Objects.requireNonNull(topLevelItems, "topLevelItems"));
        Objects.requireNonNull(selection, "selection");
        topLevelOrder = List.copyOf(topLevelOrder);
    }
}
