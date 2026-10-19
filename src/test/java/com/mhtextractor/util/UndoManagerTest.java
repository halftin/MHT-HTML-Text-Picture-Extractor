package com.mhtextractor.util;

import com.mhtextractor.engine.UndoManager;
import com.mhtextractor.model.GroupItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销栈纯逻辑测试（F4）：LIFO 弹出、深度 35 丢弃最旧、记录类型与项目引用保持。
 */
class UndoManagerTest {

    @Test
    void emptyInitially() {
        UndoManager<String> m = new UndoManager<>();
        assertTrue(m.isEmpty());
        assertEquals(0, m.size());
        assertNull(m.pop());
    }

    @Test
    void popReturnsLatestRecordFirst() {
        UndoManager<String> m = new UndoManager<>();
        m.push(UndoManager.RecordType.IMPORT, List.of("a"));
        m.push(UndoManager.RecordType.REMOVE, List.of("b", "c"));

        UndoManager.Record<String> r1 = m.pop();
        assertEquals(UndoManager.RecordType.REMOVE, r1.type());
        assertEquals(List.of("b", "c"), r1.items());
        UndoManager.Record<String> r2 = m.pop();
        assertEquals(UndoManager.RecordType.IMPORT, r2.type());
        assertEquals(List.of("a"), r2.items());
        assertTrue(m.isEmpty());
    }

    @Test
    void defaultDepthIs35AndDropsOldest() {
        UndoManager<String> m = new UndoManager<>();
        for (int i = 0; i < 40; i++) {
            m.push(UndoManager.RecordType.REMOVE, List.of("item" + i));
        }
        assertEquals(UndoManager.DEFAULT_LIMIT, m.size());
        // 最旧的 5 条（item0..item4）已被丢弃：弹出到底后应回到 item5
        UndoManager.Record<String> last = null;
        while (!m.isEmpty()) {
            last = m.pop();
        }
        assertEquals(List.of("item5"), last.items());
    }

    @Test
    void customLimitDropsOldestInOrder() {
        UndoManager<String> m = new UndoManager<>(2);
        m.push(UndoManager.RecordType.REMOVE, List.of("a"));
        m.push(UndoManager.RecordType.IMPORT, List.of("b"));
        m.push(UndoManager.RecordType.REMOVE, List.of("c"));

        assertEquals(2, m.size());
        assertEquals(List.of("c"), m.pop().items());
        assertEquals(List.of("b"), m.pop().items());
        assertTrue(m.isEmpty());
    }

    @Test
    void clearEmptiesStack() {
        UndoManager<String> m = new UndoManager<>();
        m.push(UndoManager.RecordType.IMPORT, List.of("a"));
        m.push(UndoManager.RecordType.REMOVE, List.of("b"));
        m.clear();
        assertTrue(m.isEmpty());
        assertNull(m.pop());
    }

    @Test
    void recordKeepsItemReferencesAndType() {
        List<String> items = List.of("x", "y");
        UndoManager<String> m = new UndoManager<>();
        m.push(UndoManager.RecordType.REMOVE, items);

        UndoManager.Record<String> r = m.pop();
        assertEquals(UndoManager.RecordType.REMOVE, r.type());
        assertEquals(items, r.items());
    }

    @Test
    void simpleRecordHasEmptyGroupFields() {
        UndoManager<String> m = new UndoManager<>();
        m.push(UndoManager.RecordType.REMOVE, List.of("a"));

        UndoManager.Record<String> r = m.pop();
        assertNull(r.group());
        assertEquals(-1, r.groupIndex());
        assertTrue(r.destroyedGroups().isEmpty());
        assertTrue(r.destroyedIndexes().isEmpty());
        assertTrue(r.structureBefore().isEmpty());
    }

