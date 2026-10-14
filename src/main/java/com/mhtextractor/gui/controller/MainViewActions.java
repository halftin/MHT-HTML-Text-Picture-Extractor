package com.mhtextractor.gui.controller;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uicore.GroupRow;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.uicore.ParamScope;
import java.util.List;
import javafx.scene.input.MouseEvent;

/** 列表单元格需要的最小行为面（C2）：渲染查询 + 选择/勾选交互 + 拖拽会话。实现 = ListInteractionController。 */
interface MainViewActions {
    // 渲染查询（原 PreviewListCell 的 ctl.xxx）
    boolean isTextMode();
    AppConfig effectiveForObject(Object item);
    ParamScope.Status effectiveStatus(Object item);
    int visibleMemberCount(GroupRow<PreviewRow> group);
    long unitIndex(PreviewRow item);
    boolean isRowSelected(Object row);
    boolean isListAnimating();
    /** 功能一：提取运行中，勾选框不可交互（与动画锁并列）。 */
    boolean isExtractionRunning();
    // 选择 / 勾选交互
    void onCellPressed(Object item, MouseEvent e);
    void onCellDoubleClicked(Object item, MouseEvent e);
    void onGroupCheckToggled(GroupRow<PreviewRow> group);
    /** 勾选唯一写入口（B3-1）：单元格复选框写入也经它，不得直写 PreviewRow.setSelected。 */
    void setChecked(List<PreviewRow> targets, boolean value);
    // 拖拽会话
    boolean isDragActive();
    Object draggedObject();
    List<Object> dataOrder();
    boolean isDropHandled();
    CellIndicator lastDragOverCell();
    void setLastDragOverCell(CellIndicator cell);
    void markDropHandled();
    void beginDrag(PreviewListCell sourceCell);
    boolean performDrop(Object dragged, Object target, DropResolver.Zone zone, PreviewListCell cell);
    void cancelDrag();
}
