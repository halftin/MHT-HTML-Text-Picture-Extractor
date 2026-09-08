package com.mhtextractor.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表复选框三态（F1）：列头全选 CheckBox 的三态计算与点击目标推导；
 * Shift 区间多选（快捷键多选扩展）：按显示序计算锚点行到当前行的区间。
 *
 * <p>本工具只接收纯数据，不感知 GUI 状态。区间计算的"行"为显示序元素
 * （PreviewItem 或 GroupItem，由调用方传入）；removed 项由调用方过滤。</p>
 */
public final class SelectionState {

    private SelectionState() {
    }

    /** 三态：全部选中 / 部分选中 / 全部未选 */
    public enum TriState {
        ALL, SOME, NONE
    }

    /**
     * 由已选数量与有效项总数计算三态。
     *
     * @param selectedCount 已选中数量
     * @param totalCount    有效项总数
     * @return 总数或已选数为 0 时返回 {@link TriState#NONE}；已选数达到总数时返回
     * {@link TriState#ALL}；其余返回 {@link TriState#SOME}
     */
    public static TriState computeState(int selectedCount, int totalCount) {
        if (selectedCount == 0 || totalCount == 0) {
            return TriState.NONE;
        }
        return selectedCount >= totalCount ? TriState.ALL : TriState.SOME;
    }

    /**
     * 列头 CheckBox 点击后的目标勾选值：当前全不选 → 全选（true）；全选或半选 → 全不选（false）。
     */
    public static boolean computeToggleTarget(TriState state) {
        return state == TriState.NONE;
    }

    /**
     * Shift 区间多选：按显示序取锚点行到当前行之间的全部行（含两端）。
     * 锚点或当前行不在显示序中时返回空列表（调用方忽略本次操作）。
     * 区间内含组条目行时由调用方决定是否展开为整组成员（本函数仅返回原始行序列）。
     */
    public static List<Object> shiftSelectRange(List<?> displayOrder, Object anchor, Object current) {
        if (anchor == null || current == null) {
            return List.of();
        }
        int a = displayOrder.indexOf(anchor);
        int b = displayOrder.indexOf(current);
        if (a < 0 || b < 0) {
            return List.of();
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        List<Object> out = new ArrayList<>(hi - lo + 1);
        for (int i = lo; i <= hi; i++) {
            out.add(displayOrder.get(i));
        }
        return out;
    }

    /**
     * Shift 区间反选判定：区间内全部行"已完全勾选"时返回 true（调用方对区间执行取消勾选）。
     * isChecked 由调用方提供（项目行 = 自身勾选；组行 = 全部有效成员已勾选）。
     */
    public static boolean rangeAllChecked(List<?> range, java.util.function.Predicate<Object> isChecked) {
        if (range.isEmpty()) {
            return false;
        }
        for (Object o : range) {
            if (!isChecked.test(o)) {
                return false;
            }
        }
        return true;
    }
}
