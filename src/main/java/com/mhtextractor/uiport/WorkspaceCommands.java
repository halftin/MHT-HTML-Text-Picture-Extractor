package com.mhtextractor.uiport;

/**
 * 工作区命令（最小切片，P3-3a 诚实化收敛）：撤销、清场。
 *
 * <p>命令语义由后端保证（数据序 / 分组关系 / 撤销命令的唯一所有者是后端模型）；
 * 命令生效后经 {@link PortEvents} 推送变更事件，前端据此刷新投影。</p>
 *
 * <p><b>P3-3a 收敛（诚实化）</b>：P3-2 切片中原有的 {@code setChecked(...)} 与
 * {@code cancelWorkspaceActivity()} 已删除 —— 后端当前不能回答它们：勾选状态现写在
 * {@code model.PreviewItem.selected}（无模型命令入口），在途工作区活动取消由 GUI 编排
 * （{@code parseToken} 代际）。待能力下沉后加回（P3-3b+）。</p>
 */
public interface WorkspaceCommands {

    /** 撤销上一条工作区命令（无可撤销时为 no-op）。 */
    void undo();

    /**
     * 清空工作区数据序（组随数据序一并清空；撤销栈与组号计数器不动 —— 与后端
     * {@code WorkspaceModel#clearItems()} 的既有清场语义一致）。
     */
    void clearItems();
}
