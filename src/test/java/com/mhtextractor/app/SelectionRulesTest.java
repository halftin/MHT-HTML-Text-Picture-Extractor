package com.mhtextractor.app;

import com.mhtextractor.util.SelectionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-3b-12：{@link SelectionRules} 与底层 {@link SelectionState} 同参同返对照
 * （含 {@code TriState} 枚举双向映射与 null 透传），保证 1:1 收口不引入语义偏差。
 * 零 JavaFX，可在 headless 上下文执行。
 */
class SelectionRulesTest {

    @Test
    void triStateConstantsMatchUnderlyingOrder() {
        String[] rules = new String[SelectionRules.TriState.values().length];
        String[] underlying = new String[SelectionState.TriState.values().length];
        for (int i = 0; i < rules.length; i++) {
            rules[i] = SelectionRules.TriState.values()[i].name();
        }
        for (int i = 0; i < underlying.length; i++) {
            underlying[i] = SelectionState.TriState.values()[i].name();
        }
        assertEquals(String.join(",", underlying), String.join(",", rules),
                "TriState 常量名与顺序必须一一对应");
    }

    @Test
    void computeStateMatchesUnderlying() {
        for (int selected = 0; selected <= 6; selected++) {
            for (int total = 0; total <= 6; total++) {
                assertEquals(SelectionState.computeState(selected, total).name(),
                        SelectionRules.computeState(selected, total).name(),
                        "(" + selected + "," + total + ")");
            }
        }
    }

    @Test
    void computeToggleTargetMatchesUnderlying() {
        for (SelectionRules.TriState s : SelectionRules.TriState.values()) {
            assertEquals(
                    SelectionState.computeToggleTarget(SelectionState.TriState.valueOf(s.name())),
                    SelectionRules.computeToggleTarget(s), s.name());
        }
        // 区分 true/false 的强断言（控制组：包装返回值取反必须跑红）
        assertTrue(SelectionRules.computeToggleTarget(SelectionRules.TriState.NONE),
                "全不选 → 点击目标为全选（true）");
        assertFalse(SelectionRules.computeToggleTarget(SelectionRules.TriState.SOME),
                "半选 → 点击目标为全不选（false）");
        assertFalse(SelectionRules.computeToggleTarget(SelectionRules.TriState.ALL),
                "全选 → 点击目标为全不选（false）");
        // null 原样透传：与底层直接调用结果一致
        assertEquals(SelectionState.computeToggleTarget(null),
                SelectionRules.computeToggleTarget(null));
    }

    @Test
    void shiftSelectRangeMatchesUnderlying() {
        List<String> rows = List.of("a", "b", "c", "d");
        Object[] anchors = {null, "a", "b", "d", "x"};
        Object[] currents = {null, "a", "c", "d", "x"};
        for (Object anchor : anchors) {
            for (Object current : currents) {
                assertEquals(SelectionState.shiftSelectRange(rows, anchor, current),
                        SelectionRules.shiftSelectRange(rows, anchor, current),
                        anchor + ".." + current);
            }
        }
    }

    @Test
    void rangeAllCheckedMatchesUnderlying() {
        List<String> range = List.of("a", "b");
        List<Predicate<Object>> predicates = List.of(
                o -> true, o -> "b".equals(o), o -> false);
        for (Predicate<Object> p : predicates) {
            assertEquals(SelectionState.rangeAllChecked(range, p),
                    SelectionRules.rangeAllChecked(range, p));
        }
        assertEquals(SelectionState.rangeAllChecked(List.of(), o -> true),
                SelectionRules.rangeAllChecked(List.of(), o -> true), "空区间 → false");
    }
}
