package com.mhtextractor.gui.controller;

/**
 * 列表单元格的"插入指示器"能力（步骤 3 从 {@code MainController} 的私有内部接口外迁为顶层包级接口）。
 *
 * <p><b>为什么外迁</b>：{@code PreviewListCell} 与拖拽落点逻辑需要互相表达"当前悬停的单元格"这一概念
 * （{@code MainController.lastDragOverCell}）。此前两者都是 {@code MainController} 的内部类型，
 * 单元格因此无法离开控制器。外迁后单元格可成为独立顶层类，控制器只保留一个包级字段引用它。</p>
 *
 * <p>非 public：仅 {@code com.mhtextractor.gui.controller} 包内使用。</p>
 */
interface CellIndicator {

    /** 显示"插入到该项之上/之下"的指示线 */
    void showInsertIndicator(boolean top);

    /** 显示"放入该项内部（成组）"的指示 */
    void showIntoIndicator();

    /** 清除所有插入指示 */
    void clearInsertIndicator();
}
