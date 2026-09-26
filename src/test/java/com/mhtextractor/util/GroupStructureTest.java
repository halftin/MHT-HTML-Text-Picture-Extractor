package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分组结构纯逻辑测试（F5 阶段 4）：显示序构建、组名唯一化、组编号自增、空组检测、结构快照恢复。
 * 用 String 模拟项目对象，无需 JavaFX 运行时。
 */
class GroupStructureTest {

    private static GroupItem<String> group(int no, String name, String... members) {
        GroupItem<String> g = new GroupItem<>(no, name);
        g.getMembers().addAll(List.of(members));
        return g;
    }

    private static final Predicate<Object> NEVER_REMOVED = o -> false;

    @Test
    void flattenExpandedGroupShowsMembers() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> top = new ArrayList<>(List.of("a", g, "b"));
        assertEquals(List.of("a", g, "m1", "m2", "b"), GroupStructure.flatten(top, NEVER_REMOVED));
    }

    @Test
    void flattenCollapsedGroupHidesMembers() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        g.setExpanded(false);
        List<Object> top = new ArrayList<>(List.of("a", g, "b"));
        assertEquals(List.of("a", g, "b"), GroupStructure.flatten(top, NEVER_REMOVED));
    }

    @Test
    void flattenHandlesMultipleGroupsAndMixedOrder() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2");
        GroupItem<String> g2 = group(2, "组2", "m3");
        g2.setExpanded(false);
        List<Object> top = new ArrayList<>(List.of(g1, "x", g2));
        assertEquals(List.of(g1, "m1", "m2", "x", g2), GroupStructure.flatten(top, NEVER_REMOVED));
    }

    @Test
    void flattenFiltersRemovedItems() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> top = new ArrayList<>(List.of("a", g, "b"));
        // m1、b 标记移除：顶层与组内成员均不进入显示序（数据序保留，撤销恢复位置不受影响）
        assertEquals(List.of("a", g, "m2"),
                GroupStructure.flatten(top, o -> o == "m1" || o == "b"));
    }

    @Test
    void expandedDefaultsToTrue() {
        assertTrue(group(1, "组1", "m1").isExpanded());
    }

    @Test
    void uniqueNameReturnsDesiredWhenNoConflict() {
        assertEquals("组1", GroupStructure.uniqueGroupName("组1", List.of("组2", "组3")));
    }

    @Test
    void uniqueNameAppendsSuffixUntilUnique() {
        assertEquals("组1(2)", GroupStructure.uniqueGroupName("组1", List.of("组1")));
        assertEquals("组1(3)", GroupStructure.uniqueGroupName("组1", List.of("组1", "组1(2)")));
        assertEquals("组1(4)", GroupStructure.uniqueGroupName("组1", List.of("组1", "组1(2)", "组1(3)")));
    }

    @Test
    void uniqueNameKeepsBlankAsIs() {
        assertNull(GroupStructure.uniqueGroupName(null, List.of("组1")));
        assertEquals("  ", GroupStructure.uniqueGroupName("  ", List.of("组1")));
    }

    @Test
    void nextGroupNoIncrementsMonotonically() {
        assertEquals(1, GroupStructure.nextGroupNo(0));
        assertEquals(2, GroupStructure.nextGroupNo(1));
        assertEquals(6, GroupStructure.nextGroupNo(5));
    }

    @Test
    void emptyGroupDetection() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        assertFalse(GroupStructure.isEmptyGroup(g, m -> false));
        assertTrue(GroupStructure.isEmptyGroup(g, m -> m.startsWith("m")));
        // 成员列表为空同样视为空组
        assertTrue(GroupStructure.isEmptyGroup(group(2, "组2"), m -> true));
    }

    @Test
    void snapshotAndRestoreRoundTrip() {
        GroupItem<String> g1 = group(1, "组1", "m1", "m2");
        List<Object> top = new ArrayList<>(List.of("a", g1, "b"));
        List<Object> snap = GroupStructure.snapshotStructure(top);

        // 模拟合并为组：m1、m2 移入新组，原组因空销毁
        GroupItem<String> g2 = new GroupItem<>(2, "组2");
        g2.getMembers().addAll(List.of("m1", "m2"));
        g1.getMembers().clear();
        top.remove(g1);
        top.add(g2);

        // 恢复后：顶层顺序与组内成员回到快照状态，组对象复用
        GroupStructure.restoreStructure(top, snap);
        assertEquals(List.of("a", g1, "b"), top);
        assertEquals(List.of("m1", "m2"), g1.getMembers());
        assertSame(g1, top.get(1));
    }

    @Test
    void restoreStructurePreservesTopLevelItemsAndGroupObjects() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        GroupItem<String> g2 = group(2, "组2", "m2");
        List<Object> top = new ArrayList<>(List.of(g1, "x", g2));
        List<Object> snap = GroupStructure.snapshotStructure(top);

        top.clear();
        top.add("z");
        GroupStructure.restoreStructure(top, snap);
        assertEquals(List.of(g1, "x", g2), top);
        assertEquals(List.of("m1"), g1.getMembers());
        assertEquals(List.of("m2"), g2.getMembers());
        assertSame(g1, top.get(0));
        assertSame(g2, top.get(2));
    }

    @Test
    void snapshotDoesNotAliasMemberList() {
        GroupItem<String> g1 = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of(g1));
        List<Object> snap = GroupStructure.snapshotStructure(top);

        // 快照后继续改动原组，快照内容不受影响
        g1.getMembers().add("m2");
        GroupStructure.restoreStructure(top, snap);
        assertEquals(List.of("m1"), g1.getMembers());
    }

    // ==================== 拆分组（F6） ====================

    @Test
    void dissolveGroupMovesMembersToTopLevelInOrder() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> top = new ArrayList<>(List.of("a", g, "b", "c"));

        List<String> members = GroupStructure.dissolveGroup(top, g);

        assertEquals(List.of("m1", "m2", "m3"), members);
        assertEquals(List.of("a", "m1", "m2", "m3", "b", "c"), top);
        assertTrue(g.getMembers().isEmpty());
    }

    @Test
    void dissolveGroupWorksRegardlessOfExpandedState() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        g.setExpanded(false);
        List<Object> top = new ArrayList<>(List.of("a", g, "b"));

        List<String> members = GroupStructure.dissolveGroup(top, g);

        // 折叠状态不影响拆分：成员按组内顺序插入原组位置
        assertEquals(List.of("a", "m1", "m2", "b"), top);
        assertEquals(List.of("m1", "m2"), members);
    }

    @Test
    void dissolveGroupAtListEndPlacesMembersAtEnd() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> top = new ArrayList<>(List.of("a", g));

        GroupStructure.dissolveGroup(top, g);

        assertEquals(List.of("a", "m1", "m2"), top);
    }

    @Test
    void dissolveGroupMissingFromTopLevelIsNoOp() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of("a", "b"));

        List<String> members = GroupStructure.dissolveGroup(top, g);

        assertTrue(members.isEmpty());
        assertEquals(List.of("a", "b"), top);
        assertEquals(List.of("m1"), g.getMembers());
    }

    @Test
    void dissolveGroupKeepsGroupNameAndParamsOnObject() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of(g));

        GroupStructure.dissolveGroup(top, g);

        // 组对象保留组名（参数缓存字段同理随对象保留），供撤销恢复
        assertEquals("组1", g.getName());
    }

    @Test
    void dissolveGroupReturnsDefensiveMemberCopy() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of(g));

        List<String> members = GroupStructure.dissolveGroup(top, g);
        g.getMembers().add("m2");

        // 返回的成员副本不受后续组内变更影响
        assertEquals(List.of("m1"), members);
    }

    // ==================== 组重建（F6 撤销拆分） ====================

    @Test
    void restoreGroupRebuildsGroupAtOriginalIndex() {
        GroupItem<String> g = group(1, "组1", "m1", "m2");
        List<Object> top = new ArrayList<>(List.of("a", g, "b"));
        // 模拟拆分后状态：成员在顶层原组位置，组已移除
        GroupStructure.dissolveGroup(top, g);

        GroupStructure.restoreGroup(top, g, 1, List.of("m1", "m2"));

        assertEquals(List.of("a", g, "b"), top);
        assertEquals(List.of("m1", "m2"), g.getMembers());
        assertSame(g, top.get(1));
    }

    @Test
    void restoreGroupReassignsMembersFromTopLevel() {
        GroupItem<String> g = group(1, "组1", "m1", "m2", "m3");
        List<Object> top = new ArrayList<>(List.of(g));
        GroupStructure.dissolveGroup(top, g);

        // 拆分后成员顺序与组内顺序一致（插入原组位置）
        assertEquals(List.of("m1", "m2", "m3"), top);

        GroupStructure.restoreGroup(top, g, 0, List.of("m1", "m2", "m3"));
        assertEquals(List.of(g), top);
        assertEquals(List.of("m1", "m2", "m3"), g.getMembers());
    }

    @Test
    void restoreGroupClampsIndexToTopLevelSize() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of(g));
        GroupStructure.dissolveGroup(top, g);

        // 组原索引 5 超出当前顶层大小：插入末尾
        GroupStructure.restoreGroup(top, g, 5, List.of("m1"));
        assertEquals(List.of(g), top);
    }

    @Test
    void restoreGroupKeepsGroupNameAndParamsOnObject() {
        GroupItem<String> g = group(1, "组1", "m1");
        List<Object> top = new ArrayList<>(List.of(g));
        GroupStructure.dissolveGroup(top, g);

        GroupStructure.restoreGroup(top, g, 0, List.of("m1"));

        assertEquals("组1", g.getName());
    }

    // ==================== 会话 18 · E1.1：GroupItem 去 JavaFX 后的监听器契约 ====================
    // 原实现是 javafx.beans.property.StringProperty，MainController 用它做"改名后刷新列表"。
    // 替换为自研 ChangeListener 后，必须锁定与 JavaFX 属性**等价**的触发语义，
    // 否则改名不再刷新列表（F8 回归）或刷新过度。

    @Test
    void nameChangeListenerFiresOnActualChange() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        int[] count = {0};
        g.addNameChangeListener(() -> count[0]++);

        g.setName("组2");

        // 与 JavaFX StringProperty 一致：同步触发一次
        assertEquals(1, count[0]);
        assertEquals("组2", g.getName());
    }

    @Test
    void nameChangeListenerDoesNotFireWhenValueUnchanged() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        int[] count = {0};
        g.addNameChangeListener(() -> count[0]++);

        // JavaFX 属性在旧值 equals 新值时不触发监听器 —— 必须保持等价，
        // 否则每次 setName 都会多刷一次列表
        g.setName("组1");

        assertEquals(0, count[0]);
    }

    @Test
    void nameChangeListenerSupportsMultipleAndSurvivesRemovalDuringCallback() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        int[] a = {0};
        int[] b = {0};
        g.addNameChangeListener(() -> a[0]++);
        // 回调内再注册监听器：不应抛 ConcurrentModificationException（实现里遍历副本）
        g.addNameChangeListener(() -> {
            b[0]++;
            g.addNameChangeListener(() -> { });
        });

        g.setName("组2");

        assertEquals(1, a[0]);
        assertEquals(1, b[0]);
    }

    @Test
    void nullNameChangeListenerIsIgnored() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        g.addNameChangeListener(null);
        g.setName("组2");
        assertEquals("组2", g.getName());
    }

    @Test
    void membersListBehavesAsPlainMutableList() {
        // E1.2 依赖点：成员列表从 ObservableList 改为普通 List，
        // 下列操作是 DropResolver / GroupStructure / MainController 实际用到的全部形态
        GroupItem<String> g = new GroupItem<>(1, "组1");

        g.getMembers().addAll(List.of("m1", "m2", "m3"));
        assertEquals(List.of("m1", "m2", "m3"), g.getMembers());

        g.getMembers().remove("m2");                     // DropResolver: remove(Object)
        assertEquals(List.of("m1", "m3"), g.getMembers());

        g.getMembers().add(1, "mX");                     // DropResolver: add(index, E)（含 Math.min 夹取）
        assertEquals(List.of("m1", "mX", "m3"), g.getMembers());

        g.getMembers().remove(0);                        // DropResolver: remove(int)
        assertEquals(List.of("mX", "m3"), g.getMembers());

        assertEquals(1, g.getMembers().indexOf("m3"));   // DropResolver: indexOf
        assertTrue(g.getMembers().contains("mX"));       // DropResolver: contains

        g.getMembers().clear();                          // GroupStructure.dissolveGroup
        assertTrue(g.getMembers().isEmpty());
    }

    @Test
    void expandedIsDefaultTrueAndPlainBoolean() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        // 原 BooleanProperty 默认 true（展开）
        assertTrue(g.isExpanded());
        g.setExpanded(false);
        assertFalse(g.isExpanded());
    }
}
