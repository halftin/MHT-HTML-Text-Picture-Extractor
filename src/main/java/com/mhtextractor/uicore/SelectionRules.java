package com.mhtextractor.uicore;

import java.util.List;
import java.util.function.Predicate;

/**
 * 列表复选框三态 / Shift 区间多选的规则入口（P3-3b-12）：{@code gui} 对 {@code SelectionState}
 * 的全部静态调用经本类 1:1 收口 —— 三态枚举与四静态方法的参数顺序、返回语义与底层完全一致
 * （只搬不改，枚举间双向映射）。
 *
 * <p>本类<b>零 JavaFX、零 I18n</b>，属 {@code uicore} 层。</p>
 */
public final class SelectionRules {

    private SelectionRules() {
    }

    /** 三态：全部选中 / 部分选中 / 全部未选 —— 常量名与顺序和 {@link SelectionState.TriState} 一一对应 */
    public enum TriState {
        ALL, SOME, NONE
    }

    /**
     * 由已选数量与有效项总数计算三态：1:1 转发 {@link SelectionState#computeState}。
     *
     * @param selected 已选中数量
     * @param total    有效项总数
     * @return 总数或已选数为 0 时返回 {@link TriState#NONE}；已选数达到总数时返回
     * {@link TriState#ALL}；其余返回 {@link TriState#SOME}
     */
    public static TriState computeState(int selected, int total) {
        return toRules(SelectionState.computeState(selected, total));
    }

    /**
     * 列头 CheckBox 点击后的目标勾选值：当前全不选 → 全选（true）；全选或半选 → 全不选（false）。
     * 1:1 转发 {@link SelectionState#computeToggleTarget}（null 原样透传，与底层一致）。
     */
    public static boolean computeToggleTarget(TriState state) {
        return SelectionState.computeToggleTarget(toUnderlying(state));
    }

    /**
     * Shift 区间多选：按显示序取锚点行到当前行之间的全部行（含两端）。
     * 1:1 转发 {@link SelectionState#shiftSelectRange}（不涉及枚举映射）。
     */
    public static List<Object> shiftSelectRange(List<?> order, Object anchor, Object current) {
        return SelectionState.shiftSelectRange(order, anchor, current);
    }

    /**
     * Shift 区间反选判定：区间内全部行"已完全勾选"时返回 true。
     * 1:1 转发 {@link SelectionState#rangeAllChecked}（不涉及枚举映射）。
     */
    public static boolean rangeAllChecked(List<?> range, Predicate<Object> isChecked) {
        return SelectionState.rangeAllChecked(range, isChecked);
    }

    private static TriState toRules(SelectionState.TriState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case ALL -> TriState.ALL;
            case SOME -> TriState.SOME;
            case NONE -> TriState.NONE;
        };
    }

    private static SelectionState.TriState toUnderlying(TriState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case ALL -> SelectionState.TriState.ALL;
            case SOME -> SelectionState.TriState.SOME;
            case NONE -> SelectionState.TriState.NONE;
        };
    }
}
