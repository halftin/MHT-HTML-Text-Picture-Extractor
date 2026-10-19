package com.mhtextractor.util;

import com.mhtextractor.engine.DropResolver;
import com.mhtextractor.model.GroupItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拖拽落点解析与执行测试（6.6 拖拽规则，阶段 7）：落点 → 语义（入组末尾/组内落点/顶层位置/组间位置/两组合并）、
 * no-op 检测、拖拽致空组销毁、组合并成员顺序保持。用 String 模拟项目对象，无需 JavaFX 运行时。
 */
class DropResolverTest {

    private static GroupItem<String> group(int no, String name, String... members) {
        GroupItem<String> g = new GroupItem<>(no, name);
        g.getMembers().addAll(List.of(members));
        return g;
    }

    private static List<Object> top(Object... entries) {
        return new ArrayList<>(List.of(entries));
    }

    // ==================== 落点解析（项目拖拽） ====================

    @Test
    void resolveItemOntoGroupIntoInsertsAtGroupEnd() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> t = top("a", g, "b");
        DropResolver.Operation op = DropResolver.resolve(t, "a", g, DropResolver.Zone.INTO);
        assertInstanceOf(DropResolver.InsertInto.class, op);
        DropResolver.InsertInto ii = (DropResolver.InsertInto) op;
        assertSame("a", ii.item());
        assertSame(g, ii.group());
        assertEquals(2, ii.memberIndex());
    }

    @Test
    void resolveItemOntoGroupTopBottomGoesToTopLevelAroundGroup() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top("a", g, "b");
        DropResolver.InsertTop up = (DropResolver.InsertTop)
                DropResolver.resolve(t, "a", g, DropResolver.Zone.TOP);
        assertEquals(1, up.index());
        DropResolver.InsertTop down = (DropResolver.InsertTop)
                DropResolver.resolve(t, "a", g, DropResolver.Zone.BOTTOM);
        assertEquals(2, down.index());
    }

    @Test
    void resolveItemOntoMemberRowGivesGroupSlot() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> t = top("a", g);
        DropResolver.InsertInto before = (DropResolver.InsertInto)
                DropResolver.resolve(t, "a", "m2", DropResolver.Zone.TOP);
        assertSame(g, before.group());
        assertEquals(1, before.memberIndex());
        DropResolver.InsertInto after = (DropResolver.InsertInto)
                DropResolver.resolve(t, "a", "m2", DropResolver.Zone.BOTTOM);
        assertEquals(2, after.memberIndex());
    }

    @Test
    void resolveItemOntoTopLevelRowGivesTopLevelSlot() {
        List<Object> t = top("a", "b", "c");
        DropResolver.InsertTop before = (DropResolver.InsertTop)
                DropResolver.resolve(t, "c", "b", DropResolver.Zone.TOP);
        assertEquals(1, before.index());
        DropResolver.InsertTop after = (DropResolver.InsertTop)
                DropResolver.resolve(t, "c", "b", DropResolver.Zone.BOTTOM);
        assertEquals(2, after.index());
    }

    // ==================== 落点解析（组条目拖拽） ====================

    @Test
    void resolveGroupOntoGroupTopBottomMovesWholeGroup() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "m2");
        List<Object> t = top("a", g1, g2);
        DropResolver.MoveGroup up = (DropResolver.MoveGroup)
                DropResolver.resolve(t, g1, g2, DropResolver.Zone.TOP);
        assertSame(g1, up.group());
        assertEquals(2, up.index());
        DropResolver.MoveGroup down = (DropResolver.MoveGroup)
                DropResolver.resolve(t, g1, g2, DropResolver.Zone.BOTTOM);
        assertEquals(3, down.index());
    }

    @Test
    void resolveGroupOntoGroupIntoMergesAtTargetEnd() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "m2", "m3");
        List<Object> t = top(g1, g2);
        DropResolver.MergeInto mi = (DropResolver.MergeInto)
                DropResolver.resolve(t, g1, g2, DropResolver.Zone.INTO);
        assertSame(g1, mi.dragged());
        assertSame(g2, mi.target());
        assertEquals(2, mi.memberIndex());
    }

    @Test
    void resolveGroupOntoMemberRowMergesAtSlot() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "m2", "m3");
        List<Object> t = top(g1, g2);
        DropResolver.MergeInto before = (DropResolver.MergeInto)
                DropResolver.resolve(t, g1, "m2", DropResolver.Zone.TOP);
        assertSame(g2, before.target());
        assertEquals(0, before.memberIndex());
        DropResolver.MergeInto after = (DropResolver.MergeInto)
                DropResolver.resolve(t, g1, "m3", DropResolver.Zone.BOTTOM);
        assertEquals(2, after.memberIndex());
    }

    @Test
    void resolveGroupOntoTopLevelRowMovesWholeGroup() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top("a", "b", g);
        DropResolver.MoveGroup up = (DropResolver.MoveGroup)
                DropResolver.resolve(t, g, "b", DropResolver.Zone.TOP);
        assertEquals(1, up.index());
        DropResolver.MoveGroup down = (DropResolver.MoveGroup)
                DropResolver.resolve(t, g, "a", DropResolver.Zone.BOTTOM);
        assertEquals(1, down.index());
    }

    // ==================== 落点解析（非法组合） ====================

    @Test
    void resolveRejectsGroupOntoOwnRowOrOwnMembers() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> t = top("a", g);
        for (DropResolver.Zone z : DropResolver.Zone.values()) {
            assertNull(DropResolver.resolve(t, g, g, z), "拖到自身组行应拒绝：" + z);
        }
        assertNull(DropResolver.resolve(t, g, "m1", DropResolver.Zone.TOP), "拖到自身成员行应拒绝");
        assertNull(DropResolver.resolve(t, g, "m2", DropResolver.Zone.BOTTOM), "拖到自身成员行应拒绝");
    }

    @Test
    void resolveRejectsItemOntoItself() {
        List<Object> t = top("a", "b");
        assertNull(DropResolver.resolve(t, "a", "a", DropResolver.Zone.TOP));
        assertNull(DropResolver.resolve(t, "a", "a", DropResolver.Zone.BOTTOM));
        assertNull(DropResolver.resolve(t, null, "a", DropResolver.Zone.TOP));
    }

    @Test
    void resolveRejectsTargetMissingFromStructure() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top("a", g);
        assertNull(DropResolver.resolve(t, "a", "x", DropResolver.Zone.TOP));
        assertNull(DropResolver.resolve(t, "a", "n1", DropResolver.Zone.BOTTOM));
    }

    // ==================== 执行：项目顶层移动 / 出组 / 入组 ====================

    @Test
    void applyInsertTopMovesItemDownward() {
        List<Object> t = top("a", "b", "c", "d");
        DropResolver.Operation op = DropResolver.resolve(t, "a", "c", DropResolver.Zone.BOTTOM);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top("b", "c", "a", "d"), t);
    }

    @Test
    void applyInsertTopMovesItemUpward() {
        List<Object> t = top("a", "b", "c");
        DropResolver.Operation op = DropResolver.resolve(t, "c", "a", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top("c", "a", "b"), t);
    }

    @Test
    void applyInsertTopSamePositionIsNoOp() {
        List<Object> t = top("a", "b", "c");
        assertFalse(DropResolver.apply(t,
                DropResolver.resolve(t, "a", "b", DropResolver.Zone.TOP), m -> false));
        assertFalse(DropResolver.apply(t,
                DropResolver.resolve(t, "b", "a", DropResolver.Zone.BOTTOM), m -> false));
        assertEquals(top("a", "b", "c"), t);
    }

    @Test
    void applyInsertTopPullsMemberOutToTopLevel() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> t = top("a", g, "b");
        DropResolver.Operation op = DropResolver.resolve(t, "m2", "b", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top("a", g, "m2", "b"), t);
        assertEquals(List.of("m1", "m3"), g.getMembers());
    }

    @Test
    void applyInsertTopDestroysEmptiedSourceGroup() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top("a", g, "b");
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "b", DropResolver.Zone.BOTTOM);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top("a", "b", "m1"), t);
        assertTrue(g.getMembers().isEmpty());
    }

    @Test
    void applyInsertTopDestroysEmptiedSourceGroupBeforeTargetWithIndexFix() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top(g, "x", "y");
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "y", DropResolver.Zone.BOTTOM);
        assertTrue(DropResolver.apply(t, op, m -> false));
        // 源组销毁使插入位置前移，m1 落在 y 之后
        assertEquals(top("x", "y", "m1"), t);
    }

    @Test
    void applyInsertTopKeepsSourceGroupWithRemainingMembers() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> t = top(g, "x");
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "x", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top(g, "m1", "x"), t);
        assertEquals(List.of("m2"), g.getMembers());
    }

    @Test
    void applyInsertTopUsesRemovedPredicateForEmptyGroupDetection() {
        // removed 谓词视为"已移除"的成员不阻止空组销毁判定：全 removed → 销毁
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> t = top(g, "x");
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "x", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> true));
        assertEquals(top("m1", "x"), t);
    }

    @Test
    void applyInsertIntoMovesItemDownwardWithinGroup() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> t = top(g);
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "m3", DropResolver.Zone.BOTTOM);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(List.of("m2", "m3", "m1"), g.getMembers());
    }

    @Test
    void applyInsertIntoMovesItemUpwardWithinGroup() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> t = top(g);
        DropResolver.Operation op = DropResolver.resolve(t, "m3", "m1", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(List.of("m3", "m1", "m2"), g.getMembers());
    }

    @Test
    void applyInsertIntoSamePositionIsNoOp() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> t = top(g);
        assertFalse(DropResolver.apply(t,
                DropResolver.resolve(t, "m1", "m2", DropResolver.Zone.TOP), m -> false));
        assertFalse(DropResolver.apply(t,
                DropResolver.resolve(t, "m2", "m1", DropResolver.Zone.BOTTOM), m -> false));
        assertEquals(List.of("m1", "m2"), g.getMembers());
    }

    @Test
    void applyInsertIntoMovesItemAcrossGroups() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2");
        GroupItem<String> g2 = group(2, "组2", "n1", "n2");
        List<Object> t = top(g1, g2);
        DropResolver.Operation op = DropResolver.resolve(t, "m1", "n2", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(List.of("m2"), g1.getMembers());
        assertEquals(List.of("n1", "m1", "n2"), g2.getMembers());
        assertEquals(top(g1, g2), t);
    }

    @Test
    void applyInsertIntoDestroysEmptiedSourceGroup() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1, g2);
        DropResolver.Operation op = DropResolver.resolve(t, "m1", g2, DropResolver.Zone.INTO);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top(g2), t);
        assertEquals(List.of("n1", "m1"), g2.getMembers());
    }

    @Test
    void applyInsertTopFromTopLevelIntoGroupViaGroupRow() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> t = top("a", g);
        DropResolver.Operation op = DropResolver.resolve(t, "a", g, DropResolver.Zone.INTO);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top(g), t);
        assertEquals(List.of("m1", "a"), g.getMembers());
    }

    @Test
    void applyInsertTopMissingItemIsFalse() {
        List<Object> t = top("a", "b");
        assertFalse(DropResolver.apply(t, new DropResolver.InsertTop("x", 0), m -> false));
        assertEquals(top("a", "b"), t);
    }

    // ==================== 执行：整组移动 / 两组合并 ====================

    @Test
    void applyMoveGroupMovesGroupWithMembers() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1, "x", g2);
        DropResolver.Operation op = DropResolver.resolve(t, g1, g2, DropResolver.Zone.BOTTOM);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top("x", g2, g1), t);
        assertEquals(List.of("m1", "m2"), g1.getMembers());
    }

    @Test
    void applyMoveGroupSamePositionIsNoOp() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1, "x", g2);
        assertFalse(DropResolver.apply(t,
                DropResolver.resolve(t, g2, "x", DropResolver.Zone.BOTTOM), m -> false));
        assertEquals(top(g1, "x", g2), t);
    }

    @Test
    void applyMergeAppendsMembersInOriginalOrderAndDestroysDraggedGroup() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2", "m3");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1, g2);
        DropResolver.Operation op = DropResolver.resolve(t, g1, g2, DropResolver.Zone.INTO);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(top(g2), t);
        assertEquals(List.of("n1", "m1", "m2", "m3"), g2.getMembers());
        assertTrue(g1.getMembers().isEmpty());
    }

    @Test
    void applyMergeInsertsMembersAtTargetSlot() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2");
        GroupItem<String> g2 = group(2, "组2", "n1", "n2");
        List<Object> t = top(g1, g2);
        DropResolver.Operation op = DropResolver.resolve(t, g1, "n2", DropResolver.Zone.TOP);
        assertTrue(DropResolver.apply(t, op, m -> false));
        assertEquals(List.of("n1", "m1", "m2", "n2"), g2.getMembers());
        assertEquals(top(g2), t);
    }

    @Test
    void applyMergeKeepsTargetGroupNameAndRejectsSelfMerge() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1, g2);
        assertTrue(DropResolver.apply(t,
                DropResolver.resolve(t, g1, g2, DropResolver.Zone.INTO), m -> false));
        assertEquals("组2", g2.getName());
        assertFalse(DropResolver.apply(t, new DropResolver.MergeInto(g2, g2, 0), m -> false));
        assertEquals(top(g2), t);
    }

    @Test
    void applyMergeWithMissingGroupsIsFalse() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "n1");
        List<Object> t = top(g1);
        assertFalse(DropResolver.apply(t, new DropResolver.MergeInto(g1, g2, 0), m -> false));
        assertFalse(DropResolver.apply(t, new DropResolver.MergeInto(g2, g1, 0), m -> false));
        assertEquals(top(g1), t);
        assertEquals(List.of("m1"), g1.getMembers());
    }

    // ==================== 落点分区 zoneFor（步骤 3 从 MainController.dropZone 外迁） ====================
    //
    // 为什么值得一组测试：它原本是 GUI 控制器的私有方法，只能靠真实拖拽动作间接验证；
    // 外迁为纯几何函数后，可以把边界值逐条钉死。

    /** 组行分三区：上边缘 → TOP；中间带 → INTO（入组/合并）；下边缘 → BOTTOM */
    @Test
    void zoneForGroupRowSplitsIntoThreeBands() {
        GroupItem<String> g = group(1, "组1", "m1");
        double h = 100.0;
        double edge = 0.25;   // 与 MainController.GROUP_ROW_EDGE_ZONE 同值
        assertEquals(DropResolver.Zone.TOP, DropResolver.zoneFor(g, 0.0, h, edge));
        assertEquals(DropResolver.Zone.TOP, DropResolver.zoneFor(g, 24.9, h, edge));
        assertEquals(DropResolver.Zone.INTO, DropResolver.zoneFor(g, 25.0, h, edge));
        assertEquals(DropResolver.Zone.INTO, DropResolver.zoneFor(g, 50.0, h, edge));
        assertEquals(DropResolver.Zone.INTO, DropResolver.zoneFor(g, 74.9, h, edge));
        assertEquals(DropResolver.Zone.BOTTOM, DropResolver.zoneFor(g, 75.0, h, edge));
        assertEquals(DropResolver.Zone.BOTTOM, DropResolver.zoneFor(g, 100.0, h, edge));
    }

    /** 项目行只分两区：上半 → TOP，下半 → BOTTOM；且**永不**返回 INTO */
    @Test
    void zoneForItemRowOnlyTopOrBottom() {
        double h = 100.0;
        assertEquals(DropResolver.Zone.TOP, DropResolver.zoneFor("item", 0.0, h, 0.25));
        assertEquals(DropResolver.Zone.TOP, DropResolver.zoneFor("item", 49.9, h, 0.25));
        assertEquals(DropResolver.Zone.BOTTOM, DropResolver.zoneFor("item", 50.0, h, 0.25));
        assertEquals(DropResolver.Zone.BOTTOM, DropResolver.zoneFor("item", 100.0, h, 0.25));
    }

    /** edgeRatio 必须是参数（不再硬编码 0.25）：调大后 INTO 带收窄 */
    @Test
    void zoneForHonoursConfigurableEdgeRatio() {
        GroupItem<String> g = group(1, "组1", "m1");
        double h = 100.0;
        // edge=0.4 → 0..40 TOP、40..60 INTO、60..100 BOTTOM
        assertEquals(DropResolver.Zone.TOP, DropResolver.zoneFor(g, 39.9, h, 0.4));
        assertEquals(DropResolver.Zone.INTO, DropResolver.zoneFor(g, 40.0, h, 0.4));
        assertEquals(DropResolver.Zone.INTO, DropResolver.zoneFor(g, 59.9, h, 0.4));
        assertEquals(DropResolver.Zone.BOTTOM, DropResolver.zoneFor(g, 60.0, h, 0.4));
    }
}
