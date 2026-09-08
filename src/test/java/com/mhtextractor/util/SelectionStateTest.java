package com.mhtextractor.util;

import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.mhtextractor.util.SelectionState.TriState.ALL;
import static com.mhtextractor.util.SelectionState.TriState.NONE;
import static com.mhtextractor.util.SelectionState.TriState.SOME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表复选框三态与列头全选/全不选纯逻辑测试（F1）。
 * 用 SimpleBooleanProperty 模拟项目勾选集合，无需 JavaFX 运行时。
 */
class SelectionStateTest {

    @Test
    void emptyListIsNone() {
        assertEquals(NONE, SelectionState.computeState(0, 0));
    }

    @Test
    void zeroSelectedIsNone() {
        assertEquals(NONE, SelectionState.computeState(0, 5));
    }

    @Test
    void partialSelectionIsSome() {
        assertEquals(SOME, SelectionState.computeState(1, 5));
        assertEquals(SOME, SelectionState.computeState(4, 5));
    }

    @Test
    void allSelectedIsAll() {
        assertEquals(ALL, SelectionState.computeState(5, 5));
    }

    @Test
    void toggleTargetNoneSelectsAll() {
        assertTrue(SelectionState.computeToggleTarget(NONE));
    }

    @Test
    void toggleTargetSomeOrAllClearsSelection() {
        assertFalse(SelectionState.computeToggleTarget(SOME));
        assertFalse(SelectionState.computeToggleTarget(ALL));
    }

    @Test
    void headerClickRoundTrip() {
        List<SimpleBooleanProperty> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(new SimpleBooleanProperty(false));
        }

        // 初始全不选 → 点击列头 → 全选
        assertEquals(NONE, stateOf(items));
        clickHeader(items);
        assertEquals(ALL, stateOf(items));

        // 全选 → 再点列头 → 全不选
        clickHeader(items);
        assertEquals(NONE, stateOf(items));

        // 单项勾选 → 半选 → 点击列头 → 全不选（而非全选）
        items.get(2).set(true);
        assertEquals(SOME, stateOf(items));
        assertFalse(SelectionState.computeToggleTarget(stateOf(items)));
        clickHeader(items);
        assertEquals(NONE, stateOf(items));
    }

    /** 模拟列头点击：先按当前集合算三态与目标值，再一次性应用到所有项目 */
    private static void clickHeader(List<SimpleBooleanProperty> items) {
        boolean target = SelectionState.computeToggleTarget(stateOf(items));
        items.forEach(p -> p.set(target));
    }

    private static SelectionState.TriState stateOf(List<SimpleBooleanProperty> items) {
        int total = items.size();
        int selected = (int) items.stream().filter(SimpleBooleanProperty::get).count();
        return SelectionState.computeState(selected, total);
    }

    // ==================== Shift 区间多选（路线 B） ====================

    @Test
    void shiftRangeForwardAndBackward() {
        List<String> rows = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("b", "c", "d"),
                SelectionState.shiftSelectRange(rows, "b", "d"), "正向区间含两端");
        assertEquals(List.of("b", "c", "d"),
                SelectionState.shiftSelectRange(rows, "d", "b"), "反向区间与方向无关");
    }

    @Test
    void shiftRangeSingleRowWhenAnchorEqualsCurrent() {
        List<String> rows = List.of("a", "b", "c");
        assertEquals(List.of("b"), SelectionState.shiftSelectRange(rows, "b", "b"));
    }

    @Test
    void shiftRangeEmptyWhenAnchorOrCurrentMissing() {
        List<String> rows = List.of("a", "b", "c");
        assertTrue(SelectionState.shiftSelectRange(rows, "x", "b").isEmpty(),
                "锚点不在显示序 → 空");
        assertTrue(SelectionState.shiftSelectRange(rows, "a", "x").isEmpty(),
                "当前行不在显示序 → 空");
        assertTrue(SelectionState.shiftSelectRange(rows, null, "a").isEmpty(),
                "无锚点 → 空");
    }

    @Test
    void rangeAllCheckedJudgement() {
        List<String> range = List.of("a", "b");
        assertTrue(SelectionState.rangeAllChecked(range, o -> true), "全部勾选 → true");
        assertFalse(SelectionState.rangeAllChecked(range, o -> !"b".equals(o)),
                "存在未勾选 → false");
        assertFalse(SelectionState.rangeAllChecked(List.of(), o -> true),
                "空区间 → false（不触发反选）");
    }
}
