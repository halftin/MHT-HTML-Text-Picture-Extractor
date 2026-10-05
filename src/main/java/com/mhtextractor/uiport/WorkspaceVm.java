package com.mhtextractor.uiport;

import java.util.List;
import java.util.Objects;

/**
 * 工作区视图模型快照（一次性不可变 record，自持；不引用任何业务类型）。
 *
 * @param groups        分组列表（含成员，按后端数据序）
 * @param topLevelItems 顶层条目列表（按后端数据序）
 * @param canUndo       是否有可撤销命令
 * @param undoDepth     撤销深度
 * @param running       提取是否在途
 * @param importing     导入是否在途
 * @param selection     勾选三态（{@link TriState}）
 * @param lastStats     最近一次提取统计（null = 尚无提取记录）
 */
public record WorkspaceVm(List<GroupVm> groups, List<ItemVm> topLevelItems,
                          boolean canUndo, int undoDepth, boolean running, boolean importing,
                          TriState selection, StatsVm lastStats) {

    public WorkspaceVm {
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        topLevelItems = List.copyOf(Objects.requireNonNull(topLevelItems, "topLevelItems"));
        Objects.requireNonNull(selection, "selection");
    }
}
