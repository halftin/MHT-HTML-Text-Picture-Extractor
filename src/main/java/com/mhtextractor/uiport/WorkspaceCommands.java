package com.mhtextractor.uiport;

/**
 * 工作区命令（最小切片，P3-3a 诚实化收敛）：撤销、清场。
 *
 * <p>命令语义由后端保证（数据序 / 分组关系 / 撤销命令的唯一所有者是后端模型）；
 * 命令生效后经 {@link PortEvents} 推送变更事件，前端据此刷新投影。</p>
 *
 * <p>后台活动代际、预解析和预览加载由应用层持有。</p>
 */
public interface WorkspaceCommands {

    void cancelWorkspaceActivity();
    void cancelPreviewLoad();
    void startPreparse();
    void startPreparseFor(java.util.List<String> paths);
    void refreshPreparseProgress();
    void loadPreview(String path, boolean largeFileConfirmed);
    void awaitPreviewInFlight(long millis);

    /** 撤销上一条工作区命令（无可撤销时为 no-op）。 */
    void undo();

    /**
     * 清空工作区数据序（组随数据序一并清空；撤销栈与组号计数器不动 —— 与后端
     * {@code WorkspaceModel#clearItems()} 的既有清场语义一致）。
     */
    void clearItems();
}
