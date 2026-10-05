package com.mhtextractor.uiport;

/**
 * 工作区查询（最小切片）：视图模型快照。
 */
public interface WorkspaceQueries {

    /**
     * 工作区视图模型快照（一次性不可变；后续变更经 {@link PortEvents} 送达）。
     *
     * @return 快照；无任何条目时返回空集合而非 null
     */
    WorkspaceVm workspace();
}