    @Test
    void groupRecordKeepsFullPayload() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        List<Object> before = List.of("a", "b");
        UndoManager<String> m = new UndoManager<>();
        m.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP, List.of("x"),
                g, 1, List.of(), List.of(), before));

        UndoManager.Record<String> r = m.pop();
        assertEquals(UndoManager.RecordType.GROUP, r.type());
        assertEquals(List.of("x"), r.items());
        assertSame(g, r.group());
        assertEquals(1, r.groupIndex());
        assertEquals(before, r.structureBefore());
    }

    @Test
    void removeGroupRecordKeepsGroupAndIndex() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        UndoManager<String> m = new UndoManager<>();
        m.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE_GROUP, List.of("m1", "m2"),
                g, 2, List.of(g), List.of(2), List.of()));

        UndoManager.Record<String> r = m.pop();
        assertEquals(UndoManager.RecordType.REMOVE_GROUP, r.type());
        assertEquals(List.of("m1", "m2"), r.items());
        assertSame(g, r.group());
        assertEquals(2, r.groupIndex());
        assertEquals(List.of(g), r.destroyedGroups());
        assertEquals(List.of(2), r.destroyedIndexes());
    }

    @Test
    void ungroupRecordKeepsGroupMembersAndIndex() {
        GroupItem<String> g = new GroupItem<>(1, "组1");
        UndoManager<String> m = new UndoManager<>();
        m.push(new UndoManager.Record<>(UndoManager.RecordType.UNGROUP, List.of("m1", "m2"),
                g, 1, List.of(), List.of(), List.of()));

        UndoManager.Record<String> r = m.pop();
        assertEquals(UndoManager.RecordType.UNGROUP, r.type());
        assertEquals(List.of("m1", "m2"), r.items());
        assertSame(g, r.group());
        assertEquals(1, r.groupIndex());
        assertTrue(r.destroyedGroups().isEmpty());
        assertTrue(r.destroyedIndexes().isEmpty());
        assertTrue(r.structureBefore().isEmpty());
    }

    @Test
    void removeRecordKeepsDestroyedGroupsWithIndexes() {
        GroupItem<String> g1 = new GroupItem<>(1, "组1");
        GroupItem<String> g2 = new GroupItem<>(2, "组2");
        UndoManager<String> m = new UndoManager<>();
        m.push(new UndoManager.Record<>(UndoManager.RecordType.REMOVE, List.of("m1", "m2"),
                null, -1, List.of(g2, g1), List.of(3, 1), List.of()));

        UndoManager.Record<String> r = m.pop();
        assertNull(r.group());
        assertEquals(List.of(g2, g1), r.destroyedGroups());
        assertEquals(List.of(3, 1), r.destroyedIndexes());
    }

    @Test
    void importRecordKeepsMultipleGroupsPayload() {
        GroupItem<String> g1 = new GroupItem<>(1, "组1");
        GroupItem<String> g2 = new GroupItem<>(2, "组2");
        UndoManager<String> m = new UndoManager<>();
        m.push(new UndoManager.Record<>(UndoManager.RecordType.IMPORT,
                List.of("a", "b"), List.of(g1, g2)));

        UndoManager.Record<String> r = m.pop();
        assertEquals(UndoManager.RecordType.IMPORT, r.type());
        assertEquals(List.of("a", "b"), r.items());
        assertEquals(List.of(g1, g2), r.groups());
        assertNull(r.group());
        assertEquals(-1, r.groupIndex());
        assertTrue(r.destroyedGroups().isEmpty());
        assertTrue(r.destroyedIndexes().isEmpty());
        assertTrue(r.structureBefore().isEmpty());
    }

    @Test
    void convenienceRecordsCarryEmptyGroups() {
        UndoManager<String> m = new UndoManager<>();
        m.push(UndoManager.RecordType.REMOVE, List.of("a"));
        GroupItem<String> g = new GroupItem<>(1, "组1");
        m.push(new UndoManager.Record<>(UndoManager.RecordType.UNGROUP, List.of("m"),
                g, 1, List.of(), List.of(), List.of()));

        UndoManager.Record<String> ungroup = m.pop();
        assertTrue(ungroup.groups().isEmpty());
        UndoManager.Record<String> remove = m.pop();
        assertTrue(remove.groups().isEmpty());
    }

    @Test
    void limitAppliesToGroupRecordsToo() {
        UndoManager<String> m = new UndoManager<>(2);
        GroupItem<String> g1 = new GroupItem<>(1, "组1");
        GroupItem<String> g2 = new GroupItem<>(2, "组2");
        m.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP, List.of("a"), g1, 0,
                List.of(), List.of(), List.of("x")));
        m.push(new UndoManager.Record<>(UndoManager.RecordType.GROUP, List.of("b"), g2, 0,
                List.of(), List.of(), List.of("y")));
        m.push(UndoManager.RecordType.REMOVE, List.of("c"));

        assertEquals(2, m.size());
        assertEquals(UndoManager.RecordType.REMOVE, m.pop().type());
        assertEquals(g2, m.pop().group());
        assertTrue(m.isEmpty());
    }
}
