package com.mhtextractor.uiport;

import java.util.List;

/**
 * 工作区命令（最小切片）：撤销、清场、勾选、取消在途活动。
 *
 * <p>命令语义由后端保证（数据序 / 分组关系 / 撤销命令的唯一所有者是后端模型）；
 * 命令生效后经 {@link PortEvents} 推送变更事件，前端据此刷新投影。</p>
 */
public interface WorkspaceCommands {

    /** 撤销上一条工作区命令（无可撤销时为 no-op）。 */
    void undo();

    /** 清空工作区（数据序、分组、撤销链一并重置）。 */
    void clearItems();

    /**
     * 批量设置勾选状态（提取输入事实；复选框的展示语义属前端）。
     *
     * @param itemIds 条目 id（路径）
     * @param checked 目标勾选状态
     */
    void setChecked(List<String> itemIds, boolean checked);

    /** 取消在途的工作区活动（预解析 / 预览加载等非提取活动；提取取消属后续批次端口能力）。 */
    void cancelWorkspaceActivity();
}
